package cn.ybcase.bureau.web;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.ApprovalService;
import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.ClueService;
import cn.ybcase.bureau.spi.MonitorFeedAdapter;
import cn.ybcase.bureau.spi.SignatureProvider;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** v1.1 正式化能力：签章、审批单据、模板管理、文书送达、协议处理、搜索、审计导出、监控拉取、卷宗合成 */
@RestController
@RequestMapping("/api/bureau")
@RequiredArgsConstructor
public class FormalizationController {

    private final SignatureProvider signatureProvider;
    private final ApprovalService approvalService;
    private final ClueService clueService;
    private final MonitorFeedAdapter monitorFeedAdapter;
    private final BureauConfig config;
    private final JdbcTemplate jdbc;
    private final cn.ybcase.bureau.service.CaseService caseService;

    private static boolean privileged(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_LEADER") || a.getAuthority().equals("ROLE_LEGAL")
                        || a.getAuthority().equals("ROLE_ADMIN"));
    }

    // ---------- 电子签章 ----------

    @PostMapping("/cases/{caseId}/documents/{docId}/sign")
    public R<Map<String, Object>> sign(@PathVariable Long caseId, @PathVariable Long docId,
                                       @RequestBody Map<String, String> body, Authentication auth) {
        caseService.assertInScope(caseId, auth.getName(), privileged(auth));
        var rows = jdbc.queryForList(
                "select content from case_document where id = ? and case_id = ?", docId, caseId);
        if (rows.isEmpty()) throw new BizException(2045, "文书不存在");
        String signer = body.getOrDefault("signer", auth.getName());
        String role = body.getOrDefault("signerRole", "OFFICER");
        var result = signatureProvider.sign((String) rows.get(0).get("content"), signer);
        // operator=登录用户：signer 可以是署名主体（如机构负责人），但谁点的签章必须可追溯
        jdbc.update("""
                insert into doc_signature (case_id, document_id, signer, signer_role, content_hash, signature, provider, operator)
                values (?,?,?,?,?,?,?,?)""",
                caseId, docId, signer, role, result.contentHash(), result.signature(), result.provider(),
                auth.getName());
        jdbc.update("update case_document set signed = true where id = ?", docId);
        return R.ok(Map.of("provider", result.provider(), "contentHash", result.contentHash()));
    }

    /** 验签：文书当前内容与签章时摘要一致性（防篡改） */
    @GetMapping("/cases/{caseId}/documents/{docId}/verify")
    public R<List<Map<String, Object>>> verify(@PathVariable Long caseId, @PathVariable Long docId,
                                                Authentication auth) {
        caseService.assertInScope(caseId, auth.getName(), privileged(auth));
        var doc = jdbc.queryForList(
                "select content from case_document where id = ? and case_id = ?", docId, caseId);
        if (doc.isEmpty()) throw new BizException(2045, "文书不存在");
        String content = (String) doc.get(0).get("content");
        var sigs = jdbc.queryForList("select * from doc_signature where document_id = ? order by id", docId);
        for (var s : sigs) {
            s.put("valid", signatureProvider.verify(content,
                    (String) s.get("content_hash"), (String) s.get("signature")));
        }
        return R.ok(sigs);
    }

    // ---------- 审批单据 ----------

    @PostMapping("/approvals")
    public R<Map<String, Object>> apply(@RequestBody ApprovalService.ApplyReq req, Authentication auth) {
        return R.ok(Map.of("id", approvalService.apply(req, auth.getName())));
    }

    @GetMapping("/approvals/pending")
    public R<List<Map<String, Object>>> pending(Authentication auth) {
        boolean decider = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_LEADER") || a.getAuthority().equals("ROLE_ADMIN"));
        return R.ok(approvalService.pending(decider ? null : auth.getName()));
    }

    @GetMapping("/cases/{caseId}/approvals")
    public R<List<Map<String, Object>>> byCase(@PathVariable Long caseId) {
        return R.ok(approvalService.byCase(caseId));
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    @PostMapping("/approvals/{id}/decide")
    public R<Map<String, Object>> decide(@PathVariable long id, @RequestBody Map<String, Object> body,
                                         Authentication auth) {
        return R.ok(approvalService.decide(id, Boolean.TRUE.equals(body.get("approve")),
                (String) body.get("opinion"), auth.getName()));
    }

    // ---------- 文书模板管理（改模板留历史） ----------

    @GetMapping("/doc-templates")
    public R<List<Map<String, Object>>> templates() {
        return R.ok(jdbc.queryForList("select * from doc_template order by doc_type"));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/doc-templates/{docType}")
    public R<Void> updateTemplate(@PathVariable String docType, @RequestBody Map<String, String> body,
                                  Authentication auth) {
        var old = jdbc.queryForList("select * from doc_template where doc_type = ?", docType);
        if (old.isEmpty()) throw new BizException(2053, "模板不存在");
        jdbc.update("""
                insert into doc_template_history (doc_type, title_tpl, content_tpl, saved_by)
                values (?,?,?,?)""",
                docType, old.get(0).get("title_tpl"), old.get(0).get("content_tpl"), auth.getName());
        jdbc.update("update doc_template set title_tpl = ?, content_tpl = ?, updated_at = now() where doc_type = ?",
                body.get("titleTpl"), body.get("contentTpl"), docType);
        return R.ok();
    }

    @GetMapping("/doc-templates/{docType}/history")
    public R<List<Map<String, Object>>> templateHistory(@PathVariable String docType) {
        return R.ok(jdbc.queryForList(
                "select * from doc_template_history where doc_type = ? order by id desc limit 20", docType));
    }

    // ---------- 送达泛化（告知书/听证通知等任意文书的送达与回证） ----------

    @PostMapping("/cases/{caseId}/doc-deliveries")
    public R<Void> deliverDocument(@PathVariable Long caseId, @RequestBody Map<String, Object> body) {
        String method = (String) body.get("method");
        if (!List.of("DIRECT", "MAIL", "LEFT", "ELECTRONIC", "ANNOUNCE").contains(method))
            throw new BizException(2041, "送达方式无效");
        if ("ELECTRONIC".equals(method)) {
            Boolean consent = jdbc.queryForObject(
                    "select e_delivery_consent from case_file where id = ?", Boolean.class, caseId);
            if (!Boolean.TRUE.equals(consent))
                throw new BizException(2054, "当事人未签订电子送达确认书（第59条）");
        }
        jdbc.update("""
                insert into case_delivery (case_id, method, delivered_at, receiver, note, receipt_no,
                                           receipt_signed_at, doc_kind, document_id)
                values (?,?,?,?,?,?,?,?,?)""",
                caseId, method,
                body.get("deliveredAt") == null ? LocalDate.now() : LocalDate.parse((String) body.get("deliveredAt")),
                body.get("receiver"), body.get("note"), body.get("receiptNo"),
                body.get("receiptSignedAt") == null ? null : LocalDate.parse((String) body.get("receiptSignedAt")),
                body.getOrDefault("docKind", "OTHER"),
                body.get("documentId") == null ? null : ((Number) body.get("documentId")).longValue());
        return R.ok();
    }

    // ---------- 协议处理台账（联动经办） ----------

    @PostMapping("/cases/{caseId}/agreement-actions")
    public R<Void> addAgreementAction(@PathVariable Long caseId, @RequestBody Map<String, String> body) {
        if (!List.of("INTERVIEW", "SUSPEND_AGREEMENT", "TERMINATE_AGREEMENT", "REFUSE_PAY", "RECOVER")
                .contains(body.get("action")))
            throw new BizException(2073, "协议处理类型须为 约谈/暂停协议/解除协议/拒付/追回");
        jdbc.update("""
                insert into agreement_action (case_id, action, org, sent_at, note)
                values (?,?,?,?,?)""",
                caseId, body.get("action"), body.getOrDefault("org", "医保经办机构"),
                body.get("sentAt") == null ? LocalDate.now() : LocalDate.parse(body.get("sentAt")),
                body.get("note"));
        return R.ok();
    }

    @PostMapping("/agreement-actions/{id}/reply")
    public R<Void> replyAgreementAction(@PathVariable Long id, @RequestBody Map<String, String> body) {
        int n = jdbc.update("update agreement_action set replied_at = ?, result = ? where id = ? and replied_at is null",
                LocalDate.now(), body.get("result"), id);
        if (n == 0) throw new BizException(2073, "台账不存在或已办结");
        return R.ok();
    }

    // ---------- 行刑衔接回执 ----------

    @PostMapping("/transfers/{id}/receipt")
    public R<Void> transferReceipt(@PathVariable Long id, @RequestBody Map<String, String> body) {
        if (body.get("receiptNo") == null || body.get("receiptNo").isBlank())
            throw new BizException(2059, "须填写受案回执文号");
        int n = jdbc.update("update case_transfer set receipt_no = ?, receipt_at = ? where id = ?",
                body.get("receiptNo"), LocalDate.now(), id);
        if (n == 0) throw new BizException(2059, "移送记录不存在");
        return R.ok();
    }

    // ---------- 全局搜索 ----------

    @GetMapping("/search")
    public R<Map<String, Object>> search(@RequestParam String q, Authentication auth) {
        if (q == null || q.trim().length() < 2) throw new BizException(2074, "关键词至少2个字符");
        String like = "%" + q.trim() + "%";
        return R.ok(Map.of(
                "cases", caseService.searchCases(like, auth.getName(), privileged(auth)),
                "clues", jdbc.queryForList("""
                        select id, clue_no, suspect_name, status, received_at from case_clue
                        where clue_no ilike ? or suspect_name ilike ? or content ilike ?
                        order by id desc limit 20""", like, like, like)));
    }

    // ---------- 审计导出（等保：日志定期归档，只追加不修改） ----------

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/audit/export")
    public ResponseEntity<byte[]> auditExport(@RequestParam String month) {
        var rows = jdbc.queryForList("""
                select id, username, method, path, http_status, client_ip, created_at
                from sys_audit_log where to_char(created_at, 'YYYY-MM') = ? order by id""", month);
        StringBuilder sb = new StringBuilder("﻿id,用户,方法,路径,状态码,IP,时间\n");
        for (var r : rows) {
            sb.append(r.get("id")).append(',').append(nv(r.get("username"))).append(',')
              .append(r.get("method")).append(',').append(nv(r.get("path"))).append(',')
              .append(r.get("http_status")).append(',').append(nv(r.get("client_ip"))).append(',')
              .append(r.get("created_at")).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit-" + month + ".csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=utf-8"))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 智能监控拉取（SPI，实施期换真实适配器） ----------

    @PostMapping("/clues/fetch-monitor")
    public R<Map<String, Object>> fetchMonitor(Authentication auth) {
        var suspects = monitorFeedAdapter.fetchSuspects();
        List<String> nos = new ArrayList<>();
        for (var s : suspects) {
            var clue = clueService.create(new ClueService.ClueCreateReq(
                    "MONITOR", s.get("content"), s.get("suspectName"),
                    s.getOrDefault("suspectType", "PROVIDER"), LocalDate.now(), s.get("handler")), auth.getName());
            nos.add(clue.getClueNo());
        }
        return R.ok(Map.of("fetched", nos.size(), "clueNos", nos,
                "adapter", monitorFeedAdapter.getClass().getSimpleName()));
    }

    // ---------- 卷宗合成（全部文书全文+签章，前端一次打印成册） ----------

    @GetMapping("/cases/{caseId}/archive-full")
    public R<Map<String, Object>> archiveFull(@PathVariable Long caseId, Authentication auth) {
        caseService.assertInScope(caseId, auth.getName(), privileged(auth));
        var cf = jdbc.queryForList("select case_no, name, archive_no, closed_at from case_file where id = ?", caseId);
        if (cf.isEmpty()) throw new BizException(2043, "案件不存在");
        var docs = jdbc.queryForList("select * from case_document where case_id = ?", caseId);
        var sigs = jdbc.queryForList("select * from doc_signature where case_id = ? order by id", caseId);
        return R.ok(Map.of("caseFile", cf.get(0), "documents", docs, "signatures", sigs,
                "orgName", config.str("org_name", "医疗保障局")));
    }

    private static String nv(Object v) {
        return v == null ? "" : String.valueOf(v).replace(",", "，");
    }
}
