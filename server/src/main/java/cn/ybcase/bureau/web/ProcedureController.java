package cn.ybcase.bureau.web;

import static cn.ybcase.bureau.common.CaseScopeInterceptor.privileged;

import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.service.ProcedureService;
import cn.ybcase.core.common.R;
import static cn.ybcase.bureau.common.ReqValues.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    private final cn.ybcase.bureau.service.CaseService caseService;
    private final cn.ybcase.bureau.service.SourceAttachmentAccess sourceAccess;
    private final cn.ybcase.bureau.service.SourceHistoryService history;

    // 听证
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PostMapping("/cases/{id}/hearings")
    public R<Void> scheduleHearing(@PathVariable Long id, @RequestBody ProcedureService.HearingScheduleReq req) {
        procedureService.scheduleHearing(id, req);
        return R.ok();
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PostMapping("/cases/{id}/hearings/{hearingId}/hold")
    public R<Void> holdHearing(@PathVariable Long id, @PathVariable Long hearingId,
                               @RequestBody Map<String, String> body) {
        procedureService.holdHearing(id, hearingId, body.get("record"));
        return R.ok();
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PostMapping("/cases/{id}/hearings/{hearingId}/opinion")
    public R<Void> hearingOpinion(@PathVariable Long id, @PathVariable Long hearingId,
                                  @RequestBody Map<String, String> body) {
        procedureService.hearingOpinion(id, hearingId, body.get("opinion"));
        return R.ok();
    }

    /** 撤销未举行的听证排期（改期/当事人撤回申请）——无此通道则误排一次即锁死决定 */
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PostMapping("/cases/{id}/hearings/{hearingId}/cancel")
    public R<Void> cancelHearing(@PathVariable Long id, @PathVariable Long hearingId,
                                 @RequestBody Map<String, String> body) {
        procedureService.cancelHearing(id, hearingId, body.get("reason"));
        return R.ok();
    }

    // 简易备案
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PostMapping("/cases/{id}/summary-record")
    public R<CaseFile> summaryRecord(@PathVariable Long id) {
        return R.ok(procedureService.summaryRecord(id));
    }

    // 电子送达确认书（第59条：须当事人签署，登记时同步生成确认书文书留痕）
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PostMapping("/cases/{id}/e-delivery-consent")
    public R<CaseFile> eDeliveryConsent(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        return R.ok(procedureService.eDeliveryConsent(id, b.get("receiver"), b.get("channel"), b.get("docNo")));
    }

    // 移送台账
    @GetMapping("/transfers")
    public R<?> transfers(@RequestParam(required = false) Integer page,
                          @RequestParam(defaultValue = "20") int size,
                          @RequestParam(required = false) String q, Authentication auth) {
        return R.ok(history.list(cn.ybcase.bureau.service.SourceHistoryService.Kind.TRANSFERS, page, size, q, auth.getName()));
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PostMapping("/transfers")
    public R<Void> addTransfer(@RequestBody ProcedureService.TransferReq req, Authentication auth) {
        // caseId 来自请求体，CaseScopeInterceptor 只认路径变量，这里自行补范围校验
        if (req.caseId() != null) caseService.assertInScope(req.caseId(), auth.getName(), privileged(auth));
        procedureService.addTransfer(req, auth.getName());
        return R.ok();
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PostMapping("/transfers/{id}/confirm")
    public R<Void> confirmTransfer(@PathVariable Long id, Authentication auth) {
        // 兄弟资源路由不带 caseId：按移送记录反查所属案件再做范围校验
        var rows = jdbc.queryForList("select case_id, clue_id from case_transfer where id = ?", id);
        if (rows.isEmpty()) throw new cn.ybcase.bureau.common.BizException(2059, "移送记录不存在");
        Object cid = rows.get(0).get("case_id");
        if (cid != null) caseService.assertInScope(((Number) cid).longValue(), auth.getName(), privileged(auth));
        procedureService.confirmTransfer(id, auth.getName());
        return R.ok();
    }

    // 协查台账
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PostMapping("/cases/{id}/assists")
    public R<Void> addAssist(@PathVariable Long id, @RequestBody ProcedureService.AssistReq req) {
        procedureService.addAssist(id, req);
        return R.ok();
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PostMapping("/cases/{id}/assists/{assistId}/reply")
    public R<Void> replyAssist(@PathVariable Long id, @PathVariable Long assistId,
                               @RequestBody Map<String, Object> body) {
        Object refused = body.get("refused");
        if (refused != null && !(refused instanceof Boolean))
            throw new cn.ybcase.bureau.common.BizException(2060, "拒绝协助状态须为布尔值");
        Object result = body.get("result"), refuseReason = body.get("refuseReason");
        if (result != null && !(result instanceof String) || refuseReason != null && !(refuseReason instanceof String))
            throw new cn.ybcase.bureau.common.BizException(2060, "协查复函结果与拒绝理由须为文本");
        procedureService.replyAssist(id, assistId, (String) result,
                Boolean.TRUE.equals(refused), (String) refuseReason);
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
        procedureService.saveEnforcer(body);
        return R.ok();
    }
}
