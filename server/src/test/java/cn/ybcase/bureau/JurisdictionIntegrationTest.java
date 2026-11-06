package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.ApprovalService;
import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.core.common.ConfigChangedEvent;
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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Jurisdiction facts, approval snapshots and subsequent corrections remain distinct and traceable. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class JurisdictionIntegrationTest {
    @Autowired CaseService cases;
    @Autowired ApprovalService approvals;
    @Autowired CaseCauseRepository causes;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired BureauConfig config;

    private Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("causeId", causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId());
        p.put("partyName", "JURISDICTION-" + System.nanoTime());
        p.put("partyType", "PROVIDER"); p.put("procedureType", "NORMAL");
        p.put("summary", "管辖事实测试"); p.put("amountInvolved", BigDecimal.TEN);
        p.put("officers", List.of(Map.of("name", "王办案", "certNo", "YB001", "duty", "LEAD"),
            Map.of("name", "张协办", "certNo", "YB002", "duty", "MEMBER")));
        return p;
    }

    private Map<String, Object> local(String place) {
        return Map.of("type", "LOCAL", "basis", "违法行为发生地管辖，已核查事实", "violationPlace", place);
    }

    private CaseFile create(Map<String, Object> payload) {
        return cases.create(mapper.convertValue(payload, CaseService.CaseCreateReq.class), "banban");
    }

    private CaseFile update(long id, Map<String, Object> facts, String reason) {
        return cases.updateJurisdiction(id, mapper.convertValue(Map.of("jurisdiction", facts, "reason", reason),
            CaseService.JurisdictionUpdateReq.class), "banban");
    }

    private JsonNode jurisdiction(long id) {
        return mapper.valueToTree(cases.detail(id).get("jurisdiction"));
    }

    private JsonNode approvalPayload(long approvalId) throws Exception {
        return mapper.readTree(jdbc.queryForObject("select payload from biz_approval where id = ?", String.class, approvalId));
    }

    private String token(String role) {
        SysUser user = new SysUser(); user.setUsername("jurisdiction_" + System.nanoTime());
        user.setRealName("测试" + role); user.setPassword("unused-for-jwt-tests");
        user.getRoles().add(roles.findByCode(role).orElseThrow());
        user = users.save(user);
        return jwt.issue(user.getUsername(), user.getTokenVersion());
    }

    @Test void legacyCreateWithoutJurisdictionRemainsCompatibleAndDefaultsToPending() {
        var p = payload();
        CaseFile c = create(p);
        assertEquals("PENDING", jurisdiction(c.getId()).path("type").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from case_correction where case_id = ?", Integer.class, c.getId()));
    }

    @Test void invalidTypeLengthsMissingBasisAndMissingDesignationAreRejectedBeforeCreatingCase() {
        List<Map<String, Object>> invalid = List.of(
            Map.of("type", "INVALID"),
            Map.of("type", "x".repeat(25)),
            Map.of("type", "OTHER"),
            Map.of("type", "LOCAL", "basis", "属地依据"),
            Map.of("type", "DESIGNATED", "basis", "指定管辖依据"),
            Map.of("type", "PENDING", "basis", "x".repeat(2001)),
            Map.of("type", "PENDING", "violationPlace", "x".repeat(256)),
            Map.of("type", "PENDING", "treatmentPlace", "x".repeat(256)),
            Map.of("type", "PENDING", "insuredPlace", "x".repeat(256)),
            Map.of("type", "PENDING", "designatedNo", "x".repeat(129)),
            Map.of("type", "PENDING", "disputeNote", "x".repeat(2001)));
        for (var facts : invalid) {
            var p = payload(); p.put("jurisdiction", facts);
            assertEquals(2145, assertThrows(BizException.class, () -> create(p)).code, facts.toString());
            assertEquals(0, jdbc.queryForObject("select count(*) from case_file where party_name = ?", Integer.class, p.get("partyName")));
        }
        var valid = payload();
        valid.put("jurisdiction", Map.of("type", "DESIGNATED", "basis", "上级指定", "designatedNo", "医保指〔2026〕1号"));
        assertEquals("DESIGNATED", jurisdiction(create(valid).getId()).path("type").asText());
        var boundary = payload();
        boundary.put("jurisdiction", Map.of("type", "PENDING", "basis", "x".repeat(2000), "violationPlace", "x".repeat(255),
            "treatmentPlace", "x".repeat(255), "insuredPlace", "x".repeat(255), "designatedNo", "x".repeat(128), "disputeNote", "x".repeat(2000)));
        assertEquals(2000, jurisdiction(create(boundary).getId()).path("basis").asText().length());
    }

    @Test void filingApplicationSnapshotAndApprovedCaseFactsAreIdenticalAndCannotBeRewrittenByCorrection() throws Exception {
        var p = payload(); p.put("jurisdiction", Map.of("type", " local ", "basis", " 已核查违法行为发生地 ", "violationPlace", " 甲地 "));
        long approvalId = approvals.apply(new ApprovalService.ApplyReq("FILE_CASE", null, null, p, "核查后申请立案"), "banban", false);
        var original = approvalPayload(approvalId);
        assertEquals("LOCAL", original.path("jurisdiction").path("type").asText());
        assertEquals("甲地", original.path("jurisdiction").path("violationPlace").asText());
        var result = approvals.decide(approvalId, true, "同意立案", "juzhang");
        long caseId = ((CaseFile) result.get("result")).getId();
        assertEquals(original.path("jurisdiction"), jurisdiction(caseId));
        update(caseId, local("乙地"), "补充核查修正违法发生地");
        assertEquals("乙地", jurisdiction(caseId).path("violationPlace").asText());
        assertEquals(original, approvalPayload(approvalId), "Current fact correction must not rewrite the original filing approval");
    }

    @Test void directFilingStoresJurisdictionSnapshotAndCorrectionsRetainBeforeAndAfterFacts() throws Exception {
        var p = payload(); p.put("jurisdiction", local("甲地"));
        var response = mvc.perform(post("/api/bureau/cases").header("Authorization", "Bearer " + token("ADMIN"))
            .contentType("application/json").content(mapper.writeValueAsString(p))).andReturn().getResponse();
        var body = mapper.readTree(response.getContentAsString());
        assertEquals(0, body.path("code").asInt(), body.toString());
        long id = body.path("data").path("id").asLong();
        long approvalId = jdbc.queryForObject("select id from biz_approval where case_id = ? and kind = 'FILE_CASE'", Long.class, id);
        var original = approvalPayload(approvalId);
        assertEquals(jurisdiction(id), original.path("jurisdiction"));
        assertEquals(2145, assertThrows(BizException.class, () -> update(id, local("乙地"), "")).code);
        assertEquals(2145, assertThrows(BizException.class, () -> update(id, local("乙地"), "x".repeat(1001))).code);
        assertEquals("甲地", jurisdiction(id).path("violationPlace").asText());
        update(id, local("乙地"), "补充核查发生地");
        var history = jdbc.queryForMap("select old_value, new_value, reason, operator from case_correction where case_id = ? and field = 'violationPlace'", id);
        assertEquals("甲地", history.get("old_value")); assertEquals("乙地", history.get("new_value"));
        assertEquals("补充核查发生地", history.get("reason")); assertEquals("banban", history.get("operator"));
        assertEquals(original, approvalPayload(approvalId));
        assertFalse(((List<?>) cases.detail(id).get("jurisdictionHistory")).isEmpty());
        assertFalse(((List<?>) cases.detail(id).get("jurisdictionMaterials")).isEmpty());
    }

    @Test void decidedAndArchivedCasesRejectJurisdictionChangesWithoutLeavingFalseHistory() {
        var p = payload(); p.put("procedureType", "SUMMARY");
        var decided = create(p);
        cases.decide(decided.getId(), new CaseService.DecisionReq("PUNISH", new BigDecimal("100"), BigDecimal.ZERO,
            BigDecimal.ZERO, null, "罚款100元", null, "一般情形"));
        assertEquals(2145, assertThrows(BizException.class, () -> update(decided.getId(), local("乙地"), "决定后补改")).code);
        var archived = create(payload()); cases.terminate(archived.getId(), "违法事实不能成立");
        cases.close(archived.getId(), "终止调查归档", "admin");
        assertEquals(2031, assertThrows(BizException.class, () -> update(archived.getId(), local("乙地"), "归档后补改")).code);
        for (var c : List.of(decided, archived)) {
            assertEquals("PENDING", jurisdiction(c.getId()).path("type").asText());
            assertEquals(0, jdbc.queryForObject("select count(*) from case_correction where case_id = ?", Integer.class, c.getId()));
        }
    }

    @Test void legalRoleCannotChangeAndSelfScopeDoesNotExposeForeignCase() throws Exception {
        var c = create(payload());
        String body = mapper.writeValueAsString(Map.of("jurisdiction", local("乙地"), "reason", "补充依据"));
        var legalResponse = mvc.perform(put("/api/bureau/cases/{id}/jurisdiction", c.getId())
            .header("Authorization", "Bearer " + token("LEGAL")).contentType("application/json").content(body)).andReturn().getResponse();
        assertEquals(403, mapper.readTree(legalResponse.getContentAsString()).path("code").asInt());
        String old = jdbc.queryForObject("select cfg_value from sys_config where cfg_key = 'case_view_scope'", String.class);
        try {
            jdbc.update("update sys_config set cfg_value = 'SELF' where cfg_key = 'case_view_scope'");
            config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
            var forbidden = mvc.perform(put("/api/bureau/cases/{id}/jurisdiction", c.getId())
                .header("Authorization", "Bearer " + token("HANDLER")).contentType("application/json").content(body)).andReturn().getResponse();
            assertEquals(2080, mapper.readTree(forbidden.getContentAsString()).path("code").asInt());
            assertEquals("PENDING", jurisdiction(c.getId()).path("type").asText());
            assertEquals(0, jdbc.queryForObject("select count(*) from case_correction where case_id = ?", Integer.class, c.getId()));
        } finally {
            jdbc.update("update sys_config set cfg_value = ? where cfg_key = 'case_view_scope'", old);
            config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
        }
    }
}
