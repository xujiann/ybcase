package cn.ybcase.bureau;

import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.MessageService;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
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
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
@Import(SupervisionReminderConcurrencyIntegrationTest.Gates.class)
class SupervisionReminderConcurrencyIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users; @Autowired SysRoleRepository roles; @Autowired JwtService jwt;
    @Autowired MessageService messages; @Autowired CaseService cases; @Autowired CaseCauseRepository causes;
    @MockitoSpyBean JdbcTemplate jdbc; @Autowired Gate gate;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    record Actor(long id,String name,String token) {}
    record Change(String role,String revoke) {}
    static class Pause {final CountDownLatch reached=new CountDownLatch(1),resume=new CountDownLatch(1);}
    static class Gate implements HandlerInterceptor {
        final AtomicReference<Pause> current=new AtomicReference<>();
        public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler)throws Exception {
            var p=current.get();
            if(p!=null&&"supervision-reminder".equals(req.getHeader("X-Workflow-Test-Gate"))){
                assertNotNull(SecurityContextHolder.getContext().getAuthentication());
                p.reached.countDown();assertTrue(p.resume.await(20,TimeUnit.SECONDS));
            }return true;
        }
    }
    @TestConfiguration static class Gates {
        @Bean Gate supervisionReminderGate(){return new Gate();}
        @Bean WebMvcConfigurer supervisionReminderConfigurer(Gate gate){return new WebMvcConfigurer(){
            public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(gate);}
        };}
    }
    @BeforeEach void isolate(){
        assertTrue(jdbc.queryForObject("select current_database()",String.class).matches("ybcase_authowner_(?:test|final)_20261028_[a-f0-9]{8}"));
        SecurityContextHolder.clearContext();
    }
    @AfterEach void clear(){var p=gate.current.getAndSet(null);if(p!=null)p.resume.countDown();reset(jdbc);SecurityContextHolder.clearContext();}
    Actor actor(String role){var u=new SysUser();u.setUsername("reminder_"+UUID.randomUUID().toString().replace("-",""));u.setRealName("提醒监督回归");u.setPassword("unused-jwt-fixture");u.getRoles().add(roles.findByCode(role).orElseThrow());u=users.saveAndFlush(u);return new Actor(u.getId(),u.getUsername(),jwt.issue(u.getUsername(),u.getTokenVersion()));}
    void revoke(Actor a,String kind){
        if(kind.equals("ROLE"))jdbc.update("delete from sys_user_role where user_id=?",a.id());
        else if(kind.equals("DISABLED"))jdbc.update("update sys_user set enabled=false where id=?",a.id());
        else jdbc.update("update sys_user set token_version=token_version+1 where id=?",a.id());
    }
    static Stream<Change> changedReaders(){return Stream.of("LEADER","LEGAL","ADMIN").flatMap(r->Stream.of("ROLE","DISABLED","TOKEN").map(k->new Change(r,k)));}
    void changedRequest(String path,boolean postRequest,Actor a,String kind)throws Exception {
        var p=new Pause();gate.current.set(p);var pool=Executors.newSingleThreadExecutor();
        var before=jdbc.queryForList("select * from sys_message order by id");
        try{
            var f=pool.submit(()->mvc.perform((postRequest?post(path):get(path)).servletPath(path).header("Authorization","Bearer "+a.token()).header("X-Workflow-Test-Gate","supervision-reminder")).andReturn().getResponse());
            assertTrue(p.reached.await(15,TimeUnit.SECONDS));revoke(a,kind);p.resume.countDown();
            var response=f.get(25,TimeUnit.SECONDS);assertNotEquals(500,response.getStatus());
            assertEquals(403,mapper.readTree(response.getContentAsString()).path("code").asInt());
            if(postRequest)assertEquals(before,jdbc.queryForList("select * from sys_message order by id"));
        }finally{p.resume.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
    @ParameterizedTest @MethodSource("changedReaders")
    void supervisionRechecksActualReaderAtItsSnapshot(Change c)throws Exception {changedRequest("/api/bureau/stats/supervision",false,actor(c.role()),c.revoke());}
    @ParameterizedTest @ValueSource(strings={"ROLE","DISABLED","TOKEN"})
    void manualReminderMustRetainAdministratorAuthority(String kind)throws Exception {changedRequest("/api/bureau/messages/generate-reminders",true,actor("ADMIN"),kind);}
    @ParameterizedTest @ValueSource(strings={"LEADER","LEGAL","ADMIN"})
    void legitimateSupervisorsRetainTheExistingDashboard(String role)throws Exception {
        var a=actor(role);var response=mvc.perform(get("/api/bureau/stats/supervision").header("Authorization","Bearer "+a.token())).andReturn().getResponse();
        var value=mapper.readTree(response.getContentAsString());assertEquals(0,value.path("code").asInt());
        assertTrue(value.path("data").path("caseNearDeadline").isArray());assertTrue(value.path("data").path("truncated").isObject());
    }
    @Test void manualReminderKeepsNormalAdminAndScheduledEntryUsable()throws Exception {
        var a=actor("ADMIN");var response=mvc.perform(post("/api/bureau/messages/generate-reminders").header("Authorization","Bearer "+a.token())).andReturn().getResponse();
        var value=mapper.readTree(response.getContentAsString());assertEquals(0,value.path("code").asInt());assertTrue(value.path("data").path("generated").asInt()>=0);
        assertTrue(messages.generateReminders()>=0);
    }
    @Test void ordinaryHandlerCannotUseManualReminderOrSupervisoryDashboard()throws Exception {
        var a=actor("HANDLER");
        for(String path:List.of("/api/bureau/messages/generate-reminders","/api/bureau/stats/supervision")){
            var request=path.endsWith("supervision")?get(path):post(path);
            assertEquals(403,mapper.readTree(mvc.perform(request.header("Authorization","Bearer "+a.token())).andReturn().getResponse().getContentAsString()).path("code").asInt());
        }
    }
    @ParameterizedTest @ValueSource(strings={"RESOLVED","PROCESSING"})
    void staleReminderCannotCloseFeedbackThatWasJustHandledAgain(String newStatus)throws Exception {
        var a=actor("ADMIN");long id=jdbc.queryForObject("insert into feedback(kind,title,content,username,status,reply,handled_at)values('BUG','自动关闭交错回归','合成内容',?,'RESOLVED','旧回复',now()-interval '30 days') returning id",Long.class,a.name());
        var p=new Pause();
        doAnswer(inv->{Object result=inv.callRealMethod();String sql=inv.getArgument(0,String.class).replaceAll("\\s+"," ").trim();
            if(sql.startsWith("select id, username, title from feedback")&&((List<?>)result).stream().anyMatch(r->((Number)((Map<?,?>)r).get("id")).longValue()==id)){
                p.reached.countDown();assertTrue(p.resume.await(20,TimeUnit.SECONDS));
            }return result;
        }).when(jdbc).queryForList(anyString(),any(Object[].class));
        var pool=Executors.newSingleThreadExecutor();
        try{
            var f=pool.submit(()->messages.generateReminders());assertTrue(p.reached.await(15,TimeUnit.SECONDS));
            var reply=mvc.perform(post("/api/bureau/feedback/{id}/handle",id).header("Authorization","Bearer "+a.token()).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("status",newStatus,"reply","新的有效处理回复")))).andReturn().getResponse();
            assertEquals(0,mapper.readTree(reply.getContentAsString()).path("code").asInt());var before=jdbc.queryForMap("select * from feedback where id=?",id);
            p.resume.countDown();f.get(25,TimeUnit.SECONDS);assertEquals(before,jdbc.queryForMap("select * from feedback where id=?",id));
            assertEquals(0,jdbc.queryForObject("select count(*) from sys_message where dedup_key=?",Integer.class,"FEEDBACK-AUTOCLOSE-"+id));
        }finally{p.resume.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));jdbc.update("delete from feedback where id=?",id);jdbc.update("delete from sys_message where dedup_key=?","FEEDBACK-AUTOCLOSE-"+id);}
    }
    @Test void supervisionUsesOneSnapshotAcrossItsExistingCategories()throws Exception {
        long cause=causes.findAll().stream().filter(c->c.getItemNo()==13).findFirst().orElseThrow().getId();
        long id=cases.create(new CaseService.CaseCreateReq(null,cause,"NORMAL","SUP-SNAPSHOT-"+System.nanoTime(),"PROVIDER",null,null,null,null,"合成监督快照",BigDecimal.TEN,
            List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),null,null,null,null),"admin").getId();
        var original=jdbc.queryForObject("select deadline_at from case_file where id=?",LocalDate.class,id);var a=actor("LEGAL");var p=new Pause();
        doAnswer(inv->{Object result=inv.callRealMethod();String sql=inv.getArgument(0,String.class).replaceAll("\\s+"," ").trim();
            if(sql.startsWith("select id, clue_no, suspect_name, received_at, deadline_at, extended")){p.reached.countDown();assertTrue(p.resume.await(20,TimeUnit.SECONDS));}return result;
        }).when(jdbc).queryForList(anyString());
        var pool=Executors.newSingleThreadExecutor();
        try{
            var f=pool.submit(()->mvc.perform(get("/api/bureau/stats/supervision").header("Authorization","Bearer "+a.token())).andReturn().getResponse());
            assertTrue(p.reached.await(15,TimeUnit.SECONDS));jdbc.update("update case_file set deadline_at=current_date-1 where id=?",id);p.resume.countDown();
            var value=mapper.readTree(f.get(25,TimeUnit.SECONDS).getContentAsString());assertEquals(0,value.path("code").asInt());
            assertFalse(java.util.stream.StreamSupport.stream(value.path("data").path("caseNearDeadline").spliterator(),false).anyMatch(row->row.path("id").asLong()==id));
        }finally{p.resume.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));jdbc.update("update case_file set deadline_at=? where id=?",original,id);}
    }
    void awaitBlocked(int blocker)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<until){
            if(jdbc.queryForObject("select count(*) from pg_stat_activity where ?=any(pg_blocking_pids(pid))",Integer.class,blocker)>0)return;
            Thread.sleep(25);
        }fail("Manual reminder did not reach the real feedback source lock");
    }
    long expiredFeedback(Actor a){return jdbc.queryForObject("insert into feedback(kind,title,content,username,status,reply,handled_at)values('BUG','真实锁回归','合成内容',?,'RESOLVED','旧回复',now()-interval '30 days') returning id",Long.class,a.name());}
    @ParameterizedTest @ValueSource(strings={"ROLE","DISABLED","TOKEN"})
    void manualReminderRechecksAdministratorAfterWaitingForFeedback(String kind)throws Exception {
        var a=actor("ADMIN");long id=expiredFeedback(a);var pool=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<Integer>>();
        var before=jdbc.queryForList("select * from sys_message order by id");
        try{
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.queryForList("select id from feedback where id=? for update",id);int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                pending.set(pool.submit(()->mapper.readTree(mvc.perform(post("/api/bureau/messages/generate-reminders").header("Authorization","Bearer "+a.token())).andReturn().getResponse().getContentAsString()).path("code").asInt()));
                try{awaitBlocked(blocker);}catch(Exception e){throw new RuntimeException(e);}revoke(a,kind);
            });
            assertEquals(403,pending.get().get(25,TimeUnit.SECONDS));assertEquals("RESOLVED",jdbc.queryForObject("select status from feedback where id=?",String.class,id));
            assertEquals(before,jdbc.queryForList("select * from sys_message order by id"));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));jdbc.update("delete from feedback where id=?",id);}
    }
    @Test void manualAndScheduledRunsDoNotInvertSourceAndNotificationLocks()throws Exception {
        var a=actor("ADMIN");long id=expiredFeedback(a);var pool=Executors.newFixedThreadPool(2);var manual=new AtomicReference<Future<Integer>>();var scheduled=new AtomicReference<Future<Integer>>();
        try{
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.queryForList("select id from feedback where id=? for update",id);int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                manual.set(pool.submit(()->mapper.readTree(mvc.perform(post("/api/bureau/messages/generate-reminders").header("Authorization","Bearer "+a.token())).andReturn().getResponse().getContentAsString()).path("code").asInt()));
                try{awaitBlocked(blocker);}catch(Exception e){throw new RuntimeException(e);}
                scheduled.set(pool.submit(()->messages.generateReminders()));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);boolean gateWait=false;
                while(System.nanoTime()<until){
                    // Lock rows are current even inside this transaction; pg_stat_activity
                    // can retain an earlier statistics snapshot before the second worker starts.
                    if(jdbc.queryForObject("select count(*) from pg_locks where locktype='advisory' and classid=0 and objid=2026102803 and not granted",Integer.class)>0){gateWait=true;break;}
                    try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
                }assertTrue(gateWait,"Scheduled and manual entry must share the generation gate");
            });
            assertEquals(0,manual.get().get(25,TimeUnit.SECONDS));assertTrue(scheduled.get().get(25,TimeUnit.SECONDS)>=0);
            assertEquals("CLOSED",jdbc.queryForObject("select status from feedback where id=?",String.class,id));
            assertEquals(1,jdbc.queryForObject("select count(*) from sys_message where dedup_key=?",Integer.class,"FEEDBACK-AUTOCLOSE-"+id));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));jdbc.update("delete from feedback where id=?",id);jdbc.update("delete from sys_message where dedup_key=?","FEEDBACK-AUTOCLOSE-"+id);}
    }
}
