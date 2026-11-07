package cn.ybcase.bureau;

import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FormalAccountRosterIntegrationTest {
    private static final String ENDPOINT = "/api/system/users/roster";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder passwords;

    private record Actor(String username, String token) {}
    private record Department(long id, String code) {}

    @Test void mixedRosterPreviewIsReadOnlyAndApplyPreservesLegacyCredentialsAndRoles() throws Exception {
        var administrator = actor("ADMIN");
        var legacy = actor("HANDLER");
        var bureau = department("BUREAU", true);
        var district = department("DISTRICT", true);
        String firstNew = name(), secondNew = name();
        var rows = List.of(
                create(firstNew, district, "DISTRICT", "HANDLER"),
                classify(legacy, bureau, "BUREAU"),
                create(secondNew, bureau, "BUREAU", "LEADER"));
        var before = user(legacy.username());
        var preview = call(administrator, "/preview", rows, 0);
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, preview.get(i).path("row").asInt());
            assertEquals("READY", preview.get(i).path("status").asText());
            assertFalse(preview.get(i).has("initialPassword"));
        }
        assertEquals(before, user(legacy.username()));
        assertFalse(exists(firstNew));

        var result = request(administrator, "/apply", rows);
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        var applied = data(result, 0);
        String firstPassword = applied.get(0).path("initialPassword").asText();
        String secondPassword = applied.get(2).path("initialPassword").asText();
        assertTrue(firstPassword.length() >= 20 && secondPassword.length() >= 20);
        assertTrue(!firstPassword.equals(secondPassword), "Each initial credential must be distinct");
        assertTrue(passwords.matches(firstPassword, (String) user(firstNew).get("password")));
        assertTrue(passwords.matches(secondPassword, (String) user(secondNew).get("password")));
        assertEquals(Boolean.TRUE, user(firstNew).get("must_change_password"));
        assertEquals("DISTRICT", user(firstNew).get("account_category"));
        assertEquals(district.id(), user(firstNew).get("dept_id"));
        assertEquals(Set.of("HANDLER"), roles(firstNew));
        assertFalse(applied.get(1).has("initialPassword"));

        var after = user(legacy.username());
        assertEquals("BUREAU", after.get("account_category"));
        assertEquals(bureau.id(), after.get("dept_id"));
        assertEquals((int) before.get("token_version") + 1, after.get("token_version"));
        for (String field : List.of("password", "must_change_password", "password_updated_at", "real_name", "enabled"))
            assertEquals(before.get(field), after.get(field), field);
        assertEquals(Set.of("HANDLER"), roles(legacy.username()));
        assertEquals(401, mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + legacy.token()))
                .andReturn().getResponse().getStatus());

        call(administrator, "/apply", rows, 1106);
        assertTrue(passwords.matches(firstPassword, (String) user(firstNew).get("password")));
        assertEquals(after, user(legacy.username()));
        var auditRows = jdbc.queryForList("""
                select row_to_json(a)::text from sys_audit_log a where a.username=? and a.path like ?
                """, String.class, administrator.username(), ENDPOINT + "%");
        assertFalse(auditRows.isEmpty());
        assertTrue(auditRows.stream().noneMatch(value -> value.contains(firstPassword) || value.contains(secondPassword)));
    }

    @Test void malformedRowsAndStalePreviewsNeverPartiallyApply() throws Exception {
        var administrator = actor("ADMIN");
        var legacy = actor("HANDLER");
        var valid = department("DISTRICT", true);
        var disabled = department("DISTRICT", false);
        var other = department("DISTRICT", true);
        String fresh = name(), duplicate = name();
        jdbc.update("update sys_user set dept_id=? where username=?", other.id(), legacy.username());
        var invalid = List.of(create(fresh, valid, "DISTRICT", "HANDLER"),
                classify(legacy, valid, "DISTRICT"),
                create(duplicate, valid, "DISTRICT", "HANDLER"),
                create(duplicate, valid, "DISTRICT", "HANDLER"),
                create(name(), disabled, "DISTRICT", "HANDLER"),
                create(name(), valid, "BUREAU", "LEADER"),
                create(name(), valid, "DISTRICT", "ADMIN"),
                create(name(), valid, "DISTRICT", "MISSING_ROLE"),
                Map.<String, Object>of("action", "CLASSIFY", "username", "admin", "realName", "系统管理员",
                        "departmentCode", valid.code(), "accountCategory", "DISTRICT"));
        var preview = call(administrator, "/preview", invalid, 0);
        assertEquals("READY", preview.get(0).path("status").asText());
        for (int i = 1; i < invalid.size(); i++) assertEquals("ERROR", preview.get(i).path("status").asText());
        call(administrator, "/apply", invalid, 1106);
        assertFalse(exists(fresh));
        assertFalse(exists(duplicate));
        assertNull(user(legacy.username()).get("account_category"));

        var approved = List.of(create(fresh, valid, "DISTRICT", "HANDLER"),
                classify(legacy, other, "DISTRICT"));
        assertEquals("READY", call(administrator, "/preview", approved, 0).get(1).path("status").asText());
        jdbc.update("update sys_dept set enabled=false where id=?", other.id());
        call(administrator, "/apply", approved, 1106);
        assertFalse(exists(fresh));
        assertNull(user(legacy.username()).get("account_category"));
    }

    @Test void concurrentApplicationsHaveOneWinnerAndDoNotCreateAccountsForLoser() throws Exception {
        var first = actor("ADMIN");
        var second = actor("ADMIN");
        var district = department("DISTRICT", true);
        String shared = name(), firstOnly = name(), secondOnly = name();
        var firstBatch = List.of(create(firstOnly, district, "DISTRICT", "HANDLER"),
                create(shared, district, "DISTRICT", "HANDLER"));
        var secondBatch = List.of(create(secondOnly, district, "DISTRICT", "HANDLER"),
                create(shared, district, "DISTRICT", "HANDLER"));
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("等待并发测试启动超时");
                return responseCode(request(first, "/apply", firstBatch));
            });
            var b = workers.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("等待并发测试启动超时");
                return responseCode(request(second, "/apply", secondBatch));
            });
            start.countDown();
            int firstCode = a.get(20, TimeUnit.SECONDS), secondCode = b.get(20, TimeUnit.SECONDS);
            assertEquals(Set.of(0, 1106), Set.of(firstCode, secondCode));
            assertEquals(firstCode == 0, exists(firstOnly));
            assertEquals(secondCode == 0, exists(secondOnly));
            assertTrue(exists(shared));
        }
    }

    @Test void rosterRequiresAdminAndEnforcesBatchLimit() throws Exception {
        var handler = actor("HANDLER");
        var admin = actor("ADMIN");
        var district = department("DISTRICT", true);
        var bureau = department("BUREAU", true);
        String username = name();
        var rows = List.of(create(username, district, "DISTRICT", "HANDLER"));
        call(handler, "/preview", rows, 403);
        call(handler, "/apply", rows, 403);
        call(admin, "/preview", java.util.Collections.nCopies(101, rows.getFirst()), 1106);
        call(admin, "/apply", List.of(), 1106);
        var selfClassification = List.of(rows.getFirst(), classify(admin, bureau, "BUREAU"));
        assertEquals("ERROR", call(admin, "/preview", selfClassification, 0).get(1).path("status").asText());
        call(admin, "/apply", selfClassification, 1106);
        assertNull(user(admin.username()).get("account_category"));
        assertFalse(exists(username));
    }

    private Actor actor(String role) {
        String username = name();
        FixtureUsers.ensure(jdbc, username, role);
        return new Actor(username, jwt.issue(username, 0));
    }

    private Department department(String category, boolean enabled) {
        String code = "R-" + UUID.randomUUID().toString().substring(0, 20);
        long id = jdbc.queryForObject("""
                insert into sys_dept(name,code,type,org_kind,enabled)
                values(?,?,'ADMIN',?,?) returning id
                """, Long.class, code, code, category, enabled);
        return new Department(id, code);
    }

    private static String name() { return "roster_" + UUID.randomUUID().toString().replace("-", ""); }

    private Map<String, Object> create(String username, Department dept, String category, String role) {
        return Map.of("action", "CREATE", "username", username, "realName", "名册测试人员",
                "departmentCode", dept.code(), "accountCategory", category, "roleCodes", List.of(role));
    }

    private Map<String, Object> classify(Actor actor, Department dept, String category) {
        return Map.of("action", "CLASSIFY", "username", actor.username(), "realName", user(actor.username()).get("real_name"),
                "departmentCode", dept.code(), "accountCategory", category);
    }

    private Map<String, Object> user(String username) {
        return jdbc.queryForMap("select * from sys_user where username=?", username);
    }

    private boolean exists(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from sys_user where username=?)", Boolean.class, username));
    }

    private Set<String> roles(String username) {
        return Set.copyOf(jdbc.queryForList("""
                select r.code from sys_role r join sys_user_role ur on ur.role_id=r.id
                join sys_user u on u.id=ur.user_id where u.username=?
                """, String.class, username));
    }

    private MvcResult request(Actor actor, String route, List<Map<String, Object>> rows) throws Exception {
        return mvc.perform(post(ENDPOINT + route).header("Authorization", "Bearer " + actor.token())
                        .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("rows", rows))))
                .andReturn();
    }

    private int responseCode(MvcResult response) throws Exception {
        return mapper.readTree(response.getResponse().getContentAsString()).path("code").asInt(-1);
    }

    private JsonNode call(Actor actor, String route, List<Map<String, Object>> rows, int expected) throws Exception {
        return data(request(actor, route, rows), expected);
    }

    private JsonNode data(MvcResult response, int expected) throws Exception {
        var body = mapper.readTree(response.getResponse().getContentAsString());
        assertEquals(expected, body.path("code").asInt(-1), body.path("message").asText());
        return body.path("data").path("rows");
    }
}
