package cn.ybcase.bureau;

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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
@Import(AuditReadBoundaryIntegrationTest.AuditGateConfiguration.class)
class AuditReadBoundaryIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users; @Autowired SysRoleRepository roles; @Autowired JwtService jwt;
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired AuditGate gate;
    record Actor(long id,String token) {}
    record Change(String endpoint,String kind) {}
    static class Barrier {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    }
    static class AuditGate implements HandlerInterceptor {
        final AtomicReference<Barrier> current=new AtomicReference<>();
        public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) throws Exception {
            var barrier=current.get();
            if(barrier!=null && "authenticated-audit-read".equals(req.getHeader("X-Audit-Test-Gate"))){
                assertNotNull(SecurityContextHolder.getContext().getAuthentication());
                barrier.entered.countDown();assertTrue(barrier.release.await(15,TimeUnit.SECONDS));
            }
            return true;
        }
    }
    @TestConfiguration static class AuditGateConfiguration {
        @Bean AuditGate auditReadGate(){return new AuditGate();}
        @Bean WebMvcConfigurer auditReadGateConfigurer(AuditGate gate){return new WebMvcConfigurer(){
            public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(gate);}
        };}
    }
    @BeforeEach void protect(){
        assertTrue(jdbc.queryForObject("select current_database()",String.class).startsWith("ybcase_authowner_"));
        SecurityContextHolder.clearContext();
    }
    @AfterEach void clear(){gate.current.set(null);reset(jdbc);SecurityContextHolder.clearContext();}
    Actor actor(String role){
        var user=new SysUser();user.setUsername("audit_"+UUID.randomUUID().toString().replace("-",""));
        user.setPassword("unused-jwt-fixture");user.setRealName("审计边界回归");user.setEnabled(true);
        user.getRoles().add(roles.findByCode(role).orElseThrow());user=users.saveAndFlush(user);
        return new Actor(user.getId(),jwt.issue(user.getUsername(),user.getTokenVersion()));
    }
    static Stream<Change> staleReads(){return Stream.of("ROLE","DISABLED","TOKEN").flatMap(kind->Stream.of(
        new Change("/api/audit/logs?page=1&size=20",kind),new Change("/api/bureau/audit/export?month=2026-10",kind)));}
    @ParameterizedTest @MethodSource("staleReads")
    void actualAdministratorMustStillBeValidWhenTheReadTransactionStarts(Change change) throws Exception {
        var admin=actor("ADMIN");var barrier=new Barrier();gate.current.set(barrier);var pool=Executors.newSingleThreadExecutor();
        try{
            var request=pool.submit(()->mvc.perform(get(change.endpoint()).header("Authorization","Bearer "+admin.token())
                .header("X-Audit-Test-Gate","authenticated-audit-read")).andReturn().getResponse());
            assertTrue(barrier.entered.await(10,TimeUnit.SECONDS));
            if(change.kind().equals("ROLE"))jdbc.update("delete from sys_user_role where user_id=?",admin.id());
            else if(change.kind().equals("DISABLED"))jdbc.update("update sys_user set enabled=false where id=?",admin.id());
            else jdbc.update("update sys_user set token_version=token_version+1 where id=?",admin.id());
            barrier.release.countDown();var response=request.get(20,TimeUnit.SECONDS);
            assertTrue(response.getContentType()!=null && response.getContentType().contains("application/json"),"Denied audit read must not return CSV");
            assertEquals(403,mapper.readTree(response.getContentAsString()).path("code").asInt(),response.getContentAsString());
        }finally{barrier.release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
    @ParameterizedTest @ValueSource(strings={"2026-00","2026-13","0000-01","2026-1","not-a-month"})
    void invalidExportMonthsAreControlledBusinessErrors(String month) throws Exception {
        var admin=actor("ADMIN");var response=mvc.perform(get("/api/bureau/audit/export").param("month",month)
            .header("Authorization","Bearer "+admin.token())).andReturn().getResponse();
        assertTrue(response.getContentType()!=null && response.getContentType().contains("application/json"),"Invalid month must not return CSV");
        assertEquals(2100,mapper.readTree(response.getContentAsString()).path("code").asInt());
        assertNotEquals(500,response.getStatus());
    }
    @ParameterizedTest @ValueSource(strings={"0000-01-01","+10000-01-01","2026-02-30"})
    void invalidQueryDatesDoNotReachTheDatabase(String date) throws Exception {
        var admin=actor("ADMIN");var response=mvc.perform(get("/api/audit/logs").param("from",date)
            .header("Authorization","Bearer "+admin.token())).andReturn().getResponse();
        assertEquals(2139,mapper.readTree(response.getContentAsString()).path("code").asInt());
    }
    @Test void countAndRowsUseOneSnapshotDuringConcurrentAuditAppend() throws Exception {
        var admin=actor("ADMIN");String marker="AUDIT-SNAPSHOT-"+UUID.randomUUID();var barrier=new Barrier();
        doAnswer(inv->{
            Object total=inv.callRealMethod();
            if(inv.getArgument(0,String.class).startsWith("select count(*) from sys_audit_log")
                && Arrays.toString((Object[])inv.getRawArguments()[2]).contains(marker)){
                barrier.entered.countDown();assertTrue(barrier.release.await(15,TimeUnit.SECONDS));
            }
            return total;
        }).when(jdbc).queryForObject(anyString(),eq(Long.class),any(Object[].class));
        var pool=Executors.newSingleThreadExecutor();
        try{
            var request=pool.submit(()->mvc.perform(get("/api/audit/logs").param("page","1").param("size","20").param("requestId",marker)
                .header("Authorization","Bearer "+admin.token())).andReturn().getResponse());
            assertTrue(barrier.entered.await(10,TimeUnit.SECONDS));
            jdbc.update("insert into sys_audit_log(username,method,path,http_status,request_id) values('snapshot','GET','/test/snapshot',200,?)",marker);
            barrier.release.countDown();var body=mapper.readTree(request.get(20,TimeUnit.SECONDS).getContentAsString());
            assertEquals(0,body.path("code").asInt());assertEquals(0,body.path("data").path("total").asInt());
            assertEquals(0,body.path("data").path("rows").size());
        }finally{barrier.release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
    @Test void currentAdministratorRetainsPagingAndMonthlyCsvBoundaries() throws Exception {
        var admin=actor("ADMIN");String marker="AUDIT-CSV-"+UUID.randomUUID();
        for(String day:List.of("2091-03-01","2091-03-31","2091-04-01"))
            jdbc.update("insert into sys_audit_log(username,method,path,http_status,request_id,created_at) values('=formula','GET','/test/csv',200,?,?::timestamptz)",marker+day,day+" 12:00:00+08");
        var response=mvc.perform(get("/api/bureau/audit/export").param("month","2091-03").header("Authorization","Bearer "+admin.token())).andReturn().getResponse();
        assertEquals(200,response.getStatus());String csv=response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains(marker+"2091-03-01"));assertTrue(csv.contains(marker+"2091-03-31"));assertFalse(csv.contains(marker+"2091-04-01"));
        assertTrue(csv.contains(cn.ybcase.bureau.common.Csv.cell("=formula")));
        String requestId=response.getHeader("X-Request-Id");assertNotNull(requestId);
        var trace=jdbc.queryForMap("select method,path,http_status from sys_audit_log where request_id=?",requestId);
        assertEquals("GET",trace.get("method"));assertEquals("/api/bureau/audit/export",trace.get("path"));assertEquals(200,trace.get("http_status"));
        var page=mvc.perform(get("/api/audit/logs").param("size","2").param("page","1").param("requestId",marker+"2091-03-31")
            .header("Authorization","Bearer "+admin.token())).andReturn().getResponse();
        var body=mapper.readTree(page.getContentAsString()).path("data");assertEquals(1,body.path("total").asInt());assertEquals(1,body.path("rows").size());
    }
}
