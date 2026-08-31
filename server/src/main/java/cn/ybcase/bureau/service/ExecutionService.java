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
                    caseId, req.kind(), amount, req.paidAt() != null ? req.paidAt() : LocalDate.now(),
                    req.method(), req.note(), receiptNo,
                    caseId, req.kind(), amount, decided);
            if (ok == 0) throw new BizException(2042,
                    "累计登记金额超出决定书确定的金额（" + decided + "），请刷新后核对是否已被重复登记");
            return;
        }
        // 第55条：加处罚款不得超出罚款数额
        if ("LATE_FEE".equals(req.kind())) {
            // 先 sum 再 insert 在并发下两笔都能过检、落库后累计超本金；
            // 改为把封顶写进 insert 的 where，由数据库一次性判定
            int n = jdbc.update("""
                    insert into case_execution (case_id, kind, amount, paid_at, method, note, receipt_no)
                    select ?,?,?,?,?,?,?
                    where (select coalesce(sum(amount), 0) from case_execution
                           where case_id = ? and kind = 'LATE_FEE') + ? <= ?""",
                    caseId, req.kind(), amount, req.paidAt() != null ? req.paidAt() : LocalDate.now(),
                    req.method(), req.note(), receiptNo,
                    caseId, amount, d.getFineAmount());
            if (n == 0) throw new BizException(2012, "加处罚款累计不得超出罚款数额（第55条）");
            return;
        }
        jdbc.update("insert into case_execution (case_id, kind, amount, paid_at, method, note, receipt_no) values (?,?,?,?,?,?,?)",
                caseId, req.kind(), amount, req.paidAt() != null ? req.paidAt() : LocalDate.now(),
                req.method(), req.note(), receiptNo);
    }

    /** 加处罚款测算（第55条：每日3%，不超过罚款数额；基数按参数 FULL/UNPAID） */
    public Map<String, Object> lateFeeQuote(Long caseId) {
        CaseFile c = CaseGuards.get(caseRepository, caseId);
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2042, "案件无处理决定"));
        if (c.getDeliveredAt() == null) throw new BizException(2042, "决定书尚未送达");
        // 缴款期与强执分支（applyCourtEnforce）同口径：硬编码 15 会让同一案件出现两个缴款期，
        // 参数调大后加处罚款仍按旧期起算而多收
        LocalDate payDeadline = c.getDeliveredAt().plusDays(config.intVal("payment_days", 15));
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
        // 行政强制法53/54条：须缴款期届满、经催告仍不履行，且在缴款期满起3个月内申请
        LocalDate payDeadline = c.getDeliveredAt().plusDays(config.intVal("payment_days", 15));
        LocalDate today = LocalDate.now();
        if (!today.isAfter(payDeadline))
            throw new BizException(2042, "缴款期至 " + payDeadline + " 届满前不得申请法院强制执行（行政强制法53条）");
        int urgeDays = config.intVal("court_urge_days", 10);
        var urged = jdbc.queryForList(
                "select made_at from case_document where case_id = ? and doc_type = 'URGE_LETTER' order by made_at desc",
                caseId);
        if (urged.isEmpty())
            throw new BizException(2042, "申请法院强制执行前应当先行催告（行政强制法54条）：请先制作催告书（文书类型 URGE_LETTER）");
        LocalDate urgedAt = ((java.sql.Date) urged.get(0).get("made_at")).toLocalDate();
        if (today.isBefore(urgedAt.plusDays(urgeDays)))
            throw new BizException(2042, "催告后须满 " + urgeDays + " 日当事人仍不履行方可申请（行政强制法54条）");
        if (today.isAfter(payDeadline.plusMonths(3)))
            throw new BizException(2042, "已超过缴款期满起3个月的申请期限（行政强制法53条），请说明情况另行处理");
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
