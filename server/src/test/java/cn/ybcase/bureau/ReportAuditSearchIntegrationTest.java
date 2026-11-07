package cn.ybcase.bureau;

import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReportAuditSearchIntegrationTest {
    private static final String ENDPOINT = "/api/audit/logs";
    private static final LocalDate DAY = LocalDate.of(2089, 4, 19);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;

    @BeforeEach void isolated() {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).startsWith("ybcase_"));
    }

    @Test void reportTypeCombinesWithAllExistingFiltersBeforeCountAndPaging() throws Exception {
        String admin = token("ADMIN");
        String marker = marker(), requestId = marker + "-req";
        String path = "/api/bureau/stats/" + marker + "/download";
        long penalty = event("report_operator", path, requestId, DAY, "00:00:00", "PENALTIES", 200, 0);
        long work = event("report_operator", path, requestId, DAY, "12:00:00", "ENFORCEMENT_WORK", 200, 7);
        long interview = event("report_operator", path, requestId, DAY, "23:59:59", "INTERVIEWS", 200, 1);
        event("report_operator", path, requestId, DAY.minusDays(1), "23:59:59", "PENALTIES", 200, 1);
        event("report_operator", path, requestId, DAY.plusDays(1), "00:00:00", "PENALTIES", 200, 1);
        event("other_operator", path, requestId, DAY, "12:00:00", "PENALTIES", 200, 1);
        event("report_operator", path, requestId + "-other", DAY, "12:00:00", "PENALTIES", 200, 1);
        event("report_operator", "/api/unrelated/download", requestId, DAY, "12:00:00", "PENALTIES", 200, 1);
        event("report_operator", path, requestId, DAY, "12:00:00", "PENALTIES", 403, 1);
        event("report_operator", path, requestId, DAY, "12:00:00", "PENALTIES", 200, null);
        event("report_operator", path, requestId, DAY, "12:00:00", null, 200, null);
        var filters = new HashMap<>(Map.of("username", "report_operator", "from", DAY.toString(),
                "to", DAY.toString(), "path", marker, "requestId", requestId, "sensitive", "true",
                "page", "1", "size", "2", "reportType", "PENALTIES"));

        var one = query(admin, filters, 0);
        assertEquals(1, one.path("total").asInt());
        assertEquals(List.of(penalty), ids(one.path("rows")));
        assertEquals(0, one.path("rows").get(0).path("report_row_count").asInt(-1));
        assertEquals("PENALTIES", one.path("rows").get(0).path("report_type").asText());
        assertEquals(DAY.toString(), one.path("rows").get(0).path("report_start").asText());

        filters.put("reportType", "ALL_REPORTS");
        var firstPage = query(admin, filters, 0);
        assertEquals(3, firstPage.path("total").asInt());
        assertEquals(1, firstPage.path("page").asInt());
        assertEquals(2, firstPage.path("size").asInt());
        assertEquals(List.of(interview, work), ids(firstPage.path("rows")));
        filters.put("page", "2");
        var secondPage = query(admin, filters, 0);
        assertEquals(3, secondPage.path("total").asInt());
        assertEquals(List.of(penalty), ids(secondPage.path("rows")));

        filters.remove("page");
        filters.remove("size");
        filters.put("reportType", "ENFORCEMENT_WORK");
        assertEquals(List.of(work), ids(query(admin, filters, 0)));
        filters.put("reportType", "INTERVIEWS");
        assertEquals(List.of(interview), ids(query(admin, filters, 0)));
    }

    @Test void sensitiveReportsJoinLegacySensitivePathsAndBlankTypeRetainsLegacyShape() throws Exception {
        String admin = token("ADMIN"), marker = marker();
        String path = "/api/bureau/stats/" + marker + "/download";
        long structured = event("report_operator", path, marker, DAY, "12:00:00", "INTERVIEWS", 200, 1);
        long failed = event("report_operator", path, marker, DAY, "12:00:00", null, 2100, null);
        long historical = event("report_operator", "/api/bureau/audit/export", marker, DAY, "12:00:00", null, 200, null);
        long missingFields = event("report_operator", "/api/bureau/stats/penalties/missing-fields",
                marker, DAY, "12:00:00", null, 200, null);

        var legacy = query(admin, Map.of("requestId", marker), 0);
        assertTrue(legacy.isArray());
        assertEquals(List.of(missingFields, historical, failed, structured), ids(legacy));
        assertEquals(ids(legacy), ids(query(admin, Map.of("requestId", marker, "reportType", " "), 0)));
        assertEquals(List.of(missingFields, historical, structured),
                ids(query(admin, Map.of("requestId", marker, "sensitive", "true"), 0)));
        assertEquals(List.of(structured), ids(query(admin, Map.of("requestId", marker, "reportType", "ALL_REPORTS"), 0)));
        assertTrue(query(admin, Map.of("reportType", "PENALTIES' OR TRUE"), 2139).isNull());
        assertTrue(query(token("HANDLER"), Map.of("reportType", "ALL_REPORTS"), 403).isNull());
    }

    @Test void maxIdPinsOffsetPagingWhenNewEventsAreAppended() throws Exception {
        String admin = token("ADMIN"), marker = marker();
        String path = "/api/other/" + marker;
        long oldest = event("report_operator", path, marker, DAY, "12:00:00", null, 200, null);
        long newest = event("report_operator", path, marker, DAY, "12:00:00", null, 200, null);
        var filters = new HashMap<>(Map.of("requestId", marker, "page", "1", "size", "1"));
        var first = query(admin, filters, 0);
        assertEquals(List.of(newest), ids(first.path("rows")));
        assertEquals(2, first.path("total").asInt());
        event("report_operator", path, marker, DAY, "12:00:00", null, 200, null);
        filters.put("maxId", Long.toString(newest));
        filters.put("page", "2");
        var second = query(admin, filters, 0);
        assertEquals(2, second.path("total").asInt());
        assertEquals(List.of(oldest), ids(second.path("rows")));
        assertTrue(query(admin, Map.of("maxId", "0"), 2139).isNull());
    }

    private static String marker() { return "report-audit-" + UUID.randomUUID(); }

    private String token(String role) {
        String username = "report_audit_" + UUID.randomUUID().toString().replace("-", "");
        FixtureUsers.ensure(jdbc, username, role);
        return jwt.issue(username, 0);
    }

    private long event(String username, String path, String requestId, LocalDate day, String time,
                       String reportType, int status, Integer count) {
        LocalDate metadataDate = count == null ? null : DAY;
        return jdbc.queryForObject("""
                insert into sys_audit_log(username, method, path, request_id, created_at, http_status,
                    report_type, report_start, report_end, report_cutoff, report_row_count)
                values (?, 'GET', ?, ?, (?::date + ?::time)::timestamptz, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, username, path, requestId, day, time, status,
                reportType, metadataDate, metadataDate, metadataDate, count);
    }

    private JsonNode query(String token, Map<String, String> filters, int code) throws Exception {
        var request = get(ENDPOINT).header("Authorization", "Bearer " + token);
        filters.forEach((key, value) -> request.param(key, value));
        var response = mvc.perform(request).andReturn().getResponse();
        var body = mapper.readTree(response.getContentAsString());
        assertEquals(code, body.path("code").asInt(-1), body.toString());
        return body.path("data");
    }

    private static List<Long> ids(JsonNode rows) {
        List<Long> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }
}
