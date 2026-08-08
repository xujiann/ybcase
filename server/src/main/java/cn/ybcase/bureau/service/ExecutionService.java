package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.common.Workdays;
import cn.ybcase.bureau.entity.CaseDecision;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseDecisionRepository;
import cn.ybcase.bureau.repository.CaseFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** 执行域（自 CaseService 拆分）：罚没入账、加处罚款测算、暂缓分期、法院强执（第52-55条） */
@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final CaseFileRepository caseRepository;
    private final CaseDecisionRepository decisionRepository;
    private final BureauConfig config;
    private final JdbcTemplate jdbc;

    public record ExecutionReq(String kind, BigDecimal amount, LocalDate paidAt, String method, String note,
                               String receiptNo) {}

    @Transactional
    public void addExecution(Long caseId, ExecutionReq req) {
        CaseFile c = CaseGuards.get(caseRepository, caseId);
        if (!List.of("DECIDED", "DELIVERED").contains(c.getStatus()))
            throw new BizException(2042, "仅已决定/已送达的案件可登记执行");
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2042, "案件无处理决定"));
        if (!List.of("FINE", "RECOUP", "CONFISCATE", "LATE_FEE").contains(req.kind()))
            throw new BizException(2042, "执行类型须为 罚款/退回基金/没收违法所得/加处罚款");
        BigDecimal amount = req.amount() == null ? BigDecimal.ZERO : req.amount();
        if (amount.signum() <= 0) throw new BizException(2042, "执行金额须为正数");
        // 第52条：当场收缴限额（国家100/辽宁20，参数化）
        BigDecimal onsiteLimit = config.decimal("onsite_collect_limit", "100");
        if ("ONSITE".equals(req.method()) && amount.compareTo(onsiteLimit) > 0)
            throw new BizException(2010, "当场收缴罚款限 " + onsiteLimit + " 元以下（第52条）");
        // 第52条：当场收缴必须出具财政部门统一制发的专用票据
        if ("ONSITE".equals(req.method()) && (req.receiptNo() == null || req.receiptNo().isBlank()))
            throw new BizException(2010, "当场收缴必须出具财政统一票据并登记票据号（第52条）");
        // 第55条：加处罚款不得超出罚款数额
        if ("LATE_FEE".equals(req.kind())) {
            BigDecimal paidLateFee = sum(caseId, "LATE_FEE");
            if (paidLateFee.add(amount).compareTo(d.getFineAmount()) > 0)
                throw new BizException(2012, "加处罚款累计不得超出罚款数额（第55条）");
        }
        jdbc.update("insert into case_execution (case_id, kind, amount, paid_at, method, note, receipt_no) values (?,?,?,?,?,?,?)",
                caseId, req.kind(), amount, req.paidAt() != null ? req.paidAt() : LocalDate.now(),
                req.method(), req.note(), req.receiptNo());
    }

    /** 加处罚款测算（第55条：每日3%，不超过罚款数额；基数按参数 FULL/UNPAID） */
    public Map<String, Object> lateFeeQuote(Long caseId) {
        CaseFile c = CaseGuards.get(caseRepository, caseId);
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2042, "案件无处理决定"));
        if (c.getDeliveredAt() == null) throw new BizException(2042, "决定书尚未送达");
        LocalDate payDeadline = c.getDeliveredAt().plusDays(15);
        long overdueDays = Math.max(0, ChronoUnit.DAYS.between(payDeadline, LocalDate.now()));
        BigDecimal base = d.getFineAmount();
        if ("UNPAID".equalsIgnoreCase(config.str("late_fee_base", "FULL"))) {
            base = base.subtract(sum(caseId, "FINE")).max(BigDecimal.ZERO);
        }
        BigDecimal capped = Workdays.lateFee(base, overdueDays, d.getFineAmount());
        BigDecimal accrued = base.multiply(new BigDecimal("0.03")).multiply(BigDecimal.valueOf(overdueDays));
        return Map.of("payDeadline", payDeadline, "overdueDays", overdueDays, "base", base,
                "accrued", accrued, "capped", capped, "fineAmount", d.getFineAmount());
    }

    /** 暂缓/分期缴纳批准（第54条） */
    @Transactional
    public CaseFile approveDefer(Long caseId) {
        CaseFile c = CaseGuards.get(caseRepository, caseId);
        if (!List.of("DECIDED", "DELIVERED").contains(c.getStatus()))
            throw new BizException(2042, "仅已决定/已送达的案件可批准暂缓分期");
        c.setDeferApproved(true);
        return caseRepository.save(c);
    }

    /** 申请法院强制执行（第55条） */
    @Transactional
    public CaseFile applyCourtEnforce(Long caseId) {
        CaseFile c = CaseGuards.get(caseRepository, caseId);
        if (!"DELIVERED".equals(c.getStatus())) throw new BizException(2042, "决定书送达且当事人逾期不履行方可申请强制执行");
        c.setCourtEnforceApplied(true);
        return caseRepository.save(c);
    }

    /** 按类型累计执行金额（结案条件校验共用） */
    public BigDecimal sum(Long caseId, String kind) {
        BigDecimal v = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from case_execution where case_id = ? and kind = ?",
                BigDecimal.class, caseId, kind);
        return v == null ? BigDecimal.ZERO : v;
    }
}
