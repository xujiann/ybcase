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
    private final CaseClueRepository clueRepository;
    private final JdbcTemplate jdbc;

    // ---------- 行政检查（清单第10-12项） ----------

    public record InspectionReq(Long itemId, String objectName, String objectType,
                                String officers, LocalDate plannedAt) {}

    @Transactional
    public Map<String, Object> createInspection(InspectionReq req, String username) {
        if (req.officers() == null || req.officers().split("[、,，]").length < 2)
            throw new BizException(2063, "检查人员不得少于2人（第16条）");
        String prefix = "JC" + LocalDate.now().getYear();
        Long count = jdbc.queryForObject(
                "select count(*) from inspection where insp_no like ?", Long.class, prefix + "%");
        String no = prefix + String.format("%04d", (count == null ? 0 : count) + 1);
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
        CaseClue clue = clueService.create(new ClueService.ClueCreateReq(
                "INSPECTION",
                "行政检查发现（" + insp.get("insp_no") + "）：" + insp.get("result"),
                (String) insp.get("object_name"), (String) insp.get("object_type"),
                LocalDate.now(), (String) insp.get("officers")), username);
        jdbc.update("update inspection set clue_id = ? where id = ?", clue.getId(), id);
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

    public record InstallmentReq(Integer seq, LocalDate dueAt, BigDecimal amount) {}

    @Transactional
    public void addInstallment(Long caseId, InstallmentReq req) {
        var rows = jdbc.queryForList("select defer_approved from case_file where id = ?", caseId);
        if (rows.isEmpty()) throw new BizException(2043, "案件不存在");
        if (!Boolean.TRUE.equals(rows.get(0).get("defer_approved")))
            throw new BizException(2065, "须先经负责人批准暂缓/分期缴纳（第54条）");
        jdbc.update("insert into case_installment (case_id, seq, due_at, amount) values (?,?,?,?)",
                caseId, req.seq(), req.dueAt(), req.amount());
    }

    @Transactional
    public void payInstallment(Long installmentId) {
        int n = jdbc.update("update case_installment set paid_at = ? where id = ? and paid_at is null",
                LocalDate.now(), installmentId);
        if (n == 0) throw new BizException(2065, "分期记录不存在或已缴清");
    }

    // ---------- 专家评审（第25条，期间不计入办案期限） ----------

    @Transactional
    public void startExpertReview(Long caseId, String experts) {
        if (experts == null || experts.isBlank()) throw new BizException(2066, "须填写评审专家");
        jdbc.update("insert into expert_review (case_id, experts, started_at) values (?,?,?)",
                caseId, experts, LocalDate.now());
    }

    @Transactional
    public void endExpertReview(Long caseId, Long reviewId, String opinion, LocalDate startedAt, LocalDate endedAt) {
        if (opinion == null || opinion.isBlank()) throw new BizException(2066, "须填写评审意见");
        LocalDate end = endedAt != null ? endedAt : LocalDate.now();
        var rows = jdbc.queryForList("select started_at from expert_review where id = ? and case_id = ? and ended_at is null",
                reviewId, caseId);
        if (rows.isEmpty()) throw new BizException(2066, "评审不存在或已结束");
        LocalDate start = startedAt != null ? startedAt : ((java.sql.Date) rows.get(0).get("started_at")).toLocalDate();
        jdbc.update("update expert_review set ended_at = ?, opinion = ? where id = ?", end, opinion, reviewId);
        // 第45条：专家评审时间不计入办案期限——自动登记期限扣除
        jdbc.update("insert into case_period_exclusion (case_id, reason, start_at, end_at, note) values (?,?,?,?,?)",
                caseId, "EXPERT", start, end, "专家评审（自动登记）");
    }
}
