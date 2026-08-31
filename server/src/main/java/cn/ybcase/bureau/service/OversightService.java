package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseClue;
import cn.ybcase.bureau.repository.CaseClueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 四期：行政检查（检查→线索闭环）、举报奖励、裁量建议、分期计划、专家评审 */
@Service
@RequiredArgsConstructor
public class OversightService {

    private final ClueService clueService;
    private final ExecutionService executionService;
    private final CaseClueRepository clueRepository;
    private final JdbcTemplate jdbc;
    private final BizSeqService seq;

    // ---------- 行政检查（清单第10-12项） ----------

    public record InspectionReq(Long itemId, String objectName, String objectType,
                                String officers, LocalDate plannedAt) {}

    @Transactional
    public Map<String, Object> createInspection(InspectionReq req, String username) {
        if (req.officers() == null || req.officers().split("[、,，]").length < 2)
            throw new BizException(2063, "检查人员不得少于2人（第16条）");
        int year = LocalDate.now().getYear();
        String no = "JC" + year + String.format("%04d", seq.next("INSP", year));
        jdbc.update("""
                insert into inspection (insp_no, item_id, object_name, object_type, officers, planned_at, created_by)
                values (?,?,?,?,?,?,?)""",
                no, req.itemId(), req.objectName(), req.objectType(), req.officers(),
                req.plannedAt() != null ? req.plannedAt() : LocalDate.now(), username);
        return jdbc.queryForMap("select * from inspection where insp_no = ?", no);
    }

    @Transactional
    public void completeInspection(Long id, String result, boolean violationFound) {
        if (result == null || result.isBlank()) throw new BizException(2063, "须填写检查结果");
        int n = jdbc.update("""
                update inspection set done_at = ?, result = ?, violation_found = ?
                where id = ? and done_at is null""",
                LocalDate.now(), result, violationFound, id);
        if (n == 0) throw new BizException(2063, "检查任务不存在或已完成");
    }

    /** 检查发现违法 → 一键转线索（打通 检查→线索→立案 闭环） */
    @Transactional
    public CaseClue inspectionToClue(Long id, String username) {
        var rows = jdbc.queryForList("select * from inspection where id = ?", id);
        if (rows.isEmpty()) throw new BizException(2063, "检查任务不存在");
        var insp = rows.get(0);
        if (!Boolean.TRUE.equals(insp.get("violation_found")))
            throw new BizException(2063, "该检查未发现违法行为，无需转线索");
        if (insp.get("clue_id") != null) throw new BizException(2063, "该检查已转线索");
        // 原子占位：并发双击会生成两条线索挂同一检查（下方 update 带 clue_id is null 兜底）
        CaseClue clue = clueService.create(new ClueService.ClueCreateReq(
                "INSPECTION",
                "行政检查发现（" + insp.get("insp_no") + "）：" + insp.get("result"),
                (String) insp.get("object_name"), (String) insp.get("object_type"),
                LocalDate.now(), (String) insp.get("officers")), username);
        int n = jdbc.update("update inspection set clue_id = ? where id = ? and clue_id is null", clue.getId(), id);
        if (n == 0) throw new BizException(2063, "该检查已转线索（请勿重复提交）");
        return clue;
    }

    // ---------- 举报奖励（清单第13项，条例35条） ----------

    public record RewardReq(Long clueId, String reporterName, String reporterContact, String note) {}

    @Transactional
    public void createReward(RewardReq req) {
        CaseClue clue = clueRepository.findById(req.clueId())
                .orElseThrow(() -> new BizException(2025, "线索不存在"));
        if (!"FILED".equals(clue.getStatus()))
            throw new BizException(2064, "举报线索查实（立案）后方可启动奖励程序（条例35条）");
        if (req.reporterName() == null || req.reporterName().isBlank())
            throw new BizException(2064, "须登记举报人（信息严格保密）");
        jdbc.update("""
                insert into reward (clue_id, reporter_name, reporter_contact, verified, note)
                values (?,?,?,true,?)""",
                req.clueId(), req.reporterName(), req.reporterContact(), req.note());
    }

    @Transactional
    public void approveReward(Long rewardId, BigDecimal amount, String approvedBy) {
        if (amount == null || amount.signum() <= 0) throw new BizException(2064, "奖励金额须为正数");
        int n = jdbc.update("""
                update reward set amount = ?, approved_by = ?, approved_at = ?
                where id = ? and approved_at is null""",
                amount, approvedBy, LocalDate.now(), rewardId);
        if (n == 0) throw new BizException(2064, "奖励记录不存在或已审批");
    }

    @Transactional
    public void payReward(Long rewardId) {
        int n = jdbc.update("update reward set paid_at = ? where id = ? and approved_at is not null and paid_at is null",
                LocalDate.now(), rewardId);
        if (n == 0) throw new BizException(2064, "奖励未审批或已发放");
    }

    // ---------- 裁量建议（衔接裁量基准表） ----------

    public Map<String, Object> discretionSuggest(Long caseId) {
        var caseRows = jdbc.queryForList("""
                select cf.amount_involved, cc.category from case_file cf
                join case_cause cc on cc.id = cf.cause_id where cf.id = ?""", caseId);
        if (caseRows.isEmpty()) throw new BizException(2043, "案件不存在");
        var c = caseRows.get(0);
        BigDecimal amount = (BigDecimal) c.get("amount_involved");
        List<Map<String, Object>> rules = jdbc.queryForList(
                "select * from discretion_rule where cause_category = ? order by multiplier_min", c.get("category"));
        for (var r : rules) {
            r.put("suggestMin", amount.multiply((BigDecimal) r.get("multiplier_min")));
            r.put("suggestMax", amount.multiply((BigDecimal) r.get("multiplier_max")));
        }
        return Map.of("amountInvolved", amount, "category", c.get("category"), "tiers", rules);
    }

    // ---------- 分期计划（第54条） ----------

    /** kind 缺省为 FINE，兼容旧调用；退回基金/没收类分期须显式传 */
    public record InstallmentReq(Integer seq, LocalDate dueAt, BigDecimal amount, String kind) {}

    private static final List<String> INSTALLMENT_KINDS = List.of("FINE", "RECOUP", "CONFISCATE");

    @Transactional
    public void addInstallment(Long caseId, InstallmentReq req) {
        var rows = jdbc.queryForList("select defer_approved from case_file where id = ?", caseId);
        if (rows.isEmpty()) throw new BizException(2043, "案件不存在");
        if (!Boolean.TRUE.equals(rows.get(0).get("defer_approved")))
            throw new BizException(2065, "须先经负责人批准暂缓/分期缴纳（第54条）");
        String kind = req.kind() == null || req.kind().isBlank() ? "FINE" : req.kind();
        if (!INSTALLMENT_KINDS.contains(kind))
            throw new BizException(2065, "分期款项类型须为 罚款/退回基金/没收违法所得");
        if (req.amount() == null || req.amount().signum() <= 0)
            throw new BizException(2065, "分期金额须为正数");
        // 分期计划总额不得超过决定书就该类款项确定的金额：否则缴完计划仍判"未缴清"，
        // 或反过来把超额款项入了账（入账侧的封顶在 ExecutionService，这里是计划侧的前置）
        var dec = jdbc.queryForList(
                "select fine_amount, recoup_amount, confiscate_amount from case_decision where case_id = ?", caseId);
        if (dec.isEmpty()) throw new BizException(2065, "案件无处理决定，不能制定分期计划");
        String col = switch (kind) {
            case "RECOUP" -> "recoup_amount";
            case "CONFISCATE" -> "confiscate_amount";
            default -> "fine_amount";
        };
        BigDecimal decided = (BigDecimal) dec.get(0).get(col);
        decided = decided == null ? BigDecimal.ZERO : decided;
        if (decided.signum() <= 0)
            throw new BizException(2065, "决定书未确定该类款项金额，不能就其制定分期计划");
        BigDecimal planned = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from case_installment where case_id = ? and kind = ?",
                BigDecimal.class, caseId, kind);
        if (planned.add(req.amount()).compareTo(decided) > 0)
            throw new BizException(2065, "分期计划累计（" + planned.add(req.amount())
                    + "）超出决定书确定的金额（" + decided + "）");
        jdbc.update("insert into case_installment (case_id, seq, due_at, amount, kind) values (?,?,?,?,?)",
                caseId, req.seq(), req.dueAt(), req.amount(), kind);
    }

    @Transactional
    public void payInstallment(Long installmentId) {
        // 分期缴纳同时登记为执行记录：结案判定（fullyExecuted）只看 case_execution，
        // 只标 paid_at 会让批准了分期的案件永远无法结案（第56条）。
        // 入账走 ExecutionService.addExecution 而非裸 insert——裸 insert 会写死款项类型，
        // 并绕过"该类款项须已决定""累计不超决定额"两道校验。
        var rows = jdbc.queryForList(
                "select case_id, seq, amount, kind from case_installment where id = ? and paid_at is null",
                installmentId);
        if (rows.isEmpty()) throw new BizException(2065, "分期记录不存在或已缴清");
        Long caseId = ((Number) rows.get(0).get("case_id")).longValue();
        Integer seq = ((Number) rows.get(0).get("seq")).intValue();
        BigDecimal amount = (BigDecimal) rows.get(0).get("amount");
        String kind = (String) rows.get(0).get("kind");

        int n = jdbc.update("update case_installment set paid_at = ? where id = ? and paid_at is null",
                LocalDate.now(), installmentId);
        if (n == 0) throw new BizException(2065, "分期记录不存在或已缴清");   // 并发下的二次确认

        executionService.addExecution(caseId, new ExecutionService.ExecutionReq(
                kind, amount, LocalDate.now(), "BANK", "分期缴纳第 " + seq + " 期（自动入账）", null));
    }

    // ---------- 专家评审（第25条，期间不计入办案期限） ----------

    @Transactional
    /** 起始日在"启动评审"时登记（可为实际开始日，受校验），结束时不得再改——否则期限扣除等于由客户端随意设定 */
    public void startExpertReview(Long caseId, String experts, LocalDate startedAt) {
        if (experts == null || experts.isBlank()) throw new BizException(2066, "须填写评审专家");
        var cf = jdbc.queryForList("select filed_at from case_file where id = ?", caseId);
        if (cf.isEmpty()) throw new BizException(2043, "案件不存在");
        LocalDate filedAt = ((java.sql.Date) cf.get(0).get("filed_at")).toLocalDate();
        LocalDate start = startedAt == null ? LocalDate.now() : startedAt;
        if (start.isBefore(filedAt)) throw new BizException(2066, "评审开始日期不得早于立案日期（" + filedAt + "）");
        if (start.isAfter(LocalDate.now())) throw new BizException(2066, "评审开始日期不得晚于今天");
        // 同案只允许一条进行中评审：并行多条区间可完全重合，结束时会重复扣除期限
        Integer running = jdbc.queryForObject(
                "select count(*) from expert_review where case_id = ? and ended_at is null", Integer.class, caseId);
        if (running != null && running > 0)
            throw new BizException(2066, "本案已有进行中的专家评审，请先结束后再启动新评审");
        jdbc.update("insert into expert_review (case_id, experts, started_at) values (?,?,?)",
                caseId, experts, start);
    }

    @Transactional
    public void endExpertReview(Long caseId, Long reviewId, String opinion, LocalDate startedAt, LocalDate endedAt) {
        if (opinion == null || opinion.isBlank()) throw new BizException(2066, "须填写评审意见");
        var rows = jdbc.queryForList("select started_at from expert_review where id = ? and case_id = ? and ended_at is null",
                reviewId, caseId);
        if (rows.isEmpty()) throw new BizException(2066, "评审不存在或已结束");
        // 起始日一律取库内登记值：允许调用方自带 startedAt 等于把期限扣除交给客户端随意设定
        LocalDate start = ((java.sql.Date) rows.get(0).get("started_at")).toLocalDate();
        LocalDate end = endedAt != null ? endedAt : LocalDate.now();
        if (end.isBefore(start)) throw new BizException(2066, "评审结束日期不得早于开始日期（" + start + "）");
        if (end.isAfter(LocalDate.now())) throw new BizException(2066, "评审结束日期不得晚于今天");
        jdbc.update("update expert_review set ended_at = ?, opinion = ? where id = ?", end, opinion, reviewId);
        // 第45条：专家评审时间不计入办案期限——自动登记期限扣除。
        // 与既有扣除区间（鉴定/听证等）裁剪去重后再落库：重叠部分会被 sum(end_at-start_at)
        // 双算而凭空延长办案期限，等于绕开"延期须负责人批准"（addExclusion 对手工登记已硬拒重叠，
        // 此处是自动登记，硬拒会卡住评审结束，故改为剔除重叠只记净增部分）
        var existing = jdbc.queryForList(
                "select start_at, coalesce(end_at, current_date) as end_at from case_period_exclusion where case_id = ?",
                caseId);
        java.util.Set<LocalDate> covered = new java.util.HashSet<>();
        for (var r : existing) {
            LocalDate s = ((java.sql.Date) r.get("start_at")).toLocalDate();
            LocalDate e = ((java.sql.Date) r.get("end_at")).toLocalDate();
            for (LocalDate d = s; d.isBefore(e); d = d.plusDays(1)) covered.add(d);
        }
        LocalDate runStart = null;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            boolean slotFree = d.isBefore(end) && !covered.contains(d);
            if (slotFree && runStart == null) runStart = d;
            if (!slotFree && runStart != null) {
                jdbc.update("insert into case_period_exclusion (case_id, reason, start_at, end_at, note) values (?,?,?,?,?)",
                        caseId, "EXPERT", runStart, d, "专家评审（自动登记，已剔除与既有扣除重叠的部分）");
                runStart = null;
            }
        }
    }
}
