package cn.ybcase.bureau;

import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Effective money follows the same approval sources as Excel, with original case visibility preserved. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class EffectiveStatisticsIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    @Autowired BureauConfig config;
    String originalScope;
    record Actor(long id, String name, long department, String token) {}

    @BeforeEach void isolatedSelfScope() {
        assertTrue(jdbc.queryForObject("select current_database()", String.class)
                .matches("ybcase_authowner_test_[0-9]{8}_[0-9a-f]{8}"));
        originalScope = jdbc.queryForObject("select cfg_value from sys_config where cfg_key='case_view_scope'", String.class);
        jdbc.update("update sys_config set cfg_value='SELF' where cfg_key='case_view_scope'");
        config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
    }

    @AfterEach void restoreScope() {
        if (originalScope != null) {
            jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'", originalScope);
            config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
        }
    }

    @Test void approvedDecisionChangeAndExecutionRefundAgreeAcrossOverviewAndMonthlyWithoutSourceRewrite() throws Exception {
        var owner = actor("HANDLER"); var reviewer = actor("LEGAL");
        LocalDate date = LocalDate.of(2015, 6, 15);
        long id = caseFile(owner, date);
        originalPayment(id, "FINE", 30, date);
        originalPayment(id, "RECOUP", 10, date);
        String original = source(id);
        version(id, owner, reviewer, "CHANGE", date.plusDays(1));
        long additional = execution(id, owner, reviewer, "FINE", 50, date.plusDays(2), null, false);
        execution(id, owner, reviewer, "FINE", -20, date.plusDays(3), additional, true);
        execution(id, owner, reviewer, "RECOUP", 15, date.plusDays(2), null, false);
        var overview = call(owner, "/stats/overview");
        var monthly = month(call(owner, "/stats/report?year=2015"), 6);
        amount(overview, "fineDecided", 150); amount(overview, "recoupDecided", 50);
        amount(overview, "confiscateDecided", 5);
        amount(overview, "fineCollected", 60); amount(overview, "recoupCollected", 25);
        amount(monthly, "fine_decided", 150); amount(monthly, "recoup_decided", 50); amount(monthly, "collected", 85);
        assertEquals(0, overview.path("fineCollected").decimalValue().add(overview.path("recoupCollected").decimalValue())
                .compareTo(monthly.path("collected").decimalValue()));
        assertEquals(1, overview.path("caseTotal").asInt());
        assertEquals(1, monthly.path("filed").asInt()); assertEquals(1, monthly.path("closed").asInt());
        assertEquals(original, source(id));
    }

    @Test void selfScopeExcludesOtherOwnersAndRevocationKeepsCaseCountsWhileRefundMonthRemainsNegative() throws Exception {
        var owner = actor("HANDLER"); var outsider = actor("HANDLER"); var reviewer = actor("LEGAL");
        LocalDate date = LocalDate.of(2016, 1, 15);
        long own = caseFile(owner, date), hidden = caseFile(outsider, date);
        long payment = originalPayment(own, "FINE", 30, date);
        originalPayment(hidden, "FINE", 900, date);
        String original = source(own), hiddenOriginal = source(hidden);
        version(own, owner, reviewer, "REVOKE", LocalDate.of(2016, 2, 1));
        execution(own, owner, reviewer, "FINE", -20, LocalDate.of(2016, 2, 2), payment, false);
        execution(hidden, outsider, reviewer, "FINE", 500, date.plusDays(1), null, false);
        var overview = call(owner, "/stats/overview");
        var report = call(owner, "/stats/report?year=2016");
        amount(overview, "fineDecided", 0); amount(overview, "recoupDecided", 0); amount(overview, "confiscateDecided", 0);
        amount(overview, "fineCollected", 10); amount(overview, "recoupCollected", 0);
        amount(month(report, 1), "fine_decided", 0); amount(month(report, 1), "recoup_decided", 0);
        amount(month(report, 1), "collected", 30); amount(month(report, 2), "collected", -20);
        assertEquals(1, overview.path("caseTotal").asInt());
        assertEquals(1, report.path("totals").path("filed_total").asInt());
        assertEquals(1, report.path("totals").path("closed_total").asInt());
        assertEquals(original, source(own)); assertEquals(hiddenOriginal, source(hidden));
    }

    private long execution(long caseId, Actor owner, Actor reviewer, String kind, int amount,
                           LocalDate paidAt, Long source, boolean continuationSource) {
        String key = UUID.randomUUID().toString();
        long request = request(caseId, owner, reviewer, amount < 0 ? "REFUND" : "EXECUTION", key);
        return jdbc.queryForObject("""
                insert into case_execution_continuation(case_id,request_id,kind,amount,paid_at,receipt_no,method,note,
                    refunds_execution_id,refunds_entry_id)
                values(?,?,?,?,?,?,?,'实际凭证',?,?) returning id
                """, Long.class, caseId, request, kind, amount, paidAt, key, amount < 0 ? "REFUND" : "BANK",
                amount < 0 && !continuationSource ? source : null, amount < 0 && continuationSource ? source : null);
    }

    private void version(long caseId, Actor owner, Actor reviewer, String outcome, LocalDate effectiveAt) {
        String key = UUID.randomUUID().toString();
        long remedy = jdbc.queryForObject("""
                insert into case_remedy(case_id,request_key,remedy_type,accepted_at,reference,reason,source_reference,
                    registered_by,registered_username)
                values(?,?,'CORRECTION',?,'结果文号','核对结果','原件',?,?) returning id
                """, Long.class, caseId, key, effectiveAt, owner.id(), owner.name());
        long request = request(caseId, owner, reviewer, "REMEDY_RESULT", key);
        boolean revoked = "REVOKE".equals(outcome);
        jdbc.update("""
                insert into case_decision_version(case_id,request_id,remedy_id,outcome,effective_at,decision_type,
                    decision_no,fine_amount,recoup_amount,confiscate_amount,penalty_basis,content)
                values(?,?,?,?,?,?,?,?,?,?,'结果依据','有效结果')
                """, caseId, request, remedy, outcome, effectiveAt, revoked ? "REVOKED" : "PUNISH", "STAT-V-" + key,
                revoked ? 0 : 150, revoked ? 0 : 50, revoked ? 0 : 5);
    }

    private long request(long caseId, Actor owner, Actor reviewer, String type, String key) {
        return jdbc.queryForObject("""
                insert into case_continuation_request(case_id,request_key,request_type,payload,source_snapshot,
                    reason,source_reference,requested_by,requested_username,requester_dept_id,requester_category,
                    status,reviewed_by,reviewed_username,reviewed_at,opinion)
                values(?,?,?,'{}'::jsonb,'{}'::jsonb,'实际办理','卷内原件',?,?,?,'BUREAU','APPROVED',?,?,current_timestamp,'复核通过')
                returning id
                """, Long.class, caseId, key, type, owner.id(), owner.name(), owner.department(), reviewer.id(), reviewer.name());
    }

    private long originalPayment(long caseId, String kind, int amount, LocalDate date) {
        return jdbc.queryForObject("""
                insert into case_execution(case_id,kind,amount,paid_at,method,receipt_no)
                values(?,?,?,?,'BANK',?) returning id
                """, Long.class, caseId, kind, amount, date, UUID.randomUUID().toString());
    }

    private long caseFile(Actor owner, LocalDate date) {
        long id = jdbc.queryForObject("""
                insert into case_file(case_no,name,cause_id,party_name,party_type,status,filed_at,deadline_at,
                    owner_user,owner_dept_id,decided_at,closed_at,archive_no)
                values(?,'有效统计',(select min(id) from case_cause),'有效统计机构','PROVIDER','CLOSED',?,?,?,?,?,?, '原卷') returning id
                """, Long.class, "ES-" + UUID.randomUUID(), date, date.plusDays(20), owner.name(), owner.department(), date, date);
        jdbc.update("""
                insert into case_decision(case_id,decision_type,decision_no,content,decided_at,fine_amount,recoup_amount,confiscate_amount)
                values(?,'PUNISH',?,'原决定',?,100,20,3)
                """, id, "ES-" + UUID.randomUUID(), date);
        return id;
    }

    private Actor actor(String role) {
        String name = "ES-" + UUID.randomUUID().toString().substring(0, 16);
        long department = jdbc.queryForObject("insert into sys_dept(name,code,type,org_kind) values(?,?,'ADMIN','BUREAU') returning id",
                Long.class, name, name);
        FixtureUsers.ensure(jdbc, name, role);
        jdbc.update("update sys_user set account_category='BUREAU',dept_id=? where username=?", department, name);
        return new Actor(jdbc.queryForObject("select id from sys_user where username=?", Long.class, name), name, department, jwt.issue(name, 0));
    }

    private JsonNode call(Actor actor, String path) throws Exception {
        var response = mvc.perform(get("/api/bureau" + path).header("Authorization", "Bearer " + actor.token()))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var result = mapper.readTree(response.getContentAsString());
        assertEquals(0, result.path("code").asInt(-1), result.toString());
        return result.path("data");
    }

    private static JsonNode month(JsonNode report, int month) {
        for (var row : report.path("monthly")) if (row.path("month").asInt() == month) return row;
        fail("Month not returned: " + month); return null;
    }

    private static void amount(JsonNode data, String field, int expected) {
        assertTrue(data.path(field).isNumber(), data.toString());
        assertEquals(0, data.path(field).decimalValue().compareTo(BigDecimal.valueOf(expected)), field + ": " + data);
    }

    private String source(long caseId) {
        return jdbc.queryForObject("""
                select md5(jsonb_build_object('c',to_jsonb(c),'d',to_jsonb(d),'e',
                    (select jsonb_agg(to_jsonb(e) order by e.id) from case_execution e where e.case_id=c.id))::text)
                from case_file c join case_decision d on d.case_id=c.id where c.id=?
                """, String.class, caseId);
    }
}
