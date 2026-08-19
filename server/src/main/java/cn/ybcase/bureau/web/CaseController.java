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
    private final CaseFileRepository caseRepository;


    @GetMapping
    public R<?> list(@RequestParam(required = false) String status,
                     @RequestParam(required = false) Integer page,
                     @RequestParam(defaultValue = "20") int size,
                     @RequestParam(required = false) String q,
                     Authentication auth) {
        // 未传 page 且未开数据范围时保持旧契约（E2E/既有前端）；否则走分页（含范围过滤）
        if (page == null && q == null && !caseService.scopedSelf(privileged(auth))) {
            return R.ok(status == null ? caseRepository.findTop200ByOrderByIdDesc()
                    : caseRepository.findTop200ByStatusOrderByIdDesc(status));
        }
        return R.ok(caseService.pageList(status, q, page == null ? 1 : page, Math.min(size, 100),
                auth.getName(), privileged(auth)));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id, Authentication auth) {
        caseService.assertInScope(id, auth.getName(), privileged(auth));
        return R.ok(caseService.detail(id));
    }

    /** 案件移交（承办人变更，负责人操作） */
    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    @PostMapping("/{id}/transfer-owner")
    public R<CaseFile> transferOwner(@PathVariable Long id, @RequestBody Map<String, String> body,
                                     Authentication auth) {
        return R.ok(caseService.transferOwner(id, body.get("newOwner"), auth.getName()));
    }

    @PostMapping
    public R<CaseFile> create(@RequestBody CaseService.CaseCreateReq req, Authentication auth) {
        return R.ok(caseService.create(req, auth.getName()));
    }

    @PostMapping("/{id}/officers")
    public R<Void> addOfficer(@PathVariable Long id, @RequestBody CaseService.OfficerReq req) {
        caseService.addOfficer(id, req);
        return R.ok();
    }

    @PostMapping("/{id}/officers/{officerId}/avoid")
    public R<Void> avoidOfficer(@PathVariable Long id, @PathVariable Long officerId,
                                @RequestBody Map<String, String> body) {
        caseService.avoidOfficer(id, officerId, body.get("reason"),
                body.get("applicant"), body.get("decidedBy"));
        return R.ok();
    }

    @PostMapping("/{id}/evidences")
    public R<Void> addEvidence(@PathVariable Long id, @RequestBody cn.ybcase.bureau.service.EvidenceService.EvidenceReq req) {
        evidenceService.addEvidence(id, req);
        return R.ok();
    }

    @PostMapping("/{id}/evidences/{evidenceId}/hold-disposal")
    public R<Void> disposeHold(@PathVariable Long id, @PathVariable Long evidenceId,
                               @RequestBody Map<String, String> body) {
        evidenceService.disposeHold(id, evidenceId, body.get("disposal"));
        return R.ok();
    }

    /** 证据质证（辽24条） */
    @PostMapping("/{id}/evidences/{evidenceId}/cross-exam")
    public R<Void> crossExam(@PathVariable Long id, @PathVariable Long evidenceId,
                             @RequestBody Map<String, String> body) {
        evidenceService.crossExam(id, evidenceId, body.get("opinion"));
        return R.ok();
    }

    @PostMapping("/{id}/evidences/{evidenceId}/seal")
    public R<Void> updateSeal(@PathVariable Long id, @PathVariable Long evidenceId,
                              @RequestParam boolean extend) {
        evidenceService.updateSeal(id, evidenceId, extend);
        return R.ok();
    }

    @PostMapping("/{id}/documents")
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

    /** 案件大事记（全过程记录时间轴，第4/35条） */
    @GetMapping("/{id}/timeline")
    public R<List<Map<String, Object>>> timeline(@PathVariable Long id, Authentication auth) {
        caseService.assertInScope(id, auth.getName(), privileged(auth));
        return R.ok(documentService.timeline(id));
    }

    @PostMapping("/{id}/exclusions")
    public R<Void> addExclusion(@PathVariable Long id, @RequestBody CaseService.ExclusionReq req) {
        caseService.addExclusion(id, req);
        return R.ok();
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")  // 中止批准（局令：负责人职权）
    @PostMapping("/{id}/suspend")
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
    public R<CaseFile> terminate(@PathVariable Long id, @RequestBody Map<String, String> body,
                                 Authentication auth) {
        CaseFile r = caseService.terminate(id, body.get("reason"));
        approvalService.recordDirect("TERMINATE", id, body.get("reason"), auth.getName());
        return R.ok(r);
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")  // 延期批准（局令：负责人职权）
    @PostMapping("/{id}/extend")
    public R<CaseFile> extend(@PathVariable Long id, @RequestBody Map<String, Object> body,
                              Authentication auth) {
        int days = reqInt(body, "days", "延长天数");
        CaseFile r = caseService.extend(id, days, (String) body.get("reason"));
        approvalService.recordDirect("EXTEND", id,
                "延长" + days + "日：" + body.get("reason"), auth.getName());
        return R.ok(r);
    }

    @PostMapping("/{id}/report")
    public R<CaseFile> report(@PathVariable Long id, @RequestBody Map<String, String> body,
                              Authentication auth) {
        return R.ok(caseService.report(id, body.get("content"), auth.getName()));
    }

    @PostMapping("/{id}/reviews")
    public R<CaseReview> submitReview(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(caseService.submitReview(id, body.get("requiredReason")));
    }

    @PreAuthorize("hasAnyRole('LEGAL','ADMIN')")  // 法制审核由法制机构实施（第37条）
    @PostMapping("/reviews/{reviewId}")
    public R<CaseReview> doReview(@PathVariable Long reviewId, @RequestBody Map<String, String> body) {
        return R.ok(caseService.doReview(reviewId, body.get("reviewer"),
                body.get("opinionType"), body.get("opinion")));
    }

    @PostMapping("/{id}/notice")
    public R<CaseNotice> notify(@PathVariable Long id, @RequestBody CaseService.NoticeReq req) {
        return R.ok(caseService.notify(id, req));
    }

    @PostMapping("/{id}/statement")
    public R<CaseNotice> recordStatement(@PathVariable Long id, @RequestBody CaseService.StatementReq req) {
        return R.ok(caseService.recordStatement(id, req));
    }

    @PostMapping("/{id}/meetings")
    public R<Void> addMeeting(@PathVariable Long id, @RequestBody CaseService.MeetingReq req) {
        caseService.addMeeting(id, req);
        return R.ok();
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")  // 负责人审查决定（局令：负责人职权）
    @PostMapping("/{id}/decide")
    public R<CaseDecision> decide(@PathVariable Long id, @RequestBody CaseService.DecisionReq req) {
        return R.ok(caseService.decide(id, req));
    }

    /** 处罚决定公开（7日内，辽56条） */
    @PostMapping("/{id}/publish")
    public R<CaseDecision> publish(@PathVariable Long id) {
        return R.ok(caseService.publish(id));
    }

    /** 重大处罚决定政府备案（辽54条） */
    @PostMapping("/{id}/gov-record")
    public R<CaseDecision> govRecord(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(caseService.govRecord(id, body.get("recordNo")));
    }

    @PostMapping("/{id}/deliver")
    public R<CaseFile> deliver(@PathVariable Long id, @RequestBody CaseService.DeliveryReq req) {
        return R.ok(caseService.deliver(id, req));
    }

    @PostMapping("/{id}/executions")
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
    public R<CaseFile> approveDefer(@PathVariable Long id, Authentication auth) {
        CaseFile r = executionService.approveDefer(id);
        approvalService.recordDirect("DEFER", id, null, auth.getName());
        return R.ok(r);
    }

    @PostMapping("/{id}/court-enforce")
    public R<CaseFile> applyCourtEnforce(@PathVariable Long id) {
        return R.ok(executionService.applyCourtEnforce(id));
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")  // 结案批准（局令：负责人职权）
    @PostMapping("/{id}/close")
    public R<CaseFile> close(@PathVariable Long id, @RequestBody Map<String, String> body,
                             Authentication auth) {
        return R.ok(caseService.close(id, body.get("closeReport"), auth.getName()));
    }
}
