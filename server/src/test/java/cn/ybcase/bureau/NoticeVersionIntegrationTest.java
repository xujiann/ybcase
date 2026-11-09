package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseNotice;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.ProcedureService;
import cn.ybcase.bureau.service.ReviewMaterialService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NoticeVersionIntegrationTest {
    private static final BigDecimal FINE = new BigDecimal("500000");
    @Autowired CaseService cases;
    @Autowired CaseCauseRepository causes;
    @Autowired ProcedureService procedures;
    @Autowired ReviewMaterialService reviewMaterials;
    @Autowired BureauConfig config;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @Test
    void receiptSetsDeadlinesForItsVersionAndInvalidatesTheEarlierReviewSnapshot() {
        long caseId = reportedCase();
        LocalDate today = LocalDate.now();
        CaseNotice notice = notice(caseId, null, today.minusDays(1));
        assertNull(notice.getStatementDeadline());
        assertFalse(check(caseId, "notice_delivery"));
        assertEquals(2006, assertThrows(BizException.class, () -> cases.recordStatement(caseId,
                response(notice, "申辩内容", today, null, null, null, null, null))).code);
        var before = reviewMaterials.capture(caseId);
        assertEquals(2041, assertThrows(BizException.class, () -> deliver(notice, today.plusDays(1), "未来回证")).code);
        deliver(notice, today, "真实回证");
        deliver(notice, today, "真实回证");
        assertEquals(1, jdbc.queryForObject("select count(*) from case_delivery where notice_id=?", Integer.class, notice.getId()));
        assertEquals(expectedDeadline(today, notice.getStatementPeriodDays()), notice.getStatementDeadline());
        assertEquals(expectedDeadline(today, notice.getHearingPeriodDays()), notice.getHearingRequestDeadline());
        assertTrue(check(caseId, "notice_delivery"));
        assertNotEquals(before.fingerprint(), reviewMaterials.capture(caseId).fingerprint());
        assertEquals(2041, assertThrows(BizException.class, () -> deliver(notice, today, "另一个回证")).code);
    }

    @Test
    void completedEarlierHearingCannotSatisfyAnUnchangedRequestOnNewNotice() {
        long caseId = reportedCase();
        LocalDate today = LocalDate.now();
        LocalDate receiptDate = today.minusDays(Math.max(0, config.intVal("hearing_notice_ahead_days", 7)) + 1L);
        CaseNotice first = notice(caseId, null, receiptDate);
        deliver(first, receiptDate, "原回证");
        cases.recordStatement(caseId, response(first, null, null, true, receiptDate, null, null, null));
        long firstHearing = schedule(first, receiptDate, today);
        procedures.holdHearing(caseId, firstHearing, "原告知版本听证笔录");
        assertTrue(check(caseId, "requested_hearing"));

        CaseNotice next = notice(caseId, first.getId(), receiptDate);
        assertTrue(next.getHearingRequested());
        assertNull(next.getHearingHeldAt());
        assertFalse(check(caseId, "requested_hearing"));
        assertEquals(2194, assertThrows(BizException.class, () -> deliver(first, receiptDate, "旧回证重放")).code);
        deliver(next, receiptDate, "新版本实际回证");
        assertFalse(check(caseId, "requested_hearing"));
        long nextHearing = schedule(next, receiptDate, today);
        procedures.holdHearing(caseId, nextHearing, "当前告知版本听证笔录");
        assertTrue(check(caseId, "requested_hearing"));
        assertEquals("原告知版本听证笔录", jdbc.queryForObject("select record from case_hearing where id=?", String.class, firstHearing));
    }

    @Test
    void trackedNoticeRejectsBlankStatementAndRequiresEvidenceForWaiverAndWithdrawal() {
        long caseId = reportedCase();
        LocalDate today = LocalDate.now();
        CaseNotice notice = notice(caseId, null, today);
        deliver(notice, today, "程序回证");
        assertEquals(2100, assertThrows(BizException.class, () -> cases.recordStatement(caseId,
                response(notice, "   ", today, null, null, null, null, null))).code);
        cases.recordStatement(caseId, response(notice, null, null, true, today, null, null, null));
        assertEquals(2100, assertThrows(BizException.class, () -> cases.recordStatement(caseId,
                response(notice, null, null, false, null, null, null, null))).code);
        assertTrue(notice.getHearingRequested());
        assertEquals(2100, assertThrows(BizException.class, () -> cases.recordStatement(caseId,
                response(notice, null, null, null, null, true, null, null))).code);
        cases.recordStatement(caseId, response(notice, null, null, false, null, true, "撤回申请原件", "书面放弃"));
        assertFalse(notice.getHearingRequested());
        assertTrue(notice.getStatementWaived());
        assertEquals(List.of("STATEMENT_WAIVER", "HEARING_WITHDRAWAL"), jdbc.queryForList(
                "select event_type from case_notice_procedure_event where notice_id=? order by id", String.class, notice.getId()));
    }

    private long reportedCase() {
        long cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId();
        var c = cases.create(new CaseService.CaseCreateReq(null, cause, "NORMAL", "NOTICE-" + System.nanoTime(),
                "PROVIDER", null, null, null, null, "告知版本测试", BigDecimal.TEN,
                List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"), new CaseService.OfficerReq("张协办", "YB002", "MEMBER")),
                null, null, null, null), "banban");
        cases.report(c.getId(), "调查终结报告", "banban");
        return c.getId();
    }

    private CaseNotice notice(long caseId, Long previous, LocalDate recordedAt) {
        CaseNotice notice = cases.notify(caseId, new CaseService.NoticeReq(
                "拟处罚告知", FINE, BigDecimal.ZERO, BigDecimal.ZERO, "已核对告知范围", true, previous));
        entityManager.flush();
        jdbc.update("update case_notice set notified_at=? where id=?", recordedAt, notice.getId());
        entityManager.refresh(notice);
        return notice;
    }

    private void deliver(CaseNotice notice, LocalDate date, String receiptNo) {
        cases.recordNoticeDelivery(notice.getCaseId(), notice.getId(), "DIRECT", date, "当事人", receiptNo, date, null);
    }

    private long schedule(CaseNotice notice, LocalDate sent, LocalDate planned) {
        procedures.scheduleHearing(notice.getCaseId(),
                new ProcedureService.HearingScheduleReq(null, sent, planned, "李法制", "法制科", "书记员", notice.getId()));
        return jdbc.queryForObject("select id from case_hearing where notice_id=?", Long.class, notice.getId());
    }

    private CaseService.StatementReq response(CaseNotice notice, String statement, LocalDate statementDate,
                                              Boolean hearingRequested, LocalDate requestedAt, Boolean waived,
                                              String withdrawalReference, String waiverReference) {
        return new CaseService.StatementReq(statement, null, hearingRequested, null, waived, notice.getId(),
                statementDate, requestedAt, withdrawalReference, waiverReference);
    }

    private boolean check(long caseId, String item) {
        var decision = new CaseService.DecisionReq("PUNISH", FINE, BigDecimal.ZERO, BigDecimal.ZERO,
                null, "依事实处罚", null, "一般情形");
        return cases.preflight(caseId, new CaseService.PreflightReq("DECIDE", decision, null)).items().stream()
                .filter(check -> check.key().equals(item)).findFirst().orElseThrow().passed();
    }

    private LocalDate expectedDeadline(LocalDate base, Integer days) {
        return days == null || days == 0 ? null : base.plusDays(days);
    }
}
