package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.CaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class DeliveryAndReviewBoundaryIntegrationTest {
    private static final AtomicReference<String> MODE = new AtomicReference<>("SIMULATION");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("ybcase.runtime.mode", MODE::get);
    }
    @Autowired CaseService cases;
    @Autowired CaseCauseRepository causes;
    @Autowired JdbcTemplate jdbc;

    @AfterEach void restoreMode() { MODE.set("SIMULATION"); }

    private CaseFile fresh(String procedure) {
        long cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId();
        return cases.create(new CaseService.CaseCreateReq(null, cause, procedure,
                "DELIVERY-REVIEW-" + System.nanoTime(), "PROVIDER", null, null, null, null,
                "送达和重复送审边界", BigDecimal.TEN,
                List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
                        new CaseService.OfficerReq("张协办", "YB002", "MEMBER")), null, null, null, null), "banban");
    }
    private CaseFile decided() {
        var c = fresh("SUMMARY");
        cases.decide(c.getId(), new CaseService.DecisionReq("PUNISH", BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO, null, "处罚决定", null, "一般情形"));
        return c;
    }
    private void rejected(long id, CaseService.DeliveryReq request, int code) {
        assertEquals(code, assertThrows(BizException.class, () -> cases.deliver(id, request)).code);
        assertEquals("DECIDED", cases.get(id).getStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from case_delivery where case_id=?", Integer.class, id));
    }

    @Test void missingDeliveryFactsNeverCreateAReceiptOrAdvanceTheCase() {
        var c = decided();
        for (String missing : Arrays.asList(null, "", "  ")) {
            rejected(c.getId(), new CaseService.DeliveryReq("DIRECT", LocalDate.now(), missing, null, "R-1", null), 2041);
            rejected(c.getId(), new CaseService.DeliveryReq("DIRECT", LocalDate.now(), "当事人", null, missing, null), 2041);
        }
        rejected(c.getId(), new CaseService.DeliveryReq("DIRECT", null, "当事人", null, "R-1", null), 2041);
        rejected(c.getId(), new CaseService.DeliveryReq(null, LocalDate.now(), "当事人", null, "R-1", null), 2041);
    }

    @Test void actualDeliveryDatesMustFollowTheDecisionAndCannotBeFuture() {
        var c = decided();
        for (LocalDate day : List.of(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1))) {
            rejected(c.getId(), new CaseService.DeliveryReq("DIRECT", day, "当事人", null, "R-1", null), 2041);
            rejected(c.getId(), new CaseService.DeliveryReq("MAIL", LocalDate.now(), "当事人", null, "R-1", day), 2041);
            rejected(c.getId(), new CaseService.DeliveryReq("ANNOUNCE", day, "当事人", null, "A-1", null), 2041);
        }
    }

    @Test void actualSignatureAndAnnouncementKeepTheirExistingDateSemantics() {
        var signed = decided();
        assertEquals(LocalDate.now(), cases.deliver(signed.getId(), new CaseService.DeliveryReq(
                "MAIL", null, "当事人", null, "R-SIGNED", LocalDate.now())).getDeliveredAt());
        var announced = decided();
        assertTrue(cases.deliver(announced.getId(), new CaseService.DeliveryReq(
                "ANNOUNCE", LocalDate.now(), "当事人", null, "A-VALID", null)).getDeliveredAt().isAfter(LocalDate.now()));
    }

    @Test void formalElectronicDeliveryCannotUseTheManualReceiptShortcut() {
        var c = decided();
        jdbc.update("update case_file set e_delivery_consent=true where id=?", c.getId());
        MODE.set("FORMAL");
        rejected(c.getId(), new CaseService.DeliveryReq("ELECTRONIC", LocalDate.now(), "当事人", null, "E-1", null), 2193);
    }

    @Test void repeatedPendingReviewReturnsTheOriginalWithoutResettingItsDeadline() {
        var c = fresh("NORMAL");
        cases.report(c.getId(), "调查终结报告", "banban");
        var first = cases.submitReview(c.getId(), "疑难复杂案件");
        jdbc.update("update case_review set submitted_at=current_date-2,deadline_at=current_date+3 where id=?", first.getId());
        var repeated = cases.submitReview(c.getId(), "疑难复杂案件");
        assertEquals(first.getId(), repeated.getId());
        assertEquals(LocalDate.now().minusDays(2), repeated.getSubmittedAt());
        assertEquals(LocalDate.now().plusDays(3), repeated.getDeadlineAt());
        assertEquals(1, jdbc.queryForObject("select count(*) from case_review where case_id=?", Integer.class, c.getId()));
        cases.doReview(first.getId(), "fazhi", "AGREE", "核对当前材料通过");
    }

    @Test void concurrentIdenticalReviewsCreateOnePendingRecord() throws Exception {
        var c = fresh("NORMAL");
        cases.report(c.getId(), "并发调查终结报告", "banban");
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { gate.await(); return cases.submitReview(c.getId(), "并发送审").getId(); });
            var second = pool.submit(() -> { gate.await(); return cases.submitReview(c.getId(), "并发送审").getId(); });
            gate.countDown();
            assertEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("select count(*) from case_review where case_id=?", Integer.class, c.getId()));
        } finally { pool.shutdownNow(); }
    }

    @Test void changedReviewReasonIsPreservedInANewApplication() {
        var c = fresh("NORMAL");
        cases.report(c.getId(), "调查终结报告", "banban");
        var first = cases.submitReview(c.getId(), "疑难复杂案件");
        var replacement = cases.submitReview(c.getId(), "另经核实涉及重大公共利益");
        assertNotEquals(first.getId(), replacement.getId());
        assertEquals("疑难复杂案件", jdbc.queryForObject("select required_reason from case_review where id=?", String.class, first.getId()));
        assertEquals("另经核实涉及重大公共利益", replacement.getRequiredReason());
        assertEquals(replacement.getId(), cases.submitReview(c.getId(), "另经核实涉及重大公共利益").getId());
    }
}
