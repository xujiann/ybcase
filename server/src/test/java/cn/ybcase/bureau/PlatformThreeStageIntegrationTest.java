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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class PlatformThreeStageIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired JwtService jwt; @Autowired ObjectMapper mapper;
    record Actor(String name,String token) {}
    @BeforeEach void isolated(){assertTrue(jdbc.queryForObject("select current_database()",String.class).matches("ybcase_authowner_test_20261028_[0-9a-f]{8}"));}
    Actor actor(String role){String name="stage_"+UUID.randomUUID().toString().replace("-","");FixtureUsers.ensure(jdbc,name,role);return new Actor(name,jwt.issue(name,0));}
    long department(){return jdbc.queryForObject("insert into sys_dept(name,code,type) values('待核对旧机构',?,'ADMIN') returning id",Long.class,"stage-"+UUID.randomUUID().toString().substring(0,20));}
    long caseFile(Actor owner){return jdbc.queryForObject("""
        insert into case_file(case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,status,owner_dept_id,owner_user)
        values(?,'合成验收案件',(select min(id) from case_cause),'合成机构','PROVIDER',current_date,current_date,'DECIDED',1,?) returning id
        """,Long.class,"STAGE-"+UUID.randomUUID(),owner.name());}
    JsonNode json(MvcResult r)throws Exception{return mapper.readTree(r.getResponse().getContentAsByteArray());}
    JsonNode preview(Actor a,long d,String kind)throws Exception{return json(mvc.perform(get("/api/system/depts/"+d+"/classification-preview").param("orgKind",kind).header("Authorization","Bearer "+a.token())).andReturn());}
    MvcResult postAs(Actor a,String path,Object body)throws Exception{return mvc.perform(post(path).header("Authorization","Bearer "+a.token()).contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn();}
    Map<String,Object> confirmation(JsonNode p){return Map.of("orgKind",p.path("orgKind").asText(),"expectedCode",p.path("department").path("code").asText(),"fingerprint",p.path("fingerprint").asText(),"reason","按局方核定目录归类","sourceReference","合成核对记录001");}
    String classifyPath(long id){return "/api/system/depts/"+id+"/classification";}

    @Test void classificationPreservesAccountCredentialsRolesAndSourceIdsAndCannotRepeat()throws Exception{
        Actor admin=actor("ADMIN"),owner=actor("HANDLER");long d=department(),c=caseFile(owner);
        jdbc.update("update sys_user set dept_id=? where username=?",d,owner.name());
        jdbc.update("update case_file set owner_dept_id=? where id=?",d,c);
        var account=jdbc.queryForMap("select * from sys_user where username=?",owner.name());
        var original=jdbc.queryForObject("select row_to_json(c)::text from case_file c where id=?",String.class,c);
        var p=preview(admin,d,"DISTRICT").path("data");assertEquals(1,p.path("unclassifiedAccounts").asInt());
        assertEquals(0,json(postAs(admin,classifyPath(d),confirmation(p))).path("code").asInt(-1));
        assertEquals("DISTRICT",jdbc.queryForObject("select org_kind from sys_dept where id=?",String.class,d));
        assertEquals(account,jdbc.queryForMap("select * from sys_user where username=?",owner.name()));
        assertEquals(original,jdbc.queryForObject("select row_to_json(c)::text from case_file c where id=?",String.class,c));
        assertEquals(1,jdbc.queryForObject("select count(*) from dept_classification_history where dept_id=?",Integer.class,d));
        assertEquals(1202,json(postAs(admin,classifyPath(d),confirmation(p))).path("code").asInt());
        assertEquals(403,preview(owner,d,"DISTRICT").path("code").asInt());
    }
    @Test void staleOrConflictingClassificationIsRejectedBeforeAnyMutation()throws Exception{
        var admin=actor("ADMIN");var owner=actor("HANDLER");long d=department();
        var p=preview(admin,d,"DISTRICT").path("data");
        jdbc.update("update sys_user set dept_id=?,account_category='BUREAU' where username=?",d,owner.name());
        assertEquals(1202,json(postAs(admin,classifyPath(d),confirmation(p))).path("code").asInt());
        p=preview(admin,d,"DISTRICT").path("data");assertEquals(1,p.path("conflicts").size());
        assertEquals(1202,json(postAs(admin,classifyPath(d),confirmation(p))).path("code").asInt());
        assertNull(jdbc.queryForObject("select org_kind from sys_dept where id=?",String.class,d));
        assertEquals(0,jdbc.queryForObject("select count(*) from dept_classification_history where dept_id=?",Integer.class,d));
    }
    @Test void classificationAndConcurrentAccountAssignmentShareDirectoryGate()throws Exception{
        var first=actor("ADMIN");var second=actor("ADMIN");long d=department();
        var p=preview(first,d,"DISTRICT").path("data");String newName="stage_create_"+UUID.randomUUID().toString().substring(0,15);
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection();var workers=Executors.newFixedThreadPool(2)){
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()){statement.execute("select pg_advisory_xact_lock(20261028,1201)");}
            var one=workers.submit(()->json(postAs(first,classifyPath(d),confirmation(p))).path("code").asInt(-1));
            var two=workers.submit(()->json(postAs(second,"/api/system/users",Map.of("username",newName,"password","Stage-Fixture-Password-2026!","realName","合成账号","deptId",d,"accountCategory","BUREAU","roleCodes",List.of("HANDLER")))).path("code").asInt(-1));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);int waiting=0;
            while(System.nanoTime()<until){waiting=jdbc.queryForObject("select count(*) from pg_locks where locktype='advisory' and classid=20261028 and objid=1201 and not granted",Integer.class);if(waiting>=2)break;Thread.sleep(20);}
            assertTrue(waiting>=2,"Both writes must wait on the directory gate");connection.commit();
            int classified=one.get(20,TimeUnit.SECONDS),created=two.get(20,TimeUnit.SECONDS);
            assertTrue(classified==0 && created==1106 || classified==1202 && created==0,"Exactly one incompatible operation may succeed: "+classified+","+created);
            String kind=jdbc.queryForObject("select org_kind from sys_dept where id=?",String.class,d);
            int incompatible=jdbc.queryForObject("select count(*) from sys_user where dept_id=? and account_category is not null and account_category<>?",Integer.class,d,kind);
            assertEquals(0,incompatible);
        }
    }
    @Test void integrationRoleCanOnlyReachExactCallbackAndCannotGainBusinessAccessViaMixedRoles()throws Exception{
        var service=actor("INTEGRATION");var handler=actor("HANDLER");
        for(String path:List.of("/api/system/depts","/api/bureau/cases","/api/agency/agreements","/api/integrations/delivery/receipts"))
            assertEquals(403,mvc.perform(get(path).header("Authorization","Bearer "+service.token())).andReturn().getResponse().getStatus(),path);
        assertEquals(403,postAs(handler,"/api/integrations/delivery/receipts",Map.of()).getResponse().getStatus());
        FixtureUsers.ensure(jdbc,service.name(),"ADMIN");
        assertEquals(403,mvc.perform(get("/api/system/depts").header("Authorization","Bearer "+service.token())).andReturn().getResponse().getStatus());
        var admin=actor("ADMIN");
        assertEquals(1106,json(postAs(admin,"/api/system/users",Map.of("username","mixed_"+UUID.randomUUID(),"realName","禁止混合服务账号","password","Stage-Fixture-Password-2026!","roleCodes",List.of("INTEGRATION","ADMIN")))).path("code").asInt());
    }
    @Test void consentStoresConfirmedAddressAndDecisionDeliveryCannotSpoofDocumentKindOrUseStalePolicy()throws Exception{
        var owner=actor("HANDLER");long c=caseFile(owner);
        String base="/api/bureau/cases/"+c;
        assertEquals(0,json(postAs(owner,base+"/e-delivery-consent",Map.of("receiver","合成受送达人","channel","fixture@example.invalid","docNo","CONSENT-001"))).path("code").asInt());
        assertEquals("fixture@example.invalid",jdbc.queryForObject("select e_delivery_channel from case_file where id=?",String.class,c));
        assertEquals(0,json(postAs(owner,base+"/e-delivery-consent",Map.of("receiver","签".repeat(64),"channel","fixture@example.invalid"))).path("code").asInt());
        assertEquals(2054,json(postAs(owner,base+"/e-delivery-consent",Map.of("receiver","超".repeat(65),"channel","other@example.invalid"))).path("code").asInt());
        assertEquals("fixture@example.invalid",jdbc.queryForObject("select e_delivery_channel from case_file where id=?",String.class,c));
        long doc=jdbc.queryForObject("insert into case_document(case_id,doc_type,title,content,made_at) values(?,'DECISION','合成处罚决定','合成正文',current_date) returning id",Long.class,c);
        String old=jdbc.queryForObject("select cfg_value from sys_config where cfg_key='delivery_electronic_decision_allowed'",String.class);
        try{
            jdbc.update("update sys_config set cfg_value='false' where cfg_key='delivery_electronic_decision_allowed'");
            assertEquals(2049,json(postAs(owner,base+"/doc-deliveries",Map.of("method","ELECTRONIC","documentId",doc,"docKind","OTHER"))).path("code").asInt());
            assertEquals(2049,json(postAs(owner,base+"/doc-deliveries",Map.of("method","ELECTRONIC","docKind","DECISION"))).path("code").asInt());
            assertEquals(0,jdbc.queryForObject("select count(*) from case_delivery where case_id=?",Integer.class,c));
            jdbc.update("update sys_config set cfg_value='true' where cfg_key='delivery_electronic_decision_allowed'");
            assertEquals(0,json(postAs(owner,base+"/doc-deliveries",Map.of("method","ELECTRONIC","documentId",doc,"docKind","DECISION"))).path("code").asInt());
        }finally{jdbc.update("update sys_config set cfg_value=? where cfg_key='delivery_electronic_decision_allowed'",old);}
    }
}
