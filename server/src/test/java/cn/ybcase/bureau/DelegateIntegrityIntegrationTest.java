package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.sql.Date;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Existing required delegation fields and leader authority must be checked before registration. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DelegateIntegrityIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired PlatformTransactionManager tm;

    record Actor(long id, String name, String token) {}

    @BeforeEach void isolate() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class),
                "Delegation fixtures must use a disposable database");
        SecurityContextHolder.clearContext();
    }

    Actor actor(String role) {
        var user = new SysUser();
        user.setUsername("delegate_" + UUID.randomUUID().toString().replace("-", ""));
        user.setRealName("委托登记回归");user.setPassword("unused-jwt-fixture");
        if (role != null) user.getRoles().add(roles.findByCode(role).orElseThrow());
        user = users.saveAndFlush(user);
        return new Actor(user.getId(), user.getUsername(), jwt.issue(user.getUsername(), user.getTokenVersion()));
    }

    Map<String,Object> valid() {
        var body = new LinkedHashMap<String,Object>();
        body.put("name", "委托组织" + System.nanoTime());body.put("agreementNo", "委托文号");
        body.put("scope", "原有委托事项和权限");body.put("startAt", "2026-10-01");body.put("endAt", "2026-10-31");
        return body;
    }

    int count() {return jdbc.queryForObject("select count(*) from delegate_org", Integer.class);}
    JsonNode create(Actor actor, Object body) throws Exception {
        return create(actor, body, false);
    }
    JsonNode create(Actor actor, Object body, boolean permissionDenial) throws Exception {
        var response = mvc.perform(post("/api/bureau/delegates").header("Authorization", "Bearer " + actor.token())
                .contentType("application/json").content(mapper.writeValueAsString(body))).andReturn().getResponse();
        if (permissionDenial)
            assertTrue(response.getStatus() == 200 || response.getStatus() == 403,
                    "Static permission denials may use the business envelope or the security transport response");
        else assertEquals(200, response.getStatus(), "Authenticated business calls must use the JSON envelope");
        return mapper.readTree(response.getContentAsString());
    }
    int code(Actor actor, Object body) throws Exception {return create(actor, body).path("code").asInt();}
    void rejected(Actor actor, Map<String,Object> body) throws Exception {
        var before = jdbc.queryForList("select * from delegate_org order by id");
        assertEquals(2068, code(actor, body), () -> "Invalid delegation was accepted: " + body);
        assertEquals(before, jdbc.queryForList("select * from delegate_org order by id"), "Rejected inputs must leave all prior delegations unchanged");
    }

    @Test void requiredTextRejectsMissingNullWhitespaceAndNonStringWithoutInsert() throws Exception {
        var leader = actor("LEADER");
        for (String key : List.of("name", "agreementNo", "scope")) {
            var missing = valid();missing.remove(key);rejected(leader, missing);
            for (Object value : Arrays.asList(null, "", " \t\n", 42, true, List.of("text"), Map.of("text", "value"))) {
                var body = valid();body.put(key, value);rejected(leader, body);
            }
        }
    }

    @Test void allTextFieldsRejectNulAndTheirActualDatabaseWidthBeforeSql() throws Exception {
        var admin = actor("ADMIN");
        var limits = Map.of("name", 128, "agreementNo", 64, "scope", 512, "note", 255);
        for (var field : limits.entrySet()) {
            for (String value : List.of("含\0非法字符", "😀".repeat(field.getValue() + 1))) {
                var body = valid();body.put(field.getKey(), value);rejected(admin, body);
            }
        }
        for (Object value : List.of(1, true, List.of("note"), Map.of("note", "text"))) {
            var body = valid();body.put("note", value);rejected(admin, body);
        }
    }

    @Test void bothPeriodDatesAreRequiredAndMalformedDatesNeverPersist() throws Exception {
        var leader = actor("LEADER");
        for (String key : List.of("startAt", "endAt")) {
            var missing = valid();missing.remove(key);rejected(leader, missing);
            for (Object value : Arrays.asList(null, "", " ", "2026-02-30", "2026-2-03", "2026-10-01T00:00:00",
                    "0000-01-01", "2026-10-01\0", 20261001, false, List.of("2026-10-01"))) {
                var body = valid();body.put(key, value);rejected(leader, body);
            }
        }
    }

    @Test void reversedPeriodIsRejectedButAnInclusiveSingleDayPeriodRemainsValid() throws Exception {
        var leader = actor("LEADER");var body = valid();body.put("endAt", "2026-09-30");rejected(leader, body);
        body.put("endAt", body.get("startAt"));int before = count();
        assertEquals(0, code(leader, body));assertEquals(before + 1, count());
        var row = jdbc.queryForMap("select * from delegate_org where name=?", body.get("name"));
        assertEquals(row.get("start_at"), row.get("end_at"));
    }

    @Test void optionalPublishedDateAllowsOmittedNullAndEmptyButRejectsInvalidValues() throws Exception {
        var leader = actor("LEADER");
        for (Object value : Arrays.asList("2026-02-30", "2026-1-01", "0000-01-01", "2026-10-01\0", " ", 20261001, List.of("2026-10-01"))) {
            var body = valid();body.put("publishedAt", value);rejected(leader, body);
        }
        for (Object value : Arrays.asList(null, "", "2026-09-01")) {
            var body = valid();body.put("publishedAt", value);body.put("note", null);int before = count();
            assertEquals(0, code(leader, body));assertEquals(before + 1, count());
            var row = jdbc.queryForMap("select * from delegate_org where name=?", body.get("name"));
            assertEquals(value == null || value.equals("") ? null : Date.valueOf(value.toString()), row.get("published_at"));
            assertNull(row.get("note"));
        }
    }

    @Test void unicodeWidthBoundariesAndOriginalTextArePreservedForBothAllowedRoles() throws Exception {
        for (String role : List.of("LEADER", "ADMIN")) {
            var actor = actor(role);var body = valid();
            body.put("name", "😀".repeat(126) + "  ");body.put("agreementNo", "😀".repeat(62) + "\n ");
            body.put("scope", "😀".repeat(510) + "\n ");body.put("note", "😀".repeat(253) + "\n ");
            body.put("publishedAt", "2026-09-01");int before = count();
            assertEquals(0, code(actor, body));assertEquals(before + 1, count());
            var row = jdbc.queryForMap("select * from delegate_org order by id desc limit 1");
            assertEquals(body.get("name"), row.get("name"));assertEquals(body.get("agreementNo"), row.get("agreement_no"));
            assertEquals(body.get("scope"), row.get("scope"));assertEquals(body.get("note"), row.get("note"));
        }
    }

    @Test void existingNonLeaderRolesCannotRegisterDelegations() throws Exception {
        for (String role : Arrays.asList("HANDLER", "LEGAL", null)) {
            int before = count();assertEquals(403, create(actor(role), valid(), true).path("code").asInt());assertEquals(before, count());
        }
    }

    void awaitBlocked(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_locks where not granted and ?=any(pg_blocking_pids(pid))",
                    Integer.class, blocker);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(25);
        }
        fail("The authenticated delegation registration did not wait on the held user lock");
    }

    int whileUserChanges(Actor actor, Runnable change) throws Exception {
        var pool = Executors.newSingleThreadExecutor();var pending = new AtomicReference<Future<Integer>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                jdbc.queryForList("select id from sys_user where id=? for update", actor.id());
                int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                pending.set(pool.submit(() -> code(actor, valid())));
                try {awaitBlocked(blocker);} catch (Exception failure) {throw new RuntimeException(failure);}
                change.run();
            });
            return pending.get().get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS));
        }
    }

    @Test void authenticatedLeaderDowngradedWhileWaitingCannotInsert() throws Exception {
        var leader = actor("LEADER");int before = count();
        assertEquals(403, whileUserChanges(leader, () -> {
            jdbc.update("delete from sys_user_role where user_id=?", leader.id());
            jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='HANDLER'", leader.id());
        }));
        assertEquals(before, count());
    }

    @Test void authenticatedLeaderDisabledWhileWaitingCannotInsert() throws Exception {
        var leader = actor("LEADER");int before = count();
        assertEquals(403, whileUserChanges(leader, () -> jdbc.update("update sys_user set enabled=false where id=?", leader.id())));
        assertEquals(before, count());
    }

    @Test void changedTokenVersionWhileWaitingCannotInsertUnderPreviousSession() throws Exception {
        var leader = actor("LEADER");int before = count();
        assertEquals(403, whileUserChanges(leader, () -> jdbc.update("update sys_user set token_version=token_version+1 where id=?", leader.id())));
        assertEquals(before, count());
    }

    @Test void stillAuthorizedLeaderCanRegisterAfterAnUnrelatedAccountUpdate() throws Exception {
        var leader = actor("LEADER");int before = count();
        assertEquals(0, whileUserChanges(leader, () -> jdbc.update("update sys_user set title='既有负责人' where id=?", leader.id())));
        assertEquals(before + 1, count());
    }
}
