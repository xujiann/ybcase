package cn.ybcase.bureau;

import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** A real PostgreSQL wait proves that department authorization is locked before either user row. */
@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class SupplementApprovalConcurrencyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    record Actor(long id,String name,String token,long dept) {}

    @BeforeEach void isolatedDatabaseOnly() {
        assertTrue(jdbc.queryForObject("select current_database()",String.class)
                .matches("ybcase_authowner_test_20261028_[0-9a-f]{8}"));
    }

    @Test void reviewerDepartmentDisableWaitsBeforeUserLocksAndPreventsApprovalAfterCommit() throws Exception {
        Actor owner=actor("DISTRICT","HANDLER"),reviewer=actor("BUREAU","LEGAL");
        long caseId=caseFile(owner);
        String original=original(caseId),path="/api/bureau/cases/"+caseId+"/supplement-bundles";
        long bundleId=write(owner,path,Map.of("requestKey",UUID.randomUUID().toString(),"title","并发复核原始材料","sourceReference","原始凭证第1页")).path("id").asLong();
        var upload=mvc.perform(multipart(path+"/"+bundleId+"/files")
                .file(new MockMultipartFile("file","source.txt","text/plain","独立合成原始凭证".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Authorization","Bearer "+owner.token())).andReturn().getResponse();
        assertEquals(0,mapper.readTree(upload.getContentAsString()).path("code").asInt(-1));
        write(owner,path+"/"+bundleId+"/submit",Map.of());
        assertEquals("PENDING",jdbc.queryForObject("select status from case_supplement_bundle where id=?",String.class,bundleId));
        String manifest=jdbc.queryForObject("select manifest_sha256 from case_supplement_bundle where id=?",String.class,bundleId);

        var workers=Executors.newSingleThreadExecutor();
        try(Connection administrator=Objects.requireNonNull(jdbc.getDataSource()).getConnection();
            Connection observer=jdbc.getDataSource().getConnection()) {
            administrator.setAutoCommit(false);
            // Every observer query is a separate transaction, so PostgreSQL statistics cannot be cached by a test transaction.
            observer.setAutoCommit(true);
            int blocker;
            try(var statement=administrator.createStatement();var rs=statement.executeQuery("select pg_backend_pid()")) {
                assertTrue(rs.next());blocker=rs.getInt(1);
            }
            try {
                try(var statement=administrator.prepareStatement("select id from sys_dept where id=? for no key update")) {
                    statement.setLong(1,reviewer.dept());try(var rows=statement.executeQuery()){assertTrue(rows.next());}
                }
                try(var statement=administrator.prepareStatement("update sys_dept set enabled=false where id=?")) {
                    statement.setLong(1,reviewer.dept());assertEquals(1,statement.executeUpdate());
                }
                var approval=workers.submit(()->{
                    var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/"+bundleId+"/review")
                            .header("Authorization","Bearer "+reviewer.token()).contentType("application/json")
                            .content(mapper.writeValueAsBytes(Map.of("approve",true,"opinion","独立核对原始材料"))))
                            .andReturn().getResponse();
                    return mapper.readTree(response.getContentAsString()).path("code").asInt(-1);
                });
                assertTrue(waitingOnDepartment(observer,blocker),"The actual HTTP reviewer must wait on the department FOR SHARE lock");
                assertFalse(approval.isDone(),"Approval must remain blocked until the administrator commits the department change");
                // If review had locked either actor before waiting on the department, NOWAIT would fail here.
                try(var statement=observer.prepareStatement("select id from sys_user where id in (?,?) order by id for no key update nowait")) {
                    statement.setLong(1,owner.id());statement.setLong(2,reviewer.id());
                    try(var rows=statement.executeQuery()){int users=0;while(rows.next())users++;assertEquals(2,users);}
                }
                administrator.commit();
                assertEquals(403,approval.get(20,TimeUnit.SECONDS),"The post-wait check must reject the now-disabled department");
            } finally {
                administrator.rollback();
            }
        } finally {
            workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));
        }
        assertEquals("PENDING",jdbc.queryForObject("select status from case_supplement_bundle where id=?",String.class,bundleId));
        assertNull(jdbc.queryForObject("select reviewed_by from case_supplement_bundle where id=?",String.class,bundleId));
        assertEquals(manifest,jdbc.queryForObject("select manifest_sha256 from case_supplement_bundle where id=?",String.class,bundleId));
        assertEquals(original,original(caseId),"The original sealed case, decision, documents and attachments must not change");
    }

    private boolean waitingOnDepartment(Connection observer,int blocker) throws SQLException,InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline) {
            try(var statement=observer.prepareStatement("""
                select exists(select 1 from pg_stat_activity a
                  where a.datname=current_database() and a.state='active' and a.wait_event_type='Lock'
                    and ? = any(pg_blocking_pids(a.pid))
                    and a.query like '%from sys_dept%' and a.query like '%for share%')
                """)) {
                statement.setInt(1,blocker);
                try(var rows=statement.executeQuery()){assertTrue(rows.next());if(rows.getBoolean(1))return true;}
            }
            Thread.sleep(20);
        }
        return false;
    }
    private Actor actor(String category,String role) {
        String name="supwait_"+UUID.randomUUID().toString().replace("-","");
        long department=jdbc.queryForObject("insert into sys_dept(code,name,type,org_kind) values(?,?,'ADMIN',?) returning id",Long.class,name.substring(0,30),"材料并发合成机构",category);
        FixtureUsers.ensure(jdbc,name,role);jdbc.update("update sys_user set dept_id=?,account_category=? where username=?",department,category,name);
        long id=jdbc.queryForObject("select id from sys_user where username=?",Long.class,name);
        return new Actor(id,name,jwt.issue(name,0),department);
    }
    private long caseFile(Actor owner) {
        long id=jdbc.queryForObject("""
            insert into case_file(case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,status,owner_user,owner_dept_id,archive_no)
            values(?,'并发材料复核测试',(select min(id) from case_cause),'合成机构','PROVIDER',current_date-30,current_date+60,'CLOSED',?,?,'原封存卷') returning id
            """,Long.class,"SUPWAIT-"+UUID.randomUUID(),owner.name(),owner.dept());
        jdbc.update("insert into case_decision(case_id,decision_type,decision_no,content,decided_at,fine_amount,recoup_amount,confiscate_amount) values(?,'PUNISH',?,'原决定全文',current_date-20,100,0,0)",id,"SUPWAIT-D-"+id);
        return id;
    }
    private String original(long id) {
        return jdbc.queryForObject("""
            select jsonb_build_object('case',to_jsonb(c),'decision',(select to_jsonb(d) from case_decision d where d.case_id=c.id),
             'documents',(select jsonb_agg(to_jsonb(d) order by id) from case_document d where d.case_id=c.id),
             'attachments',(select jsonb_agg(to_jsonb(a) order by id) from case_attachment a where a.case_id=c.id))::text
            from case_file c where id=?
            """,String.class,id);
    }
    private JsonNode write(Actor actor,String path,Object body) throws Exception {
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization","Bearer "+actor.token()).contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
        var json=mapper.readTree(response.getContentAsString());assertEquals(0,json.path("code").asInt(-1),json.toString());return json.path("data");
    }
}
