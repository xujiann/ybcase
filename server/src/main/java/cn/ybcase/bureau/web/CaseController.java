package cn.ybcase.bureau.web;

import static cn.ybcase.bureau.common.CaseScopeInterceptor.privileged;
import cn.ybcase.bureau.entity.*;
import cn.ybcase.bureau.repository.CaseFileRepository;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.core.common.R;
import static cn.ybcase.bureau.common.ReqValues.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bureau/cases")
@RequiredArgsConstructor
public class CaseController {

    private final CaseService caseService;
    private final cn.ybcase.bureau.service.ApprovalService approvalService;
    private final cn.ybcase.bureau.service.EvidenceService evidenceService;
    private final cn.ybcase.bureau.service.ExecutionService executionService;
    private final cn.ybcase.bureau.service.DocumentService documentService;
    private final cn.ybcase.bureau.service.OversightService oversightService;
    private final CaseFileRepository caseRepository;
    private final cn.ybcase.bureau.service.EvidenceCatalogService evidenceCatalogService;


    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public R<?> list(@RequestParam(required = false) String status,
                     @RequestParam(required = false) Integer page,
                     @RequestParam(defaultValue = "20") int size,
                     @RequestParam(required = false) String q,
                     @RequestParam(required = false) String owner,
                     Authentication auth) {
        // 未传 page/q/owner 且未开数据范围时保持旧契约（E2E/既有前端）；否则走分页（含范围过滤）
        if (page == null && q == null && owner == null && !caseService.hasCaseScope(auth.getName(), privileged(auth))) {
            return R.ok(status == null ? caseRepository.findTop200ByOrderByIdDesc()
                    : caseRepository.findTop200ByStatusOrderByIdDesc(status));
        }
        return R.ok(caseService.pageList(status, q, owner, page == null ? 1 : page, Math.min(size, 100),
                auth.getName(), privileged(auth)));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id, Authentication auth) {
        caseService.assertInScope(id, auth.getName(), privileged(auth));
        return R.ok(caseService.detail(id));
    }

    /** 案件要素更正（留痕）：办案/负责人/管理员可改，法制不参与要素认定；数据范围由 CaseScopeInterceptor 统一校验 */
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PutMapping("/{id}/party-info")
    public R<CaseFile> correctPartyInfo(@PathVariable Long id, @RequestBody CaseService.CorrectionReq req,
                                        Authentication auth) {
        return R.ok(caseService.correctPartyInfo(id, req, auth.getName()));
    }

    /** 要素更正记录（详情接口 corrections 已带回，此处供单独查询） */
    @GetMapping("/{id}/corrections")
    public R<List<Map<String, Object>>> corrections(@PathVariable Long id) {
        return R.ok(caseService.corrections(id));
    }

    /** 案件移交（承办人变更，负责人操作） */
    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    @PostMapping("/{id}/transfer-owner")
    public R<CaseFile> transferOwner(@PathVariable Long id, @RequestBody Map<String, String> body,
                                     Authentication auth) {
        return R.ok(caseService.transferOwner(id, body.get("newOwner"), auth.getName()));
    }

    // 直接立案会补记一张"负责人直接批准"的立案审批单（见下），故本接口必须限负责人：
    // 办案人员直接调用会写出 approver 是自己的假批准单，伪造第17条的负责人批准证据。
    // 办案人员立案改走 /approvals 的 FILE_CASE 申请流程（申请→负责人批准→建案），前端已相应分流。
    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    @PostMapping
    @org.springframework.transaction.annotation.Transactional
    public R<CaseFile> create(@RequestBody CaseService.CaseCreateReq req, Authentication auth) {
        CaseFile c = caseService.create(req, auth.getName());
        // 第17条"凡批准必有单"：直接立案（不走 /approvals FILE_CASE 流程）同样补记立案审批表单据
        approvalService.recordDirect("FILE_CASE", c.getId(),
                "直接立案：" + c.getPartyName(), auth.getName());
        return R.ok(c);
    }

    @PostMapping("/{id}/officers")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Void> addOfficer(@PathVariable Long id, @RequestBody CaseService.OfficerReq req) {
        caseService.addOfficer(id, req);
        return R.ok();
    }

    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PutMapping("/{id}/jurisdiction")
    public R<CaseFile> updateJurisdiction(@PathVariable Long id,
                                          @RequestBody CaseService.JurisdictionUpdateReq req,
                                          Authentication auth) {
        caseService.assertInScope(id, auth.getName(), privileged(auth));
        return R.ok(caseService.updateJurisdiction(id, req, auth.getName()));
    }

    @PostMapping("/{id}/officers/{officerId}/avoid")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Void> avoidOfficer(@PathVariable Long id, @PathVariable Long officerId,
                                @RequestBody Map<String, String> body) {
        caseService.avoidOfficer(id, officerId, body.get("reason"),
                body.get("applicant"), body.get("decidedBy"));
        return R.ok();
    }

    @PostMapping("/{id}/evidences")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Void> addEvidence(@PathVariable Long id, @RequestBody cn.ybcase.bureau.service.EvidenceService.EvidenceReq req) {
        evidenceService.addEvidence(id, req);
        return R.ok();
    }

    @PostMapping("/{id}/evidences/{evidenceId}/hold-disposal")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Void> disposeHold(@PathVariable Long id, @PathVariable Long evidenceId,
                               @RequestBody Map<String, String> body) {
        evidenceService.disposeHold(id, evidenceId, body.get("disposal"));
        return R.ok();
    }

    /** 证据质证（辽24条） */
    @PostMapping("/{id}/evidences/{evidenceId}/cross-exam")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Void> crossExam(@PathVariable Long id, @PathVariable Long evidenceId,
                             @RequestBody Map<String, String> body) {
        evidenceService.crossExam(id, evidenceId, body.get("opinion"));
        return R.ok();
    }

    @PostMapping("/{id}/evidences/{evidenceId}/seal")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Void> updateSeal(@PathVariable Long id, @PathVariable Long evidenceId,
                              @RequestParam boolean extend) {
        evidenceService.updateSeal(id, evidenceId, extend);
        return R.ok();
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Void> addDocument(@PathVariable Long id, @RequestBody CaseService.DocumentReq req) {
        caseService.addDocument(id, req);
        return R.ok();
    }

    @GetMapping("/{id}/documents/{docId}")
    public R<Map<String, Object>> document(@PathVariable Long id, @PathVariable Long docId, Authentication auth) {
        caseService.assertInScope(id, auth.getName(), privileged(auth));
        return R.ok(caseService.documentDetail(id, docId));
    }

    /** 文书模板渲染（要素自动填充，含执法事项法律依据带出） */
    @GetMapping("/{id}/documents/render")
    public R<Map<String, String>> renderDocument(@PathVariable Long id, @RequestParam String docType) {
        return R.ok(documentService.render(id, docType));
    }

    /** 案卷目录（一案一卷排序+齐全性检查，第57条） */
    @GetMapping("/{id}/archive-catalog")
    public R<Map<String, Object>> archiveCatalog(@PathVariable Long id, Authentication auth) {
        caseService.assertInScope(id, auth.getName(), privileged(auth));
        return R.ok(documentService.archiveCatalog(id));
    }

    /** Separate evidence volume: stable identifiers and attachment associations, without binary bodies. */
    @GetMapping("/{id}/evidence-catalog")
    public R<Map<String, Object>> evidenceCatalog(@PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean includeVoided, Authentication auth) {
        caseService.assertInScope(id, auth.getName(), privileged(auth));
        boolean manager = auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_LEADER".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
        return R.ok(evidenceCatalogService.catalog(id, includeVoided && manager));
    }

    /** 案件大事记（全过程记录时间轴，第4/35条） */
    @GetMapping("/{id}/timeline")
    public R<List<Map<String, Object>>> timeline(@PathVariable Long id, Authentication auth) {
        caseService.assertInScope(id, auth.getName(), privileged(auth));
        return R.ok(documentService.timeline(id));
    }

    /** 进行中的扣除事由结束后补登结束日（收口开放区间） */
    @PostMapping("/{id}/exclusions/{exclusionId}/end")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Void> endExclusion(@PathVariable Long id, @PathVariable Long exclusionId,
                                @RequestBody Map<String, String> body) {
        caseService.endExclusion(id, exclusionId,
                optDate(body, "endAt", "结束日期"));
        return R.ok();
    }

    @PostMapping("/{id}/exclusions")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Void> addExclusion(@PathVariable Long id, @RequestBody CaseService.ExclusionReq req) {
        caseService.addExclusion(id, req);
        return R.ok();
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")  // 中止批准（局令：负责人职权）
    @PostMapping("/{id}/suspend")
    @org.springframework.transaction.annotation.Transactional
    public R<CaseFile> suspend(@PathVariable Long id, @RequestBody Map<String, String> body,
                               Authentication auth) {
        CaseFile r = caseService.suspend(id, body.get("reason"));
        approvalService.recordDirect("SUSPEND", id, body.get("reason"), auth.getName());
        return R.ok(r);
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")  // 恢复（局令：负责人职权）
    @PostMapping("/{id}/resume")
    public R<CaseFile> resume(@PathVariable Long id) {
        return R.ok(caseService.resume(id));
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")  // 终止批准（局令：负责人职权）
    @PostMapping("/{id}/terminate")
    @org.springframework.transaction.annotation.Transactional
    public R<CaseFile> terminate(@PathVariable Long id, @RequestBody Map<String, String> body,
                                 Authentication auth) {
        CaseFile r = caseService.terminate(id, body.get("reason"));
        approvalService.recordDirect("TERMINATE", id, body.get("reason"), auth.getName());
        return R.ok(r);
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")  // 延期批准（局令：负责人职权）
    @PostMapping("/{id}/extend")
    @org.springframework.transaction.annotation.Transactional
    public R<CaseFile> extend(@PathVariable Long id, @RequestBody Map<String, Object> body,
                              Authentication auth) {
        int days = reqInt(body, "days", "延长天数");
        CaseFile r = caseService.extend(id, days, (String) body.get("reason"));
        approvalService.recordDirect("EXTEND", id,
                "延长" + days + "日：" + body.get("reason"), auth.getName());
        return R.ok(r);
    }

    @PostMapping("/{id}/convert-to-ordinary")
    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    public R<CaseFile> convertToOrdinary(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                         Authentication auth) {
        return R.ok(caseService.convertToOrdinary(id,
                body == null || !(body.get("reason") instanceof String value) ? null : value, auth.getName()));
    }

    @PostMapping("/{id}/report")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<CaseFile> report(@PathVariable Long id, @RequestBody CaseService.ReportReq body,
                              Authentication auth) {
        return R.ok(caseService.report(id, body, auth.getName()));
    }

    @PostMapping("/{id}/reviews")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<CaseReview> submitReview(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(caseService.submitReview(id, body.get("requiredReason")));
    }

    @PreAuthorize("hasAnyRole('LEGAL','ADMIN')")  // 法制审核由法制机构实施（第37条）
    @PostMapping("/reviews/{reviewId}")
    public R<CaseReview> doReview(@PathVariable Long reviewId, @RequestBody Map<String, String> body, Authentication auth) {
        return R.ok(caseService.doReview(reviewId, auth.getName(),
                body.get("opinionType"), body.get("opinion")));
    }

    @PostMapping("/{id}/notice")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<CaseNotice> notify(@PathVariable Long id, @RequestBody CaseService.NoticeReq req) {
        return R.ok(caseService.notify(id, req));
    }

    @PostMapping("/{id}/statement")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<CaseNotice> recordStatement(@PathVariable Long id, @RequestBody CaseService.StatementReq req) {
        return R.ok(caseService.recordStatement(id, req));
    }

    @PostMapping("/{id}/meetings")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Void> addMeeting(@PathVariable Long id, @RequestBody CaseService.MeetingReq req) {
        caseService.addMeeting(id, req);
        return R.ok();
    }

    /** Read-only checklist: all authenticated users may inspect cases within their data scope. */
    @PostMapping("/{id}/preflight")
    public R<CaseService.PreflightResult> preflight(@PathVariable Long id,
                                                   @RequestBody CaseService.PreflightReq req,
                                                   Authentication auth) {
        caseService.assertInScope(id, auth.getName(), privileged(auth));
        return R.ok(caseService.preflight(id, req));
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")  // 负责人审查决定（局令：负责人职权）
    @PostMapping("/{id}/decide")
    public R<CaseDecision> decide(@PathVariable Long id, @RequestBody CaseService.DecisionReq req) {
        return R.ok(caseService.decide(id, req));
    }

    /** 处罚决定公开（7日内，辽56条） */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<CaseDecision> publish(@PathVariable Long id) {
        return R.ok(caseService.publish(id));
    }

    /** 撤回公开（行政处罚法第48条第2款：被撤销/变更/确认违法的决定三日内撤回并说明理由） */
    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    @PostMapping("/{id}/publish-withdraw")
    public R<Void> publishWithdraw(@PathVariable Long id, @RequestBody Map<String, String> body,
                                   org.springframework.security.core.Authentication auth) {
        oversightService.withdrawPublish(id, body.get("reason"), auth.getName());
        return R.ok();
    }

    /** 重大处罚决定政府备案（辽54条） */
    @PostMapping("/{id}/gov-record")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<CaseDecision> govRecord(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(caseService.govRecord(id, body.get("recordNo")));
    }

    @PostMapping("/{id}/deliver")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<CaseFile> deliver(@PathVariable Long id, @RequestBody CaseService.DeliveryReq req) {
        return R.ok(caseService.deliver(id, req));
    }

    @PostMapping("/{id}/executions")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Void> addExecution(@PathVariable Long id, @RequestBody cn.ybcase.bureau.service.ExecutionService.ExecutionReq req) {
        executionService.addExecution(id, req);
        return R.ok();
    }

    @GetMapping("/{id}/late-fee-quote")
    public R<Map<String, Object>> lateFeeQuote(@PathVariable Long id, Authentication auth) {
        caseService.assertInScope(id, auth.getName(), privileged(auth));
        return R.ok(executionService.lateFeeQuote(id));
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")  // 暂缓分期批准（局令：负责人职权）
    @PostMapping("/{id}/approve-defer")
    @org.springframework.transaction.annotation.Transactional
    public R<CaseFile> approveDefer(@PathVariable Long id, Authentication auth) {
        CaseFile r = executionService.approveDefer(id);
        approvalService.recordDirect("DEFER", id, null, auth.getName());
        return R.ok(r);
    }

    @PostMapping("/{id}/court-enforce")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<CaseFile> applyCourtEnforce(@PathVariable Long id) {
        return R.ok(executionService.applyCourtEnforce(id));
    }

    @PreAuthorize("hasAnyRole('LEGAL','LEADER','ADMIN')")
    @PostMapping("/{id}/relief-deadline")
    public R<CaseFile> registerRelief(@PathVariable Long id, @RequestBody cn.ybcase.bureau.service.ExecutionService.ReliefReq req, Authentication auth) {
        return R.ok(executionService.registerRelief(id, req, auth.getName()));
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    @PostMapping("/{id}/court-result")
    public R<CaseFile> courtResult(@PathVariable Long id, @RequestBody cn.ybcase.bureau.service.ExecutionService.CourtResultReq req) {
        return R.ok(executionService.courtResult(id, req));
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")  // 结案批准（局令：负责人职权）
    @PostMapping("/{id}/close")
    public R<CaseFile> close(@PathVariable Long id, @RequestBody Map<String, String> body,
                             Authentication auth) {
        return R.ok(caseService.close(id, body.get("closeReport"), auth.getName()));
    }
}
