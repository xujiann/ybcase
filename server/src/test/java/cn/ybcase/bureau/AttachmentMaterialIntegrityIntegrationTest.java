package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.*;
import cn.ybcase.bureau.web.AttachmentController;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/** All rows and files are disposable fixtures; never point this suite at the development database. */
@SpringBootTest
@ActiveProfiles("test")
class AttachmentMaterialIntegrityIntegrationTest {
    @Autowired AttachmentController attachments;
    @Autowired AttachmentStorage storage;
    @Autowired PrecaseAttachmentService sources;
    @Autowired CaseService cases;
    @Autowired ClueService clues;
    @Autowired OversightService oversight;
    @Autowired CaseCauseRepository causes;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired BureauConfig config;
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;
    final Map<String,String> original = new LinkedHashMap<>();
    final List<Path> ownFiles = new ArrayList<>();
    final Path ownRoot = Path.of("data","recovery","attachment-integrity-test-files",UUID.randomUUID().toString()).toAbsolutePath().normalize();
    static final byte[] BYTES = "attachment integrity\u0000医保材料".getBytes(StandardCharsets.UTF_8);
    volatile Pause pause;
    static class Pause {
        final Predicate<String> sql;
        final CountDownLatch reached=new CountDownLatch(1),resume=new CountDownLatch(1);
        final AtomicBoolean claimed=new AtomicBoolean();
        Pause(Predicate<String> sql){this.sql=sql;}
    }

    record Actor(Long id, String username, Authentication auth) {}
    @BeforeEach void prepare() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()",String.class));
        auth(new Actor(0L,"admin",new UsernamePasswordAuthenticationToken("admin","test",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))));
        cfg("case_view_scope","SELF"); cfg("attachment_storage","DB");
        doAnswer(invocation->{
            Object result=invocation.callRealMethod();
            Pause current=pause;
            if(current!=null&&current.sql.test(invocation.getArgument(0,String.class))&&current.claimed.compareAndSet(false,true)){
                current.reached.countDown();assertTrue(current.resume.await(20,TimeUnit.SECONDS),"Paused attachment SELECT was not released");
            }
            return result;
        }).when(jdbc).queryForList(anyString(),any(Object[].class));
    }
    @AfterEach void restore() throws IOException {
        if(pause!=null)pause.resume.countDown();pause=null;
        original.forEach((k,v)-> {jdbc.update("update sys_config set cfg_value=? where cfg_key=?",v,k);config.onConfigChanged(new ConfigChangedEvent(k));});
        SecurityContextHolder.clearContext();
        for (Path path : ownFiles) Files.deleteIfExists(path);
    }
    void cfg(String key,String value) {
        original.computeIfAbsent(key,k->jdbc.queryForObject("select cfg_value from sys_config where cfg_key=?",String.class,k));
        jdbc.update("update sys_config set cfg_value=? where cfg_key=?",value,key);config.onConfigChanged(new ConfigChangedEvent(key));
    }
    Actor actor(String role) {
        var u=new SysUser();u.setUsername("material_"+System.nanoTime());u.setRealName("附件回归");u.setPassword("unused-service-fixture");
        u.getRoles().add(roles.findByCode(role).orElseThrow());u=users.saveAndFlush(u);
        return new Actor(u.getId(),u.getUsername(),new UsernamePasswordAuthenticationToken(u.getUsername(),"test",List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
    void auth(Actor actor) { SecurityContextHolder.getContext().setAuthentication(actor.auth()); }
    long fresh(Actor owner) {
        long cause=causes.findAll().stream().filter(c->c.getItemNo()==13).findFirst().orElseThrow().getId();
        return cases.create(new CaseService.CaseCreateReq(null,cause,"NORMAL","ATT-"+System.nanoTime(),"PROVIDER",null,null,null,null,
                "附件一致性回归",BigDecimal.TEN,List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),null,null,null,null),owner.username()).getId();
    }
    long clue(Actor owner) {
        return clues.create(new ClueService.ClueCreateReq("COMPLAINT","附件权限回归","医院","PROVIDER",LocalDate.now(),owner.username()),owner.username()).getId();
    }
    long inspection(Actor owner) {
        long id=((Number)oversight.createInspection(new OversightService.InspectionReq(null,"附件检查","PROVIDER","王办案、张协办",LocalDate.now()),owner.username()).get("id")).longValue();
        oversight.completeInspection(id,"发现涉嫌违法",true,owner.username());return id;
    }
    MockMultipartFile file() { return new MockMultipartFile("file","材料.bin","application/octet-stream",BYTES); }
    byte[] bytes(org.springframework.core.io.Resource resource)throws IOException {try(var stream=resource.getInputStream()){return stream.readAllBytes();}}
    long upload(long caseId,Actor owner) {
        auth(owner);
        try { attachments.upload(caseId,file(),null,null,"OTHER",owner.auth()); }
        catch(IOException e) { throw new UncheckedIOException(e); }
        return jdbc.queryForObject("select max(id) from case_attachment where case_id=?",Long.class,caseId);
    }
    <T>T tx(Supplier<T> work) { return new TransactionTemplate(tm).execute(s->work.get()); }
    <T>Future<T> async(ExecutorService pool,Actor actor,CompletableFuture<Integer> pid,Supplier<T> work) {
        return pool.submit(()-> {auth(actor);try{return tx(()->{pid.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return work.get();});}
        finally{SecurityContextHolder.clearContext();}});
    }
    void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(25);
        assertTrue(condition.getAsBoolean(),"Expected PostgreSQL blocking relation was not observed");
    }
    boolean blocked(int pid) {return Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0",Boolean.class,pid));}
    void waitFor(CountDownLatch signal) {try{assertTrue(signal.await(20,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}}
    void failure(Future<?> work,int code) {
        var e=assertThrows(ExecutionException.class,()->work.get(20,TimeUnit.SECONDS));assertEquals(code,assertInstanceOf(BizException.class,e.getCause()).code);
    }
    void stop(ExecutorService pool)throws Exception {pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}

    @Test void waitingUploadRechecksOwnershipAfterHandover() throws Exception {waitingCaseWrite("UPLOAD","OWNER");}
    @Test void waitingVoidRechecksOwnershipAfterHandover() throws Exception {waitingCaseWrite("VOID","OWNER");}
    @Test void waitingUploadRechecksCurrentWriterRole() throws Exception {waitingCaseWrite("UPLOAD","ROLE");}
    @Test void waitingVoidRechecksCurrentWriterRole() throws Exception {waitingCaseWrite("VOID","ROLE");}
    void waitingCaseWrite(String action,String change)throws Exception {
        Actor owner=actor("HANDLER"),replacement=actor("HANDLER");long cid=fresh(owner),aid=upload(cid,owner);
        int before=jdbc.queryForObject("select count(*) from case_attachment where case_id=?",Integer.class,cid);
        var pool=Executors.newFixedThreadPool(2);var locked=new CountDownLatch(1);var resume=new CountDownLatch(1);var pid=new CompletableFuture<Integer>();
        try {
            var holder=pool.submit(()->tx(()->{CaseGuards.lock(jdbc,cid);
                if("OWNER".equals(change))jdbc.update("update case_file set owner_user=? where id=?",replacement.username(),cid);
                else jdbc.update("delete from sys_user_role where user_id=?",owner.id());
                locked.countDown();waitFor(resume);return null;}));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            var writer=async(pool,owner,pid,()->{
                // Equivalent to the pre-controller interceptor: READ COMMITTED still sees the old owner.
                cases.assertInScope(cid,owner.username(),false);
                if("VOID".equals(action))attachments.voidAttachment(cid,aid,Map.of("reason","应拒绝并发旧权限"),owner.auth());
                else try{attachments.upload(cid,file(),null,null,"OTHER",owner.auth());}catch(IOException e){throw new UncheckedIOException(e);}
                return null;});
            int backend=pid.get(10,TimeUnit.SECONDS);await(()->blocked(backend));resume.countDown();holder.get(20,TimeUnit.SECONDS);
            failure(writer,"OWNER".equals(change)?2080:403);
            assertEquals(before,jdbc.queryForObject("select count(*) from case_attachment where case_id=?",Integer.class,cid));
            assertFalse(jdbc.queryForObject("select voided from case_attachment where id=?",Boolean.class,aid));
        }finally{resume.countDown();stop(pool);}
    }

    @Test void waitingSourceUploadRechecksCurrentWriterRole()throws Exception {
        Actor owner=actor("HANDLER");long clue=clue(owner);var pool=Executors.newFixedThreadPool(2);
        var locked=new CountDownLatch(1);var resume=new CountDownLatch(1);var pid=new CompletableFuture<Integer>();
        try {
            var holder=pool.submit(()->tx(()->{jdbc.queryForList("select id from case_clue where id=? for update",clue);
                jdbc.update("delete from sys_user_role where user_id=?",owner.id());locked.countDown();waitFor(resume);return null;}));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            var upload=async(pool,owner,pid,()->{try{return sources.upload("CLUE",clue,file(),"OTHER",owner.auth());}catch(IOException e){throw new UncheckedIOException(e);}});
            int backend=pid.get(10,TimeUnit.SECONDS);await(()->blocked(backend));resume.countDown();holder.get(20,TimeUnit.SECONDS);failure(upload,403);
            assertEquals(0,jdbc.queryForObject("select count(*) from case_attachment where source_clue_id=?",Integer.class,clue));
        }finally{resume.countDown();stop(pool);}
    }

    @Test void downloadCannotCombineOldUnvoidedBytesWithNewOwnerAuthorization()throws Exception {mixedDownloadSnapshot(false);}
    @Test void sourceDownloadCannotCombineOldUnvoidedBytesWithNewAdoptedCaseAuthorization()throws Exception {mixedDownloadSnapshot(true);}
    void mixedDownloadSnapshot(boolean source)throws Exception {
        Actor owner=actor("HANDLER"),reader=actor("HANDLER");long clue=source?clue(owner):0,cid=fresh(source?reader:owner);
        long aid=source?sources.upload("CLUE",clue,file(),"OTHER",owner.auth()):upload(cid,owner);
        pause=new Pause(sql->sql.contains("from case_attachment")&&sql.toLowerCase(Locale.ROOT).contains("where id"));
        var pool=Executors.newSingleThreadExecutor();
        try {
            // The service/controller must establish its own real transaction, including isolation level.
            var download=pool.submit(()->{auth(reader);try{return source?sources.download(aid,reader.auth()):attachments.download(aid,reader.auth());}
                finally{SecurityContextHolder.clearContext();}});
            assertTrue(pause.reached.await(10,TimeUnit.SECONDS),"The real initial attachment SELECT was not reached");
            tx(()->{
                if(source)jdbc.queryForList("select id from case_clue where id=? for update",clue);
                CaseGuards.lock(jdbc,cid);
                jdbc.update("update case_attachment set voided=true,void_reason='误传',voided_by=? where id=?",owner.username(),aid);
                if(source){jdbc.update("update case_file set clue_id=? where id=?",clue,cid);jdbc.update("update case_attachment set case_id=? where id=?",cid,aid);
                    jdbc.update("update case_clue set status='FILED' where id=?",clue);}
                else jdbc.update("update case_file set owner_user=? where id=?",reader.username(),cid);
                return null;
            });
            pause.resume.countDown();var e=assertThrows(ExecutionException.class,()->download.get(20,TimeUnit.SECONDS));
            int code=assertInstanceOf(BizException.class,e.getCause()).code;
            assertTrue(code==2080||code==2135,"Snapshot scope or freshly locked void state must deny the mixed read: "+code);
            assertTrue(jdbc.queryForObject("select voided from case_attachment where id=?",Boolean.class,aid));
            assertArrayEquals(BYTES,jdbc.queryForObject("select data from case_attachment where id=?",byte[].class,aid));
        }finally{pause.resume.countDown();stop(pool);}
    }

    @Test void voidedBytesRemainAvailableOnlyToCurrentManagers()throws Exception {
        Actor owner=actor("HANDLER"),manager=actor("ADMIN"),legal=actor("LEGAL");long cid=fresh(owner),aid=upload(cid,owner);
        attachments.voidAttachment(cid,aid,Map.of("reason","误传材料留痕"),owner.auth());
        assertEquals(2135,assertThrows(BizException.class,()->attachments.download(aid,owner.auth())).code);
        auth(legal);assertEquals(2135,assertThrows(BizException.class,()->attachments.download(aid,legal.auth())).code);
        auth(manager);var response=attachments.download(aid,manager.auth());assertEquals(BYTES.length,response.getHeaders().getContentLength());
        assertArrayEquals(BYTES,bytes(response.getBody()));
    }

    @Test void convertedInspectionAttachmentCanBeVoidedThroughItsCurrentClue()throws Exception {
        Actor owner=actor("HANDLER");auth(owner);long inspection=inspection(owner);
        long aid=sources.upload("INSPECTION",inspection,file(),"OTHER",owner.auth());
        long clue=oversight.inspectionToClue(inspection,owner.username()).getId();
        sources.voidAttachment(aid,"转线索后仍在来源正确留痕",owner.auth());
        assertTrue(jdbc.queryForObject("select voided from case_attachment where id=?",Boolean.class,aid));
        assertEquals(inspection,jdbc.queryForObject("select source_inspection_id from case_attachment where id=?",Long.class,aid));
        assertEquals(clue,jdbc.queryForObject("select clue_id from inspection where id=?",Long.class,inspection));
    }

    @Test void conversionDuringVoidSourceLookupRejectsAndRequestsRefresh()throws Exception {
        Actor owner=actor("HANDLER");auth(owner);long inspection=inspection(owner);
        long aid=sources.upload("INSPECTION",inspection,file(),"OTHER",owner.auth());
        pause=new Pause(sql->sql.startsWith("select * from inspection where id="));var pool=Executors.newSingleThreadExecutor();
        try{
            var voiding=pool.submit(()->{auth(owner);try{sources.voidAttachment(aid,"不得锁反向来源",owner.auth());return null;}
                finally{SecurityContextHolder.clearContext();}});
            assertTrue(pause.reached.await(10,TimeUnit.SECONDS));
            oversight.inspectionToClue(inspection,owner.username());
            pause.resume.countDown();failure(voiding,2146);
            assertFalse(jdbc.queryForObject("select voided from case_attachment where id=?",Boolean.class,aid));
        }finally{pause.resume.countDown();stop(pool);}
    }

    Map<String,Object> row() {
        var row=new HashMap<String,Object>();row.put("filename","历史材料.bin");row.put("content_type","application/octet-stream");
        row.put("size_bytes",(long)BYTES.length);row.put("data",BYTES);return row;
    }
    @Test void missingOrMismatchedDatabaseBytesAreAnIntentionalStorageError() {
        var row=row();row.remove("data");assertEquals(2054,assertThrows(BizException.class,()->storage.response(row)).code);
        row.put("data",BYTES);row.put("size_bytes",BYTES.length+1L);assertEquals(2054,assertThrows(BizException.class,()->storage.response(row)).code);
        row.put("size_bytes",-1L);assertEquals(2054,assertThrows(BizException.class,()->storage.response(row)).code);
    }
    Path ownDirectory()throws IOException {
        Path root=ownRoot;
        assertFalse(root.toString().toLowerCase(Locale.ROOT).contains("onedrive"));Files.createDirectories(root);return root;
    }
    @Test void externalStorageRequiresARegularReadableFileWithItsRegisteredLength()throws IOException {
        Path dir=ownDirectory(),file=Files.createTempFile(dir,"own-",".bin");ownFiles.add(file);Files.write(file,BYTES);
        var row=row();row.put("file_path",file.toString());var response=storage.response(row);
        assertEquals(BYTES.length,response.getHeaders().getContentLength());assertArrayEquals(BYTES,bytes(response.getBody()));
        row.put("size_bytes",BYTES.length+1L);assertEquals(2054,assertThrows(BizException.class,()->storage.response(row)).code);
        row.put("file_path",dir.toString());assertEquals(2054,assertThrows(BizException.class,()->storage.response(row)).code);
        row.put("file_path",file+"\u0000invalid");assertEquals(2054,assertThrows(BizException.class,()->storage.response(row)).code);
        row.put("file_path",file.resolveSibling("missing-"+UUID.randomUUID()).toString());assertEquals(2054,assertThrows(BizException.class,()->storage.response(row)).code);
    }
    @Test void validLegacyMimeFallbackPreservesExactBytesAndEncodedFilename()throws IOException {
        var row=row();row.put("content_type","invalid mime type");row.put("filename","材料\r\n X-Test=unsafe.bin");
        var response=storage.response(row);assertEquals("application/octet-stream",response.getHeaders().getContentType().toString());
        assertFalse(response.getHeaders().getFirst("Content-Disposition").contains("\r"));
        assertEquals(BYTES.length,response.getHeaders().getContentLength());assertArrayEquals(BYTES,bytes(response.getBody()));
    }
    @Test void databaseFailureRemovesTheNewExternalFileAndNeverCreatesAnAttachmentRow()throws IOException {
        Actor owner=actor("HANDLER");long cid=fresh(owner);Path root=ownDirectory();cfg("attachment_storage","FILE");cfg("attachment_dir",root.toString());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tx(()->{
            try{return storage.store(cid,null,null,Long.MAX_VALUE,null,file(),"OTHER",owner.username());}catch(IOException e){throw new UncheckedIOException(e);}}));
        assertEquals(0,jdbc.queryForObject("select count(*) from case_attachment where case_id=?",Integer.class,cid));
        try(var files=Files.list(root.resolve(Long.toString(cid)))){assertEquals(0,files.count());}
    }
    @Test void partialTransferFailureRemovesThePartialFileOnRollback()throws IOException {
        Actor owner=actor("HANDLER");long cid=fresh(owner);Path root=ownDirectory();cfg("attachment_storage","FILE");cfg("attachment_dir",root.toString());
        var broken=new MockMultipartFile("file","partial.bin","application/octet-stream",BYTES){
            @Override public void transferTo(Path dest)throws IOException {Files.write(dest,new byte[]{1,2});throw new IOException("deliberate isolated partial transfer");}};
        assertThrows(UncheckedIOException.class,()->tx(()->{try{return storage.store(cid,null,null,null,null,broken,"OTHER",owner.username());}
            catch(IOException e){throw new UncheckedIOException(e);}}));
        assertEquals(0,jdbc.queryForObject("select count(*) from case_attachment where case_id=?",Integer.class,cid));
        try(var files=Files.list(root.resolve(Long.toString(cid)))){assertEquals(0,files.count());}
    }
    @Test void nulMetadataIsRejectedBeforeBothDatabaseAndFileStorageWrites()throws IOException {
        Actor owner=actor("HANDLER");long cid=fresh(owner);Path root=ownDirectory();cfg("attachment_dir",root.toString());
        for(String mode:List.of("DB","FILE")){
            cfg("attachment_storage",mode);
            for(var file:List.of(new MockMultipartFile("file","bad\u0000name","application/octet-stream",BYTES),new MockMultipartFile("file","good.bin","bad\u0000type",BYTES)))
                assertEquals(2054,assertThrows(BizException.class,()->tx(()->{try{return storage.store(cid,null,null,null,null,file,"OTHER",owner.username());}
                    catch(IOException e){throw new UncheckedIOException(e);}})).code);
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from case_attachment where case_id=?",Integer.class,cid));
        assertFalse(Files.exists(root.resolve(Long.toString(cid))),"Metadata validation must precede filesystem mutation");
    }
    @Test void incompleteSuccessfulTransferCannotCreateMismatchedDatabaseOrFileRows()throws IOException {
        Actor owner=actor("HANDLER");long cid=fresh(owner);Path root=ownDirectory();cfg("attachment_dir",root.toString());
        var shortFile=new MockMultipartFile("file","short.bin","application/octet-stream",BYTES){
            @Override public byte[] getBytes(){return new byte[]{1,2};}
            @Override public void transferTo(Path dest)throws IOException {Files.write(dest,new byte[]{1,2});}};
        for(String mode:List.of("DB","FILE")){
            cfg("attachment_storage",mode);
            assertEquals(2054,assertThrows(BizException.class,()->tx(()->{try{return storage.store(cid,null,null,null,null,shortFile,"OTHER",owner.username());}
                catch(IOException e){throw new UncheckedIOException(e);}})).code);
            assertEquals(0,jdbc.queryForObject("select count(*) from case_attachment where case_id=?",Integer.class,cid));
        }
        try(var files=Files.list(root.resolve(Long.toString(cid)))){assertEquals(0,files.count());}
    }
    @Test void unknownTransactionCompletionPreservesPotentiallyCommittedExternalBytesAndMetadata()throws IOException {
        Actor owner=actor("HANDLER");long cid=fresh(owner);Path root=ownDirectory();cfg("attachment_storage","FILE");cfg("attachment_dir",root.toString());
        var registered=new AtomicReference<org.springframework.transaction.support.TransactionSynchronization>();
        long aid=tx(()->{
            try{
                long created=storage.store(cid,null,null,null,null,file(),"OTHER",owner.username());
                var callbacks=org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().stream()
                        .filter(callback->callback.getClass().getEnclosingClass()==AttachmentStorage.class).toList();
                assertEquals(1,callbacks.size());registered.set(callbacks.get(0));return created;
            }catch(IOException e){throw new UncheckedIOException(e);}
        });
        Path stored=Path.of(jdbc.queryForObject("select file_path from case_attachment where id=?",String.class,aid));
        var before=jdbc.queryForMap("select * from case_attachment where id=?",aid);
        try{
            // Unit-level completion semantics only: no real connection failure or commit acknowledgement is injected.
            registered.get().afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_UNKNOWN);
            assertArrayEquals(BYTES,Files.readAllBytes(stored));
            assertEquals(before,jdbc.queryForMap("select * from case_attachment where id=?",aid));
            assertArrayEquals(BYTES,bytes(storage.response(before).getBody()));
        }finally{
            // Remove only this exact test-owned row/file after checking that UNKNOWN retained both.
            jdbc.update("delete from case_attachment where id=? and case_id=?",aid,cid);Files.deleteIfExists(stored);
        }
    }
}
