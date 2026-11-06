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
    private final ExecutionDeadlines deadlines;
    private final MaterialAccess materialAccess;

    public record ExecutionReq(String kind, BigDecimal amount, LocalDate paidAt, String method, String note,
                               String receiptNo) {}

    @Transactional
    public void addExecution(Long caseId, ExecutionReq req) {
        // 按案件加行锁，把同一案件的入账串行化。
        // 仅把封顶谓词写进 insert 的 where 是不够的：READ COMMITTED 下并发的两个事务
        // 各自读到的都是插入前的 sum，两笔都能过检，落库后累计超出决定额，
        // 进而被 fullyExecuted 判为"执行完毕"。
        CaseGuards.lock(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        CaseFile c = CaseGuards.get(caseRepository, caseId);
        if (!List.of("DECIDED", "DELIVERED").contains(c.getStatus()))
            throw new BizException(2042, "仅已决定/已送达的案件可登记执行");
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2042, "案件无处理决定"));
        if (req.kind() == null || !List.of("FINE", "RECOUP", "CONFISCATE", "LATE_FEE").contains(req.kind()))
            throw new BizException(2042, "执行类型须为 罚款/退回基金/没收违法所得/加处罚款");
        BigDecimal amount = cn.ybcase.bureau.common.Money.positive(req.amount(), "执行金额");
        LocalDate paidAt = req.paidAt() == null ? LocalDate.now() : req.paidAt();
        if (paidAt.isAfter(LocalDate.now())) throw new BizException(2042, "实际缴款日期不得晚于今天");
        if (d.getDecidedAt() != null && paidAt.isBefore(d.getDecidedAt()))
            throw new BizException(2042, "缴款日期不得早于处理决定日期（" + d.getDecidedAt() + "）");
        if (req.method() == null || !List.of("BANK", "ONSITE", "INSTALLMENT").contains(req.method()))
            throw new BizException(2042, "缴款方式须为 银行缴付/当场收缴/分期缴纳");
        // 票据号空白归一为 null：前端表单默认发 receiptNo:""，而 uq_execution_receipt 只排除 null，
        // 空串会被当成有效票据号，全库第二条不填票据的执行记录就会撞唯一索引而入不了账
        String receiptNo = req.receiptNo() == null || req.receiptNo().isBlank() ? null : req.receiptNo().trim();
        if (amount.signum() <= 0) throw new BizException(2042, "执行金额须为正数");
        // 第52条：当场收缴限额（国家100/辽宁20，参数化）
        BigDecimal onsiteLimit = config.decimal("onsite_collect_limit", "100");
        if ("ONSITE".equals(req.method()) && amount.compareTo(onsiteLimit) > 0)
            throw new BizException(2010, "当场收缴罚款限 " + onsiteLimit + " 元以下（第52条）");
        // 第52条：当场收缴必须出具财政部门统一制发的专用票据
        if ("ONSITE".equals(req.method()) && (req.receiptNo() == null || req.receiptNo().isBlank()))
            throw new BizException(2010, "当场收缴必须出具财政统一票据并登记票据号（第52条）");
        // 累计不得超出决定书金额：此前只校验单笔为正，重复录入或多打一个零都会让
        // sum(amount) 超过决定额，close() 的 fullyExecuted 判定"执行完毕"而实际未足额缴纳
        if (List.of("FINE", "RECOUP", "CONFISCATE").contains(req.kind())) {
            BigDecimal decided = switch (req.kind()) {
                case "FINE" -> d.getFineAmount();
                case "RECOUP" -> d.getRecoupAmount();
                default -> d.getConfiscateAmount();
            };
            decided = decided == null ? BigDecimal.ZERO : decided;
            if (decided.signum() <= 0)
                throw new BizException(2042, "决定书未确定该类款项金额，不能登记此类执行");
            BigDecimal already = sum(caseId, req.kind());
            if (already.add(amount).compareTo(decided) > 0)
                throw new BizException(2042, "累计登记金额（" + already.add(amount)
                        + "）超出决定书确定的金额（" + decided + "），请核对是否重复登记或金额录入有误");
            // 上面的先查后插在并发下两笔都能过检（双击提交、超时重试），落库后累计超出决定额，
            // 进而被 fullyExecuted 判为"执行完毕"。与 LATE_FEE 分支一样把封顶写进 insert 的 where，
            // 由数据库一次性判定；上面的预检保留，只为给出带具体金额的友好提示
            int ok = jdbc.update("""
                    insert into case_execution (case_id, kind, amount, paid_at, method, note, receipt_no)
                    select ?,?,?,?,?,?,?
                    where (select coalesce(sum(amount), 0) from case_execution
                           where case_id = ? and kind = ?) + ? <= ?""",
                    caseId, req.kind(), amount, paidAt,
                    req.method(), req.note(), receiptNo,
                    caseId, req.kind(), amount, decided);
            if (ok == 0) throw new BizException(2042,
                    "累计登记金额超出决定书确定的金额（" + decided + "），请刷新后核对是否已被重复登记");
            return;
        }
        // 第55条：加处罚款不得超出罚款数额
        if ("LATE_FEE".equals(req.kind())) {
            // Retain the principal-cap error before timing checks, but do not treat that cap as an accrued entitlement.
            if (sum(caseId, "LATE_FEE").add(amount).compareTo(d.getFineAmount()) > 0)
                throw new BizException(2012, "加处罚款累计不得超出罚款数额（第55条）");
            if (!"PUNISH".equals(d.getDecisionType()) || !"DELIVERED".equals(c.getStatus())
                    || c.getDeliveredAt() == null || c.getDeliveredAt().isAfter(paidAt))
                throw new BizException(2042, "处罚决定书有效送达后方可登记加处罚款");
            Map<String, Object> quote = lateFeeQuoteAsOf(c, d, paidAt);
            LocalDate paymentDeadline = (LocalDate) quote.get("payDeadline");
            if (!paidAt.isAfter(paymentDeadline))
                throw new BizException(2042, "缴款期限（" + paymentDeadline + "）届满后方可登记加处罚款");
            BigDecimal remaining = ((BigDecimal) quote.get("capped")).subtract(sum(caseId, "LATE_FEE")).max(BigDecimal.ZERO);
            if (amount.compareTo(remaining) > 0)
                throw new BizException(2012, "登记金额超出截至缴款日按当前配置测算的剩余加处罚款（" + remaining + "元）");
            // 先 sum 再 insert 在并发下两笔都能过检、落库后累计超本金；
            // 改为把封顶写进 insert 的 where，由数据库一次性判定
            int n = jdbc.update("""
                    insert into case_execution (case_id, kind, amount, paid_at, method, note, receipt_no)
                    select ?,?,?,?,?,?,?
                    where (select coalesce(sum(amount), 0) from case_execution
                           where case_id = ? and kind = 'LATE_FEE') + ? <= ?""",
                    caseId, req.kind(), amount, paidAt,
                    req.method(), req.note(), receiptNo,
                    caseId, amount, d.getFineAmount());
            if (n == 0) throw new BizException(2012, "加处罚款累计不得超出罚款数额（第55条）");
            return;
        }
        jdbc.update("insert into case_execution (case_id, kind, amount, paid_at, method, note, receipt_no) values (?,?,?,?,?,?,?)",
                caseId, req.kind(), amount, paidAt,
                req.method(), req.note(), receiptNo);
    }

    /** 加处罚款测算（第55条：每日3%，不超过罚款数额；基数按参数 FULL/UNPAID） */
    public Map<String, Object> lateFeeQuote(Long caseId) {
        CaseFile c = CaseGuards.get(caseRepository, caseId);
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2042, "案件无处理决定"));
        return lateFeeQuoteAsOf(c, d, LocalDate.now());
    }

    /** Existing FULL/UNPAID configuration formula evaluated at the recorded payment date; not a daily historical recalculation. */
    private Map<String, Object> lateFeeQuoteAsOf(CaseFile c, CaseDecision d, LocalDate asOf) {
        if (c.getDeliveredAt() == null) throw new BizException(2042, "决定书尚未送达");
        LocalDate payDeadline = deadlines.paymentDeadline(c);
        if (payDeadline == null) throw new BizException(2042, "暂缓或分期缴款期限尚未明确，请先登记已批准的期限或分期计划");
        long overdueDays = Math.max(0, ChronoUnit.DAYS.between(payDeadline, asOf));
        BigDecimal base = d.getFineAmount();
        if ("UNPAID".equalsIgnoreCase(config.str("late_fee_base", "FULL"))) {
            BigDecimal paid = jdbc.queryForObject("select coalesce(sum(amount),0) from case_execution where case_id=? and kind='FINE' and paid_at<=?",
                    BigDecimal.class, c.getId(), asOf);
            base = base.subtract(paid == null ? BigDecimal.ZERO : paid).max(BigDecimal.ZERO);
        }
        BigDecimal capped = Workdays.lateFee(base, overdueDays, d.getFineAmount());
        BigDecimal accrued = base.multiply(new BigDecimal("0.03")).multiply(BigDecimal.valueOf(overdueDays));
        return Map.of("payDeadline", payDeadline, "overdueDays", overdueDays, "base", base,
                "accrued", accrued, "capped", capped, "fineAmount", d.getFineAmount());
    }

    /** 暂缓/分期缴纳批准（第54条） */
    @Transactional
    public CaseFile approveDefer(Long caseId) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseRoles(caseId, "LEADER", "ADMIN");
        CaseFile c = CaseGuards.get(caseRepository, caseId);
        if (!List.of("DECIDED", "DELIVERED").contains(c.getStatus()))
            throw new BizException(2042, "仅已决定/已送达的案件可批准暂缓分期");
        c.setDeferApproved(true);
        return caseRepository.save(c);
    }

    /** 申请法院强制执行（第55条） */
    @Transactional
    public CaseFile applyCourtEnforce(Long caseId) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        CaseFile c = CaseGuards.get(caseRepository, caseId);
        if (!"DELIVERED".equals(c.getStatus())) throw new BizException(2042, "决定书送达且当事人逾期不履行方可申请强制执行");
        if (Boolean.TRUE.equals(c.getCourtEnforceApplied())) throw new BizException(2142, "该案已登记法院强执申请");
        CaseDecision decision = decisionRepository.findByCaseId(caseId).orElseThrow();
        if (!"PUNISH".equals(decision.getDecisionType())) throw new BizException(2142, "仅未履行的处罚决定可申请法院强执");
        if (sum(caseId,"FINE").compareTo(decision.getFineAmount()) >= 0
                && sum(caseId,"RECOUP").compareTo(decision.getRecoupAmount()) >= 0
                && sum(caseId,"CONFISCATE").compareTo(decision.getConfiscateAmount()) >= 0)
            throw new BizException(2142, "该决定已执行完毕，无需申请法院强执");
        LocalDate payDeadline = deadlines.paymentDeadline(c);
        LocalDate today = LocalDate.now();
        LocalDate start = deadlines.applicationStart(c);
        if (Boolean.TRUE.equals(c.getReliefPending())) throw new BizException(2142, "尚有未结束的复议或诉讼，请先登记救济结果与期限依据");
        if (start == null) throw new BizException(2142, "须由法制或负责人登记法定救济期限依据；暂缓分期须登记期限");
        if (!today.isAfter(start))
            throw new BizException(2142, "缴款或法定救济期限尚未届满（" + start + "）");
        if (!today.isAfter(payDeadline))
            throw new BizException(2042, "缴款期至 " + payDeadline + " 届满前不得申请法院强制执行（行政强制法53条）");
        int urgeDays = config.intVal("court_urge_days", 10);
        var urged = jdbc.queryForList(
                """
                select max(d.delivered_at) as delivered_at from case_delivery d join case_document doc on doc.id = d.document_id
                where d.case_id = ? and doc.case_id = d.case_id and doc.doc_type = 'URGE_LETTER'
                having max(d.delivered_at) is not null""",
                caseId);
        if (urged.isEmpty())
            throw new BizException(2042, "申请法院强制执行前应当先行催告（行政强制法54条）：请先制作催告书（文书类型 URGE_LETTER）");
        LocalDate urgedAt = ((java.sql.Date) urged.get(0).get("delivered_at")).toLocalDate();
        if (today.isBefore(urgedAt.plusDays(urgeDays)))
            throw new BizException(2042, "催告后须满 " + urgeDays + " 日当事人仍不履行方可申请（行政强制法54条）");
        if (today.isAfter(deadlines.applicationDeadline(c)))
            throw new BizException(2142, "已超过登记的法定申请期限，请由法制核实后另行处理");
        c.setCourtEnforceApplied(true);
        c.setCourtEnforceStatus("APPLIED");
        c.setCourtAppliedAt(today);
        return caseRepository.save(c);
    }

    public record ReliefReq(LocalDate deadline, Boolean pending, String basis, LocalDate deferUntil) {}

    @Transactional
    public CaseFile registerRelief(Long caseId, ReliefReq req, String operator) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseRoles(caseId, "LEGAL", "LEADER", "ADMIN");
        CaseFile c = CaseGuards.get(caseRepository, caseId);
        if (!"DELIVERED".equals(c.getStatus()) || c.getDeliveredAt() == null)
            throw new BizException(2142, "决定书送达后方可登记法定救济期限");
        if (Boolean.TRUE.equals(c.getCourtEnforceApplied())) throw new BizException(2142, "申请后不能改写期限依据");
        if (req.deadline() == null || req.deadline().isBefore(c.getDeliveredAt()) || req.basis() == null || req.basis().isBlank())
            throw new BizException(2142, "须填写不早于送达日的救济期限届满日与法律或文书依据");
        if (req.deferUntil() != null && (!Boolean.TRUE.equals(c.getDeferApproved()) || req.deferUntil().isBefore(c.getDeliveredAt())))
            throw new BizException(2142, "暂缓期限须已获批准且不得早于送达日");
        c.setReliefDeadline(req.deadline()); c.setReliefPending(Boolean.TRUE.equals(req.pending()));
        c.setReliefBasis(req.basis().trim()); c.setReliefRegisteredBy(operator); c.setDeferUntil(req.deferUntil());
        return caseRepository.save(c);
    }

    public record CourtResultReq(String status, String receiptNo, LocalDate receivedAt, String note) {}

    @Transactional
    public CaseFile courtResult(Long caseId, CourtResultReq req) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseRoles(caseId, "LEADER", "ADMIN");
        CaseFile c = CaseGuards.get(caseRepository, caseId);
        if (!Boolean.TRUE.equals(c.getCourtEnforceApplied()) || !"DELIVERED".equals(c.getStatus()))
            throw new BizException(2142, "须先登记强执申请");
        if (req.status() == null || !List.of("ACCEPTED","RETURNED","REJECTED").contains(req.status()) || req.receiptNo() == null
                || req.receiptNo().isBlank() || req.receivedAt() == null || req.receivedAt().isAfter(LocalDate.now())
                || req.receivedAt().isBefore(c.getCourtAppliedAt() == null ? c.getDeliveredAt() : c.getCourtAppliedAt()))
            throw new BizException(2142, "须登记法院结果、回执文号与申请后不晚于今天的回执日期");
        if (c.getCourtReceiptNo() != null) throw new BizException(2142, "已登记法院结果，不可覆盖历史回执");
        c.setCourtEnforceStatus(req.status()); c.setCourtReceiptNo(req.receiptNo().trim());
        c.setCourtReceivedAt(req.receivedAt()); c.setCourtResultNote(req.note());
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
