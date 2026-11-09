package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class WorkdayDeadlinePreviewIntegrationTest {
    private static final String PATH = "/api/bureau/holidays/deadline-preview";
    private static final LocalDate FRIDAY = LocalDate.of(2097, 1, 4);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;

    @Test
    void previewUsesImportedDaysAndCurrentUnitsWithoutWritingStoredDeadlines() throws Exception {
        String admin = token("ADMIN"), handler = token("HANDLER");
        assertEquals(403, preview(handler, 1, 25).path("code").asInt());
        assertEquals(0, call(get("/api/bureau/holidays/coverage"), handler).path("code").asInt());
        rule("clue_verify", "1", "WORKDAY");
        rule("legal_review", "1", "NATURAL");
        jdbc.update("delete from sys_holiday where day >= date '2097-01-01' and day < date '2099-01-01'");
        long adjustedBefore = jdbc.queryForObject("select count(*) from case_clue where status='PENDING' and (extended or excluded_days<>0)", Long.class);
        long open = clue("PREVIEW-OPEN", "PENDING", FRIDAY, false, 0);
        clue("PREVIEW-EXTENDED", "PENDING", FRIDAY, true, 0);
        clue("PREVIEW-EXCLUDED", "PENDING", FRIDAY, false, 3);
        clue("PREVIEW-CLOSED", "REJECTED", FRIDAY, false, 0);
        LocalDate crossYear = LocalDate.of(2097, 12, 31);
        clue("PREVIEW-CROSS-YEAR", "PENDING", crossYear, false, 0);
        long active = caseRow("PREVIEW-REVIEW", "NOTIFIED");
        long old = review(active, false);
        long latest = review(active, false);
        long completed = caseRow("PREVIEW-REVIEW-DONE", "REPORTED");
        review(completed, false);
        review(completed, true);
        review(caseRow("PREVIEW-DECIDED", "DECIDED"), false);
        review(caseRow("PREVIEW-RETURNED", "INVESTIGATING"), false);

        assertEquals("MISSING_CALENDAR", row(preview(admin, 1, 100).path("data"), "PREVIEW-OPEN").path("status").asText());
        var imported = call(post("/api/bureau/holidays/batch").contentType("application/json").content("""
                [{"day":"2097-01-05","kind":"SHIFT_WORK","name":"测试调休"},
                 {"day":"2097-01-07","kind":"HOLIDAY","name":"测试放假"}]
                """), admin);
        assertEquals(0, imported.path("code").asInt(), imported.toString());
        var result = preview(admin, 1, 100);
        assertEquals(0, result.path("code").asInt(), result.toString());
        var data = result.path("data");
        assertEquals(adjustedBefore + 2, data.path("excludedAdjustedClues").asLong());
        var comparison = row(data, "PREVIEW-OPEN");
        assertEquals(open, comparison.path("id").asLong());
        assertEquals("DIFFERENT", comparison.path("status").asText());
        assertEquals("2097-01-05", comparison.path("recalculatedDeadline").asText());
        assertEquals("2097-01-07", comparison.path("storedDeadline").asText());
        var uncertain = row(data, "PREVIEW-CROSS-YEAR");
        assertEquals("MISSING_CALENDAR", uncertain.path("status").asText());
        assertEquals(2098, uncertain.path("missingYears").get(0).asInt());
        assertTrue(uncertain.path("recalculatedDeadline").isNull());
        var current = row(data, "PREVIEW-REVIEW");
        assertEquals(latest, current.path("id").asLong());
        assertEquals("NATURAL", current.path("unit").asText());
        assertEquals("SAME", current.path("status").asText());
        var rows = entries(data);
        assertEquals(3, rows.stream().filter(r -> r.path("referenceNo").asText().startsWith("PREVIEW-")).count());
        assertFalse(rows.stream().anyMatch(r -> "LEGAL_REVIEW".equals(r.path("kind").asText()) && r.path("id").asLong() == old));
        assertEquals(1, entries(preview(admin, 1, 1).path("data")).size());
        assertNotEquals(entries(preview(admin, 1, 1).path("data")), entries(preview(admin, 2, 1).path("data")));
        assertEquals(FRIDAY.plusDays(3), jdbc.queryForObject("select deadline_at from case_clue where id=?",
                (rs, n) -> rs.getObject(1, LocalDate.class), open));
        assertEquals(FRIDAY.plusDays(1), jdbc.queryForObject("select deadline_at from case_review where id=?",
                (rs, n) -> rs.getObject(1, LocalDate.class), latest));
    }

    private void rule(String key, String days, String unit) {
        jdbc.update("update sys_config set cfg_value=? where cfg_key=?", days, key + "_days");
        jdbc.update("update sys_config set cfg_value=? where cfg_key=?", unit, key + "_day_unit");
    }

    private long clue(String reference, String status, LocalDate started, boolean extended, int excluded) {
        return jdbc.queryForObject("""
                insert into case_clue(clue_no,source,content,suspect_name,suspect_type,received_at,deadline_at,status,extended,excluded_days)
                values(?,'COMPLAINT','测试线索','测试主体','PROVIDER',?,?,?,?,?) returning id
                """, Long.class, reference, started, started.plusDays(3), status, extended, excluded);
    }

    private long caseRow(String reference, String status) {
        return jdbc.queryForObject("""
                insert into case_file(case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,status)
                values(?, '测试案件',(select min(id) from case_cause),'测试主体','PROVIDER',?,?,?) returning id
                """, Long.class, reference, FRIDAY, FRIDAY.plusDays(90), status);
    }

    private long review(long caseId, boolean completed) {
        return jdbc.queryForObject("""
                insert into case_review(case_id,required_reason,submitted_at,deadline_at,reviewed_at)
                values(?, '测试审核',?,?,?) returning id
                """, Long.class, caseId, FRIDAY, FRIDAY.plusDays(1), completed ? FRIDAY.plusDays(1) : null);
    }

    private String token(String role) {
        var actor = new SysUser();
        actor.setUsername("calprev_" + UUID.randomUUID().toString().replace("-", ""));
        actor.setRealName("期限预览回归");
        actor.setPassword("unused-jwt-fixture");
        actor.getRoles().add(roles.findByCode(role).orElseThrow());
        actor = users.saveAndFlush(actor);
        return jwt.issue(actor.getUsername(), actor.getTokenVersion());
    }

    private JsonNode preview(String token, int page, int size) throws Exception {
        return call(get(PATH).param("page", String.valueOf(page)).param("size", String.valueOf(size)), token);
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token) throws Exception {
        var response = mvc.perform(request.header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return mapper.readTree(response.getContentAsString());
    }

    private List<JsonNode> entries(JsonNode data) {
        var rows = new ArrayList<JsonNode>();
        data.path("rows").forEach(rows::add);
        return rows;
    }

    private JsonNode row(JsonNode data, String reference) {
        return entries(data).stream().filter(r -> reference.equals(r.path("referenceNo").asText())).findFirst().orElseThrow();
    }
}
