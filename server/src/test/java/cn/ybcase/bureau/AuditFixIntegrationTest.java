package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.entity.CaseDecision;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Audit regressions run on the independent test database; never reset development data. */
@SpringBootTest
@ActiveProfiles("test")
class AuditFixIntegrationTest {
    @org.junit.jupiter.api.BeforeEach void authenticateControllerCalls() {
        authenticate("banban", "HANDLER");
    }
    @org.junit.jupiter.api.AfterEach void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    @Autowired CaseService cases;
    @Autowired CaseCauseRepository causes;
    @Autowired OversightService oversight;
    @Autowired ExecutionService execution;
    @Autowired ProcedureService procedure;
    @Autowired DocumentService documents;
    @Autowired ClueService clues;
    @Autowired BureauConfig config;
    @Autowired JdbcTemplate jdbc;
    @Autowired cn.ybcase.bureau.web.FormalizationController formalization;

    static void authenticate(String username, String role) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(username, "unused",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role))));
    }
    /** Fixture setup uses real stored accounts; each operation restores the ordinary handler for assertions. */
    <T> T asUser(String username, String role, Supplier<T> action) {
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        authenticate(username, role);
        try {return action.get();}
        finally {
            if (previous == null) org.springframework.security.core.context.SecurityContextHolder.clearContext();
            else org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }
    CaseDecision decide(Long id, CaseService.DecisionReq req) {
        return asUser("admin", "ADMIN", () -> cases.decide(id, req));
    }
    CaseFile close(Long id, String report, String maker) {
        return asUser("admin", "ADMIN", () -> cases.close(id, report, maker));
    }
    CaseFile terminate(Long id, String reason) {
        return asUser("admin", "ADMIN", () -> cases.terminate(id, reason));
    }
    CaseFile approveDefer(Long id) {
        return asUser("admin", "ADMIN", () -> execution.approveDefer(id));
    }
    CaseFile registerRelief(Long id, ExecutionService.ReliefReq req, String operator) {
        return asUser("fazhi", "LEGAL", () -> execution.registerRelief(id, req, operator));
    }
    CaseFile courtResult(Long id, ExecutionService.CourtResultReq req) {
        return asUser("admin", "ADMIN", () -> execution.courtResult(id, req));
    }

    CaseFile fresh() {
        long cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId();
        return asUser("admin", "ADMIN", () -> cases.create(new CaseService.CaseCreateReq(null, cause, "NORMAL", "AUDIT-" + System.nanoTime(),
            "PROVIDER", null, null, null, null, "反例测试", BigDecimal.TEN,
            List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
                    new CaseService.OfficerReq("张协办", "YB002", "MEMBER")), null, null, null, null), "banban"));
    }
    CaseFile noticed(BigDecimal fine, BigDecimal recoup) {
        CaseFile c = fresh();
        cases.report(c.getId(), "调查终结", "banban");
        cases.notify(c.getId(), new CaseService.NoticeReq("拟处理", fine, recoup, BigDecimal.ZERO, null));
        cases.recordStatement(c.getId(), new CaseService.StatementReq(null, null, null, null, true));
        return c;
    }
    CaseFile delivered() {
        CaseFile c = noticed(new BigDecimal("100"), BigDecimal.ZERO);
        decide(c.getId(), new CaseService.DecisionReq("PUNISH", new BigDecimal("100"), BigDecimal.ZERO,
            BigDecimal.ZERO, null, "罚款100元", null, "一般情形"));
        cases.deliver(c.getId(), new CaseService.DeliveryReq("DIRECT", LocalDate.now(), "当事人", null, "TEST-RECEIPT", null));
        return cases.get(c.getId());
    }
    void rejected(int code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BizException.class, action).code);
    }

    @Test void reviewerMustUseActualQualifiedAccount() {
        CaseFile c = noticed(new BigDecimal("100"), BigDecimal.ZERO);
        var review = cases.submitReview(c.getId(), "法制审核");
        rejected(2070, () -> asUser("fazhi", "LEGAL", () -> cases.doReview(review.getId(), "李法制", "AGREE", "伪造姓名")));
        rejected(2038, () -> cases.doReview(review.getId(), "banban", "AGREE", "自己审核自己"));
        var result = asUser("fazhi", "LEGAL", () -> cases.doReview(review.getId(), "fazhi", "AGREE", "合格账号审核"));
        assertEquals("李法制", result.getReviewer());
        assertEquals("fazhi", jdbc.queryForObject("select operator from case_review where id = ?", String.class, result.getId()));
    }

    @Test void statementCannotInventHearingEvenInPast() {
        CaseFile c = noticed(new BigDecimal("150000"), BigDecimal.ZERO);
        for (LocalDate date : List.of(LocalDate.now().minusDays(1), LocalDate.of(2099, 1, 1))) {
            assertThrows(BizException.class, () -> cases.recordStatement(c.getId(),
                new CaseService.StatementReq(null, null, true, date, true)));
        }
        assertNull(jdbc.queryForObject("select hearing_held_at from case_notice where case_id = ? order by id desc limit 1", java.sql.Date.class, c.getId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from case_hearing where case_id = ?", Integer.class, c.getId()));
    }

    @Test void onlyActualHearingWithRecordReleasesHearingGuard() {
        CaseFile c = noticed(new BigDecimal("150000"), BigDecimal.ZERO);
        cases.recordStatement(c.getId(), new CaseService.StatementReq(null, null, true, null, true));
        procedure.scheduleHearing(c.getId(), new ProcedureService.HearingScheduleReq(LocalDate.now().minusDays(10),
            LocalDate.now().minusDays(8), LocalDate.now(), "李法制", "法制科", "书记员"));
        Long id = jdbc.queryForObject("select id from case_hearing where case_id = ?", Long.class, c.getId());
        rejected(2057, () -> procedure.holdHearing(c.getId(), id, ""));
        assertNull(jdbc.queryForObject("select held_at from case_hearing where id = ?", java.sql.Date.class, id));
        procedure.holdHearing(c.getId(), id, "真实举行并形成笔录");
        assertEquals(LocalDate.now(), jdbc.queryForObject("select held_at from case_hearing where id = ?", java.sql.Date.class, id).toLocalDate());
        assertEquals(LocalDate.now(), jdbc.queryForObject("select hearing_held_at from case_notice where case_id = ? order by id desc limit 1", java.sql.Date.class, c.getId()).toLocalDate());
    }

    @Test void reminderCalendarCoversWeekdayWeekendHolidayAndShiftWork() {
        LocalDate monday = LocalDate.of(2098, 3, 3);
        while (monday.getDayOfWeek() != java.time.DayOfWeek.MONDAY) monday = monday.plusDays(1);
        LocalDate holiday = monday.plusDays(1), saturday = monday.plusDays(5), shift = monday.plusDays(6);
        try {
            jdbc.update("insert into sys_holiday(day,kind,name) values (?, 'HOLIDAY', 'audit'), (?, 'SHIFT_WORK', 'audit')", holiday, shift);
            config.evictHolidays();
            assertTrue(config.isWorkingDay(monday));
            assertFalse(config.isWorkingDay(saturday));
            assertFalse(config.isWorkingDay(holiday));
            assertTrue(config.isWorkingDay(shift));
        } finally {
            jdbc.update("delete from sys_holiday where day in (?, ?)", holiday, shift); config.evictHolidays();
        }
    }

    @Test void negativeAndFractionalMoneyNeverReachDatabase() {
        CaseFile c = noticed(new BigDecimal("100"), BigDecimal.ZERO);
        for (String amount : List.of("-100", "0.001", "1000000000000")) {
            rejected(2140, () -> decide(c.getId(), new CaseService.DecisionReq("PUNISH", new BigDecimal(amount),
                BigDecimal.ZERO, BigDecimal.ZERO, null, "金额反例", null, "一般情形")));
        }
        rejected(2140, () -> decide(c.getId(), new CaseService.DecisionReq("NO_PUNISH", BigDecimal.ONE,
            BigDecimal.ZERO, BigDecimal.ZERO, null, "非处罚带罚款", null, "一般情形")));
        assertEquals(0, jdbc.queryForObject("select count(*) from case_decision where case_id = ?", Integer.class, c.getId()));
        CaseFile d = delivered();
        rejected(2140, () -> execution.addExecution(d.getId(), new ExecutionService.ExecutionReq("FINE",
            new BigDecimal("0.001"), LocalDate.now(), "BANK", null, null)));
        execution.addExecution(d.getId(), new ExecutionService.ExecutionReq("FINE", new BigDecimal("0.01"), LocalDate.now(), "BANK", null, null));
        assertEquals(0, new BigDecimal("0.01").compareTo(execution.sum(d.getId(), "FINE")));
    }

    @Test void concurrentInstallmentsCannotOverbookDecision() throws Exception {
        CaseFile c = delivered(); approveDefer(c.getId());
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> outcomes = new java.util.ArrayList<>();
            for (int seq = 1; seq <= 2; seq++) {
                int number = seq;
                outcomes.add(pool.submit(() -> {
                    authenticate("banban", "HANDLER");
                    try {
                        ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                        try {
                            oversight.addInstallment(c.getId(), new OversightService.InstallmentReq(number,
                                LocalDate.now().plusDays(number * 30), new BigDecimal("60"), "FINE")); return true;
                        } catch (BizException e) { assertEquals(2065, e.code); return false; }
                    } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            int successes = 0;
            for (var f : outcomes) if (f.get(20, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(0, new BigDecimal("60").compareTo(jdbc.queryForObject(
                "select sum(amount) from case_installment where case_id = ?", BigDecimal.class, c.getId())));
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void directPaymentConsumesRemainingInstallmentCapacity() {
        CaseFile c = delivered(); approveDefer(c.getId());
        execution.addExecution(c.getId(), new ExecutionService.ExecutionReq("FINE", new BigDecimal("40"), LocalDate.now(), "BANK", null, null));
        rejected(2065, () -> oversight.addInstallment(c.getId(), new OversightService.InstallmentReq(1, LocalDate.now().plusDays(30), new BigDecimal("61"), "FINE")));
        oversight.addInstallment(c.getId(), new OversightService.InstallmentReq(1, LocalDate.now().plusDays(30), new BigDecimal("60"), "FINE"));
    }

    @Test void bothClosedAndArchivedTerminatedCasesAreSealed() {
        CaseFile closed = delivered();
        execution.addExecution(closed.getId(), new ExecutionService.ExecutionReq("FINE", new BigDecimal("100"), LocalDate.now(), "BANK", null, null));
        close(closed.getId(), "执行完毕归档", "admin");
        CaseFile terminated = fresh(); terminate(terminated.getId(), "违法事实不能成立");
        close(terminated.getId(), "终止归档", "admin");
        for (CaseFile c : List.of(closed, terminated)) {
            int before = jdbc.queryForObject("select count(*) from case_document where case_id = ?", Integer.class, c.getId());
            rejected(2031, () -> procedure.eDeliveryConsent(c.getId(), "法人", "123", "回执"));
            rejected(2031, () -> oversight.startExpertReview(c.getId(), "专家", LocalDate.now()));
            rejected(2031, () -> oversight.addInstallment(c.getId(), new OversightService.InstallmentReq(1, LocalDate.now().plusDays(1), BigDecimal.ONE, "FINE")));
            rejected(2031, () -> cases.addDocument(c.getId(), new CaseService.DocumentReq("OTHER", "补写", "补写", LocalDate.now(), "admin", false, null, null)));
            assertEquals(before, jdbc.queryForObject("select count(*) from case_document where case_id = ?", Integer.class, c.getId()));
        }
    }

    @Test void appliedCourtCaseCannotCloseUntilReceiptAccepted() {
        CaseFile c = delivered();
        rejected(2142, () -> execution.applyCourtEnforce(c.getId()));
        registerRelief(c.getId(), new ExecutionService.ReliefReq(LocalDate.now().plusDays(180), false, "救济期限", null), "fazhi");
        rejected(2142, () -> execution.applyCourtEnforce(c.getId()));
        // Backdate only historical dates; use real application, delivery and receipt APIs.
        jdbc.update("update case_file set delivered_at = current_date - 50 where id = ?", c.getId());
        registerRelief(c.getId(), new ExecutionService.ReliefReq(LocalDate.now().minusDays(20), false, "已届满救济期限", null), "fazhi");
        cases.addDocument(c.getId(), new CaseService.DocumentReq("URGE_LETTER", "催告书", "限期履行",
            LocalDate.now().minusDays(15), "admin", false, null, null));
        rejected(2042, () -> execution.applyCourtEnforce(c.getId()));
        Long doc = jdbc.queryForObject("select id from case_document where case_id = ? and doc_type = 'URGE_LETTER'", Long.class, c.getId());
        formalization.deliverDocument(c.getId(), Map.of("documentId", doc, "method", "DIRECT", "receiver", "法人", "deliveredAt", LocalDate.now().minusDays(5).toString()));
        rejected(2042, () -> execution.applyCourtEnforce(c.getId()));
        jdbc.update("update case_delivery set delivered_at = current_date - 11 where document_id = ?", doc);
        assertEquals("APPLIED", execution.applyCourtEnforce(c.getId()).getCourtEnforceStatus());
        rejected(2011, () -> close(c.getId(), "仅申请不能结案", "admin"));
        courtResult(c.getId(), new ExecutionService.CourtResultReq("ACCEPTED", "法院回执-" + c.getId(), LocalDate.now(), "受理"));
        assertEquals("COURT", close(c.getId(), "法院受理归档", "admin").getCloseReason());
    }

    @Test void pendingReliefFutureInstallmentAndFullPaymentBlockCourtApplication() {
        CaseFile c = delivered();
        jdbc.update("update case_file set delivered_at = current_date - 50 where id = ?", c.getId());
        registerRelief(c.getId(), new ExecutionService.ReliefReq(LocalDate.now().minusDays(20), true, "诉讼未结束", null), "fazhi");
        rejected(2142, () -> execution.applyCourtEnforce(c.getId()));
        registerRelief(c.getId(), new ExecutionService.ReliefReq(LocalDate.now().minusDays(20), false, "诉讼结束", null), "fazhi");
        approveDefer(c.getId());
        oversight.addInstallment(c.getId(), new OversightService.InstallmentReq(1, LocalDate.now().plusDays(30), new BigDecimal("100"), "FINE"));
        rejected(2142, () -> execution.applyCourtEnforce(c.getId()));
        execution.addExecution(c.getId(), new ExecutionService.ExecutionReq("FINE", new BigDecimal("100"), LocalDate.now(), "BANK", null, null));
        rejected(2142, () -> execution.applyCourtEnforce(c.getId()));
    }

    @Test void ongoingExclusionExtendsVisibleEffectiveDeadline() {
        CaseFile c = fresh(); jdbc.update("update case_file set filed_at = filed_at - 30 where id = ?", c.getId());
        cases.addExclusion(c.getId(), new CaseService.ExclusionReq("APPRAISE", LocalDate.now().minusDays(10), null, "鉴定中"));
        assertEquals(c.getDeadlineAt().plusDays(10).toString(), String.valueOf(cases.detail(c.getId()).get("effectiveDeadline")));
    }

    @Test void invalidSecondImportRowRollsBackFirstAndValidBatchImportsBoth() {
        String marker = "IMPORT-" + System.nanoTime();
        assertThrows(BizException.class, () -> clues.importMonitor(List.of(Map.of("content", marker, "suspectName", "医院"), Map.of("content", "", "suspectName", "医院")), "admin"));
        assertEquals(0, jdbc.queryForObject("select count(*) from case_clue where content = ?", Integer.class, marker));
        clues.importMonitor(List.of(Map.of("content", marker, "suspectName", "医院"), Map.of("content", marker + "-2", "suspectName", "医院")), "admin");
        assertEquals(2, jdbc.queryForObject("select count(*) from case_clue where content like ?", Integer.class, marker + "%"));
    }

    @Test void handlerMustApplyAndLeaderActuallyApprovesClueExtension() {
        var c = clues.create(new ClueService.ClueCreateReq("COMPLAINT", "延期核查", "医院", "PROVIDER", LocalDate.now(), "王办案"), "banban");
        rejected(403, () -> clues.extend(c.getId(), "文字不能替代批准", "banban"));
        assertFalse(Boolean.TRUE.equals(clues.get(c.getId()).getExtended()));
        var approved = clues.extend(c.getId(), "特殊情况批准", "juzhang");
        assertTrue(approved.getExtended());
        assertEquals("juzhang", jdbc.queryForObject("select extend_approved_by from case_clue where id = ?", String.class, c.getId()));
        rejected(2022, () -> clues.extend(c.getId(), "重复延期", "juzhang"));
    }

    @Test void recoupThresholdRequiresReviewAndArchiveUsesSameRule() {
        CaseFile c = noticed(BigDecimal.ZERO, new BigDecimal("200000"));
        assertFalse(documents.legalReviewRequired(c.getId(), BigDecimal.ZERO, new BigDecimal("99999.99"), BigDecimal.ZERO));
        assertTrue(documents.legalReviewRequired(c.getId(), BigDecimal.ZERO, new BigDecimal("100000"), BigDecimal.ZERO));
        assertTrue(documents.legalReviewRequired(c.getId(), BigDecimal.ZERO, new BigDecimal("200000"), BigDecimal.ZERO));
        rejected(2005, () -> decide(c.getId(), new CaseService.DecisionReq("PUNISH", BigDecimal.ZERO,
            new BigDecimal("200000"), BigDecimal.ZERO, null, "追回基金", null, "一般情形")));
        var review = cases.submitReview(c.getId(), "较大数额追回基金");
        asUser("fazhi", "LEGAL", () -> cases.doReview(review.getId(), "fazhi", "AGREE", "追回依据合法"));
        assertEquals(0, new BigDecimal("200000").compareTo(decide(c.getId(), new CaseService.DecisionReq("PUNISH", BigDecimal.ZERO,
            new BigDecimal("200000"), BigDecimal.ZERO, null, "追回基金", null, "一般情形")).getRecoupAmount()));
    }
}
