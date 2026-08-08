package cn.ybcase.bureau.web;

import cn.ybcase.bureau.entity.CaseClue;
import cn.ybcase.bureau.service.OversightService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 四期接口：行政检查、举报奖励、裁量建议、分期、专家评审、公示导出 */
@RestController
@RequestMapping("/api/bureau")
@RequiredArgsConstructor
public class OversightController {

    private final OversightService oversightService;
    private final JdbcTemplate jdbc;
    private final cn.ybcase.bureau.service.CaseService caseService;

    private static boolean privileged(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_LEADER") || a.getAuthority().equals("ROLE_LEGAL")
                        || a.getAuthority().equals("ROLE_ADMIN"));
    }

    // 行政检查
    @GetMapping("/inspections")
    public R<List<Map<String, Object>>> inspections() {
        return R.ok(jdbc.queryForList("""
                select i.*, li.name as item_name from inspection i
                left join law_enforce_item li on li.id = i.item_id order by i.id desc limit 200"""));
    }

    @PostMapping("/inspections")
    public R<Map<String, Object>> createInspection(@RequestBody OversightService.InspectionReq req,
                                                   Authentication auth) {
        return R.ok(oversightService.createInspection(req, auth.getName()));
    }

    @PostMapping("/inspections/{id}/complete")
    public R<Void> completeInspection(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        oversightService.completeInspection(id, (String) body.get("result"),
                Boolean.TRUE.equals(body.get("violationFound")));
        return R.ok();
    }

    @PostMapping("/inspections/{id}/to-clue")
    public R<CaseClue> toClue(@PathVariable Long id, Authentication auth) {
        return R.ok(oversightService.inspectionToClue(id, auth.getName()));
    }

    // 举报奖励
    @GetMapping("/rewards")
    public R<List<Map<String, Object>>> rewards() {
        return R.ok(jdbc.queryForList("""
                select r.*, c.clue_no, c.suspect_name from reward r
                join case_clue c on c.id = r.clue_id order by r.id desc limit 200"""));
    }

    @PostMapping("/rewards")
    public R<Void> createReward(@RequestBody OversightService.RewardReq req) {
        oversightService.createReward(req);
        return R.ok();
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    @PostMapping("/rewards/{id}/approve")
    public R<Void> approveReward(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                 Authentication auth) {
        oversightService.approveReward(id, new BigDecimal(String.valueOf(body.get("amount"))), auth.getName());
        return R.ok();
    }

    @PostMapping("/rewards/{id}/pay")
    public R<Void> payReward(@PathVariable Long id) {
        oversightService.payReward(id);
        return R.ok();
    }

    // 裁量建议
    @GetMapping("/cases/{id}/discretion-suggest")
    public R<Map<String, Object>> discretionSuggest(@PathVariable Long id, Authentication auth) {
        caseService.assertInScope(id, auth.getName(), privileged(auth));
        return R.ok(oversightService.discretionSuggest(id));
    }

    // 分期计划
    @GetMapping("/cases/{id}/installments")
    public R<List<Map<String, Object>>> installments(@PathVariable Long id, Authentication auth) {
        caseService.assertInScope(id, auth.getName(), privileged(auth));
        return R.ok(jdbc.queryForList("select * from case_installment where case_id = ? order by seq", id));
    }

    @PostMapping("/cases/{id}/installments")
    public R<Void> addInstallment(@PathVariable Long id, @RequestBody OversightService.InstallmentReq req) {
        oversightService.addInstallment(id, req);
        return R.ok();
    }

    @PostMapping("/installments/{id}/pay")
    public R<Void> payInstallment(@PathVariable Long id) {
        oversightService.payInstallment(id);
        return R.ok();
    }

    // 专家评审
    @PostMapping("/cases/{id}/expert-reviews")
    public R<Void> startExpertReview(@PathVariable Long id, @RequestBody Map<String, String> body) {
        oversightService.startExpertReview(id, body.get("experts"));
        return R.ok();
    }

    @PostMapping("/cases/{id}/expert-reviews/{reviewId}/end")
    public R<Void> endExpertReview(@PathVariable Long id, @PathVariable Long reviewId,
                                   @RequestBody Map<String, String> body) {
        oversightService.endExpertReview(id, reviewId, body.get("opinion"),
                body.get("startedAt") == null ? null : java.time.LocalDate.parse(body.get("startedAt")),
                body.get("endedAt") == null ? null : java.time.LocalDate.parse(body.get("endedAt")));
        return R.ok();
    }

    /** 处罚决定公示导出（脱敏：自然人姓名保留首字，局令46条/辽56条） */
    @GetMapping("/decisions/publish-export")
    public R<List<Map<String, Object>>> publishExport() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select cf.party_name, cf.party_type, cc.category as cause, d.decision_no,
                       d.fine_amount, d.recoup_amount, d.decided_at, d.published_at
                from case_decision d
                join case_file cf on cf.id = d.case_id
                join case_cause cc on cc.id = cf.cause_id
                where d.published = true order by d.published_at desc""");
        for (var r : rows) {
            if ("INDIVIDUAL".equals(r.get("party_type"))) {
                String name = (String) r.get("party_name");
                r.put("party_name", name.isEmpty() ? name : name.charAt(0) + "**");
            }
        }
        return R.ok(rows);
    }
}
