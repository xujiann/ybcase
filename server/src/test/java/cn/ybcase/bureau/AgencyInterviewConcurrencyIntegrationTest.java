package cn.ybcase.bureau;

import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The held interview row creates a real, deterministic PostgreSQL overlap between two permitted HTTP paths. */
@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class AgencyInterviewConcurrencyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    record Actor(long id, long department, String username, String token) {}
    record Source(long caseId, long agreementId, long interviewId, Actor receiver) {}

    @BeforeEach void ownedDatabaseOnly() {
        assertTrue(jdbc.queryForObject("select current_database()", String.class)
                .matches("ybcase_authowner_test_20261028_[a-f0-9]{8}"));
    }

    @ParameterizedTest @ValueSource(strings={"CORRECT", "VOID"})
    void actualInterviewRetryAndReviewedChangeUseTheSameSourceBeforeActorOrder(String kind) throws Exception {
        Source source=completedInterview();
        String original=original(source);
        var workers=Executors.newFixedThreadPool(2);
        try(Connection blocker=jdbc.getDataSource().getConnection(); Connection observer=jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);observer.setAutoCommit(true);
            try {
                int blockerPid=backendPid(blocker);
                try(var lock=blocker.prepareStatement("select id from department_interview where id=? for update")) {
                    lock.setLong(1,source.interviewId());try(var rows=lock.executeQuery()){assertTrue(rows.next());}
                }
                var change=workers.submit(()->call(source.receiver(),"/api/agency/interview-changes/"+source.interviewId(),change(kind)));
                int changePid=awaitBlocked(observer,blockerPid,"department_interview");
                var retry=workers.submit(()->call(source.receiver(),actualPath(source),retryBody()));
                awaitBlocked(observer,changePid,"agreement_action");
                boolean actorAvailable=actorLockAvailable(observer,source.receiver().id());
                blocker.commit();
                JsonNode retryResponse=retry.get(20,TimeUnit.SECONDS),changeResponse=change.get(20,TimeUnit.SECONDS);
                int rows=jdbc.queryForObject("select count(*) from department_interview where source_agreement_id=?",Integer.class,source.agreementId());
                int requests=jdbc.queryForObject("select count(*) from interview_change_request where interview_id=?",Integer.class,source.interviewId());
                System.out.printf("AGENCY_INTERVIEW_RACE kind=%s retryCode=%d changeCode=%d actorFreeBeforeSourceRelease=%s interviews=%d requests=%d originalUnchanged=%s%n",
                        kind,retryResponse.path("code").asInt(-1),changeResponse.path("code").asInt(-1),actorAvailable,rows,requests,original.equals(original(source)));
                assertEquals(0,retryResponse.path("code").asInt(-1),retryResponse.toString());
                assertEquals(0,changeResponse.path("code").asInt(-1),changeResponse.toString());
                assertTrue(actorAvailable,"A retry waiting on the agreement must not retain the agency actor lock");
                assertEquals(source.interviewId(),retryResponse.path("data").path("id").asLong());
                assertEquals(1,rows);assertEquals(1,requests);
                assertEquals("PENDING",jdbc.queryForObject("select status from interview_change_request where interview_id=?",String.class,source.interviewId()));
                assertEquals(original,original(source));
            } finally {blocker.rollback();}
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));}
    }

    @Test void concurrentIdenticalActualInterviewRetriesRetainOneOriginalRecord() throws Exception {
        Source source=completedInterview();String original=original(source);
        var workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->call(source.receiver(),actualPath(source),retryBody()));
            var second=workers.submit(()->call(source.receiver(),actualPath(source),retryBody()));
            for(var result:java.util.List.of(first,second)) {
                JsonNode response=result.get(20,TimeUnit.SECONDS);
                assertEquals(0,response.path("code").asInt(-1),response.toString());
                assertEquals(source.interviewId(),response.path("data").path("id").asLong());
            }
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));}
        assertEquals(1,jdbc.queryForObject("select count(*) from department_interview where source_agreement_id=?",Integer.class,source.agreementId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from interview_change_request where interview_id=?",Integer.class,source.interviewId()));
        assertEquals(original,original(source));
    }

    @ParameterizedTest @ValueSource(strings={"receive", "return", "reply"})
    void receiverOperationAndOutdatedActualRegistrationSerializeWithoutANewLockCycle(String operation)throws Exception {
        Source source=source("receive".equals(operation)?"PENDING":"ACCEPTED");
        String originalCase=jdbc.queryForObject("select row_to_json(c)::text from case_file c where id=?",String.class,source.caseId());
        var workers=Executors.newFixedThreadPool(2);
        try(Connection blocker=jdbc.getDataSource().getConnection();Connection observer=jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);observer.setAutoCommit(true);
            try {
                int blockerPid=backendPid(blocker);
                try(var lock=blocker.prepareStatement("select id from sys_user where id=? for update")) {
                    lock.setLong(1,source.receiver().id());try(var rows=lock.executeQuery()){assertTrue(rows.next());}
                }
                Map<String,Object> body="receive".equals(operation)?Map.of("cycle",1):"return".equals(operation)
                        ?Map.of("cycle",1,"reason","合成核查后退回")
                        :Map.of("cycle",1,"result","已落实合成实际约谈","resultType","COMPLETED","interview",details());
                var action=workers.submit(()->call(source.receiver(),"/api/agency/agreement-actions/"+source.agreementId()+"/"+operation,body));
                int actionPid=awaitBlocked(observer,blockerPid,"sys_user");
                var outdated=workers.submit(()->call(source.receiver(),actualPath(source),retryBody()));
                awaitOtherWait(observer,actionPid);
                blocker.commit();
                JsonNode actionResponse=action.get(20,TimeUnit.SECONDS),registration=outdated.get(20,TimeUnit.SECONDS);
                System.out.printf("AGENCY_RECEIVER_ORDER operation=%s actionCode=%d registrationCode=%d%n",
                        operation,actionResponse.path("code").asInt(-1),registration.path("code").asInt(-1));
                assertEquals(0,actionResponse.path("code").asInt(-1),actionResponse.toString());
                assertEquals("reply".equals(operation)?0:2192,registration.path("code").asInt(-1),registration.toString());
                assertEquals("receive".equals(operation)?"ACCEPTED":"return".equals(operation)?"RETURNED":"COMPLETED",
                        jdbc.queryForObject("select agency_status from agreement_action where id=?",String.class,source.agreementId()));
                assertEquals("reply".equals(operation)?1:0,jdbc.queryForObject("select count(*) from department_interview where source_agreement_id=?",Integer.class,source.agreementId()));
                assertEquals(1,jdbc.queryForObject("select count(*) from agency_agreement_event where agreement_id=?",Integer.class,source.agreementId()));
                assertEquals(originalCase,jdbc.queryForObject("select row_to_json(c)::text from case_file c where id=?",String.class,source.caseId()));
            } finally {blocker.rollback();}
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));}
    }

    @Test void historicalMixedAgencyActorCannotReachAdministrativeMutation()throws Exception {
        Source source=source("PENDING");
        // The HTTP filter excludes AGENCY from bureau routes; the agency service also rejects mixed roles.
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='ADMIN'",source.receiver().id());
        String original=original(source);
        var workers=Executors.newFixedThreadPool(2);
        try {
                var administrator=workers.submit(()->call(source.receiver(),"/api/bureau/agreement-actions/"+source.agreementId()+"/reassign",
                        Map.of("cycle",1,"agencyDeptId",source.receiver().department(),"dueAt",LocalDate.now().plusDays(5).toString(),"reason","合成历史账号重新分派")));
                var refused=workers.submit(()->call(source.receiver(),"/api/agency/agreement-actions/"+source.agreementId()+"/receive",Map.of("cycle",1)));
                JsonNode adminResponse=administrator.get(20,TimeUnit.SECONDS),agencyResponse=refused.get(20,TimeUnit.SECONDS);
                System.out.printf("AGENCY_MIXED_BOUNDARY adminCode=%d refusedAgencyCode=%d cycle=%d%n",
                        adminResponse.path("code").asInt(-1),agencyResponse.path("code").asInt(-1),
                        jdbc.queryForObject("select agency_cycle from agreement_action where id=?",Integer.class,source.agreementId()));
                assertEquals(403,adminResponse.path("code").asInt(-1),adminResponse.toString());
                assertEquals(403,agencyResponse.path("code").asInt(-1),agencyResponse.toString());
                assertEquals(1,jdbc.queryForObject("select agency_cycle from agreement_action where id=?",Integer.class,source.agreementId()));
                assertEquals("PENDING",jdbc.queryForObject("select agency_status from agreement_action where id=?",String.class,source.agreementId()));
                assertEquals(0,jdbc.queryForObject("select count(*) from agency_agreement_event where agreement_id=?",Integer.class,source.agreementId()));
                assertEquals(0,jdbc.queryForObject("select count(*) from department_interview where source_agreement_id=?",Integer.class,source.agreementId()));
                assertEquals(original,original(source));
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));}
    }

    private Source completedInterview() throws Exception {
        Source source=source("ACCEPTED");
        JsonNode completed=call(source.receiver(),"/api/agency/agreement-actions/"+source.agreementId()+"/reply",
                Map.of("cycle",1,"result","已落实合成实际约谈","resultType","COMPLETED","interview",details()));
        assertEquals(0,completed.path("code").asInt(-1),completed.toString());
        assertEquals("COMPLETED",jdbc.queryForObject("select agency_status from agreement_action where id=?",String.class,source.agreementId()));
        long interview=jdbc.queryForObject("select id from department_interview where source_agreement_id=?",Long.class,source.agreementId());
        return new Source(source.caseId(),source.agreementId(),interview,source.receiver());
    }

    private Source source(String status) {
        String nonce=UUID.randomUUID().toString().replace("-","");
        long department=jdbc.queryForObject("insert into sys_dept(name,code,type,org_kind) values('合成经办部门',?,'ADMIN','AGENCY') returning id",Long.class,"ACON-"+nonce.substring(0,16));
        String username="acon_"+nonce;
        long user=jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,account_category,must_change_password) values(?,'unused-jwt-fixture','约谈并发合成经办',?,'AGENCY',false) returning id",Long.class,username,department);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='AGENCY'",user);
        Actor receiver=new Actor(user,department,username,jwt.issue(username,0));
        long caseId=jdbc.queryForObject("""
                insert into case_file(case_no,name,cause_id,party_name,party_type,status,filed_at,deadline_at,decided_at,delivered_at,
                  institution_code,institution_ownership,institution_grade)
                values(?,'合成实际约谈来源',(select min(id) from case_cause where subject_type='PROVIDER'),
                  '合成实际约谈机构','PROVIDER','DELIVERED',current_date-10,current_date+30,current_date-7,current_date-6,'ACON-OLD','PUBLIC','SECONDARY') returning id
                """,Long.class,"ACON-"+nonce);
        jdbc.update("insert into case_decision(case_id,decision_type,decision_no,fine_amount,recoup_amount,content,decided_at) values(?,'PUNISH',?,10,0,'合成处理决定',current_date-7)",caseId,"ACON-D-"+nonce);
        long agreement=jdbc.queryForObject("""
                insert into agreement_action(case_id,action,org,sent_at,agency_dept_id,agency_status,agency_cycle,agency_due_at)
                values(?,'INTERVIEW','合成经办部门',current_date-5,?,?,1,current_date+5) returning id
                """,Long.class,caseId,department,status);
        return new Source(caseId,agreement,0,receiver);
    }

    private static Map<String,Object> details() {
        return Map.of("institutionCode","ACON-OLD","interviewType","个别约谈","reason","合成实际约谈事由",
                "content","合成实际约谈原记录","participants","甲、乙","interviewDate",LocalDate.now().minusDays(1).toString());
    }
    private static Map<String,Object> retryBody() {var body=new LinkedHashMap<>(details());body.put("cycle",1);return body;}
    private static Map<String,Object> change(String kind) {
        var body=new LinkedHashMap<String,Object>();body.put("requestKey",UUID.randomUUID().toString());body.put("kind",kind);
        if("CORRECT".equals(kind)) {var value=new LinkedHashMap<>(details());value.put("institutionName","合成实际约谈机构");value.put("institutionCode","ACON-NEW");body.put("values",value);}
        body.put("reason","核对原始约谈后申请"+kind);body.put("sourceReference","合成原始约谈记录第1页");return body;
    }
    private static String actualPath(Source source){return "/api/agency/agreement-actions/"+source.agreementId()+"/interview";}
    private JsonNode call(Actor actor,String path,Object body)throws Exception {
        var result=mvc.perform(post(path).header("Authorization","Bearer "+actor.token()).contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn();
        for(Throwable failure=result.getResolvedException();failure!=null;failure=failure.getCause())
            if(failure instanceof SQLException sql)System.out.printf("AGENCY_HTTP_SQLSTATE state=%s path=%s%n",sql.getSQLState(),path);
        var response=result.getResponse();
        assertTrue(response.getStatus()==200 || response.getStatus()==403,"Unexpected HTTP status "+response.getStatus());
        JsonNode bodyJson=mapper.readTree(response.getContentAsByteArray());
        if(response.getStatus()==403)assertEquals(403,bodyJson.path("code").asInt(-1));
        return bodyJson;
    }
    private static int backendPid(Connection connection)throws SQLException {
        try(var statement=connection.createStatement();var rows=statement.executeQuery("select pg_backend_pid()")){assertTrue(rows.next());return rows.getInt(1);}
    }
    private static int awaitBlocked(Connection observer,int blocker,String table)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline) {
            try(var query=observer.prepareStatement("select pid from pg_stat_activity where datname=current_database() and state='active' and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid)) and query like ? and query like '%for update%'")) {
                query.setInt(1,blocker);query.setString(2,"%from "+table+"%");
                try(var rows=query.executeQuery()){if(rows.next())return rows.getInt(1);}
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Expected a real PostgreSQL wait on "+table);
    }
    private static boolean actorLockAvailable(Connection observer,long actor)throws SQLException {
        try(var query=observer.prepareStatement("select id from sys_user where id=? for update nowait")) {
            query.setLong(1,actor);try(var rows=query.executeQuery()){assertTrue(rows.next());return true;}
        } catch(SQLException error){if("55P03".equals(error.getSQLState()))return false;throw error;}
    }
    private static void awaitOtherWait(Connection observer,int firstPid)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline) {
            try(var query=observer.prepareStatement("select exists(select 1 from pg_stat_activity where datname=current_database() and pid<>? and state='active' and wait_event_type='Lock' and (query like '%from agreement_action%' or query like '%from sys_user%'))")) {
                query.setInt(1,firstPid);try(var rows=query.executeQuery()){rows.next();if(rows.getBoolean(1))return;}
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Expected the second actual HTTP operation to wait");
    }
    private String original(Source source) {
        return jdbc.queryForObject("""
                select jsonb_build_object('case',(select to_jsonb(c) from case_file c where id=?),
                  'agreement',(select to_jsonb(a) from agreement_action a where id=?),
                  'interview',(select to_jsonb(i) from department_interview i where id=?))::text
                """,String.class,source.caseId(),source.agreementId(),source.interviewId());
    }
}
