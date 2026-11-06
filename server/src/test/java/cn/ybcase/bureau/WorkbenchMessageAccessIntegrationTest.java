package cn.ybcase.bureau;

import cn.ybcase.bureau.service.MessageService;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real authentication boundaries, PostgreSQL snapshots and actor-row lock waits. */
@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
@Import(WorkbenchMessageAccessIntegrationTest.Gates.class)
class WorkbenchMessageAccessIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users; @Autowired SysRoleRepository roles; @Autowired JwtService jwt;
    @Autowired MessageService messages; @Autowired PlatformTransactionManager transactions; @Autowired Gate gate;
    @MockitoSpyBean JdbcTemplate jdbc;
    String originalScope;
    record Actor(long id,String name,String token) {}
    record Change(String endpoint,String revoke) {}
    static class Pause { final CountDownLatch reached=new CountDownLatch(1),resume=new CountDownLatch(1); }
    static class Gate implements HandlerInterceptor {
        final AtomicReference<Pause> current=new AtomicReference<>();
        public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler)throws Exception {
            var p=current.get();
            if(p!=null&&"workbench-message".equals(req.getHeader("X-Workflow-Test-Gate"))){
                assertNotNull(SecurityContextHolder.getContext().getAuthentication());
                p.reached.countDown();assertTrue(p.resume.await(20,TimeUnit.SECONDS));
            }return true;
        }
    }
    @TestConfiguration static class Gates {
        @Bean Gate workbenchMessageGate(){return new Gate();}
        @Bean WebMvcConfigurer workbenchMessageConfigurer(Gate gate){return new WebMvcConfigurer(){
            public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(gate);}
        };}
    }
    @BeforeEach void isolate(){
        assertTrue(jdbc.queryForObject("select current_database()",String.class)
                .matches("ybcase_authowner_(?:test|final)_202610(?:28|29)_[a-f0-9]{8}"));
        SecurityContextHolder.clearContext();
        originalScope=jdbc.queryForObject("select cfg_value from sys_config where cfg_key='case_view_scope'",String.class);
        jdbc.update("update sys_config set cfg_value='SELF' where cfg_key='case_view_scope'");
    }
    @AfterEach void restore(){
        var p=gate.current.getAndSet(null);if(p!=null)p.resume.countDown();reset(jdbc);SecurityContextHolder.clearContext();
        if(originalScope!=null)jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'",originalScope);
    }
    Actor actor(String role){
        var u=new SysUser();u.setUsername("workbench_"+UUID.randomUUID().toString().replace("-",""));
        u.setRealName("工作台消息回归");u.setPassword("unused-jwt-fixture");
        if(role!=null)u.getRoles().add(roles.findByCode(role).orElseThrow());
        u=users.saveAndFlush(u);return new Actor(u.getId(),u.getUsername(),jwt.issue(u.getUsername(),u.getTokenVersion()));
    }
    long message(Actor a,String link){
        messages.send(a.name(),"SYSTEM","本人消息","合成回归内容",link,null);
        return jdbc.queryForObject("select max(id) from sys_message where username=?",Long.class,a.name());
    }
    long caseRow(Actor a){return jdbc.queryForObject("""
            insert into case_file(case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,owner_user)
            values(?, '工作台快照案件',(select min(id) from case_cause),'合成主体','PROVIDER',current_date,current_date+10,?) returning id
            """,Long.class,"WB"+UUID.randomUUID(),a.name());}
    void revoke(Actor a,String kind){
        if(kind.equals("ROLE"))jdbc.update("delete from sys_user_role where user_id=?",a.id());
        else if(kind.equals("DISABLED"))jdbc.update("update sys_user set enabled=false where id=?",a.id());
        else jdbc.update("update sys_user set token_version=token_version+1 where id=?",a.id());
    }
    JsonNode call(Actor a,String endpoint,long id,boolean gated)throws Exception {
        String path=switch(endpoint){case "workbench"->"/api/bureau/my/workbench";case "inbox"->"/api/bureau/messages";
            case "unread"->"/api/bureau/messages/unread-count";case "read"->"/api/bureau/messages/"+id+"/read";
            case "all"->"/api/bureau/messages/read-all";default->throw new IllegalArgumentException(endpoint);};
        var req=(Set.of("read","all").contains(endpoint)?post(path):get(path)).servletPath(path).header("Authorization","Bearer "+a.token());
        if(gated)req.header("X-Workflow-Test-Gate","workbench-message");
        var r=mvc.perform(req).andReturn().getResponse();assertNotEquals(500,r.getStatus(),r.getContentAsString());
        return mapper.readTree(r.getContentAsString());
    }
    static Stream<Change> changes(){
        return Stream.concat(Stream.of("ROLE","DISABLED","TOKEN").map(k->new Change("workbench",k)),
                Stream.of("inbox","unread","read","all").flatMap(e->Stream.of("DISABLED","TOKEN").map(k->new Change(e,k))));
    }
    @ParameterizedTest @MethodSource("changes")
    void requestCannotUseAccountStateCapturedBeforeController(Change c)throws Exception {
        var a=actor("HANDLER");long id=message(a,"/my/feedback");caseRow(a);
        var p=new Pause();gate.current.set(p);var pool=Executors.newSingleThreadExecutor();
        try{
            var f=pool.submit(()->call(a,c.endpoint(),id,true));assertTrue(p.reached.await(15,TimeUnit.SECONDS));
            revoke(a,c.revoke());p.resume.countDown();assertEquals(403,f.get(25,TimeUnit.SECONDS).path("code").asInt());
            assertNull(jdbc.queryForObject("select read_at from sys_message where id=?",Timestamp.class,id));
        }finally{p.resume.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
    @Test void downgradedLeaderSeesOwnWorkbenchWithoutSupervisoryCount()throws Exception {
        var a=actor("LEADER");long cid=caseRow(a);
        jdbc.update("insert into biz_approval(kind,case_id,reason,applicant)values('SUSPEND',?,'工作台降岗回归',?)",cid,a.name());
        var p=new Pause();gate.current.set(p);var pool=Executors.newSingleThreadExecutor();
        try{
            var f=pool.submit(()->call(a,"workbench",0,true));assertTrue(p.reached.await(15,TimeUnit.SECONDS));
            jdbc.update("delete from sys_user_role where user_id=?",a.id());
            jdbc.update("insert into sys_user_role(user_id,role_id)select ?,id from sys_role where code='HANDLER'",a.id());
            p.resume.countDown();var r=f.get(25,TimeUnit.SECONDS);assertEquals(0,r.path("code").asInt());
            assertEquals(0,r.path("data").path("pendingForMe").asInt());assertEquals(1,r.path("data").path("myApplications").size());
        }finally{p.resume.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
    @Test void workbenchReadsCasesApplicationsAndUnreadFromOneSnapshot()throws Exception {
        var a=actor("HANDLER");var other=actor("HANDLER");long cid=caseRow(a);message(a,"/case/detail/"+cid);
        long approval=jdbc.queryForObject("insert into biz_approval(kind,case_id,reason,applicant)values('SUSPEND',?,'同快照回归',?)returning id",Long.class,cid,a.name());
        var p=new Pause();
        doAnswer(inv->{Object result=inv.callRealMethod();String sql=inv.getArgument(0,String.class).replaceAll("\\s+"," ").trim();
            if(sql.startsWith("select cf.id, cf.case_no, cf.name, cf.status, cf.filed_at,")){
                p.reached.countDown();assertTrue(p.resume.await(20,TimeUnit.SECONDS));
            }return result;
        }).when(jdbc).queryForList(anyString(),any(Object[].class));
        var pool=Executors.newSingleThreadExecutor();
        try{
            var f=pool.submit(()->call(a,"workbench",0,false));assertTrue(p.reached.await(15,TimeUnit.SECONDS));
            jdbc.update("update case_file set owner_user=? where id=?",other.name(),cid);p.resume.countDown();
            var r=f.get(25,TimeUnit.SECONDS);assertEquals(0,r.path("code").asInt());var d=r.path("data");
            assertTrue(java.util.stream.StreamSupport.stream(d.path("myCases").spliterator(),false).anyMatch(n->n.path("id").asLong()==cid));
            assertTrue(java.util.stream.StreamSupport.stream(d.path("myApplications").spliterator(),false).anyMatch(n->n.path("id").asLong()==approval));
            assertEquals(1,d.path("unread").asInt());
            assertEquals(0,call(a,"workbench",0,false).path("data").path("myApplications").size());
        }finally{p.resume.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
    @ParameterizedTest @ValueSource(strings={"inbox","unread"})
    void messagePolicyAndSourceRowsShareOneSnapshot(String endpoint)throws Exception {
        var a=actor("HANDLER");var other=actor("HANDLER");long cid=caseRow(other);message(a,"/case/detail/"+cid);
        var p=new Pause();
        doAnswer(inv->{Object result=inv.callRealMethod();String sql=inv.getArgument(0,String.class).replaceAll("\\s+"," ").trim();
            if(sql.startsWith("select cfg_value from sys_config where cfg_key=?")){
                p.reached.countDown();assertTrue(p.resume.await(20,TimeUnit.SECONDS));
            }return result;
        }).when(jdbc).queryForList(anyString(),eq(String.class),any(Object[].class));
        var pool=Executors.newSingleThreadExecutor();
        try{
            var f=pool.submit(()->call(a,endpoint,0,false));assertTrue(p.reached.await(15,TimeUnit.SECONDS));
            jdbc.update("update sys_config set cfg_value='ALL' where cfg_key='case_view_scope'");
            jdbc.update("update case_file set owner_user=? where id=?",a.name(),cid);p.resume.countDown();
            var r=f.get(25,TimeUnit.SECONDS);assertEquals(0,r.path("code").asInt());
            assertEquals(0,endpoint.equals("inbox")?r.path("data").size():r.path("data").asInt());
            reset(jdbc);var fresh=call(a,endpoint,0,false);assertEquals(1,endpoint.equals("inbox")?fresh.path("data").size():fresh.path("data").asInt());
        }finally{p.resume.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
    void awaitBlocked(int blocker,Future<?> pending)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<until){
            if(jdbc.queryForObject("select count(*) from (select distinct pid from pg_locks where not granted) waits where ?=any(pg_blocking_pids(waits.pid))",Integer.class,blocker)>0)return;
            assertFalse(pending.isDone(),"The HTTP write completed without its account row lock");
            Thread.sleep(25);
        }fail("The HTTP write did not wait on its actual account lock");
    }
    static Stream<Change> waitingChanges(){return Stream.of("read","all").flatMap(e->Stream.of("DISABLED","TOKEN").map(k->new Change(e,k)));}
    @ParameterizedTest @MethodSource("waitingChanges")
    void readMarkersRecheckAccountAfterWaitingForActualUserLock(Change c)throws Exception {
        var a=actor("HANDLER");long id=message(a,"/my/feedback");var pool=Executors.newSingleThreadExecutor();
        var f=new AtomicReference<Future<JsonNode>>();
        try{
            new TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.queryForList("select id from sys_user where id=? for update",a.id());int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                f.set(pool.submit(()->call(a,c.endpoint(),id,false)));
                try{awaitBlocked(blocker,f.get());}catch(Exception e){throw new RuntimeException(e);}revoke(a,c.revoke());
            });
            assertEquals(403,f.get().get(25,TimeUnit.SECONDS).path("code").asInt());
            assertNull(jdbc.queryForObject("select read_at from sys_message where id=?",Timestamp.class,id));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
    @Test void legitimateOwnMessagesAndOtherRecipientsRemainIsolated()throws Exception {
        var a=actor("HANDLER");var other=actor("LEGAL");long own=message(a,"/my/feedback"),foreign=message(other,"/my/feedback");
        assertEquals(1,call(a,"inbox",own,false).path("data").size());assertEquals(1,call(a,"unread",own,false).path("data").asInt());
        assertEquals(0,call(a,"read",foreign,false).path("code").asInt());assertNull(jdbc.queryForObject("select read_at from sys_message where id=?",Timestamp.class,foreign));
        assertEquals(0,call(a,"read",own,false).path("code").asInt());assertNotNull(jdbc.queryForObject("select read_at from sys_message where id=?",Timestamp.class,own));
        message(a,"/my/feedback");assertEquals(0,call(a,"all",own,false).path("code").asInt());assertEquals(0,call(a,"unread",own,false).path("data").asInt());
    }
    @Test void existingInternalMessageReadsDoNotRequireAnHttpPrincipalOrBusinessRole(){
        var a=actor(null);long id=message(a,"/my/feedback");SecurityContextHolder.clearContext();
        assertEquals(1,messages.inbox(a.name()).size());assertEquals(1,messages.unread(a.name()));
        messages.markRead(a.name(),id);message(a,"/my/feedback");messages.markAllRead(a.name());assertEquals(0,messages.unread(a.name()));
    }
    @Test void authenticatedPersonalMessageOperationsDoNotAddAnExtraBusinessRoleRequirement(){
        var a=actor(null);long id=message(a,"/my/feedback");
        var principal=new cn.ybcase.core.security.AuthUser(a.name(),"unused",true,0,a.id(),false,List.of());
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        try{
            assertEquals(1,messages.inbox(a.name()).size());assertEquals(1,messages.unread(a.name()));
            messages.markRead(a.name(),id);message(a,"/my/feedback");messages.markAllRead(a.name());assertEquals(0,messages.unread(a.name()));
        }finally{SecurityContextHolder.clearContext();}
    }
}
