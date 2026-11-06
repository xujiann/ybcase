package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.AuthUser;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Actual HTTP authentication precedes the test-only gate; live reads must not trust that captured role. */
@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
@Import(OwnerBoundaryConcurrencyIntegrationTest.ReadEntryGateConfiguration.class)
class OwnerBoundaryConcurrencyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired PlatformTransactionManager tm;
    record Actor(long id, String name, String token) {}
    record ReadProbe(String role, String path) {}
    record ReadChange(String role, String path, Change change) {}
    enum Change { ROLE, DISABLED, TOKEN }
    enum BadIds { FRACTIONAL, OVERFLOW, BOOLEAN, NULL, TEXT, OBJECT }
    static final AtomicReference<EntryGate> entryGate = new AtomicReference<>();
    static class EntryGate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final String username;
        EntryGate(String username) { this.username=username; }
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class ReadEntryGateConfiguration implements WebMvcConfigurer {
        @Override public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                    EntryGate gate=entryGate.get();
                    if(gate==null || !"GET".equals(request.getMethod())) return true;
                    var auth=SecurityContextHolder.getContext().getAuthentication();
                    assertNotNull(auth);assertEquals(gate.username,auth.getName());assertInstanceOf(AuthUser.class,auth.getPrincipal());
                    gate.entered.countDown();
                    assertTrue(gate.release.await(20,TimeUnit.SECONDS),"HTTP entry gate was not released");
                    return true;
                }
            }).addPathPatterns("/api/bureau/owners", "/api/bureau/owners/candidates");
        }
    }
    @BeforeEach void protect() {
        assertNotEquals("ybcase",jdbc.queryForObject("select current_database()",String.class));
        SecurityContextHolder.clearContext();entryGate.set(null);
    }
    @AfterEach void clear() {
        EntryGate gate=entryGate.getAndSet(null);if(gate!=null)gate.release.countDown();
        SecurityContextHolder.clearContext();
    }
    Actor actor(String role) {
        var user=new SysUser();user.setUsername("owner_boundary_"+UUID.randomUUID().toString().replace("-",""));
        user.setRealName("承办移交回归");user.setPassword("unused-jwt-fixture");user.setEnabled(true);
        if(role!=null)user.getRoles().add(roles.findByCode(role).orElseThrow());
        user=users.saveAndFlush(user);
        return new Actor(user.getId(),user.getUsername(),jwt.issue(user.getUsername(),user.getTokenVersion()));
    }
    long item(Actor owner) {
        return jdbc.queryForObject("""
                insert into case_file(case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,owner_user,owner_dept_id)
                values(?, '承办移交回归', (select min(id) from case_cause), '合成机构', 'PROVIDER', current_date,
                current_date+90, ?, (select min(id) from sys_dept)) returning id""",Long.class,"OWNER-BOUNDARY-"+UUID.randomUUID(),owner.name());
    }
    JsonNode call(Actor actor,String method,String path,Object body) throws Exception {
        var builder=request(HttpMethod.valueOf(method),path).header("Authorization","Bearer "+actor.token());
        if(body!=null)builder.contentType("application/json").content(mapper.writeValueAsBytes(body));
        var response=mvc.perform(builder).andReturn().getResponse();
        assertNotEquals(500,response.getStatus(),response.getContentAsString());
        return mapper.readTree(response.getContentAsString());
    }
    void revoke(Actor actor,Change change) {
        jdbc.queryForList("select id from sys_user where id=? for update",actor.id());
        switch(change) {
            case ROLE -> {
                jdbc.update("delete from sys_user_role where user_id=?",actor.id());
                jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='HANDLER'",actor.id());
            }
            case DISABLED -> jdbc.update("update sys_user set enabled=false where id=?",actor.id());
            case TOKEN -> jdbc.update("update sys_user set token_version=token_version+1 where id=?",actor.id());
        }
    }
    static Stream<ReadProbe> readers() {
        return Stream.of("LEADER","LEGAL","ADMIN").flatMap(role->Stream.of(
                new ReadProbe(role,"/api/bureau/owners"),new ReadProbe(role,"/api/bureau/owners/candidates")));
    }
    static Stream<ReadChange> readChanges() {
        return readers().flatMap(probe->Arrays.stream(Change.values()).map(change->new ReadChange(probe.role(),probe.path(),change)));
    }
    @ParameterizedTest @MethodSource("readChanges")
    void authenticatedOldRoleCannotReadAfterEntryChange(ReadChange probe) throws Exception {
        var actor=actor(probe.role());var owner=actor("HANDLER");long id=item(owner);
        var before=jdbc.queryForMap("select * from case_file where id=?",id);
        EntryGate gate=new EntryGate(actor.name());entryGate.set(gate);var pool=Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> pending=pool.submit(()->call(actor,"GET",probe.path(),null));
            assertTrue(gate.entered.await(15,TimeUnit.SECONDS),"Request did not authenticate and reach the entry gate");
            new TransactionTemplate(tm).executeWithoutResult(tx->revoke(actor,probe.change()));
            gate.release.countDown();
            assertEquals(403,pending.get(20,TimeUnit.SECONDS).path("code").asInt());
            assertEquals(before,jdbc.queryForMap("select * from case_file where id=?",id));
        } finally {gate.release.countDown();entryGate.set(null);pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
    @ParameterizedTest @MethodSource("readers")
    void currentOwnerReadersRetainTheirExistingCatalogAccess(ReadProbe probe) throws Exception {
        var reader=actor(probe.role());var owner=actor("HANDLER");item(owner);
        JsonNode response=call(reader,"GET",probe.path(),null);assertEquals(0,response.path("code").asInt());
        assertTrue(response.path("data").findValuesAsText("username").contains(owner.name()));
    }
    @Test void ordinaryHandlerCannotReadOwnerAdministrationCatalogs() throws Exception {
        var handler=actor("HANDLER");
        for(String path:List.of("/api/bureau/owners","/api/bureau/owners/candidates"))
            assertEquals(403,call(handler,"GET",path,null).path("code").asInt());
    }
    void awaitBlocked(int blocker) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<until) {
            if(jdbc.queryForObject("select count(*) from pg_locks where not granted and ?=any(pg_blocking_pids(pid))",Integer.class,blocker)>0)return;
            Thread.sleep(25);
        }
        fail("Bulk owner transfer did not reach the held case row");
    }
    Map<String,Object> snapshot(long first,long second,Actor receiver) {
        return Map.of("cases",jdbc.queryForList("select * from case_file where id in (?,?) order by id",first,second),
                "messages",jdbc.queryForList("select * from sys_message where username=? order by id",receiver.name()));
    }
    @ParameterizedTest @EnumSource(Change.class)
    void bulkTransferKeepsExistingPostLockAuthorityChecks(Change change) throws Exception {
        var operator=actor("LEADER");var owner=actor("HANDLER");var receiver=actor("HANDLER");long first=item(owner),second=item(owner);
        var before=snapshot(first,second,receiver);var pool=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<JsonNode>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(tx->{
                jdbc.queryForList("select id from case_file where id=? for update",first);
                int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                pending.set(pool.submit(()->call(operator,"POST","/api/bureau/owners/transfer",Map.of("fromOwner",owner.name(),"toOwner",receiver.name()))));
                try{awaitBlocked(blocker);}catch(Exception failure){throw new RuntimeException(failure);}
                revoke(operator,change);
            });
            assertEquals(403,pending.get().get(20,TimeUnit.SECONDS).path("code").asInt());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
        assertEquals(before,snapshot(first,second,receiver));
    }
    Object invalidIds(BadIds kind,long id) {
        return switch(kind) {
            case FRACTIONAL -> List.of(new BigDecimal(id+".5"));
            case OVERFLOW -> List.of(new BigDecimal("9223372036854775808"));
            case BOOLEAN -> List.of(true);
            case NULL -> Collections.singletonList(null);
            case TEXT -> List.of("not-a-case-id");
            case OBJECT -> Map.of("id",id);
        };
    }
    @ParameterizedTest @EnumSource(BadIds.class)
    void malformedCaseIdsNeverSelectAnotherCaseOrMutatePartOfTheBatch(BadIds kind) throws Exception {
        var operator=actor("LEADER");var owner=actor("HANDLER");var receiver=actor("HANDLER");long first=item(owner),second=item(owner);
        var before=snapshot(first,second,receiver);
        var response=call(operator,"POST","/api/bureau/owners/transfer",Map.of("fromOwner",owner.name(),"toOwner",receiver.name(),"caseIds",invalidIds(kind,first)));
        assertEquals(2112,response.path("code").asInt());assertEquals(before,snapshot(first,second,receiver));
    }
    static Stream<Boolean> emptyModes() {return Stream.of(false,true);}
    @ParameterizedTest @MethodSource("emptyModes")
    void missingAndEmptySelectionRetainWholeActiveOwnerBatchSemantics(boolean explicitEmpty) throws Exception {
        var operator=actor("LEADER");var owner=actor("HANDLER");var receiver=actor("LEGAL");long first=item(owner),second=item(owner);
        jdbc.update("update sys_user set dept_id=null where id=?",receiver.id());
        var body=new LinkedHashMap<String,Object>(Map.of("fromOwner",owner.name(),"toOwner",receiver.name()));
        body.put("caseIds",explicitEmpty?List.of():null);
        JsonNode result=call(operator,"POST","/api/bureau/owners/transfer",body);assertEquals(0,result.path("code").asInt());
        assertEquals(2,result.path("data").path("count").asInt());
        assertEquals(2,jdbc.queryForObject("select count(*) from case_file where id in (?,?) and owner_user=? and owner_dept_id is null",Integer.class,first,second,receiver.name()));
        assertEquals(2,jdbc.queryForObject("select count(*) from sys_message where username=?",Integer.class,receiver.name()));
    }
    @Test void duplicateExactCaseIdsRemainSingleTransferAndSingleNotification() throws Exception {
        var operator=actor("ADMIN");var owner=actor("HANDLER");var receiver=actor("HANDLER");long id=item(owner);
        JsonNode result=call(operator,"POST","/api/bureau/owners/transfer",Map.of("fromOwner",owner.name(),"toOwner",receiver.name(),"caseIds",List.of(String.valueOf(id),id)));
        assertEquals(0,result.path("code").asInt());assertEquals(1,result.path("data").path("count").asInt());
        assertEquals(1,jdbc.queryForObject("select count(*) from sys_message where username=?",Integer.class,receiver.name()));
    }
    @Test void disabledOwnersWithOutstandingWorkStayVisibleForHandover() throws Exception {
        var reader=actor("LEGAL");var owner=actor("HANDLER");item(owner);
        jdbc.update("update sys_user set enabled=false where id=?",owner.id());
        JsonNode row=call(reader,"GET","/api/bureau/owners",null).path("data").findParents("username").stream()
                .filter(candidate->owner.name().equals(candidate.path("username").asText())).findFirst().orElseThrow();
        assertFalse(row.path("enabled").asBoolean());assertEquals(1,row.path("active_cases").asInt());
    }
    @Test void receiverCatalogKeepsActualRolePolicyAndRejectsPureAdministrators() throws Exception {
        var reader=actor("LEGAL");var admin=actor("ADMIN");var disabled=actor("HANDLER");var handler=actor("HANDLER");var leader=actor("LEADER");var legal=actor("LEGAL");
        jdbc.update("update sys_user set enabled=false where id=?",disabled.id());
        var names=call(reader,"GET","/api/bureau/owners/candidates",null).path("data").findValuesAsText("username");
        assertFalse(names.contains(admin.name()));assertFalse(names.contains(disabled.name()));
        assertTrue(names.containsAll(List.of(handler.name(),leader.name(),legal.name())));
    }
}
