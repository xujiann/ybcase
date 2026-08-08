package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseCause;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.ApprovalService;
import cn.ybcase.bureau.service.CaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 法定守卫服务层集成测试（真库 ybcase_test，Flyway 全迁移；CI 由 postgres 服务容器提供） */
@SpringBootTest
@ActiveProfiles("test")
class GuardIntegrationTest {

    @Autowired CaseService caseService;
    @Autowired ApprovalService approvalService;
    @Autowired CaseCauseRepository causeRepository;

    private CaseCause providerCause() {
        return causeRepository.findAll().stream()
                .filter(c -> c.getItemNo() == 13).findFirst().orElseThrow();
    }

    private CaseService.CaseCreateReq req(String party, List<CaseService.OfficerReq> officers) {
        return new CaseService.CaseCreateReq(null, providerCause().getId(), "NORMAL",
                party, "PROVIDER", null, null, null, null, "集成测试", BigDecimal.TEN,
                officers, null, null, null, null);
    }

    private static final List<CaseService.OfficerReq> TWO = List.of(
            new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
            new CaseService.OfficerReq("张协办", "YB002", "MEMBER"));

    @Test
    void 执法人员不足两人拒2002() {
        var e = assertThrows(BizException.class, () -> caseService.create(
                req("IT-单人立案" + System.nanoTime(), List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"))),
                "it"));
        assertEquals(2002, e.code);
    }

    @Test
    void 执法证不在台账拒2055() {
        var e = assertThrows(BizException.class, () -> caseService.create(
                req("IT-无证立案" + System.nanoTime(), List.of(
                        new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
                        new CaseService.OfficerReq("路人", "NOPE", "MEMBER"))), "it"));
        assertEquals(2055, e.code);
    }

    @Test
    void 案由主体不匹配拒2001() {
        var cause = providerCause();
        var e = assertThrows(BizException.class, () -> caseService.create(
                new CaseService.CaseCreateReq(null, cause.getId(), "NORMAL",
                        "IT-个人" + System.nanoTime(), "INDIVIDUAL", null, null, null, null, null,
                        BigDecimal.ONE, TWO, null, null, null, null), "it"));
        assertEquals(2001, e.code);
    }

    @Test
    void 简易程序限额拒2003() {
        CaseFile c = caseService.create(new CaseService.CaseCreateReq(null,
                causeRepository.findAll().stream().filter(x -> x.getItemNo() == 31).findFirst().orElseThrow().getId(),
                "SUMMARY", "IT-简易" + System.nanoTime(), "INDIVIDUAL", null, null, null, null, null,
                BigDecimal.ONE, TWO, null, null, null, null), "it");
        var e = assertThrows(BizException.class, () -> caseService.decide(c.getId(),
                new CaseService.DecisionReq("PUNISH", new BigDecimal("500"), null, null, null, "x", null, "r")));
        assertEquals(2003, e.code);
    }

    @Test
    void 告知前决定拒2006与不加重拒2007() {
        CaseFile c = caseService.create(req("IT-流程" + System.nanoTime(), TWO), "it");
        var e = assertThrows(BizException.class, () -> caseService.decide(c.getId(),
                new CaseService.DecisionReq("PUNISH", BigDecimal.ONE, null, null, null, "x", null, "r")));
        assertEquals(2006, e.code);
        caseService.report(c.getId(), "调查终结：集成测试", "it");
        caseService.notify(c.getId(), new CaseService.NoticeReq("拟罚", new BigDecimal("1000"), BigDecimal.ZERO));
        var e2 = assertThrows(BizException.class, () -> caseService.decide(c.getId(),
                new CaseService.DecisionReq("PUNISH", new BigDecimal("2000"), null, null, null, "x", null, "r")));
        assertEquals(2007, e2.code);
    }

    @Test
    void 审批单流转_延期申请批准后期限顺延() {
        CaseFile c = caseService.create(req("IT-审批" + System.nanoTime(), TWO), "banban");
        long id = approvalService.apply(new ApprovalService.ApplyReq(
                "EXTEND", null, c.getId(), Map.of("days", 10), "案情复杂"), "banban");
        var result = approvalService.decide(id, true, "同意", "juzhang");
        assertTrue((Boolean) result.get("approved"));
        CaseFile after = caseService.get(c.getId());
        assertEquals(c.getDeadlineAt().plusDays(10), after.getDeadlineAt());
        assertEquals(10, after.getExtensionDays());
    }

    @Test
    void 立案审批payload含LocalDate字段可正确执行() {
        // violationEndDate 走 JSON 存 payload 再反序列化为 LocalDate（须 JavaTime 模块，回归 ObjectMapper 注入修复）
        var cause = providerCause();
        String party = "IT-立案审批" + System.nanoTime();
        long id = approvalService.apply(new ApprovalService.ApplyReq("FILE_CASE", null, null,
                Map.of("causeId", cause.getId(), "procedureType", "NORMAL",
                        "partyName", party, "partyType", "PROVIDER",
                        "summary", "含时效日期的立案审批", "amountInvolved", 100,
                        "violationEndDate", java.time.LocalDate.now().minusMonths(6).toString(),
                        "healthHarm", false,
                        "officers", List.of(
                                Map.of("name", "王办案", "certNo", "YB001", "duty", "LEAD"),
                                Map.of("name", "张协办", "certNo", "YB002", "duty", "MEMBER"))),
                "立案审批表"), "banban");
        var result = approvalService.decide(id, true, "同意立案", "juzhang");
        assertTrue((Boolean) result.get("approved"));
    }

    @Test
    void 审批驳回不执行动作() {
        CaseFile c = caseService.create(req("IT-驳回" + System.nanoTime(), TWO), "banban");
        long id = approvalService.apply(new ApprovalService.ApplyReq(
                "SUSPEND", null, c.getId(), null, "待鉴定"), "banban");
        approvalService.decide(id, false, "理由不足", "juzhang");
        assertEquals("INVESTIGATING", caseService.get(c.getId()).getStatus());
    }
}
