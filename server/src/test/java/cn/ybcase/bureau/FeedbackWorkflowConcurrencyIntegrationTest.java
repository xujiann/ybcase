package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.web.FeedbackController;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.AuthUser;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** An entry-time authenticated feedback request must recheck its actor after its row/actor wait. */
@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class FeedbackWorkflowConcurrencyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager tm;
    @Autowired FeedbackController feedback;
    record Actor(long id, String name, String token, int version, String role) {}
    enum Change { DISABLED, TOKEN_VERSION, ROLE_REMOVED }
    enum AdminRead { LIST, EXPORT }

    @BeforeEach void protect() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class));
        SecurityContextHolder.clearContext();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    Actor actor(String role) {
        var user = new SysUser(); user.setUsername("feedback_" + UUID.randomUUID().toString().replace("-", ""));
        user.setRealName("反馈并发回归"); user.setPassword("unused-fixture"); user.setEnabled(true);
        user.getRoles().add(roles.findByCode(role).orElseThrow()); user = users.saveAndFlush(user);
        return new Actor(user.getId(), user.getUsername(), jwt.issue(user.getUsername(), user.getTokenVersion()), user.getTokenVersion(), role);
    }
    Map<String,Object> requestBody() { return Map.of("kind", "QUESTION", "title", "原反馈标题", "content", "原反馈正文", "screenshotBase64", "AQID"); }
    long item(Actor owner, String status) {
        return jdbc.queryForObject("insert into feedback(kind,title,content,username,status,screenshot) values('QUESTION','原反馈标题','原反馈正文',?,?,decode('010203','hex')) returning id",
                Long.class, owner.name(), status);
    }
    int call(Actor actor, String path, Object body) throws Exception {
        var response = mvc.perform(request(HttpMethod.POST, path).header("Authorization", "Bearer " + actor.token())
                .contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), "The request must pass entry authentication before waiting");
        return mapper.readTree(response.getContentAsString()).path("code").asInt();
    }
    void change(Actor actor, Change change) {
        jdbc.queryForList("select id from sys_user where id=? for update", actor.id());
        switch (change) {
            case DISABLED -> jdbc.update("update sys_user set enabled=false where id=?", actor.id());
            case TOKEN_VERSION -> jdbc.update("update sys_user set token_version=token_version+1 where id=?", actor.id());
            case ROLE_REMOVED -> jdbc.update("delete from sys_user_role where user_id=?", actor.id());
        }
    }
    void blocked(int blocker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            int count = jdbc.queryForObject("select count(*) from pg_locks where not granted and ?=any(pg_blocking_pids(pid))", Integer.class, blocker);
            if (count > 0) return;
            try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        fail("Feedback request did not reach the expected PostgreSQL lock");
    }
    int race(Actor actor, Long id, String action, Object body, Runnable mutation) throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var pending = new AtomicReference<Future<Integer>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(tx -> {
                if (id == null) jdbc.queryForList("select id from sys_user where id=? for update", actor.id());
                else jdbc.queryForList("select id from feedback where id=? for update", id);
                int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                String path = "/api/bureau/feedback" + (id == null ? "" : "/" + id + "/" + action);
                pending.set(pool.submit(() -> call(actor, path, body))); blocked(blocker); mutation.run();
            });
            return pending.get().get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS)); }
    }
    Object snapshot(long id, Actor owner) {
        return mapper.valueToTree(Map.of("row", jdbc.queryForMap("select * from feedback where id=?", id),
                "messages", jdbc.queryForList("select * from sys_message where username=? and link='/my/feedback' order by id", owner.name())));
    }

    @ParameterizedTest @EnumSource(Change.class)
    void oldAdministratorCannotHandleAfterFeedbackLockWait(Change change) throws Exception {
        var owner = actor("HANDLER"); var admin = actor("ADMIN"); long id = item(owner, "NEW"); var before = snapshot(id, owner);
        assertEquals(403, race(admin, id, "handle", Map.of("status", "RESOLVED", "reply", "过期处理"), () -> change(admin, change)));
        assertEquals(before, snapshot(id, owner));
    }
    @ParameterizedTest @EnumSource(Change.class)
    void oldAuthorSessionCannotCloseAfterFeedbackLockWait(Change change) throws Exception {
        var owner = actor("HANDLER"); long id = item(owner, "RESOLVED"); var before = snapshot(id, owner);
        assertEquals(403, race(owner, id, "close", Map.of(), () -> change(owner, change))); assertEquals(before, snapshot(id, owner));
    }
    @ParameterizedTest @EnumSource(Change.class)
    void expiredSubmitCannotAppendFeedbackOrNotificationsAfterActorWait(Change change) throws Exception {
        var actor = actor("HANDLER"); int feedbackCount = jdbc.queryForObject("select count(*) from feedback", Integer.class);
        int messages = jdbc.queryForObject("select count(*) from sys_message", Integer.class);
        assertEquals(403, race(actor, null, "", requestBody(), () -> change(actor, change)));
        assertEquals(feedbackCount, jdbc.queryForObject("select count(*) from feedback", Integer.class));
        assertEquals(messages, jdbc.queryForObject("select count(*) from sys_message", Integer.class));
    }
    @Test void formerAdministratorCannotUseCapturedAuthorityToReadAnotherAuthorsScreenshot() {
        var owner = actor("HANDLER"); var admin = actor("ADMIN"); long id = item(owner, "NEW");
        var auth = new UsernamePasswordAuthenticationToken(new AuthUser(admin.name(), "unused", true, admin.version(), admin.id(), false,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))), "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        jdbc.update("delete from sys_user_role where user_id=?", admin.id());
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='HANDLER'", admin.id());
        try { ResponseEntity<byte[]> response = feedback.screenshot(id, auth); assertEquals(403, response.getStatusCode().value()); }
        catch (BizException denied) { assertEquals(403, denied.code); }
    }
    @Test void authorCloseObservesTheAdministratorsLatestProcessingStateAfterWait() throws Exception {
        var owner = actor("HANDLER"); long id = item(owner, "RESOLVED");
        assertEquals(2091, race(owner, id, "close", Map.of(), () -> jdbc.update("update feedback set status='PROCESSING',reply='仍需核实' where id=?", id)));
        assertEquals("PROCESSING", jdbc.queryForObject("select status from feedback where id=?", String.class, id));
    }
    @Test void currentAdministratorAndAuthorCompleteTheExistingResolveCloseProtocol() throws Exception {
        var owner = actor("LEGAL"); var admin = actor("ADMIN"); long id = item(owner, "NEW");
        assertEquals(0, call(admin, "/api/bureau/feedback/" + id + "/handle", Map.of("status", "RESOLVED", "reply", "已解决")));
        assertEquals(0, call(owner, "/api/bureau/feedback/" + id + "/close", Map.of()));
        var before = snapshot(id, owner);
        assertEquals(2091, call(admin, "/api/bureau/feedback/" + id + "/handle", Map.of("status", "PROCESSING")));
        assertEquals(before, snapshot(id, owner));
    }

    UsernamePasswordAuthenticationToken captured(Actor actor) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + actor.role()));
        var auth = new UsernamePasswordAuthenticationToken(new AuthUser(actor.name(), "unused", true,
                actor.version(), actor.id(), false, authorities), "unused", authorities);
        SecurityContextHolder.getContext().setAuthentication(auth); return auth;
    }
    @ParameterizedTest @EnumSource(AdminRead.class)
    void listAndExportRecheckRealAdministratorInsteadOfCapturedAuthority(AdminRead endpoint) {
        var admin = actor("ADMIN"); var auth = captured(admin);
        jdbc.update("delete from sys_user_role where user_id=?", admin.id());
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='HANDLER'", admin.id());
        BizException denied = assertThrows(BizException.class, () -> {
            if (endpoint == AdminRead.LIST) feedback.list(null, null, auth); else feedback.export(auth);
        });
        assertEquals(403, denied.code);
    }
    @ParameterizedTest @EnumSource(Change.class)
    void myFeedbackReadRefusesARevokedCapturedSession(Change change) {
        var owner = actor("HANDLER"); item(owner, "NEW"); var auth = captured(owner);
        new TransactionTemplate(tm).executeWithoutResult(tx -> change(owner, change));
        assertEquals(403, assertThrows(BizException.class, () -> feedback.mine(auth)).code);
    }
    static Stream<Arguments> malformedSubmissions() {
        return Stream.of(
                Arguments.of("title", "题".repeat(256)), Arguments.of("title", "坏\0标题"),
                Arguments.of("content", "坏\0正文"), Arguments.of("pageRoute", "路".repeat(129)),
                Arguments.of("caseRef", "案".repeat(65)), Arguments.of("appVersion", "v".repeat(33)),
                Arguments.of("userAgent", "u".repeat(256)), Arguments.of("requestId", "r".repeat(65)),
                Arguments.of("recentErrors", "坏\0诊断"), Arguments.of("title", Map.of("unexpected", true)),
                Arguments.of("kind", List.of("BUG")), Arguments.of("userAgent", 42),
                Arguments.of("screenshotBase64", List.of("AQID")));
    }
    @ParameterizedTest @MethodSource("malformedSubmissions")
    void malformedExistingFieldsFailWithoutAppendingFeedbackOrMessages(String field, Object value) throws Exception {
        var owner = actor("HANDLER"); var body = new LinkedHashMap<>(requestBody()); body.put(field, value);
        int rows = jdbc.queryForObject("select count(*) from feedback", Integer.class);
        int messages = jdbc.queryForObject("select count(*) from sys_message", Integer.class);
        assertEquals(2090, call(owner, "/api/bureau/feedback", body));
        assertEquals(rows, jdbc.queryForObject("select count(*) from feedback", Integer.class));
        assertEquals(messages, jdbc.queryForObject("select count(*) from sys_message", Integer.class));
    }
    static Stream<Object> malformedReplies() { return Stream.of("坏\0回复", Map.of("unexpected", "reply")); }
    @ParameterizedTest @MethodSource("malformedReplies")
    void malformedHandlingReplyDoesNotPartiallyChangeStateOrNotify(Object reply) throws Exception {
        var owner = actor("HANDLER"); var admin = actor("ADMIN"); long id = item(owner, "NEW"); var before = snapshot(id, owner);
        assertEquals(2091, call(admin, "/api/bureau/feedback/" + id + "/handle", Map.of("status", "RESOLVED", "reply", reply)));
        assertEquals(before, snapshot(id, owner));
    }
    @Test void maximumUnicodeTextIsPreservedWithoutTruncation() throws Exception {
        var owner = actor("HANDLER"); var admin = actor("ADMIN"); var body = new LinkedHashMap<>(requestBody()); String title = "😀".repeat(255);
        body.put("title", title); body.put("userAgent", title);
        assertEquals(0, call(owner, "/api/bureau/feedback", body));
        assertEquals(title, jdbc.queryForObject("select title from feedback where username=?", String.class, owner.name()));
        assertEquals(title, jdbc.queryForObject("select user_agent from feedback where username=?", String.class, owner.name()));
        long id = jdbc.queryForObject("select id from feedback where username=?", Long.class, owner.name());
        assertEquals(0, call(admin, "/api/bureau/feedback/" + id + "/handle", Map.of("status", "RESOLVED", "reply", "原处理回复")));
        assertEquals(title, jdbc.queryForObject("select title from feedback where id=?", String.class, id));
        var notifications = jdbc.queryForList("select title from sys_message where (username=? and link='/my/feedback') or (username=? and link='/system/feedback')", String.class,
                owner.name(), admin.name());
        assertEquals(2, notifications.size());
        assertTrue(notifications.stream().allMatch(value -> value.codePointCount(0, value.length()) <= 255));
        assertEquals("原处理回复", jdbc.queryForObject("select content from sys_message where username=? and link='/my/feedback'", String.class, owner.name()));
    }
}
