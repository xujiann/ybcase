package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.ExecutionService;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Preflight must predict the same guards as execution without creating any case material or taking numbers. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PreflightIntegrationTest {
    @Autowired CaseService cases;
    @Autowired CaseCauseRepository causes;
    @Autowired ExecutionService execution;
    @Autowired BureauConfig config;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;

    private final Map<String, String> originalConfig = new LinkedHashMap<>();

    private void configure(String key, String value) {
        if (!originalConfig.containsKey(key)) {
            var rows = jdbc.queryForList("select cfg_value from sys_config where cfg_key = ?", key);
            originalConfig.put(key, rows.isEmpty() ? null : (String) rows.get(0).get("cfg_value"));
        }
        assertEquals(1, jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?", value, key));
        config.onConfigChanged(new ConfigChangedEvent(key));
    }

    @BeforeEach void defaultGuardSettings() {
        configure("archive_completeness_required", "false");
        configure("legal_review_mode", "THRESHOLD");
        configure("statement_wait_required", "true");
        configure("discretion_reason_required", "true");
    }

    @AfterEach void restoreSettings() {
        originalConfig.forEach((key, value) -> {
            if (value == null) jdbc.update("delete from sys_config where cfg_key = ?", key);
            else jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?", value, key);
            config.onConfigChanged(new ConfigChangedEvent(key));
        });
        originalConfig.clear();
    }

    private CaseFile fresh() {
        long cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId();
        return cases.create(new CaseService.CaseCreateReq(null, cause, "NORMAL", "PREFLIGHT-" + System.nanoTime(),
            "PROVIDER", null, null, null, null, "前置检查测试", BigDecimal.TEN,
            List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
                    new CaseService.OfficerReq("张协办", "YB002", "MEMBER")), null, null, null, null), "banban");
    }

    private CaseService.DecisionReq decision(BigDecimal fine, String reason) {
        return new CaseService.DecisionReq("PUNISH", fine, BigDecimal.ZERO, BigDecimal.ZERO,
            null, "依事实处罚", null, reason);
    }

    private CaseFile noticed(BigDecimal fine, boolean waive) {
        var c = fresh();
        cases.report(c.getId(), "调查终结报告", "banban");
        cases.notify(c.getId(), new CaseService.NoticeReq("拟处罚", fine, BigDecimal.ZERO, BigDecimal.ZERO, null));
        if (waive) cases.recordStatement(c.getId(), new CaseService.StatementReq(null, null, null, null, true));
        return c;
    }

    private Map<String, Object> preflight(long id, String action, CaseService.DecisionReq decision, String closeReport) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        if (decision != null) body.put("decision", decision);
        if (closeReport != null) body.put("close", Map.of("closeReport", closeReport));
        var req = mapper.convertValue(body, CaseService.PreflightReq.class);
        return mapper.convertValue(cases.preflight(id, req), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }

    private Map<String, Object> state(long id) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("case", jdbc.queryForMap("select status, archive_no, closed_at, close_reason from case_file where id = ?", id));
        state.put("decisions", jdbc.queryForList("select * from case_decision where case_id = ? order by id", id));
        state.put("documents", jdbc.queryForList("select * from case_document where case_id = ? order by id", id));
        state.put("sequences", jdbc.queryForList("select * from biz_seq order by kind, year"));
        return state;
    }

    @Test void decisionPreflightReturnsMultipleGapsAndRejectsRealDecisionWithoutWriting() {
        var c = noticed(new BigDecimal("150000"), false);
        var request = decision(new BigDecimal("150000"), null);
        cases.recordStatement(c.getId(), new CaseService.StatementReq(null, null, true, null, null));
        var before = state(c.getId());
        var result = preflight(c.getId(), "DECIDE", request, null);
        assertEquals("DECIDE", result.get("action"));
        assertEquals(false, result.get("ready"));
        var failures = items(result).stream().filter(i -> Boolean.FALSE.equals(i.get("passed"))).toList();
        assertTrue(failures.size() >= 3, "Hearing, legal review, discussion and discretion gaps should be collected together: " + result);
        assertTrue(failures.stream().allMatch(i -> i.get("key") != null && i.get("label") != null && i.get("message") != null && i.get("role") != null));
        assertEquals(before, state(c.getId()), "Failed preflight must not persist material or allocate business numbers");
        assertThrows(BizException.class, () -> cases.decide(c.getId(), request));
        assertEquals(before, state(c.getId()), "Rejected final operation must leave the same state");
    }

    @Test void readyDecisionPreflightDoesNotCreateDecisionOrTakeNumberButActualDecisionDoes() {
        var c = noticed(new BigDecimal("100"), true);
        var request = decision(new BigDecimal("100"), "一般情形");
        var before = state(c.getId());
        for (int i = 0; i < 2; i++) {
            var result = preflight(c.getId(), "DECIDE", request, null);
            assertEquals(true, result.get("ready"), result.toString());
            assertTrue(items(result).stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed"))));
            assertEquals(before, state(c.getId()), "Repeated preflight must remain read only");
        }
        var saved = cases.decide(c.getId(), request);
        assertNotNull(saved.getDecisionNo());
        assertEquals("DECIDED", cases.get(c.getId()).getStatus());
        assertEquals(1, jdbc.queryForObject("select count(*) from case_decision where case_id = ?", Integer.class, c.getId()));
        assertNotEquals(before.get("sequences"), state(c.getId()).get("sequences"));
    }

    @Test void deliveredPunishmentMustBePaidBeforeReadyAndPreflightNeverWritesCloseReport() {
        var c = noticed(new BigDecimal("100"), true);
        cases.decide(c.getId(), decision(new BigDecimal("100"), "一般情形"));
        cases.deliver(c.getId(), new CaseService.DeliveryReq("DIRECT", LocalDate.now(), "法人", null, "TEST-RECEIPT", null));
        var unpaid = state(c.getId());
        var blocked = preflight(c.getId(), "CLOSE", null, "执行完毕结案报告");
        assertEquals(false, blocked.get("ready"));
        assertEquals(unpaid, state(c.getId()));
        assertThrows(BizException.class, () -> cases.close(c.getId(), "未付不能结案", "admin"));
        execution.addExecution(c.getId(), new ExecutionService.ExecutionReq("FINE", new BigDecimal("100"), LocalDate.now(), "BANK", null, null));
        var paid = state(c.getId());
        var ready = preflight(c.getId(), "CLOSE", null, "执行完毕结案报告");
        assertEquals(true, ready.get("ready"), ready.toString());
        assertEquals(paid, state(c.getId()), "Ready close preflight must neither assign archive number nor create close report");
        assertNull(cases.get(c.getId()).getArchiveNo());
        assertEquals(0, jdbc.queryForObject("select count(*) from case_document where case_id = ? and doc_type = 'CLOSE_REPORT'", Integer.class, c.getId()));
        assertEquals("CLOSED", cases.close(c.getId(), "执行完毕结案报告", "admin").getStatus());
        assertEquals(1, jdbc.queryForObject("select count(*) from case_document where case_id = ? and doc_type = 'CLOSE_REPORT'", Integer.class, c.getId()));
    }

    @Test void terminatedPreflightAccountsForPendingCloseReportButStillRequiresOtherArchiveMaterials() {
        configure("archive_completeness_required", "true");
        var c = fresh();
        cases.terminate(c.getId(), "违法事实不能成立");
        addDocument(c.getId(), "FILING_APPROVAL");
        var before = state(c.getId());
        var missing = preflight(c.getId(), "CLOSE", null, "终止调查归档报告");
        assertEquals(false, missing.get("ready"));
        assertTrue(items(missing).stream().filter(i -> Boolean.FALSE.equals(i.get("passed"))).anyMatch(i -> String.valueOf(i.get("message")).contains("TERMINATE_DECISION") || String.valueOf(i.get("label")).contains("终止")), missing.toString());
        assertEquals(before, state(c.getId()));
        assertThrows(BizException.class, () -> cases.close(c.getId(), "终止调查归档报告", "admin"));
        assertEquals(before, state(c.getId()), "Failed archive must roll back any generated close report");
        addDocument(c.getId(), "TERMINATE_DECISION");
        var complete = state(c.getId());
        var ready = preflight(c.getId(), "CLOSE", null, "终止调查归档报告");
        assertEquals(true, ready.get("ready"), "Only pending CLOSE_REPORT remains and final operation supplies it: " + ready);
        assertEquals(complete, state(c.getId()));
        var archived = cases.close(c.getId(), "终止调查归档报告", "admin");
        assertEquals("TERMINATED", archived.getStatus());
        assertNotNull(archived.getArchiveNo());
        assertEquals(1, jdbc.queryForObject("select count(*) from case_document where case_id = ? and doc_type = 'CLOSE_REPORT'", Integer.class, c.getId()));
    }

    private void addDocument(long id, String type) {
        cases.addDocument(id, new CaseService.DocumentReq(type, type, "已形成材料", LocalDate.now(), "admin", false, null, null));
    }

    @Test void caseScopeDeniesPreflightBeforeReturningChecklist() throws Exception {
        configure("case_view_scope", "SELF");
        var c = fresh();
        SysUser outsider = new SysUser();
        outsider.setUsername("preflight_scope_" + System.nanoTime());
        outsider.setRealName("无此案权限的办案员");
        outsider.setPassword("not-used-for-jwt-test");
        outsider.getRoles().add(roles.findByCode("HANDLER").orElseThrow());
        outsider = users.save(outsider);
        String token = jwt.issue(outsider.getUsername(), outsider.getTokenVersion());
        var before = state(c.getId());
        var response = mvc.perform(post("/api/bureau/cases/{id}/preflight", c.getId())
            .header("Authorization", "Bearer " + token).contentType("application/json")
            .content("{\"action\":\"CLOSE\",\"close\":{\"closeReport\":\"无权预检\"}}"))
            .andReturn().getResponse();
        var body = mapper.readTree(response.getContentAsString());
        assertEquals(2080, body.path("code").asInt(), response.getContentAsString());
        assertTrue(body.path("data").isNull() || body.path("data").isMissingNode(), "Scope denial must not leak a checklist");
        assertEquals(before, state(c.getId()));
    }

    @Test void invalidActionIsRejectedWithoutChanges() {
        var c = fresh();
        var before = state(c.getId());
        var error = assertThrows(BizException.class, () -> preflight(c.getId(), "UNKNOWN", null, "报告"));
        assertEquals(2144, error.code);
        assertEquals(before, state(c.getId()));
    }
}
