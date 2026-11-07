package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** PostgreSQL + an isolated mock supplier. This does not certify any real provider. */
@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class IntegrationOperationsIntegrationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final AtomicBoolean FAIL=new AtomicBoolean(false),MISMATCH=new AtomicBoolean(false);
    static final AtomicInteger LOOKUPS=new AtomicInteger(),SENDS=new AtomicInteger();
    static final AtomicReference<String> CALLBACK=new AtomicReference<>("not-configured-fixture");
    static final Map<String,String> PROVIDERS=new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.concurrent.ExecutorService WORKERS=Executors.newVirtualThreadPerTaskExecutor();
    static final HttpServer GATEWAY=gateway();
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;@Autowired SysRoleRepository roles;@Autowired JwtService jwt;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        for(String target:List.of("treasury","delivery")) {
            registry.add("ybcase.integration."+target+".enabled",()->"true");
            registry.add("ybcase.integration."+target+".url",()->"http://127.0.0.1:"+GATEWAY.getAddress().getPort());
            registry.add("ybcase.integration."+target+".token",()->"operation-fixture-token");
        }
        registry.add("ybcase.integration.delivery.callback-username",CALLBACK::get);
    }
    @AfterAll static void close(){GATEWAY.stop(0);WORKERS.shutdownNow();}
    @BeforeEach void reset(){FAIL.set(false);MISMATCH.set(false);LOOKUPS.set(0);SENDS.set(0);}
    record Actor(String name,String token){}
    Actor actor(String role,String category,Long dept){
        var u=new SysUser();u.setUsername("integration_"+UUID.randomUUID().toString().replace("-",""));u.setRealName("合成集成验收人员");u.setPassword("unused");u.setDeptId(dept);u.setAccountCategory(category);u.getRoles().add(roles.findByCode(role).orElseThrow());u=users.saveAndFlush(u);return new Actor(u.getUsername(),jwt.issue(u.getUsername(),u.getTokenVersion()));
    }
    long caseId(Actor owner,String status){return jdbc.queryForObject("""
        insert into case_file(case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,status,owner_dept_id,owner_user,e_delivery_consent,e_delivery_receiver,e_delivery_channel)
        values(?,'合成网关案件',(select min(id) from case_cause),'合成机构','PROVIDER',current_date,current_date,?,1,?,true,'合成受送达人','fixture@example.invalid') returning id
        """,Long.class,"IO-"+UUID.randomUUID(),status,owner.name());}
    long execution(long c){return jdbc.queryForObject("insert into case_execution(case_id,kind,amount,paid_at,method,receipt_no) values(?,'FINE',23.45,current_date,'BANK',?) returning id",Long.class,c,"IO-RECEIPT-"+UUID.randomUUID());}
    long doc(long c,String type){return jdbc.queryForObject("insert into case_document(case_id,doc_type,title,content,made_at) values(?,?,?,'合成文书内容',current_date) returning id",Long.class,c,type,"合成"+type);}
    JsonNode postAs(Actor actor,String path,Object body)throws Exception{return mapper.readTree(mvc.perform(post("/api"+path).header("Authorization","Bearer "+actor.token()).contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse().getContentAsByteArray());}
    JsonNode getAs(Actor actor,String path)throws Exception{return mapper.readTree(mvc.perform(get("/api"+path).header("Authorization","Bearer "+actor.token())).andReturn().getResponse().getContentAsByteArray());}
    long batch(Actor a,long e,String key)throws Exception{var result=postAs(a,"/bureau/integrations/treasury/batches",Map.of("executionIds",List.of(e),"requestKey",key));assertEquals(0,result.path("code").asInt(),result.toString());return result.path("data").path("id").asLong();}
    long envelope(Actor a,long c,long d)throws Exception{var result=postAs(a,"/bureau/cases/"+c+"/electronic-deliveries",Map.of("documentId",d,"requestKey",UUID.randomUUID().toString()));assertEquals(0,result.path("code").asInt(),result.toString());return result.path("data").path("id").asLong();}
    JsonNode send(Actor a,long c,long e)throws Exception{return postAs(a,"/bureau/cases/"+c+"/electronic-deliveries/"+e+"/send",Map.of());}
    Map<String,Object> receipt(long id,String event,String status){var result=new HashMap<String,Object>();result.put("deliveryId",id);result.put("providerId","supplier-"+id);result.put("eventId",event);result.put("status",status);result.put("occurredAt",OffsetDateTime.now().toString());result.put("receiptNo","RECEIPT-"+id);return result;}

    @Test void batchDeduplicatesAndPersistsExactResultsWithoutPosting()throws Exception{
        var a=actor("LEADER","BUREAU",1L);long c=caseId(a,"DELIVERED"),e=execution(c);String key=UUID.randomUUID().toString();long b=batch(a,e,key);
        assertEquals(b,batch(a,e,key));assertEquals(1,jdbc.queryForObject("select count(*) from treasury_batch_item where batch_id=?",Integer.class,b));
        assertEquals(0,postAs(a,"/bureau/integrations/treasury/batches/"+b+"/run",Map.of()).path("code").asInt());
        assertEquals("MATCH",jdbc.queryForObject("select result from treasury_batch_item where batch_id=?",String.class,b));
        assertEquals(b,getAs(a,"/bureau/integrations/treasury/batches/"+b).path("data").path("id").asLong());
        assertTrue(getAs(a,"/bureau/integrations/treasury/batches?page=1&size=20").path("data").path("total").asLong()>0);
        assertTrue(getAs(a,"/bureau/integrations/treasury/sources?page=1&size=20").path("data").path("total").asLong()>0);
        postAs(a,"/bureau/integrations/treasury/batches/"+b+"/run",Map.of());assertEquals(1,LOOKUPS.get());
        assertEquals(1,jdbc.queryForObject("select count(*) from case_execution where case_id=?",Integer.class,c));
    }
    @Test void failedGatewayTicketCanBeResumedAndDoesNotEraseEarlierEvidence()throws Exception{
        var a=actor("LEADER","BUREAU",1L);long c=caseId(a,"DELIVERED"),b=batch(a,execution(c),UUID.randomUUID().toString());FAIL.set(true);
        postAs(a,"/bureau/integrations/treasury/batches/"+b+"/run",Map.of());assertEquals("FAILED",jdbc.queryForObject("select result from treasury_batch_item where batch_id=?",String.class,b));
        FAIL.set(false);postAs(a,"/bureau/integrations/treasury/batches/"+b+"/run",Map.of());assertEquals("MATCH",jdbc.queryForObject("select result from treasury_batch_item where batch_id=?",String.class,b));
        assertEquals(2,jdbc.queryForObject("select count(*) from treasury_item_history h join treasury_batch_item i on i.id=h.item_id where i.batch_id=?",Integer.class,b));
    }
    @Test void differenceRequiresAssignmentEvidenceAndAnotherReviewer()throws Exception{
        Actor a=actor("LEADER","BUREAU",1L),reviewer=actor("LEGAL","BUREAU",1L);long c=caseId(a,"DELIVERED"),b=batch(a,execution(c),UUID.randomUUID().toString());MISMATCH.set(true);
        postAs(a,"/bureau/integrations/treasury/batches/"+b+"/run",Map.of());long item=jdbc.queryForObject("select id from treasury_batch_item where batch_id=?",Long.class,b);String path="/bureau/integrations/treasury/items/"+item;
        assertEquals(0,postAs(a,path+"/assign",Map.of("username",a.name())).path("code").asInt());
        assertNotEquals(0,postAs(a,path+"/investigate",Map.of("note","核查","sourceReference","")).path("code").asInt());
        assertEquals(0,postAs(a,path+"/investigate",Map.of("note","经核查存在日期差异，另走续登记流程","sourceReference","合成凭据001")).path("code").asInt());
        assertNotEquals(0,postAs(a,path+"/review",Map.of("approve",true,"reason","自己复核")).path("code").asInt());
        assertEquals(0,postAs(reviewer,path+"/review",Map.of("approve",false,"reason","补充核查依据")).path("code").asInt());
        postAs(a,path+"/investigate",Map.of("note","补充核查完成","sourceReference","合成凭据002"));
        assertEquals(0,postAs(reviewer,path+"/review",Map.of("approve",true,"reason","核查过程及依据完整")).path("code").asInt());
        assertEquals("RESOLVED",jdbc.queryForObject("select review_status from treasury_batch_item where id=?",String.class,item));
        assertEquals(0,new java.math.BigDecimal("99.00").compareTo(jdbc.queryForObject("select external_amount from treasury_batch_item where id=?",java.math.BigDecimal.class,item)));
        assertEquals(0,new java.math.BigDecimal("99.00").compareTo(new java.math.BigDecimal(jdbc.queryForObject("select result_snapshot->>'external_amount' from treasury_item_history where item_id=? and action='CHECK'",String.class,item))));
        assertEquals(1,jdbc.queryForObject("select count(*) from case_execution where case_id=?",Integer.class,c));
    }
    @Test void sourceScopeAndChangedSnapshotAreCheckedBeforeExternalReads()throws Exception{
        Actor a=actor("LEADER","BUREAU",1L),district=actor("LEADER","DISTRICT",2L);long c=caseId(a,"DELIVERED"),e=execution(c),b=batch(a,e,UUID.randomUUID().toString());
        assertEquals(403,getAs(district,"/bureau/integrations/treasury/batches/"+b).path("code").asInt());
        long item=jdbc.queryForObject("select id from treasury_batch_item where batch_id=?",Long.class,b);
        assertEquals(403,getAs(district,"/bureau/integrations/treasury/items/"+item).path("code").asInt());
        assertEquals(1,getAs(a,"/bureau/integrations/treasury/items/"+item).path("data").path("items").size());
        assertNotEquals(0,postAs(district,"/bureau/integrations/treasury/batches",Map.of("executionIds",List.of(e),"requestKey",UUID.randomUUID().toString())).path("code").asInt());
        jdbc.update("update case_execution set amount=24 where id=?",e);postAs(a,"/bureau/integrations/treasury/batches/"+b+"/run",Map.of());
        assertEquals("SOURCE_CHANGED",jdbc.queryForObject("select result from treasury_batch_item where batch_id=?",String.class,b));assertEquals(0,LOOKUPS.get());
    }
    @Test void deliveryIsIdempotentAndAuthenticatedReceiptBindsDocumentAndArchive()throws Exception{
        Actor a=actor("LEADER","BUREAU",1L),service=actor("INTEGRATION",null,null);CALLBACK.set(service.name());long c=caseId(a,"DECIDED"),d=doc(c,"DECISION"),id=envelope(a,c,d);
        assertEquals(id,envelope(a,c,d));assertEquals("SENT",send(a,c,id).path("data").path("status").asText());send(a,c,id);assertEquals(1,SENDS.get());
        var payload=receipt(id,"event-"+UUID.randomUUID(),"DELIVERED");assertEquals(403,postAs(a,"/integrations/delivery/receipts",payload).path("code").asInt());
        assertTrue(postAs(service,"/integrations/delivery/receipts",payload).path("data").path("accepted").asBoolean());
        assertTrue(postAs(service,"/integrations/delivery/receipts",payload).path("data").path("accepted").asBoolean());
        assertEquals(1,jdbc.queryForObject("select count(*) from case_delivery where case_id=? and document_id=?",Integer.class,c,d));
        assertEquals(1,jdbc.queryForObject("select count(*) from case_document where case_id=? and doc_type='DELIVERY_RECEIPT'",Integer.class,c));
        assertEquals("DELIVERED",jdbc.queryForObject("select status from case_file where id=?",String.class,c));
        var conflicting=new HashMap<>(payload);conflicting.put("status","REJECTED");assertNotEquals(0,postAs(service,"/integrations/delivery/receipts",conflicting).path("code").asInt());
    }
    @Test void callbackRequiresConfiguredDedicatedLiveAccount()throws Exception{
        Actor a=actor("LEADER","BUREAU",1L),service=actor("INTEGRATION",null,null),other=actor("INTEGRATION",null,null);CALLBACK.set(service.name());long c=caseId(a,"DECIDED"),id=envelope(a,c,doc(c,"DECISION"));send(a,c,id);
        assertEquals(403,postAs(other,"/integrations/delivery/receipts",receipt(id,"wrong-"+UUID.randomUUID(),"DELIVERED")).path("code").asInt());
        jdbc.update("update sys_user set token_version=token_version+1 where username=?",service.name());
        assertTrue(mvc.perform(post("/api/integrations/delivery/receipts").header("Authorization","Bearer "+service.token()).contentType("application/json").content(mapper.writeValueAsBytes(receipt(id,"stale-"+UUID.randomUUID(),"DELIVERED")))).andReturn().getResponse().getStatus()>=400);
        assertEquals(0,jdbc.queryForObject("select count(*) from case_delivery where case_id=?",Integer.class,c));
    }
    @Test void sealedCaseReceivesRejectedEvidenceWithoutChangingArchive()throws Exception{
        Actor a=actor("LEADER","BUREAU",1L),service=actor("INTEGRATION",null,null);CALLBACK.set(service.name());long c=caseId(a,"DECIDED"),id=envelope(a,c,doc(c,"DECISION"));send(a,c,id);jdbc.update("update case_file set archive_no='SYNTHETIC-SEALED' where id=?",c);
        assertFalse(postAs(service,"/integrations/delivery/receipts",receipt(id,"sealed-"+UUID.randomUUID(),"DELIVERED")).path("data").path("accepted").asBoolean());
        assertEquals(1,jdbc.queryForObject("select count(*) from electronic_delivery_event where envelope_id=? and outcome='REJECTED'",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("select count(*) from case_delivery where case_id=?",Integer.class,c));assertEquals("DECIDED",jdbc.queryForObject("select status from case_file where id=?",String.class,c));
    }
    @Test void deliveryFailuresRetryWithSameSupplierKeyAndRejectionDoesNotMarkServed()throws Exception{
        Actor a=actor("LEADER","BUREAU",1L),service=actor("INTEGRATION",null,null);CALLBACK.set(service.name());long c=caseId(a,"DECIDED"),id=envelope(a,c,doc(c,"DECISION"));FAIL.set(true);
        assertEquals("FAILED",send(a,c,id).path("data").path("status").asText());FAIL.set(false);assertEquals("SENT",send(a,c,id).path("data").path("status").asText());
        assertTrue(postAs(service,"/integrations/delivery/receipts",receipt(id,"reject-"+UUID.randomUUID(),"REJECTED")).path("data").path("accepted").asBoolean());
        assertEquals("DECIDED",jdbc.queryForObject("select status from case_file where id=?",String.class,c));assertEquals(0,jdbc.queryForObject("select count(*) from case_delivery where case_id=?",Integer.class,c));
    }
    @Test void missingConsentAddressAndChangedDocumentsCannotBeSent()throws Exception{
        var a=actor("LEADER","BUREAU",1L);long c=caseId(a,"DECIDED"),d=doc(c,"DECISION");jdbc.update("update case_file set e_delivery_channel=null where id=?",c);
        assertEquals(2054,postAs(a,"/bureau/cases/"+c+"/electronic-deliveries",Map.of("documentId",d,"requestKey",UUID.randomUUID().toString())).path("code").asInt());
        jdbc.update("update case_file set e_delivery_channel='fixture@example.invalid' where id=?",c);long id=envelope(a,c,d);jdbc.update("update case_document set content='已变更' where id=?",d);
        assertNotEquals(0,send(a,c,id).path("code").asInt());assertEquals(0,SENDS.get());
    }
    @Test void overdueReceiptScanRecordsTimeoutWithoutServingAndLateValidReceiptCanRecover()throws Exception{
        var beforeMidnight=OffsetDateTime.parse("2026-11-07T23:59:00+08:00").toInstant();
        var afterMidnight=OffsetDateTime.parse("2026-11-08T00:02:00+08:00").toInstant();
        assertThrows(cn.ybcase.bureau.common.BizException.class,()->cn.ybcase.bureau.service.ElectronicDeliveryService.businessDate(afterMidnight,beforeMidnight));
        assertEquals(java.time.LocalDate.of(2026,11,7),cn.ybcase.bureau.service.ElectronicDeliveryService.businessDate(beforeMidnight,beforeMidnight));
        Actor a=actor("LEADER","BUREAU",1L),service=actor("INTEGRATION",null,null);CALLBACK.set(service.name());
        long c=caseId(a,"DECIDED"),id=envelope(a,c,doc(c,"DECISION"));send(a,c,id);
        jdbc.update("update electronic_delivery set deadline_at=current_timestamp-interval '1 hour' where id=?",id);
        assertEquals(1,postAs(a,"/bureau/cases/"+c+"/electronic-deliveries/expire",Map.of()).path("data").path("expired").asInt());
        assertEquals(0,postAs(a,"/bureau/cases/"+c+"/electronic-deliveries/expire",Map.of()).path("data").path("expired").asInt());
        assertEquals("DECIDED",jdbc.queryForObject("select status from case_file where id=?",String.class,c));
        assertEquals(1,jdbc.queryForObject("select count(*) from electronic_delivery_event where envelope_id=? and status='TIMED_OUT'",Integer.class,id));
        assertTrue(postAs(service,"/integrations/delivery/receipts",receipt(id,"late-"+UUID.randomUUID(),"DELIVERED")).path("data").path("accepted").asBoolean());
    }
    @Test void disabledDecisionRuleAndAddressChangeRejectCallbackWithoutAppendingMaterials()throws Exception{
        Actor a=actor("LEADER","BUREAU",1L),service=actor("INTEGRATION",null,null);CALLBACK.set(service.name());
        long c=caseId(a,"DECIDED"),id=envelope(a,c,doc(c,"DECISION"));send(a,c,id);
        var policy=jdbc.queryForMap("select * from sys_config where cfg_key='delivery_electronic_decision_allowed'");
        try {
            jdbc.update("update sys_config set cfg_value='false' where cfg_key='delivery_electronic_decision_allowed'");
            assertFalse(postAs(service,"/integrations/delivery/receipts",receipt(id,"disabled-"+UUID.randomUUID(),"DELIVERED")).path("data").path("accepted").asBoolean());
            jdbc.update("delete from sys_config where cfg_key='delivery_electronic_decision_allowed'");
            assertFalse(postAs(service,"/integrations/delivery/receipts",receipt(id,"missing-policy-"+UUID.randomUUID(),"DELIVERED")).path("data").path("accepted").asBoolean());
        } finally {
            jdbc.update("insert into sys_config(cfg_key,cfg_value,remark,updated_at) values(?,?,?,?) on conflict(cfg_key) do update set cfg_value=excluded.cfg_value,remark=excluded.remark,updated_at=excluded.updated_at",policy.get("cfg_key"),policy.get("cfg_value"),policy.get("remark"),policy.get("updated_at"));
        }
        jdbc.update("update case_file set e_delivery_channel='changed@example.invalid' where id=?",c);
        assertFalse(postAs(service,"/integrations/delivery/receipts",receipt(id,"changed-"+UUID.randomUUID(),"DELIVERED")).path("data").path("accepted").asBoolean());
        assertEquals(0,jdbc.queryForObject("select count(*) from case_delivery where case_id=?",Integer.class,c));
    }
    @Test void operationsDiagnosticIsAdminOnlyAndRedactsNetworkAndSecrets()throws Exception{
        Actor admin=actor("ADMIN","BUREAU",1L),handler=actor("HANDLER","BUREAU",1L);
        assertEquals(403,getAs(handler,"/bureau/integrations/readiness").path("code").asInt());
        var result=getAs(admin,"/bureau/integrations/readiness");assertEquals(0,result.path("code").asInt());assertFalse(result.toString().contains("operation-fixture-token"));assertFalse(result.toString().contains("127.0.0.1"));
        assertEquals("READY",postAs(admin,"/bureau/integrations/probe/treasury",Map.of()).path("data").path("status").asText());
    }
    static HttpServer gateway(){try{
        var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);s.setExecutor(WORKERS);
        s.createContext("/",exchange->{try(exchange){
            if(!"Bearer operation-fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(401,-1);return;}
            if(FAIL.get()){exchange.sendResponseHeaders(503,-1);return;}
            var input=JSON.readTree(exchange.getRequestBody());Object result;
            switch(exchange.getRequestURI().getPath()){
                case "/receipts/lookup"->{LOOKUPS.incrementAndGet();result=Map.of("status","FOUND","receiptNo",input.path("receiptNo").asText(),"amount",new java.math.BigDecimal(MISMATCH.get()?"99.00":"23.45"),"paidDate",java.time.LocalDate.now().toString(),"paid",true);}
                case "/deliveries/send"->{SENDS.incrementAndGet();String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");if(key==null){exchange.sendResponseHeaders(400,-1);return;}String provider="supplier-"+input.path("deliveryId").asLong();PROVIDERS.putIfAbsent(key,provider);result=Map.of("status","ACCEPTED","providerId",PROVIDERS.get(key));}
                case "/health/check"->result=Map.of("ready",true);
                default->{exchange.sendResponseHeaders(404,-1);return;}
            }
            byte[] bytes=JSON.writeValueAsBytes(result);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }});s.start();return s;
    }catch(java.io.IOException ex){throw new IllegalStateException(ex);}}
}
