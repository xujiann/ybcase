package cn.ybcase.bureau.web;

import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.service.ProcedureService;
import cn.ybcase.core.common.R;
import static cn.ybcase.bureau.common.ReqValues.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 三期程序接口：听证、简易备案、移送、协查、电子送达确认、执法证台账 */
@RestController
@RequestMapping("/api/bureau")
@RequiredArgsConstructor
public class ProcedureController {

    private final ProcedureService procedureService;
    private final JdbcTemplate jdbc;

    // 听证
    @PostMapping("/cases/{id}/hearings")
    public R<Void> scheduleHearing(@PathVariable Long id, @RequestBody ProcedureService.HearingScheduleReq req) {
        procedureService.scheduleHearing(id, req);
        return R.ok();
    }

    @PostMapping("/cases/{id}/hearings/{hearingId}/hold")
    public R<Void> holdHearing(@PathVariable Long id, @PathVariable Long hearingId,
                               @RequestBody Map<String, String> body) {
        procedureService.holdHearing(id, hearingId, body.get("record"));
        return R.ok();
    }

    @PostMapping("/cases/{id}/hearings/{hearingId}/opinion")
    public R<Void> hearingOpinion(@PathVariable Long id, @PathVariable Long hearingId,
                                  @RequestBody Map<String, String> body) {
        procedureService.hearingOpinion(id, hearingId, body.get("opinion"));
        return R.ok();
    }

    // 简易备案
    @PostMapping("/cases/{id}/summary-record")
    public R<CaseFile> summaryRecord(@PathVariable Long id) {
        return R.ok(procedureService.summaryRecord(id));
    }

    // 电子送达确认书（第59条：须当事人签署，登记时同步生成确认书文书留痕）
    @PostMapping("/cases/{id}/e-delivery-consent")
    public R<CaseFile> eDeliveryConsent(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        return R.ok(procedureService.eDeliveryConsent(id, b.get("receiver"), b.get("channel"), b.get("docNo")));
    }

    // 移送台账
    @GetMapping("/transfers")
    public R<List<Map<String, Object>>> transfers() {
        return R.ok(jdbc.queryForList("""
                select t.*, c.clue_no, cf.case_no from case_transfer t
                left join case_clue c on c.id = t.clue_id
                left join case_file cf on cf.id = t.case_id order by t.id desc limit 200"""));
    }

    @PostMapping("/transfers")
    public R<Void> addTransfer(@RequestBody ProcedureService.TransferReq req) {
        procedureService.addTransfer(req);
        return R.ok();
    }

    @PostMapping("/transfers/{id}/confirm")
    public R<Void> confirmTransfer(@PathVariable Long id) {
        procedureService.confirmTransfer(id);
        return R.ok();
    }

    // 协查台账
    @PostMapping("/cases/{id}/assists")
    public R<Void> addAssist(@PathVariable Long id, @RequestBody ProcedureService.AssistReq req) {
        procedureService.addAssist(id, req);
        return R.ok();
    }

    @PostMapping("/cases/{id}/assists/{assistId}/reply")
    public R<Void> replyAssist(@PathVariable Long id, @PathVariable Long assistId,
                               @RequestBody Map<String, Object> body) {
        procedureService.replyAssist(id, assistId, (String) body.get("result"),
                Boolean.TRUE.equals(body.get("refused")), (String) body.get("refuseReason"));
        return R.ok();
    }

    // 执法证台账
    @GetMapping("/enforcers")
    public R<List<Map<String, Object>>> enforcers() {
        return R.ok(jdbc.queryForList("""
                select e.*, su.username, su.real_name as user_real_name
                from enforcer e left join sys_user su on su.id = e.user_id order by e.id"""));
    }

    @PostMapping("/enforcers")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> saveEnforcer(@RequestBody Map<String, Object> body) {
        // userId：执法人员与系统账号的权威映射，数据范围（case_view_scope=SELF）据此判定参办
        jdbc.update("""
                insert into enforcer (name, cert_no, dept, cert_expire_at, legal_qualified, enabled, user_id)
                values (?,?,?,?,?,?,?)
                on conflict (cert_no) do update set name = excluded.name, dept = excluded.dept,
                    cert_expire_at = excluded.cert_expire_at, legal_qualified = excluded.legal_qualified,
                    enabled = excluded.enabled, user_id = excluded.user_id""",
                body.get("name"), body.get("certNo"), body.get("dept"),
                optDate(body, "certExpireAt", "执法证有效期"),
                Boolean.TRUE.equals(body.get("legalQualified")), !Boolean.FALSE.equals(body.get("enabled")),
                optLong(body, "userId"));
        return R.ok();
    }
}
