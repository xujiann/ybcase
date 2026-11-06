package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.entity.CaseReview;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.web.ProcedureController;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Only disposable databases; real PostgreSQL lock interleavings, no production test hooks. */
@SpringBootTest
@ActiveProfiles("test")
class EnforcerRegistryConcurrencyIntegrationTest {
    @Autowired CaseService cases;
    @Autowired ProcedureController controller;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired CaseCauseRepository causes;
    @Autowired JdbcTemplate jdbc;
    @Autowired BureauConfig config;
    @Autowired BureauUserInitializer initializer;
    @Autowired PlatformTransactionManager tm;
    @Autowired DataSource dataSource;
    final Map<String,String> original = new LinkedHashMap<>();
    static void auth() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "test",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    @BeforeEach void start() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class));
        auth(); cfg("enforcer_cert_check", "true"); cfg("review_legal_qualified_required", "true"); cfg("case_view_scope", "SELF");
    }
    @AfterEach void restore() {
        original.forEach((k,v)-> { jdbc.update("update sys_config set cfg_value=? where cfg_key=?", v,k); config.onConfigChanged(new ConfigChangedEvent(k)); });
        SecurityContextHolder.clearContext();
    }
    void cfg(String key,String value) {
        original.computeIfAbsent(key,k->jdbc.queryForObject("select cfg_value from sys_config where cfg_key=?",String.class,k));
        jdbc.update("update sys_config set cfg_value=? where cfg_key=?",value,key);config.onConfigChanged(new ConfigChangedEvent(key));
    }
    SysUser user(String role) {
        var u=new SysUser();u.setUsername("cert_"+System.nanoTime());u.setRealName("证件测试"+u.getUsername());u.setPassword("unused-service-fixture");
        u.getRoles().add(roles.findByCode(role).orElseThrow());return users.saveAndFlush(u);
    }
    record Cert(String name,String no,Long userId) {}
    Cert cert(SysUser mapped,boolean legal) {
        String no="REG-"+System.nanoTime();String name=mapped==null ? "执法员"+no : mapped.getRealName();
        var cert=new Cert(name,no,mapped==null?null:mapped.getId()); save(cert,legal,true,mapped==null?null:mapped.getId(),true);return cert;
    }
    Map<String,Object> body(Cert cert,boolean legal,boolean enabled,Long userId,boolean includeUser) {
        var data=new LinkedHashMap<String,Object>();data.put("name",cert.name());data.put("certNo",cert.no());data.put("dept","测试科室");
        data.put("certExpireAt",LocalDate.now().plusDays(365).toString());data.put("legalQualified",legal);data.put("enabled",enabled);
        if(includeUser)data.put("userId",userId);return data;
    }
    void save(Cert cert,boolean legal,boolean enabled,Long id,boolean includeUser) {
        assertEquals(0,controller.saveEnforcer(body(cert,legal,enabled,id,includeUser)).getCode());
    }
    Long mapping(Cert cert) { return jdbc.queryForObject("select user_id from enforcer where cert_no=?",Long.class,cert.no()); }
    CaseService.CaseCreateReq request(List<CaseService.OfficerReq> officers) {
        long cause=causes.findAll().stream().filter(c->c.getItemNo()==13).findFirst().orElseThrow().getId();
        return new CaseService.CaseCreateReq(null,cause,"NORMAL","REG-CASE-"+System.nanoTime(),"PROVIDER",null,null,null,null,"证件并发回归",BigDecimal.TEN,officers,null,null,null,null);
    }
    List<CaseService.OfficerReq> officers(Cert cert) {
        return List.of(new CaseService.OfficerReq(cert.name(),cert.no(),"LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER"));
    }
    CaseFile fresh(SysUser owner) {
        return cases.create(request(List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER"))),owner.getUsername());
    }
    CaseReview review(CaseFile c) { cases.report(c.getId(),"调查终结报告",c.getOwnerUser());return cases.submitReview(c.getId(),"审核资格测试"); }
    <T>T tx(Supplier<T> work) { return new TransactionTemplate(tm).execute(s->work.get()); }
    <T>Future<T> async(ExecutorService pool,Supplier<T> work) {
        return pool.submit(()->{auth();try{return tx(work);}finally{SecurityContextHolder.clearContext();}});
    }
    void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(25);
        assertTrue(condition.getAsBoolean(),"Expected PostgreSQL blocking relation was not observed");
    }
    boolean blocked(int pid) { return Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0",Boolean.class,pid)); }
    void release(CountDownLatch signal) { signal.countDown(); }
    void waitFor(CountDownLatch signal) {
        try {assertTrue(signal.await(20,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
    }
    void stop(ExecutorService pool) throws Exception {pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    BizException failure(Future<?> result,int code) {
        var error=assertThrows(ExecutionException.class,()->result.get(20,TimeUnit.SECONDS));
        var biz=assertInstanceOf(BizException.class,error.getCause());assertEquals(code,biz.code);return biz;
    }

    @Test void explicitNullUnlinksButAnOmittedMappingPreservesThePreviousAccount() {
        var actor=user("HANDLER");var cert=cert(actor,false);
        save(cert,false,true,null,false);assertEquals(actor.getId(),mapping(cert));
        save(cert,false,true,null,true);assertNull(mapping(cert));
        save(cert,false,true,actor.getId(),true);assertEquals(actor.getId(),mapping(cert));
        var clear=body(cert,false,true,null,true);clear.put("userId","");assertEquals(0,controller.saveEnforcer(clear).getCode());assertNull(mapping(cert));
    }

    @Test void restartingExistingDatabaseNeverGuessesADeletedMappingByName() {
        var actor=user("HANDLER");var cert=cert(actor,false);
        jdbc.update("update enforcer set user_id=null where cert_no=?",cert.no());
        initializer.run(new DefaultApplicationArguments());assertNull(mapping(cert));
    }

    @Test void invalidMappingInputsRejectAtomicallyRatherThanTruncatingOrThrowingForeignKeyErrors() {
        var actor=user("HANDLER");var cert=cert(actor,false);var before=jdbc.queryForMap("select * from enforcer where cert_no=?",cert.no());
        for(Object invalid:List.of(new BigDecimal(actor.getId()+".5"),new java.math.BigInteger("9223372036854775808"),-1,999999999999L,"not-an-id",true)) {
            var data=body(cert,false,false,null,true);data.put("name","不应写入");data.put("userId",invalid);
            assertEquals(2100,assertThrows(BizException.class,()->controller.saveEnforcer(data)).code);
            assertEquals(before,jdbc.queryForMap("select * from enforcer where cert_no=?",cert.no()));
        }
        for(String invalidExpiry:Arrays.asList(null,"")) {
            var data=body(cert,false,false,actor.getId(),true);data.put("name","日期无效不得写入");data.put("certExpireAt",invalidExpiry);
            assertEquals(2100,assertThrows(BizException.class,()->controller.saveEnforcer(data)).code);
            assertEquals(before,jdbc.queryForMap("select * from enforcer where cert_no=?",cert.no()));
        }
    }

    @Test void historicalParticipationSurvivesClearButNewOfficerSnapshotsDoNotGetOldAccess() {
        var owner=user("HANDLER");var actor=user("HANDLER");var cert=cert(actor,false);
        var first=cases.create(request(officers(cert)),owner.getUsername());save(cert,false,true,null,true);
        var second=cases.create(request(officers(cert)),owner.getUsername());
        cases.assertInScope(first.getId(),actor.getUsername(),false);
        assertEquals(2080,assertThrows(BizException.class,()->cases.assertInScope(second.getId(),actor.getUsername(),false)).code);
        assertEquals(actor.getId(),jdbc.queryForObject("select user_id from case_officer where case_id=? and cert_no=?",Long.class,first.getId(),cert.no()));
        assertNull(jdbc.queryForObject("select user_id from case_officer where case_id=? and cert_no=?",Long.class,second.getId(),cert.no()));
    }

    @Test void committedDisableRejectsWaitingCreation() throws Exception { changeBeforeWrite("CREATE","DISABLE"); }
    @Test void committedRenameRejectsWaitingCreation() throws Exception { changeBeforeWrite("CREATE","RENAME"); }
    @Test void committedExpirationRejectsWaitingOfficerAddition() throws Exception { changeBeforeWrite("ADD","EXPIRE"); }
    @Test void committedRemappingIsUsedByWaitingCreation() throws Exception { changeBeforeWrite("CREATE","REMAP"); }
    @Test void committedRemappingIsUsedByWaitingOfficerAddition() throws Exception { changeBeforeWrite("ADD","REMAP"); }
    @Test void disablingQualificationChecksStillReadsOneLockedMappingSnapshot() throws Exception {
        cfg("enforcer_cert_check","false");changeBeforeWrite("CREATE","REMAP");
    }

    void changeBeforeWrite(String action,String change) throws Exception {
        var owner=user("HANDLER");var actor=user("HANDLER");var replacement=user("HANDLER");var cert=cert(actor,false);
        var existing="ADD".equals(action)?fresh(owner):null;
        var pool=Executors.newFixedThreadPool(2);var locked=new CountDownLatch(1);var resume=new CountDownLatch(1);var pid=new CompletableFuture<Integer>();
        try {
            var writer=async(pool,()->{
                var data=body(cert,false,!"DISABLE".equals(change),"REMAP".equals(change)?replacement.getId():actor.getId(),true);
                if("RENAME".equals(change))data.put("name","已变更姓名");if("EXPIRE".equals(change))data.put("certExpireAt",LocalDate.now().minusDays(1).toString());
                assertEquals(0,controller.saveEnforcer(data).getCode());locked.countDown();waitFor(resume);return true;
            });
            assertTrue(locked.await(15,TimeUnit.SECONDS));
            var write=async(pool,()->{pid.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                if(existing==null)return cases.create(request(officers(cert)),owner.getUsername()).getId();
                cases.addOfficer(existing.getId(),new CaseService.OfficerReq(cert.name(),cert.no(),"MEMBER"));return existing.getId();});
            int backend=pid.get(15,TimeUnit.SECONDS);await(()->write.isDone()||blocked(backend));assertFalse(write.isDone(),"Writes must wait for the certificate snapshot");
            resume.countDown();assertTrue(writer.get(20,TimeUnit.SECONDS));
            if("REMAP".equals(change)){
                long caseId=write.get(20,TimeUnit.SECONDS);assertEquals(replacement.getId(),jdbc.queryForObject("select user_id from case_officer where case_id=? and cert_no=?",Long.class,caseId,cert.no()));
            } else {
                failure(write,2055);assertEquals(0,jdbc.queryForObject("select count(*) from case_officer where cert_no=?",Integer.class,cert.no()));
            }
        } finally {resume.countDown();stop(pool);}
    }

    @Test void committedQualificationRemovalRejectsWaitingLegalReview() throws Exception {
        var owner=user("HANDLER");var reviewer=user("LEGAL");var cert=cert(reviewer,true);var c=fresh(owner);var r=review(c);
        var pool=Executors.newFixedThreadPool(2);var locked=new CountDownLatch(1);var resume=new CountDownLatch(1);var pid=new CompletableFuture<Integer>();
        try {
            var writer=async(pool,()->{save(cert,false,true,reviewer.getId(),true);locked.countDown();waitFor(resume);return true;});
            assertTrue(locked.await(15,TimeUnit.SECONDS));
            var reviewing=async(pool,()->{pid.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return cases.doReview(r.getId(),reviewer.getUsername(),"AGREE","同意");});
            int backend=pid.get(15,TimeUnit.SECONDS);await(()->reviewing.isDone()||blocked(backend));assertFalse(reviewing.isDone());resume.countDown();assertTrue(writer.get(20,TimeUnit.SECONDS));
            failure(reviewing,2070);assertNull(jdbc.queryForObject("select reviewed_at from case_review where id=?",java.sql.Date.class,r.getId()));
        } finally {resume.countDown();stop(pool);}
    }

    @Test void certificateMutationWaitsForCreationToCommitItsValidatedOfficerSnapshot() throws Exception { writeBeforeChange(false); }
    @Test void certificateMutationWaitsForLegalReviewToCommitItsQualifiedSnapshot() throws Exception { writeBeforeChange(true); }
    void writeBeforeChange(boolean legalReview) throws Exception {
        var owner=user("HANDLER");var actor=user(legalReview?"LEGAL":"HANDLER");var replacement=user("HANDLER");var cert=cert(actor,legalReview);
        CaseReview r=legalReview?review(fresh(owner)):null;
        String name="cert_pause_"+System.nanoTime();long key=Math.abs(System.nanoTime());
        String table=legalReview?"case_review":"case_officer";
        jdbc.execute("create function "+name+"() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock("+key+"); return new; end $$");
        jdbc.execute("create trigger "+name+" before "+(legalReview?"update":"insert")+" on "+table+" for each row when ("+(legalReview?"old.id="+r.getId():"new.cert_no='"+cert.no()+"'")+") execute function "+name+"()");
        var pool=Executors.newFixedThreadPool(2);var writingPid=new CompletableFuture<Integer>();var savePid=new CompletableFuture<Integer>();
        try(Connection gate=dataSource.getConnection()) {
            gate.createStatement().execute("select pg_advisory_lock("+key+")");
            try {
                var writing=async(pool,()->{writingPid.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                    if(legalReview){cases.doReview(r.getId(),actor.getUsername(),"AGREE","受锁资格");return r.getCaseId();}
                    return cases.create(request(officers(cert)),owner.getUsername()).getId();});
                int first=writingPid.get(15,TimeUnit.SECONDS);await(()->blocked(first));
                var changing=async(pool,()->{savePid.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));save(cert,false,false,replacement.getId(),true);return true;});
                int second=savePid.get(15,TimeUnit.SECONDS);await(()->changing.isDone()||blocked(second));assertFalse(changing.isDone(),"Certificate update must not interleave after validation");
                gate.createStatement().execute("select pg_advisory_unlock("+key+")");long caseId=writing.get(20,TimeUnit.SECONDS);assertTrue(changing.get(20,TimeUnit.SECONDS));
                if(legalReview){assertEquals(actor.getId(),jdbc.queryForObject("select reviewer_user_id from case_review where id=?",Long.class,r.getId()));assertNotNull(jdbc.queryForObject("select reviewed_at from case_review where id=?",java.sql.Date.class,r.getId()));}
                else assertEquals(actor.getId(),jdbc.queryForObject("select user_id from case_officer where case_id=? and cert_no=?",Long.class,caseId,cert.no()));
                assertEquals(replacement.getId(),mapping(cert));
            } finally {gate.createStatement().execute("select pg_advisory_unlock_all()");}
        } finally {stop(pool);jdbc.execute("drop trigger if exists "+name+" on "+table);jdbc.execute("drop function if exists "+name+"()");}
    }

    @Test void reversedCertificateInputsAndCrossedOwnerMappingsDoNotDeadlock() throws Exception {
        var a=user("HANDLER");var b=user("HANDLER");var one=cert(b,false);var two=cert(a,false);
        var first=List.of(new CaseService.OfficerReq(one.name(),one.no(),"LEAD"),new CaseService.OfficerReq(two.name(),two.no(),"MEMBER"));
        var second=List.of(new CaseService.OfficerReq(two.name(),two.no(),"LEAD"),new CaseService.OfficerReq(one.name(),one.no(),"MEMBER"));
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var x=async(pool,()->{waitFor(start);return cases.create(request(first),a.getUsername()).getId();});
            var y=async(pool,()->{waitFor(start);return cases.create(request(second),b.getUsername()).getId();});
            start.countDown();long cx=x.get(25,TimeUnit.SECONDS),cy=y.get(25,TimeUnit.SECONDS);assertNotEquals(cx,cy);
            for(long id:List.of(cx,cy))assertEquals(2,jdbc.queryForObject("select count(*) from case_officer where case_id=? and user_id in (?,?)",Integer.class,id,a.getId(),b.getId()));
        } finally {start.countDown();stop(pool);}
    }

    @Test void absentCertificateCannotBeInsertedBetweenUncheckedValidationAndItsSnapshot() throws Exception {
        cfg("enforcer_cert_check","false");var owner=user("HANDLER");var mapped=user("HANDLER");
        var cert=new Cert(mapped.getRealName(),"ABSENT-"+System.nanoTime(),mapped.getId());
        String name="cert_absent_"+System.nanoTime();long key=Math.abs(System.nanoTime());
        jdbc.execute("create function "+name+"() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock("+key+"); return new; end $$");
        jdbc.execute("create trigger "+name+" before insert on case_officer for each row when (new.cert_no='"+cert.no()+"') execute function "+name+"()");
        var pool=Executors.newFixedThreadPool(2);var createPid=new CompletableFuture<Integer>();var registrationPid=new CompletableFuture<Integer>();
        try(Connection gate=dataSource.getConnection()) {
            gate.createStatement().execute("select pg_advisory_lock("+key+")");
            try {
                var creating=async(pool,()->{createPid.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return cases.create(request(officers(cert)),owner.getUsername()).getId();});
                int first=createPid.get(15,TimeUnit.SECONDS);await(()->blocked(first));
                var registration=async(pool,()->{registrationPid.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));save(cert,false,true,mapped.getId(),true);return true;});
                int second=registrationPid.get(15,TimeUnit.SECONDS);await(()->registration.isDone()||blocked(second));assertFalse(registration.isDone());
                gate.createStatement().execute("select pg_advisory_unlock("+key+")");
                long caseId=creating.get(20,TimeUnit.SECONDS);assertTrue(registration.get(20,TimeUnit.SECONDS));
                assertNull(jdbc.queryForObject("select user_id from case_officer where case_id=? and cert_no=?",Long.class,caseId,cert.no()));
                assertEquals(mapped.getId(),mapping(cert));
            } finally {gate.createStatement().execute("select pg_advisory_unlock_all()");}
        } finally {stop(pool);jdbc.execute("drop trigger if exists "+name+" on case_officer");jdbc.execute("drop function if exists "+name+"()");}
    }

    @Test void existingSelfReviewAndQualificationErrorsKeepTheirBusinessCodes() {
        var owner=user("HANDLER");var actor=user("HANDLER");var cert=cert(actor,true);var c=cases.create(request(officers(cert)),owner.getUsername());var r=review(c);
        assertEquals(2038,assertThrows(BizException.class,()->cases.doReview(r.getId(),actor.getUsername(),"AGREE","自审无效")).code);
        var unqualified=user("LEGAL");assertEquals(2070,assertThrows(BizException.class,()->cases.doReview(r.getId(),unqualified.getUsername(),"AGREE","无资格")).code);
    }
}
