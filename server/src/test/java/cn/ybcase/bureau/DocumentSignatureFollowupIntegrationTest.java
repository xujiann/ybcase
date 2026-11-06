package cn.ybcase.bureau;

import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.*;
import cn.ybcase.bureau.spi.MockSignatureProvider;
import cn.ybcase.bureau.spi.SignatureProvider;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Existing signatures and history are retained; real PostgreSQL locks control authorization races. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DocumentSignatureFollowupIntegrationTest {
    @Autowired CaseService cases;
    @Autowired ClueService clues;
    @Autowired CaseCauseRepository causes;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired BureauConfig config;
    @Autowired PlatformTransactionManager tm;
    final Map<String,String> original=new LinkedHashMap<>();
    final Set<String> templates=new LinkedHashSet<>();
    record Actor(Long id,String name,String token) {}

    @BeforeEach void setup() {
        assertNotEquals("ybcase",jdbc.queryForObject("select current_database()",String.class));
        SecurityContextHolder.clearContext();cfg("case_view_scope","SELF");
    }
    @AfterEach void restore() {
        SecurityContextHolder.clearContext();
        original.forEach((key,value)->{jdbc.update("update sys_config set cfg_value=? where cfg_key=?",value,key);config.onConfigChanged(new ConfigChangedEvent(key));});
        for(String type:templates) {
            jdbc.update("delete from doc_template_history where doc_type=?",type);
            jdbc.update("delete from doc_template where doc_type=?",type);
        }
    }
    void cfg(String key,String value) {
        original.computeIfAbsent(key,k->jdbc.queryForObject("select cfg_value from sys_config where cfg_key=?",String.class,k));
        jdbc.update("update sys_config set cfg_value=? where cfg_key=?",value,key);config.onConfigChanged(new ConfigChangedEvent(key));
    }
    Actor actor(String role) {
        var user=new SysUser();user.setUsername("signature_"+System.nanoTime());user.setRealName("签章回归");user.setPassword("unused-jwt-fixture");
        user.getRoles().add(roles.findByCode(role).orElseThrow());user=users.saveAndFlush(user);
        return new Actor(user.getId(),user.getUsername(),jwt.issue(user.getUsername(),user.getTokenVersion()));
    }
    CaseFile fresh(Actor owner) {return fresh(owner,null);}
    CaseFile fresh(Actor owner,Long clueId) {
        long cause=causes.findAll().stream().filter(c->c.getItemNo()==13).findFirst().orElseThrow().getId();
        return cases.create(new CaseService.CaseCreateReq(clueId,cause,"NORMAL","签章测试"+System.nanoTime(),"PROVIDER",null,null,null,null,"原始事实",BigDecimal.TEN,
                List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),null,null,null,null),owner.name());
    }
    long document(long id) {
        cases.addDocument(id,new CaseService.DocumentReq("OTHER","原件","应完整保留的原正文",LocalDate.now(),"回归",false,null,null));
        return jdbc.queryForObject("select max(id) from case_document where case_id=?",Long.class,id);
    }
    String template() {
        String type="FOLLOW_"+System.nanoTime();templates.add(type);
        jdbc.update("insert into doc_template(doc_type,title_tpl,content_tpl) values(?,?,?)",type,"旧标题","  旧正文\n保持原样  ");return type;
    }
    int history(String type) {return jdbc.queryForObject("select count(*) from doc_template_history where doc_type=?",Integer.class,type);}
    JsonNode request(Actor actor,String method,String path,Object body) throws Exception {
        var builder=switch(method) {case "PUT"->put("/api/bureau"+path);case "GET"->get("/api/bureau"+path);default->post("/api/bureau"+path);};
        builder.header("Authorization","Bearer "+actor.token());
        if(!"GET".equals(method))builder.contentType("application/json").content(mapper.writeValueAsString(body));
        var response=mvc.perform(builder).andReturn().getResponse();return mapper.readTree(response.getContentAsString());
    }
    int postCode(Actor actor,String path,Object body) throws Exception {return request(actor,"POST",path,body).path("code").asInt();}
    int putCode(Actor actor,String type,Object body) throws Exception {return request(actor,"PUT","/doc-templates/"+type,body).path("code").asInt();}
    <T>T tx(Supplier<T> work) {return new TransactionTemplate(tm).execute(status->work.get());}
    void stop(ExecutorService pool) throws Exception {pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    void awaitBlocked(int blocker,int count) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline) {
            Integer waiting=jdbc.queryForObject("""
                    with recursive blocked(pid) as (
                        select pid from pg_locks where not granted and ?=any(pg_blocking_pids(pid))
                        union
                        select locks.pid from pg_locks locks join blocked parent
                          on parent.pid=any(pg_blocking_pids(locks.pid)) where not locks.granted
                    ) select count(distinct pid) from blocked""",Integer.class,blocker);
            if(waiting!=null&&waiting>=count)return;Thread.sleep(25);
        }
        fail("Expected database waiters did not reach the held material row");
    }
    int caseRace(long caseId,Actor actor,String path,Object body,Runnable change) throws Exception {
        var pool=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<Integer>>();
        try {
            tx(()->{CaseGuards.lock(jdbc,caseId);change.run();int pid=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                pending.set(pool.submit(()->postCode(actor,path,body)));try {awaitBlocked(pid,1);}catch(Exception e){throw new RuntimeException(e);}return null;});
            return pending.get().get(20,TimeUnit.SECONDS);
        } finally {stop(pool);}
    }
    void role(Actor actor,String role) {
        jdbc.queryForList("select id from sys_user where id=? for update",actor.id());
        jdbc.update("delete from sys_user_role where user_id=?",actor.id());
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",actor.id(),role);
    }
    long transfer(Long clueId,Long caseId) {
        return jdbc.queryForObject("insert into case_transfer(clue_id,case_id,direction,target_org,kind,reason,sent_at) values(?,?,'OUT','回归机关','ADMIN','合法移送',current_date) returning id",Long.class,clueId,caseId);
    }
    static String changed(String value) {return (value.charAt(0)=='0'?"1":"0")+value.substring(1);}

    @Test void mockVerificationChecksHistoricalSignatureIdentityAndProviderAndFailsClosedWithoutContext() {
        var provider=new MockSignatureProvider();String content="历史原件\n保持不变",signer="  原署名  ";var signed=provider.sign(content,signer);
        assertTrue(provider.verify(content,signed.contentHash(),signed.signature(),signer,"MOCK"));
        assertFalse(provider.verify(content,signed.contentHash(),signed.signature()));
        for(String invalid:Arrays.asList(null,"",changed(signed.signature()),"invalid-signature"))
            assertFalse(provider.verify(content,signed.contentHash(),invalid,signer,"MOCK"));
        assertFalse(provider.verify(content,signed.contentHash(),signed.signature(),"另一署名","MOCK"));
        assertFalse(provider.verify(content,signed.contentHash(),signed.signature(),null,"MOCK"));
        assertFalse(provider.verify(content,signed.contentHash(),signed.signature(),signer,"OTHER"));
        assertFalse(provider.verify(content,signed.contentHash(),signed.signature(),signer,null));
        assertFalse(provider.verify(content,changed(signed.contentHash()),signed.signature(),signer,"MOCK"));
        assertFalse(provider.verify(content+"变更",signed.contentHash(),signed.signature(),signer,"MOCK"));
        assertFalse(provider.verify(null,signed.contentHash(),signed.signature(),signer,"MOCK"));
    }

    @Test void existingExternalProviderVerificationRemainsCompatibleWithoutCreatingAnotherSignature() {
        var verified=new AtomicInteger();SignatureProvider provider=new SignatureProvider() {
            @Override public SignResult sign(String content,String signer) {throw new AssertionError("Verification must never create a new signature");}
            @Override public boolean verify(String content,String hash,String signature) {verified.incrementAndGet();return "existing-external-signature".equals(signature);}
        };
        assertTrue(provider.verify("content","hash","existing-external-signature","stored-signer","EXTERNAL"));
        assertEquals(1,verified.get());
    }

    @Test void verificationDetectsSignatureRecordDamageWithoutRewritingHistoricalRows() throws Exception {
        var owner=actor("HANDLER");long id=fresh(owner).getId(),doc=document(id);
        assertEquals(0,postCode(owner,"/cases/"+id+"/documents/"+doc+"/sign",Map.of("signer","原署名")));
        var originalSignature=jdbc.queryForMap("select * from doc_signature where document_id=?",doc);
        var originalDocument=jdbc.queryForMap("select * from case_document where id=?",doc);
        String path="/cases/"+id+"/documents/"+doc+"/verify";
        assertTrue(request(owner,"GET",path,null).path("data").get(0).path("valid").asBoolean());
        for(String column:List.of("signature","signer","provider","content_hash")) {
            Object bad=switch(column) {case "signature","content_hash"->changed((String)originalSignature.get(column));case "provider"->"OTHER";default->"错误署名";};
            jdbc.update("update doc_signature set "+column+"=? where id=?",bad,originalSignature.get("id"));
            var before=jdbc.queryForMap("select * from doc_signature where id=?",originalSignature.get("id"));
            assertFalse(request(owner,"GET",path,null).path("data").get(0).path("valid").asBoolean(),column);
            assertEquals(before,jdbc.queryForMap("select * from doc_signature where id=?",originalSignature.get("id")));
            jdbc.update("update doc_signature set "+column+"=? where id=?",originalSignature.get(column),originalSignature.get("id"));
        }
        jdbc.update("update case_document set content='原正文已被改变' where id=?",doc);
        assertFalse(request(owner,"GET",path,null).path("data").get(0).path("valid").asBoolean());
        assertEquals(originalSignature,jdbc.queryForMap("select * from doc_signature where document_id=?",doc));
        jdbc.update("update case_document set content=? where id=?",originalDocument.get("content"),doc);
        assertEquals(originalDocument,jdbc.queryForMap("select * from case_document where id=?",doc));
    }

    @Test void concurrentLegitimateSignaturesRemainSeparateValidRecords() throws Exception {
        var owner=actor("HANDLER");long id=fresh(owner).getId(),doc=document(id);var pool=Executors.newFixedThreadPool(2);
        var pending=new ArrayList<Future<Integer>>();
        try {
            tx(()->{CaseGuards.lock(jdbc,id);int pid=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                pending.add(pool.submit(()->postCode(owner,"/cases/"+id+"/documents/"+doc+"/sign",Map.of("signer","当事人","signerRole","PARTY"))));
                pending.add(pool.submit(()->postCode(owner,"/cases/"+id+"/documents/"+doc+"/sign",Map.of("signer","办案人","signerRole","OFFICER"))));
                try {awaitBlocked(pid,2);}catch(Exception e){throw new RuntimeException(e);}return null;});
            for(var result:pending)assertEquals(0,result.get(20,TimeUnit.SECONDS));
            assertEquals(Set.of("当事人","办案人"),new HashSet<>(jdbc.queryForList("select signer from doc_signature where document_id=?",String.class,doc)));
            assertTrue(jdbc.queryForObject("select signed from case_document where id=?",Boolean.class,doc));
            var response=request(owner,"GET","/cases/"+id+"/documents/"+doc+"/verify",null);assertEquals(2,response.path("data").size());
            for(var signature:response.path("data"))assertTrue(signature.path("valid").asBoolean());
        } finally {stop(pool);}
    }

    @Test void invalidTemplateInputsNeverChangeCurrentTemplateOrAppendHistory() throws Exception {
        var admin=actor("ADMIN");String type=template();var before=jdbc.queryForMap("select * from doc_template where doc_type=?",type);
        var invalid=new ArrayList<Object>();invalid.add(Map.of());invalid.add(Map.of("titleTpl","标题"));invalid.add(Map.of("contentTpl","正文"));
        for(String key:List.of("titleTpl","contentTpl"))for(Object bad:Arrays.asList(null," ",true,Map.of("text","无效"),"内\0容")) {
            var body=new LinkedHashMap<String,Object>();body.put("titleTpl","标题");body.put("contentTpl","正文");body.put(key,bad);invalid.add(body);
        }
        invalid.add(Map.of("titleTpl","x".repeat(256),"contentTpl","正文"));
        for(Object body:invalid) {
            assertEquals(2100,putCode(admin,type,body));assertEquals(0,history(type));
            assertEquals(before,jdbc.queryForMap("select * from doc_template where doc_type=?",type));
        }
        String content="  {{summary}}\n正文原样  ";assertEquals(0,putCode(admin,type,Map.of("titleTpl","{{partyName}}","contentTpl",content)));
        assertEquals(content,jdbc.queryForObject("select content_tpl from doc_template where doc_type=?",String.class,type));
        assertEquals(1,history(type));assertEquals(before.get("content_tpl"),jdbc.queryForObject("select content_tpl from doc_template_history where doc_type=?",String.class,type));
    }

    @Test void templateLockWaitCannotPreserveRevokedAdminAuthority() throws Exception {
        var admin=actor("ADMIN");String type=template();var before=jdbc.queryForMap("select * from doc_template where doc_type=?",type);
        var pool=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<Integer>>();
        try {
            tx(()->{jdbc.queryForList("select doc_type from doc_template where doc_type=? for update",type);role(admin,"HANDLER");int pid=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                pending.set(pool.submit(()->putCode(admin,type,Map.of("titleTpl","越权修改","contentTpl","不应保存"))));
                try {awaitBlocked(pid,1);}catch(Exception e){throw new RuntimeException(e);}return null;});
            assertEquals(403,pending.get().get(20,TimeUnit.SECONDS));assertEquals(0,history(type));
            assertEquals(before,jdbc.queryForMap("select * from doc_template where doc_type=?",type));
        } finally {stop(pool);}
    }

    @Test void concurrentTemplateUpdatesRetainBothPreviousVersionsAndExactContent() throws Exception {
        var first=actor("ADMIN");var second=actor("ADMIN");String type=template();var pool=Executors.newFixedThreadPool(2);var pending=new ArrayList<Future<Integer>>();
        try {
            tx(()->{jdbc.queryForList("select doc_type from doc_template where doc_type=? for update",type);int pid=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                pending.add(pool.submit(()->putCode(first,type,Map.of("titleTpl","甲标题","contentTpl","  甲原文\n "))));
                pending.add(pool.submit(()->putCode(second,type,Map.of("titleTpl","乙标题","contentTpl","  乙原文\n "))));
                try {awaitBlocked(pid,2);}catch(Exception e){throw new RuntimeException(e);}return null;});
            for(var result:pending)assertEquals(0,result.get(20,TimeUnit.SECONDS));
            var rows=jdbc.queryForList("select title_tpl,content_tpl from doc_template_history where doc_type=? order by id",type);assertEquals(2,rows.size());
            assertEquals("旧标题",rows.get(0).get("title_tpl"));assertEquals("  旧正文\n保持原样  ",rows.get(0).get("content_tpl"));
            var current=jdbc.queryForMap("select title_tpl,content_tpl from doc_template where doc_type=?",type);
            assertEquals(Set.of("甲标题","乙标题"),Set.of((String)rows.get(1).get("title_tpl"),(String)current.get("title_tpl")));
            for(var row:List.of(rows.get(1),current))assertEquals("甲标题".equals(row.get("title_tpl"))?"  甲原文\n ":"  乙原文\n ",row.get("content_tpl"));
        } finally {stop(pool);}
    }

    @Test void caseReceiptRechecksOwnerAfterWaitingForItsCaseLock() throws Exception {
        var owner=actor("HANDLER");var next=actor("HANDLER");long id=fresh(owner).getId(),transfer=transfer(null,id);
        assertEquals(2080,caseRace(id,owner,"/transfers/"+transfer+"/receipt",Map.of("receiptNo","不应登记"),()->jdbc.update("update case_file set owner_user=? where id=?",next.name(),id)));
        assertNull(jdbc.queryForObject("select receipt_no from case_transfer where id=?",String.class,transfer));
    }

    @Test void historicalCrossSourceTransferCannotUseAnOwnedCaseToWriteAForeignClueReceipt() throws Exception {
        var owner=actor("HANDLER");var foreign=actor("HANDLER");
        long ownClue=clues.create(new ClueService.ClueCreateReq("COMPLAINT","本案来源","回归医院","PROVIDER",LocalDate.now(),owner.name()),owner.name()).getId();
        long foreignClue=clues.create(new ClueService.ClueCreateReq("COMPLAINT","他人来源","另一医院","PROVIDER",LocalDate.now(),foreign.name()),foreign.name()).getId();
        long id=fresh(owner,ownClue).getId(),inconsistent=transfer(foreignClue,id);
        var before=jdbc.queryForMap("select * from case_transfer where id=?",inconsistent);
        assertEquals(2059,postCode(owner,"/transfers/"+inconsistent+"/receipt",Map.of("receiptNo","不得越界登记")));
        assertEquals(before,jdbc.queryForMap("select * from case_transfer where id=?",inconsistent),"Historical source bindings and receipt remain untouched");
        long matching=transfer(ownClue,id),caseOnly=transfer(null,id);
        assertEquals(0,postCode(owner,"/transfers/"+matching+"/receipt",Map.of("receiptNo","一致来源回执")));
        assertEquals(0,postCode(owner,"/transfers/"+caseOnly+"/receipt",Map.of("receiptNo","仅案件回执")));
        assertEquals("一致来源回执",jdbc.queryForObject("select receipt_no from case_transfer where id=?",String.class,matching));
        assertEquals("仅案件回执",jdbc.queryForObject("select receipt_no from case_transfer where id=?",String.class,caseOnly));
    }

    @Test void agreementReplyRechecksActualWriterRoleAfterWaitingForCaseLock() throws Exception {
        var owner=actor("HANDLER");var leader=actor("LEADER");long id=fresh(owner).getId();
        long action=jdbc.queryForObject("insert into agreement_action(case_id,action,org,sent_at) values(?,'INTERVIEW','回归机构',current_date) returning id",Long.class,id);
        assertEquals(403,caseRace(id,leader,"/agreement-actions/"+action+"/reply",Map.of("result","不应回复"),()->role(leader,"LEGAL")));
        var row=jdbc.queryForMap("select replied_at,result from agreement_action where id=?",action);assertNull(row.get("replied_at"));assertNull(row.get("result"));
    }

    @Test void clueOnlyReceiptLocksCasesBeforeActorAndRechecksSourceAfterDowngrade() throws Exception {
        var owner=actor("HANDLER");var leader=actor("LEADER");
        long clue=clues.create(new ClueService.ClueCreateReq("COMPLAINT","来源回执权限核查","回归医院","PROVIDER",LocalDate.now(),owner.name()),owner.name()).getId();
        long id=fresh(owner,clue).getId(),transfer=transfer(clue,null);
        assertEquals(2080,caseRace(id,leader,"/transfers/"+transfer+"/receipt",Map.of("receiptNo","不应登记"),()->role(leader,"HANDLER")));
        assertNull(jdbc.queryForObject("select receipt_no from case_transfer where id=?",String.class,transfer));
    }
}
