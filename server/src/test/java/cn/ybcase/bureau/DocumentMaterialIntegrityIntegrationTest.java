package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.*;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real PG transactions; the JDBC spy only pauses a SELECT and always executes the real query. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DocumentMaterialIntegrityIntegrationTest {
    @Autowired CaseService cases;
    @Autowired EvidenceService evidence;
    @Autowired DocumentService documents;
    @Autowired ReviewMaterialService materials;
    @Autowired EvidenceCatalogService catalog;
    @Autowired CaseCauseRepository causes;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired BureauConfig config;
    @Autowired PlatformTransactionManager tm;
    @MockitoSpyBean JdbcTemplate jdbc;
    final Map<String,String> original = new LinkedHashMap<>();
    volatile Pause pause;
    volatile Pause pauseAfter;
    record Actor(Long id, String name, String token) {}
    static class Pause {
        final Predicate<String> sql;
        final CountDownLatch reached = new CountDownLatch(1), resume = new CountDownLatch(1);
        final AtomicBoolean claimed = new AtomicBoolean();
        Pause(Predicate<String> sql) { this.sql=sql; }
    }

    @BeforeEach void configure() {
        assertNotEquals("ybcase",jdbc.queryForObject("select current_database()",String.class));
        SecurityContextHolder.clearContext();
        cfg("case_view_scope","SELF");
        doAnswer(invocation -> {
            Pause current = pause;
            if (current != null && current.sql.test(invocation.getArgument(0, String.class)) && current.claimed.compareAndSet(false,true)) {
                current.reached.countDown();
                assertTrue(current.resume.await(20,TimeUnit.SECONDS),"Paused material SELECT was not released");
            }
            return invocation.callRealMethod();
        }).when(jdbc).queryForList(anyString(),any(Object[].class));
        doAnswer(invocation -> {
            Pause current=pause;
            if(current!=null&&current.sql.test(invocation.getArgument(0,String.class))&&current.claimed.compareAndSet(false,true)) {
                current.reached.countDown();assertTrue(current.resume.await(20,TimeUnit.SECONDS),"Paused policy SELECT was not released");
            }
            return invocation.callRealMethod();
        }).when(jdbc).queryForList(anyString(),eq(String.class),any(Object[].class));
        doAnswer(invocation -> {
            Object result=invocation.callRealMethod();Pause current=pauseAfter;
            if(current!=null&&current.sql.test(invocation.getArgument(0,String.class))&&current.claimed.compareAndSet(false,true)) {
                current.reached.countDown();assertTrue(current.resume.await(20,TimeUnit.SECONDS),"Scope SELECT was not released");
            }
            return result;
        }).when(jdbc).queryForObject(anyString(),eq(Integer.class),any(Object[].class));
    }
    @AfterEach void restore() {
        if(pause!=null)pause.resume.countDown();
        if(pauseAfter!=null)pauseAfter.resume.countDown();
        pause=null;
        pauseAfter=null;
        original.forEach((key,value)->{jdbc.update("update sys_config set cfg_value=? where cfg_key=?",value,key);config.onConfigChanged(new ConfigChangedEvent(key));});
        SecurityContextHolder.clearContext();
    }
    void cfg(String key,String value) {
        original.computeIfAbsent(key,k->jdbc.queryForObject("select cfg_value from sys_config where cfg_key=?",String.class,k));
        jdbc.update("update sys_config set cfg_value=? where cfg_key=?",value,key);config.onConfigChanged(new ConfigChangedEvent(key));
    }
    Actor actor(String role) {
        var user=new SysUser();user.setUsername("material_"+System.nanoTime());user.setRealName("材料权限回归");user.setPassword("unused-jwt-fixture");
        user.getRoles().add(roles.findByCode(role).orElseThrow());user=users.saveAndFlush(user);
        return new Actor(user.getId(),user.getUsername(),jwt.issue(user.getUsername(),user.getTokenVersion()));
    }
    CaseFile fresh(Actor owner) {
        long cause=causes.findAll().stream().filter(c->c.getItemNo()==13).findFirst().orElseThrow().getId();
        return cases.create(new CaseService.CaseCreateReq(null,cause,"NORMAL","材料快照原主体","PROVIDER",null,null,null,null,"材料原事实",BigDecimal.TEN,
                List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),null,null,null,null),owner.name());
    }
    long document(long caseId) {
        cases.addDocument(caseId,new CaseService.DocumentReq("OTHER","原文书","正文保持\n原始空格  ",LocalDate.now(),"回归",false,null,null));
        return jdbc.queryForObject("select max(id) from case_document where case_id=?",Long.class,caseId);
    }
    int postCode(Actor actor,String path,Object body) throws Exception {
        var response=mvc.perform(post("/api/bureau"+path).header("Authorization","Bearer "+actor.token()).contentType("application/json")
                .content(mapper.writeValueAsString(body))).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString()).path("code").asInt();
    }
    int getCode(Actor actor,String path) throws Exception {
        var response=mvc.perform(get("/api/bureau"+path).header("Authorization","Bearer "+actor.token())).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString()).path("code").asInt();
    }
    <T>T tx(Supplier<T> work) {return new TransactionTemplate(tm).execute(status->work.get());}
    void stop(ExecutorService pool) throws Exception {pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    void waitForBlockedBy(int blocker) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline) {
            Integer count=jdbc.queryForObject("select count(distinct pid) from pg_locks where not granted and ?=any(pg_blocking_pids(pid))",Integer.class,blocker);
            if(count!=null&&count>0)return;
            Thread.sleep(25);
        }
        fail("Request did not wait on the held case row");
    }
    int writeAfterConcurrentChange(long id,Actor actor,String path,Object body,Runnable change) throws Exception {
        var pool=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<Integer>>();
        try {
            tx(()->{
                CaseGuards.lock(jdbc,id);int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                change.run();pending.set(pool.submit(()->postCode(actor,path,body)));
                try {waitForBlockedBy(blocker);}catch(Exception e){throw new RuntimeException(e);}
                return null;
            });
            return pending.get().get(20,TimeUnit.SECONDS);
        } finally {stop(pool);}
    }
    Map<String,Object> documentBody() {return Map.of("docType","OTHER","title","不能越权写入","content","不能越权写入");}
    Map<String,Object> evidenceBody() {return Map.of("type","DOCUMENT","name","不能越权证据");}

    @Test void documentRequiredFieldsAndColumnBoundsAreValidatedWithoutPartialInsert() {
        long id=fresh(actor("HANDLER")).getId();
        for(var bad:List.of(
                new CaseService.DocumentReq(null,"标题","内容",null,null,false,null,null),
                new CaseService.DocumentReq("OTHER","  ","内容",null,null,false,null,null),
                new CaseService.DocumentReq("OTHER","标题",null,null,null,false,null,null),
                new CaseService.DocumentReq("OTHER","标题","  ",null,null,false,null,null),
                new CaseService.DocumentReq("OTHER","标题","内容",null,"x".repeat(65),false,null,null),
                new CaseService.DocumentReq("x".repeat(33),"标题","内容",null,null,false,null,null),
                new CaseService.DocumentReq("OTHER","x".repeat(256),"内容",null,null,false,null,null),
                new CaseService.DocumentReq("OTHER","标题","内容",null,null,false,"x".repeat(256),null))) {
            assertEquals(2100,assertThrows(BizException.class,()->cases.addDocument(id,bad)).code);
            assertEquals(0,jdbc.queryForObject("select count(*) from case_document where case_id=?",Integer.class,id));
        }
        assertEquals(2100,assertThrows(BizException.class,()->cases.addDocument(id,null)).code);
    }

    @Test void validCustomDocumentTypesAndExactBodyRemainSupported() {
        long id=fresh(actor("HANDLER")).getId();String body="  原稿\n换行和末尾空格  ";
        cases.addDocument(id,new CaseService.DocumentReq("CUSTOM_TYPE","自定义文书",body,null,"回归",false,null,null));
        var row=jdbc.queryForMap("select doc_type,content from case_document where case_id=?",id);
        assertEquals("CUSTOM_TYPE",row.get("doc_type"));assertEquals(body,row.get("content"));
    }

    @Test void nulCharactersCannotReachPostgresOrLeavePartialDocumentEvidenceSignatureOrDelivery() throws Exception {
        var owner=actor("HANDLER");long id=fresh(owner).getId();
        for(int field=0;field<5;field++) {
            String[] values={"OTHER","标题","正文","制作人","备注"};values[field]+="\0";
            var request=new CaseService.DocumentReq(values[0],values[1],values[2],null,values[3],false,values[4],null);
            assertEquals(2100,assertThrows(BizException.class,()->cases.addDocument(id,request)).code);
            assertEquals(0,jdbc.queryForObject("select count(*) from case_document where case_id=?",Integer.class,id));
        }
        for(int field=0;field<4;field++) {
            String[] values={"证据","来源","保管人","备注"};values[field]+="\0";
            var request=new EvidenceService.EvidenceReq("DOCUMENT",values[0],values[1],null,values[2],values[3],false,false);
            assertEquals(2100,assertThrows(BizException.class,()->evidence.addEvidence(id,request)).code);
            assertEquals(0,jdbc.queryForObject("select count(*) from case_evidence where case_id=?",Integer.class,id));
        }
        assertEquals(2053,assertThrows(BizException.class,()->documents.render(id,"OTHER\0")).code);
        long doc=document(id);
        for(String key:List.of("signer","signerRole")) {
            assertEquals(2100,postCode(owner,"/cases/"+id+"/documents/"+doc+"/sign",Map.of(key,"错误\0字段")));
            assertFalse(jdbc.queryForObject("select signed from case_document where id=?",Boolean.class,doc));
            assertEquals(0,jdbc.queryForObject("select count(*) from doc_signature where document_id=?",Integer.class,doc));
        }
        for(String key:List.of("receiver","note","receiptNo","docKind")) {
            var body=new LinkedHashMap<String,Object>();body.put("method","DIRECT");body.put("documentId",doc);body.put(key,"错误\0字段");
            assertEquals(2100,postCode(owner,"/cases/"+id+"/doc-deliveries",body));
            assertEquals(0,jdbc.queryForObject("select count(*) from case_delivery where case_id=?",Integer.class,id));
        }
    }

    @Test void invalidEvidenceIsAnIntentionalErrorAndCannotCreatePartialMaterials() {
        long id=fresh(actor("HANDLER")).getId();
        assertEquals(2027,assertThrows(BizException.class,()->evidence.addEvidence(id,null)).code);
        assertEquals(2027,assertThrows(BizException.class,()->evidence.addEvidence(id,new EvidenceService.EvidenceReq(null,"证据",null,null,null,null,false,false))).code);
        for(var bad:List.of(
                new EvidenceService.EvidenceReq("DOCUMENT",null,null,null,null,null,false,false),
                new EvidenceService.EvidenceReq("DOCUMENT"," ",null,null,null,null,false,false),
                new EvidenceService.EvidenceReq("DOCUMENT","证据","x".repeat(256),null,null,null,false,false),
                new EvidenceService.EvidenceReq("DOCUMENT","证据",null,null,"x".repeat(65),null,false,false),
                new EvidenceService.EvidenceReq("DOCUMENT","证据",null,null,null,"x".repeat(513),false,false)))
            assertEquals(2100,assertThrows(BizException.class,()->evidence.addEvidence(id,bad)).code);
        assertEquals(0,jdbc.queryForObject("select count(*) from case_evidence where case_id=?",Integer.class,id));
        assertEquals(2028,assertThrows(BizException.class,()->evidence.disposeHold(id,999999L,null)).code);
    }

    @Test void signatureFieldsRejectInvalidDataBeforeWritingSignatureOrSignedFlag() throws Exception {
        var owner=actor("HANDLER");long id=fresh(owner).getId(),doc=document(id);
        for(Object invalid:Arrays.asList(null," ",123,Map.of("name","签名"),"x".repeat(65))) {
            var body=new LinkedHashMap<String,Object>();body.put("signer",invalid);
            assertEquals(2100,postCode(owner,"/cases/"+id+"/documents/"+doc+"/sign",body));
            assertEquals(0,jdbc.queryForObject("select count(*) from doc_signature where document_id=?",Integer.class,doc));
            assertFalse(jdbc.queryForObject("select signed from case_document where id=?",Boolean.class,doc));
        }
        for(Object invalid:Arrays.asList(null," ",false,"x".repeat(33))) {
            var body=new LinkedHashMap<String,Object>();body.put("signerRole",invalid);
            assertEquals(2100,postCode(owner,"/cases/"+id+"/documents/"+doc+"/sign",body));
            assertEquals(0,jdbc.queryForObject("select count(*) from doc_signature where document_id=?",Integer.class,doc));
        }
        assertEquals(0,postCode(owner,"/cases/"+id+"/documents/"+doc+"/sign",Map.of()));
        assertEquals(owner.name(),jdbc.queryForObject("select operator from doc_signature where document_id=?",String.class,doc));
        assertEquals(0,getCode(owner,"/cases/"+id+"/documents/"+doc+"/verify"));
    }

    @Test void foreignCasesAndCrossCaseDocumentIdsRemainHiddenWhileLegalReadsRemainAllowed() throws Exception {
        var owner=actor("HANDLER");var foreign=actor("HANDLER");var legal=actor("LEGAL");long id=fresh(owner).getId(),doc=document(id);
        for(String suffix:List.of("/documents/"+doc,"/documents/render?docType=NOTICE","/documents/"+doc+"/verify","/archive-catalog","/archive-full","/evidence-catalog")) {
            assertEquals(2080,getCode(foreign,"/cases/"+id+suffix),suffix);
            assertEquals(0,getCode(legal,"/cases/"+id+suffix),suffix);
        }
        assertEquals(403,postCode(legal,"/cases/"+id+"/documents",documentBody()));
        assertEquals(403,postCode(legal,"/cases/"+id+"/evidences",evidenceBody()));
        assertEquals(403,postCode(legal,"/cases/"+id+"/documents/"+doc+"/sign",Map.of()));
        long other=fresh(owner).getId();
        assertEquals(2045,getCode(owner,"/cases/"+other+"/documents/"+doc));
        assertEquals(2045,getCode(owner,"/cases/"+other+"/documents/"+doc+"/verify"));
    }

    @Test void deliveryDocumentReferencesCannotBeTruncatedOrCrossCaseAndInvalidFieldsAreAtomic() throws Exception {
        var owner=actor("HANDLER");long id=fresh(owner).getId(),doc=document(id),other=fresh(owner).getId(),foreign=document(other);
        for(Object invalid:List.of(new BigDecimal(doc+".5"),doc+".5",new java.math.BigInteger("9223372036854775808"),-1,true,Map.of("id",doc))) {
            assertEquals(2100,postCode(owner,"/cases/"+id+"/doc-deliveries",Map.of("method","DIRECT","documentId",invalid)));
            assertEquals(0,jdbc.queryForObject("select count(*) from case_delivery where case_id=?",Integer.class,id));
        }
        assertEquals(2041,postCode(owner,"/cases/"+id+"/doc-deliveries",Map.of("method","DIRECT","documentId",foreign)));
        for(var invalid:List.of(Map.<String,Object>of("receiver",Map.of("name","当事人")),Map.<String,Object>of("note","x".repeat(256)),
                Map.<String,Object>of("receiptNo",false),Map.<String,Object>of("docKind","x".repeat(25)))) {
            var body=new LinkedHashMap<String,Object>();body.put("method","DIRECT");body.put("documentId",doc);body.putAll(invalid);
            assertEquals(2100,postCode(owner,"/cases/"+id+"/doc-deliveries",body));
            assertEquals(0,jdbc.queryForObject("select count(*) from case_delivery where case_id=?",Integer.class,id));
        }
        assertEquals(0,postCode(owner,"/cases/"+id+"/doc-deliveries",Map.of("method","DIRECT","documentId",String.valueOf(doc))));
        assertEquals(doc,jdbc.queryForObject("select document_id from case_delivery where case_id=?",Long.class,id));
        assertEquals(0,postCode(owner,"/cases/"+id+"/doc-deliveries",Map.of("method","DIRECT")));
        assertEquals(1,jdbc.queryForObject("select count(*) from case_delivery where case_id=? and document_id is null and doc_kind='OTHER'",Integer.class,id));
    }

    @Test void ownerTransferWinningTheCaseLockPreventsOldOwnerFromAddingDocument() throws Exception {
        var owner=actor("HANDLER");var next=actor("HANDLER");long id=fresh(owner).getId();
        assertEquals(2080,writeAfterConcurrentChange(id,owner,"/cases/"+id+"/documents",documentBody(),()->jdbc.update("update case_file set owner_user=? where id=?",next.name(),id)));
        assertEquals(0,jdbc.queryForObject("select count(*) from case_document where case_id=?",Integer.class,id));
    }

    @Test void ownerTransferWinningTheCaseLockPreventsOldOwnerFromAddingEvidence() throws Exception {
        var owner=actor("HANDLER");var next=actor("HANDLER");long id=fresh(owner).getId();
        assertEquals(2080,writeAfterConcurrentChange(id,owner,"/cases/"+id+"/evidences",evidenceBody(),()->jdbc.update("update case_file set owner_user=? where id=?",next.name(),id)));
        assertEquals(0,jdbc.queryForObject("select count(*) from case_evidence where case_id=?",Integer.class,id));
    }

    @Test void ownerTransferWinningTheCaseLockPreventsOldOwnerSigningOrDelivering() throws Exception {
        var owner=actor("HANDLER");var next=actor("HANDLER");
        for(boolean signing:List.of(true,false)) {
            long id=fresh(owner).getId(),doc=document(id);
            String path="/cases/"+id+(signing?"/documents/"+doc+"/sign":"/doc-deliveries");
            Object body=signing?Map.of():Map.of("method","DIRECT","documentId",doc);
            assertEquals(2080,writeAfterConcurrentChange(id,owner,path,body,()->jdbc.update("update case_file set owner_user=? where id=?",next.name(),id)));
            assertFalse(jdbc.queryForObject("select signed from case_document where id=?",Boolean.class,doc));
            assertEquals(0,jdbc.queryForObject("select count(*) from doc_signature where document_id=?",Integer.class,doc));
            assertEquals(0,jdbc.queryForObject("select count(*) from case_delivery where case_id=?",Integer.class,id));
        }
    }

    @Test void leaderRoleDowngradeCannotRetainOldPrivilegedDocumentWrite() throws Exception {
        var owner=actor("HANDLER");var leader=actor("LEADER");long id=fresh(owner).getId();
        assertEquals(2080,writeAfterConcurrentChange(id,leader,"/cases/"+id+"/documents",documentBody(),()->{
            jdbc.queryForList("select id from sys_user where id=? for update",leader.id());
            jdbc.update("delete from sys_user_role where user_id=?",leader.id());
            jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='HANDLER'",leader.id());
        }));
        assertEquals(0,jdbc.queryForObject("select count(*) from case_document where case_id=?",Integer.class,id));
    }

    @Test void archiveWinningTheCaseLockPreventsSigning() throws Exception {
        var owner=actor("HANDLER");long id=fresh(owner).getId(),doc=document(id);
        assertEquals(2031,writeAfterConcurrentChange(id,owner,"/cases/"+id+"/documents/"+doc+"/sign",Map.of(),()->jdbc.update("update case_file set archive_no=? where id=?","TEST-ARCHIVE-"+id,id)));
        assertFalse(jdbc.queryForObject("select signed from case_document where id=?",Boolean.class,doc));
        assertEquals(0,jdbc.queryForObject("select count(*) from doc_signature where document_id=?",Integer.class,doc));
    }

    @Test void aCompletedInterceptorScopeCheckDoesNotAuthorizeLaterRenderAfterTransfer() throws Exception {
        var owner=actor("HANDLER");var next=actor("HANDLER");long id=fresh(owner).getId();
        Pause current=new Pause(sql->sql.startsWith("select count(*) from case_file where id=?"));pauseAfter=current;
        var pool=Executors.newSingleThreadExecutor();
        try {
            var rendered=pool.submit(()->getCode(owner,"/cases/"+id+"/documents/render?docType=NOTICE"));
            assertTrue(current.reached.await(15,TimeUnit.SECONDS));
            jdbc.update("update case_file set owner_user=?,party_name='移交后新材料' where id=?",next.name(),id);
            current.resume.countDown();assertEquals(2080,rendered.get(20,TimeUnit.SECONDS));
        } finally {current.resume.countDown();stop(pool);pauseAfter=null;}
    }

    @Test void staleLeaderAuthorityCannotExposeVoidedMaterialAfterLiveRoleDowngrade() {
        var owner=actor("HANDLER");long id=fresh(owner).getId();
        jdbc.update("insert into case_attachment(case_id,filename,content_type,size_bytes,data,uploaded_by,voided,void_reason) values(?,?,?,?,?,?,true,?)",
                id,"voided-only.bin","application/octet-stream",1,new byte[]{1},owner.name(),"已作废");
        // This is the principal a request captured before the user's role downgrade committed.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.name(),"fixture",
                List.of(new SimpleGrantedAuthority("ROLE_LEADER"))));
        try {assertTrue(((List<?>)catalog.catalog(id,true).get("items")).isEmpty());}
        finally {SecurityContextHolder.clearContext();}
        assertEquals(1,((List<?>)catalog.catalog(id,true).get("items")).size(),"Trusted internal catalog keeps its explicit include-voided behavior");
    }

    @Test void scopePolicySwitchCannotGrantNewCachePermissionToAnOlderMaterialSnapshot() throws Exception {
        var owner=actor("HANDLER");var reader=actor("HANDLER");long id=fresh(owner).getId();String type="POLICY_"+System.nanoTime();
        jdbc.update("insert into doc_template(doc_type,title_tpl,content_tpl) values(?,?,?)",type,"{{partyName}}","{{summary}}");
        assertEquals("SELF",config.str("case_view_scope","ALL"));
        // MaterialAccess has already read the current actor/roles when this policy SELECT is reached.
        Pause current=new Pause(sql->sql.equals("select cfg_value from sys_config where cfg_key=?"));pause=current;
        var pool=Executors.newSingleThreadExecutor();
        try {
            var rendered=pool.submit(()->{
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(reader.name(),"fixture",
                        List.of(new SimpleGrantedAuthority("ROLE_HANDLER"))));
                try {return documents.render(id,type);}finally {SecurityContextHolder.clearContext();}
            });
            assertTrue(current.reached.await(15,TimeUnit.SECONDS));
            tx(()->{
                jdbc.update("update sys_config set cfg_value='ALL' where cfg_key='case_view_scope'");
                jdbc.update("update case_file set summary='配置切换后的新事实' where id=?",id);
                return null;
            });
            config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
            assertEquals("ALL",config.str("case_view_scope","SELF"),"Process cache must reflect the committed grant while the reader is paused");
            current.resume.countDown();
            var failure=assertThrows(ExecutionException.class,()->rendered.get(20,TimeUnit.SECONDS));
            assertEquals(2080,assertInstanceOf(BizException.class,failure.getCause()).code);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(reader.name(),"fixture",
                    List.of(new SimpleGrantedAuthority("ROLE_HANDLER"))));
            try {assertEquals("配置切换后的新事实",documents.render(id,type).get("content"),"A fresh snapshot must honor the committed ALL policy");}
            finally {SecurityContextHolder.clearContext();}
        } finally {current.resume.countDown();stop(pool);pause=null;jdbc.update("delete from doc_template where doc_type=?",type);}
    }

    @Test void renderingUsesOneDatabaseSnapshotForCaseFactsAndTemplate() throws Exception {
        long id=fresh(actor("HANDLER")).getId();String type="SNAP_"+System.nanoTime();
        jdbc.update("insert into doc_template(doc_type,title_tpl,content_tpl) values(?,?,?)",type,"旧模板 {{partyName}}","原稿 {{summary}}");
        Pause current=new Pause(sql->sql.contains("from doc_template"));pause=current;var pool=Executors.newSingleThreadExecutor();
        try {
            var rendered=pool.submit(()->documents.render(id,type));assertTrue(current.reached.await(15,TimeUnit.SECONDS));
            tx(()->{jdbc.update("update case_file set party_name='材料新主体',summary='材料新事实' where id=?",id);
                jdbc.update("update doc_template set title_tpl='新模板 {{partyName}}',content_tpl='新稿 {{summary}}' where doc_type=?",type);return null;});
            current.resume.countDown();var result=rendered.get(20,TimeUnit.SECONDS);
            assertEquals("旧模板 材料快照原主体",result.get("title"));assertEquals("原稿 材料原事实",result.get("content"));
        } finally {current.resume.countDown();stop(pool);pause=null;jdbc.update("delete from doc_template where doc_type=?",type);}
    }

    @Test void materialCaptureCannotMixOldFactsWithNewDocumentRows() throws Exception {
        long id=fresh(actor("HANDLER")).getId();Pause current=new Pause(sql->sql.contains("from case_document"));pause=current;
        var pool=Executors.newSingleThreadExecutor();
        try {
            var captured=pool.submit(()->materials.capture(id));assertTrue(current.reached.await(15,TimeUnit.SECONDS));
            tx(()->{jdbc.update("update case_file set summary='原子新事实' where id=?",id);
                jdbc.update("insert into case_document(case_id,doc_type,title,content,made_at) values(?,'OTHER','原子新增文书','原子新事实',current_date)",id);return null;});
            current.resume.countDown();var snapshot=mapper.readTree(captured.get(20,TimeUnit.SECONDS).json());
            assertEquals("材料原事实",snapshot.path("caseFacts").path("summary").asText());assertEquals(0,snapshot.path("documents").size());
        } finally {current.resume.countDown();stop(pool);pause=null;}
    }
}
