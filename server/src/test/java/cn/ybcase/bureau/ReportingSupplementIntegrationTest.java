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
import java.util.HashMap;
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
class ReportingSupplementIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    @Autowired cn.ybcase.bureau.service.ReportingSupplementService supplements;

    private record Actor(long id, String username, String token, long department) {}

    @BeforeEach void isolated() {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).startsWith("ybcase_"));
    }

    @Test void approvedReportValuesLeaveClosedCaseDecisionAndDocumentsUnchanged() throws Exception {
        var author = actor("DISTRICT", "HANDLER");
        var reviewer = actor("BUREAU", "LEGAL");
        long caseId = caseFile(author, "PROVIDER", "CLOSED", "PUNISH");
        jdbc.update("update case_file set institution_code='  ',confirmed_violation_amount=0 where id=?", caseId);
        String sourceBefore = legalSource(caseId);
        var values = completeProviderValues();
        values.remove("confirmedViolationAmount");
        String path = path(caseId);
        assertTrue(read(author, path, 0).path("canRequest").asBoolean());
        long id = write(author, path, request(values), 0).path("id").asLong();
        assertFalse(read(author, path, 0).path("canRequest").asBoolean());
        assertNull(jdbc.queryForObject("select institution_code from case_reporting_facts where case_id=?", String.class, caseId));
        var queue = read(reviewer, "/report-supplements/pending?page=1&size=100", 0).path("rows");
        assertTrue(contains(queue, id));
        read(author, "/report-supplements/pending", 403);
        write(reviewer, path + "/" + id + "/review", Map.of("approve", true, "opinion", "已核对原始材料"), 0);

        assertEquals(sourceBefore, legalSource(caseId));
        var facts = jdbc.queryForMap("select * from case_reporting_facts where case_id=?", caseId);
        assertEquals("H-0001", facts.get("institution_code"));
        assertEquals("SOCIAL", facts.get("institution_ownership"));
        assertEquals("UNGRADED", facts.get("institution_grade"));
        assertEquals("来源材料确认的事实", facts.get("confirmed_violation_facts"));
        assertEquals("测试处罚依据", facts.get("penalty_basis"));
        assertEquals(0, ((java.math.BigDecimal) facts.get("confirmed_violation_amount")).signum());
        assertEquals(true, facts.get("has_approved_supplement"));
        var state = read(author, path, 0);
        assertEquals(0, state.path("missingFields").size());
        assertFalse(state.path("canRequest").asBoolean());
        var history = state.path("history").get(0);
        assertEquals("APPROVED", history.path("status").asText());
        assertEquals("  ", history.path("originalSnapshot").path("institutionCode").asText());
        assertEquals("H-0001", history.path("approvedSnapshot").path("institutionCode").asText());
        assertEquals(author.username(), history.path("requestedBy").asText());
        assertEquals(reviewer.username(), history.path("reviewedBy").asText());
        write(author, path, request(Map.of("institutionCode", "OVERWRITE")), 2191);

        // A later legal source value always takes priority over the report-only supplement.
        jdbc.update("update case_file set institution_code='ORIGINAL-HAS-PRIORITY' where id=?", caseId);
        assertEquals("ORIGINAL-HAS-PRIORITY", jdbc.queryForObject(
                "select institution_code from case_reporting_facts where case_id=?", String.class, caseId));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "update case_reporting_supplement_request set reason='改写申请' where id=?", id));
    }

    @Test void permissionsSeparateRequestingFromReviewAndProtectForeignCases() throws Exception {
        var author = actor("DISTRICT", "HANDLER");
        var outsider = actor("DISTRICT", "LEADER");
        var adminOnly = actor("BUREAU", "ADMIN");
        var districtLegal = actor("DISTRICT", "LEGAL");
        var selfReviewer = actor("BUREAU", "LEADER");
        long caseId = caseFile(author, "INDIVIDUAL", "DELIVERED", "PUNISH");
        String path = path(caseId);
        read(outsider, path, 2080);
        write(outsider, path, request(Map.of("partyCreditNo", "ID-0001")), 2080);
        long id = write(author, path, request(Map.of("partyCreditNo", "ID-0001")), 0).path("id").asLong();
        write(adminOnly, path + "/" + id + "/review", Map.of("approve", true, "opinion", "管理员单岗"), 403);
        read(adminOnly, "/report-supplements/pending", 403);
        read(districtLegal, "/report-supplements/pending", 403);
        long otherCase = caseFile(selfReviewer, "INDIVIDUAL", "DECIDED", "PUNISH");
        long ownId = write(selfReviewer, path(otherCase), request(Map.of("partyCreditNo", "ID-SELF")), 0).path("id").asLong();
        write(selfReviewer, path(otherCase) + "/" + ownId + "/review",
                Map.of("approve", true, "opinion", "本人审核"), 403);
        write(selfReviewer, path(otherCase) + "/" + ownId + "/cancel", Map.of("opinion", "撤回重核"), 0);
        FixtureUsers.ensure(jdbc, adminOnly.username(), "LEGAL");
        write(adminOnly, path + "/" + id + "/review", Map.of("approve", true, "opinion", "兼任法制已核验"), 0);
        assertEquals("ID-0001", jdbc.queryForObject(
                "select party_credit_no from case_reporting_facts where case_id=?", String.class, caseId));
        assertNull(jdbc.queryForObject("select party_credit_no from case_file where id=?", String.class, caseId));
    }

    @Test void staleOrDisabledApplicationsRemainRejectableAndCancellable() throws Exception {
        var author = actor("DISTRICT", "HANDLER");
        var reviewer = actor("BUREAU", "LEGAL");
        var leader = actorAt("DISTRICT", "LEADER", author.department());
        long caseId = caseFile(author, "PROVIDER", "DECIDED", "PUNISH");
        String path = path(caseId);
        long id = write(author, path, request(Map.of("institutionCode", "FIRST")), 0).path("id").asLong();
        jdbc.update("update case_file set summary='来源在提交后发生变化' where id=?", caseId);
        write(reviewer, path + "/" + id + "/review", Map.of("approve", true, "opinion", "过期申请"), 2191);
        assertNull(jdbc.queryForObject("select institution_code from case_reporting_facts where case_id=?", String.class, caseId));
        write(reviewer, path + "/" + id + "/review", Map.of("approve", false, "opinion", "请按新资料重提"), 0);

        long replacement = write(author, path, request(Map.of("institutionCode", "SECOND")), 0).path("id").asLong();
        jdbc.update("update sys_user set enabled=false where id=?", author.id());
        write(reviewer, path + "/" + replacement + "/review", Map.of("approve", true, "opinion", "申请人已停用"), 403);
        write(leader, path + "/" + replacement + "/cancel", Map.of("opinion", "原登记人已停用"), 0);
        assertNull(jdbc.queryForObject("select institution_code from case_reporting_facts where case_id=?", String.class, caseId));
        write(reviewer, path + "/" + replacement + "/review", Map.of("approve", false, "opinion", "重复处理"), 2191);
    }

    @Test void invalidInputCannotOverwriteZeroOrChangeLegalFields() throws Exception {
        var author = actor("DISTRICT", "HANDLER");
        long caseId = caseFile(author, "PROVIDER", "CLOSED", "PUNISH");
        jdbc.update("update case_file set confirmed_violation_amount=0 where id=?", caseId);
        String path = path(caseId);
        for (Map<String, Object> values : List.of(
                Map.<String, Object>of("fineAmount", 7),
                Map.<String, Object>of("partyCreditNo", "WRONG-SUBJECT"),
                Map.<String, Object>of("confirmedViolationAmount", 5),
                Map.<String, Object>of("institutionOwnership", "UNKNOWN"),
                Map.<String, Object>of("confirmedViolationStart", LocalDate.now().toString()),
                Map.<String, Object>of("institutionCode", ""))) {
            write(author, path, request(values), 2191);
        }
        write(author, path, Map.of("reason", "缺来源", "values", Map.of("institutionCode", "NO-SOURCE")), 2191);
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from case_reporting_supplement_request where case_id=?", Integer.class, caseId));
        long preciseCase = caseFile(author, "INDIVIDUAL", "DECIDED", "PUNISH");
        var precisionResponse = mvc.perform(post("/api/bureau" + path(preciseCase))
                .header("Authorization", "Bearer " + author.token()).contentType("application/json")
                .content("""
                        {"reason":"精度测试","sourceReference":"原案卷",
                         "values":{"confirmedViolationAmount":999999999999.990000000001}}
                        """)).andReturn().getResponse();
        assertEquals(2140, mapper.readTree(precisionResponse.getContentAsString()).path("code").asInt());
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from case_reporting_supplement_request where case_id=?", Integer.class, preciseCase));
        for (String status : List.of("INVESTIGATING", "REPORTED", "NOTIFIED", "TERMINATED"))
            write(author, path(caseFile(author, "PROVIDER", status, "PUNISH")), request(Map.of("institutionCode", "INELIGIBLE")), 2191);
        write(author, path(caseFile(author, "PROVIDER", "DECIDED", "NO_PUNISH")),
                request(Map.of("institutionCode", "NON-PUNISHMENT")), 2191);
    }

    @Test void concurrentReviewsApplyExactlyOnce() throws Exception {
        var author = actor("DISTRICT", "HANDLER");
        var first = actor("BUREAU", "LEGAL");
        var second = actor("BUREAU", "LEADER");
        long caseId = caseFile(author, "INDIVIDUAL", "CLOSED", "PUNISH");
        String path = path(caseId);
        long id = write(author, path, request(Map.of("partyCreditNo", "CONCURRENT-ID", "confirmedViolationAmount", 0)), 0)
                .path("id").asLong();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = List.of(first, second).stream().map(actor -> pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return writeCode(actor, path + "/" + id + "/review", Map.of("approve", true, "opinion", "并发复核"));
            })).toList();
            start.countDown();
            assertEquals(Set.of(0, 2191), Set.of(results.getFirst().get(15, TimeUnit.SECONDS), results.getLast().get(15, TimeUnit.SECONDS)));
        }
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from case_reporting_supplement_values where case_id=?", Integer.class, caseId));
        assertEquals(0, jdbc.queryForObject(
                "select confirmed_violation_amount from case_reporting_facts where case_id=?", java.math.BigDecimal.class, caseId).signum());
        write(author, path, request(Map.of("confirmedViolationAmount", 1)), 2191);
    }

    @Test void concurrentRequestsHaveOnlyOnePendingRecord() throws Exception {
        var author = actor("DISTRICT", "HANDLER");
        long caseId = caseFile(author, "PROVIDER", "CLOSED", "PUNISH");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = List.of("FIRST", "SECOND").stream().map(code -> pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return writeCode(author, path(caseId), request(Map.of("institutionCode", code)));
            })).toList();
            start.countDown();
            assertEquals(Set.of(0, 2191), Set.of(results.getFirst().get(15, TimeUnit.SECONDS), results.getLast().get(15, TimeUnit.SECONDS)));
        }
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from case_reporting_supplement_request where case_id=? and status='PENDING'
                """, Integer.class, caseId));
    }

    @Test void historyAndCancellationRecheckRolesAtTheServiceEntry() throws Exception {
        var author = actor("DISTRICT", "HANDLER");
        long caseId = caseFile(author, "PROVIDER", "CLOSED", "PUNISH");
        long id = write(author, path(caseId), request(Map.of("institutionCode", "ROLE-RECHECK")), 0).path("id").asLong();
        jdbc.update("delete from sys_user_role where user_id=?", author.id());
        var readDenied = assertThrows(cn.ybcase.bureau.common.BizException.class,
                () -> supplements.state(caseId, author.username()));
        assertEquals(403, readDenied.code);
        var cancelDenied = assertThrows(cn.ybcase.bureau.common.BizException.class,
                () -> supplements.cancel(caseId, id, Map.of("opinion", "岗位已撤回"), author.username()));
        assertEquals(403, cancelDenied.code);
        assertEquals("PENDING", jdbc.queryForObject(
                "select status from case_reporting_supplement_request where id=?", String.class, id));
    }

    private Actor actor(String category, String role) {
        String code = "SUP-" + UUID.randomUUID().toString().substring(0, 18);
        long dept = jdbc.queryForObject("""
                insert into sys_dept(name,code,type,org_kind) values(?,?,'ADMIN',?) returning id
                """, Long.class, code, code, category);
        return actorAt(category, role, dept);
    }

    private Actor actorAt(String category, String role, long dept) {
        String username = "supplement_" + UUID.randomUUID().toString().replace("-", "");
        FixtureUsers.ensure(jdbc, username, role);
        jdbc.update("update sys_user set dept_id=?,account_category=? where username=?", dept, category, username);
        long id = jdbc.queryForObject("select id from sys_user where username=?", Long.class, username);
        return new Actor(id, username, jwt.issue(username, 0), dept);
    }

    private long caseFile(Actor owner, String party, String status, String decision) {
        String number = "SUP-" + UUID.randomUUID();
        long id = jdbc.queryForObject("""
                insert into case_file(case_no,name,cause_id,party_name,party_type,status,filed_at,deadline_at,
                                      owner_user,owner_dept_id,archive_no)
                values(?, '补录测试',(select min(id) from case_cause),?,?,?,current_date,current_date,?,?,?)
                returning id
                """, Long.class, number, "测试对象", party, status, owner.username(), owner.department(),
                "CLOSED".equals(status) ? number + "卷" : null);
        jdbc.update("""
                insert into case_decision(case_id,decision_type,content,decided_at,fine_amount)
                values(?,?,'原决定全文',current_date,?)
                """, id, decision, "PUNISH".equals(decision) ? 1 : 0);
        jdbc.update("""
                insert into case_document(case_id,doc_type,title,content,made_at,maker)
                values(?,'DECISION','原决定文书','原文书全文',current_date,?)
                """, id, owner.username());
        return id;
    }

    private String legalSource(long caseId) {
        return jdbc.queryForObject("""
                select jsonb_build_object('case',to_jsonb(c),'decision',to_jsonb(d),
                    'documents',(select jsonb_agg(to_jsonb(doc) order by doc.id) from case_document doc where doc.case_id=c.id))::text
                from case_file c join case_decision d on d.case_id=c.id where c.id=?
                """, String.class, caseId);
    }

    private Map<String, Object> completeProviderValues() {
        return new HashMap<>(Map.of("institutionCode", "H-0001", "institutionOwnership", "SOCIAL",
                "institutionGrade", "UNGRADED", "confirmedViolationStart", LocalDate.now().minusDays(1).toString(),
                "confirmedViolationEnd", LocalDate.now().toString(), "confirmedViolationFacts", "来源材料确认的事实",
                "confirmedViolationAmount", 0, "penaltyBasis", "测试处罚依据"));
    }

    private static String path(long caseId) { return "/cases/" + caseId + "/report-supplements"; }
    private static Map<String, Object> request(Map<String, Object> values) {
        return Map.of("reason", "核对旧案报表缺项", "sourceReference", "原案卷第3至5页", "values", values);
    }

    private JsonNode read(Actor actor, String path, int expected) throws Exception {
        var response = mvc.perform(get("/api/bureau" + path).header("Authorization", "Bearer " + actor.token()))
                .andReturn().getResponse();
        var root = mapper.readTree(response.getContentAsString());
        assertEquals(expected, root.path("code").asInt(-1), root.toString());
        return root.path("data");
    }

    private JsonNode write(Actor actor, String path, Map<String, Object> body, int expected) throws Exception {
        var response = mvc.perform(post("/api/bureau" + path).header("Authorization", "Bearer " + actor.token())
                        .contentType("application/json").content(mapper.writeValueAsBytes(body)))
                .andReturn().getResponse();
        var root = mapper.readTree(response.getContentAsString());
        assertEquals(expected, root.path("code").asInt(-1), root.toString());
        return root.path("data");
    }

    private int writeCode(Actor actor, String path, Map<String, Object> body) throws Exception {
        var response = mvc.perform(post("/api/bureau" + path).header("Authorization", "Bearer " + actor.token())
                        .contentType("application/json").content(mapper.writeValueAsBytes(body)))
                .andReturn().getResponse();
        return mapper.readTree(response.getContentAsString()).path("code").asInt(-1);
    }

    private boolean contains(JsonNode rows, long id) {
        for (var row : rows) if (row.path("id").asLong() == id) return true;
        return false;
    }
}
