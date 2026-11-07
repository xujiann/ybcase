package cn.ybcase.bureau.web;

import static cn.ybcase.bureau.common.CaseScopeInterceptor.privileged;
import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.AgencyAgreementService;
import cn.ybcase.bureau.service.ApprovalService;
import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.ClueService;
import cn.ybcase.bureau.spi.MonitorFeedAdapter;
import cn.ybcase.bureau.spi.SignatureProvider;
import cn.ybcase.core.common.R;
import static cn.ybcase.bureau.common.ReqValues.*;
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
    private final cn.ybcase.bureau.service.MonitorGatewayImportService monitorGatewayImports;
    private final BureauConfig config;
    private final JdbcTemplate jdbc;
    private final cn.ybcase.bureau.service.CaseService caseService;
    private final cn.ybcase.bureau.service.DocumentService documentService;
    private final cn.ybcase.bureau.service.EvidenceCatalogService evidenceCatalogService;
    private final cn.ybcase.bureau.service.SourceAttachmentAccess sourceAccess;
    private final cn.ybcase.bureau.service.MaterialAccess materialAccess;
    private final cn.ybcase.core.security.AdminReadAccess adminReads;
    private final AgencyAgreementService agencyAgreements;


    // ---------- 电子签章 ----------

    @PostMapping("/cases/{caseId}/documents/{docId}/sign")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Map<String, Object>> sign(@PathVariable Long caseId, @PathVariable Long docId,
                                       @RequestBody Map<String, Object> body, Authentication auth) {
        cn.ybcase.bureau.service.CaseGuards.lockWritable(jdbc, caseId);
        var actor = materialAccess.requireCaseWriter(caseId, auth.getName());
        var rows = jdbc.queryForList(
                "select content from case_document where id = ? and case_id = ?", docId, caseId);
        if (rows.isEmpty()) throw new BizException(2045, "文书不存在");
        String signer = signatureText(body, "signer", auth.getName(), "签章人", 64);
        String role = signatureText(body, "signerRole", "OFFICER", "签章身份", 32);
        String content = (String) rows.get(0).get("content");
        if (signatureProvider instanceof cn.ybcase.bureau.spi.GatewaySignatureProvider) {
            if (!auth.getName().equals(signer) || !("OFFICER".equals(role)
                    || "LEADER".equals(role) && actor.roles().contains("LEADER")))
                throw new BizException(403, "外部签章须以当前账号及相应岗位签署");
            String hash = cn.ybcase.bureau.spi.GatewaySignatureProvider.contentHash(content);
            Integer existing = jdbc.queryForObject("""
                    select count(*) from doc_signature
                    where case_id=? and document_id=? and signer=? and signer_role=?
                      and provider='CA_GATEWAY' and content_hash=?
                    """, Integer.class, caseId, docId, signer, role, hash);
            if (existing != null && existing > 0) return R.ok(Map.of("provider", "CA_GATEWAY", "contentHash", hash));
        }
        var result = signatureProvider.sign(content, signer);
        // operator=登录用户：signer 可以是署名主体（如机构负责人），但谁点的签章必须可追溯
        jdbc.update("""
                insert into doc_signature (case_id, document_id, signer, signer_role, content_hash, signature, provider, operator)
                values (?,?,?,?,?,?,?,?)""",
                caseId, docId, signer, role, result.contentHash(), result.signature(), result.provider(),
                auth.getName());
        jdbc.update("update case_document set signed = true where id = ? and case_id = ?", docId, caseId);
        return R.ok(Map.of("provider", result.provider(), "contentHash", result.contentHash()));
    }

    /** 验签：文书当前内容与签章时摘要一致性（防篡改） */
    @GetMapping("/cases/{caseId}/documents/{docId}/verify")
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public R<List<Map<String, Object>>> verify(@PathVariable Long caseId, @PathVariable Long docId,
                                                Authentication auth) {
        materialAccess.requireCaseReader(caseId, auth.getName());
        var doc = jdbc.queryForList(
                "select content from case_document where id = ? and case_id = ?", docId, caseId);
        if (doc.isEmpty()) throw new BizException(2045, "文书不存在");
        String content = (String) doc.get(0).get("content");
        var sigs = jdbc.queryForList("select * from doc_signature where document_id = ? and case_id = ? order by id", docId, caseId);
        for (var s : sigs) {
            s.put("valid", signatureProvider.verify(content,
                    (String) s.get("content_hash"), (String) s.get("signature"),
                    (String) s.get("signer"), (String) s.get("provider")));
        }
        return R.ok(sigs);
    }

    private static String signatureText(Map<String, Object> body, String key, String fallback, String label, int max) {
        Object value = body == null ? null : body.getOrDefault(key, fallback);
        if (!(value instanceof String text) || text.isBlank()) throw new BizException(2100, "请填写" + label);
        if (text.indexOf('\0') >= 0) throw new BizException(2100, label + "含无效字符");
        if (text.codePointCount(0, text.length()) > max) throw new BizException(2100, label + "不能超过" + max + "个字符");
        return text;
    }

    // ---------- 审批单据 ----------

    @PostMapping("/approvals")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Map<String, Object>> apply(@RequestBody ApprovalService.ApplyReq req, Authentication auth) {
        return R.ok(Map.of("id", approvalService.apply(req, auth.getName(), privileged(auth))));
    }

    @GetMapping("/approvals/pending")
    public R<List<Map<String, Object>>> pending(Authentication auth, @RequestParam(required = false) Long focusId) {
        return R.ok(approvalService.pendingFor(auth.getName(), focusId));
    }

    @GetMapping("/cases/{caseId}/approvals")
    public R<List<Map<String, Object>>> byCase(@PathVariable Long caseId) {
        return R.ok(approvalService.byCase(caseId));
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    @PostMapping("/approvals/{id}/decide")
    public R<Map<String, Object>> decide(@PathVariable long id, @RequestBody Map<String, Object> body,
                                         Authentication auth) {
        if (body == null || !(body.get("approve") instanceof Boolean approve))
            throw new BizException(2071, "须明确选择批准或驳回，approve 须为布尔值");
        if (!(body.get("opinion") instanceof String opinion) || opinion.isBlank())
            throw new BizException(2071, "须填写审批意见或驳回理由");
        return R.ok(approvalService.decide(id, approve, opinion, auth.getName()));
    }

    // ---------- 文书模板管理（改模板留历史） ----------

    @GetMapping("/doc-templates")
    public R<List<Map<String, Object>>> templates() {
        return R.ok(jdbc.queryForList("select * from doc_template order by doc_type"));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/doc-templates/{docType}")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> updateTemplate(@PathVariable String docType, @RequestBody Map<String, Object> body,
                                  Authentication auth) {
        requireTemplateType(docType);
        var old = jdbc.queryForList("select * from doc_template where doc_type = ? for update", docType);
        if (old.isEmpty()) throw new BizException(2053, "模板不存在");
        var actor = materialAccess.lockActor(auth.getName(), false);
        if (!actor.roles().contains("ADMIN")) throw new BizException(403, "当前岗位无权维护文书模板");
        String title = templateText(body, "titleTpl", "模板标题", 255);
        String content = templateText(body, "contentTpl", "模板正文", Integer.MAX_VALUE);
        jdbc.update("""
                insert into doc_template_history (doc_type, title_tpl, content_tpl, saved_by)
                values (?,?,?,?)""",
                docType, old.get(0).get("title_tpl"), old.get(0).get("content_tpl"), auth.getName());
        jdbc.update("update doc_template set title_tpl = ?, content_tpl = ?, updated_at = now() where doc_type = ?",
                title, content, docType);
        return R.ok();
    }

    @GetMapping("/doc-templates/{docType}/history")
    public R<List<Map<String, Object>>> templateHistory(@PathVariable String docType) {
        requireTemplateType(docType);
        return R.ok(jdbc.queryForList(
                "select * from doc_template_history where doc_type = ? order by id desc limit 20", docType));
    }

    private static void requireTemplateType(String type) {
        if (type == null || type.isBlank() || type.indexOf('\0') >= 0 || type.codePointCount(0, type.length()) > 32)
            throw new BizException(2053, "模板类型无效");
    }

    private static String templateText(Map<String, Object> body, String key, String label, int max) {
        Object value = body == null ? null : body.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new BizException(2100, "请填写" + label);
        if (text.indexOf('\0') >= 0 || text.codePointCount(0, text.length()) > max)
            throw new BizException(2100, label + "含无效字符或超过长度限制");
        return text;
    }

    // ---------- 送达泛化（告知书/听证通知等任意文书的送达与回证） ----------

    @PostMapping("/cases/{caseId}/doc-deliveries")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> deliverDocument(@PathVariable Long caseId, @RequestBody Map<String, Object> body) {
        cn.ybcase.bureau.service.CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        String method = body.get("method") instanceof String value ? value : null;
        if (method == null || !List.of("DIRECT", "MAIL", "LEFT", "ELECTRONIC", "ANNOUNCE").contains(method))
            throw new BizException(2041, "送达方式无效");
        Integer caseExists = jdbc.queryForObject("select count(*) from case_file where id = ?", Integer.class, caseId);
        if (caseExists == null || caseExists == 0) throw new BizException(2043, "案件不存在");
        Long documentId = deliveryDocumentId(body.get("documentId"));
        boolean decisionDocument = "DECISION".equals(body.get("docKind"));
        if (documentId != null) {
            Integer own = jdbc.queryForObject(
                    "select count(*) from case_document where id = ? and case_id = ?", Integer.class, documentId, caseId);
            if (own == null || own == 0) throw new BizException(2041, "所选文书不属于本案");
            decisionDocument |= "DECISION".equals(jdbc.queryForObject(
                    "select doc_type from case_document where id=? and case_id=?", String.class, documentId, caseId));
        }
        if ("ELECTRONIC".equals(method)) {
            if (decisionDocument && !Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from sys_config where cfg_key='delivery_electronic_decision_allowed' and cfg_value='true')", Boolean.class)))
                throw new BizException(2049, "当前规则不允许电子送达处罚决定书");
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
                dateOrToday(body, "deliveredAt", "送达日期"),
                deliveryText(body, "receiver", null, "受送达人", 64, false),
                deliveryText(body, "note", null, "送达备注", 255, false),
                deliveryText(body, "receiptNo", null, "回证编号", 64, false),
                optDate(body, "receiptSignedAt", "回证签收日期"),
                deliveryText(body, "docKind", "OTHER", "送达文书类型", 24, true), documentId);
        return R.ok();
    }

    private static Long deliveryDocumentId(Object value) {
        if (value == null || value instanceof String text && text.isBlank()) return null;
        try {
            long id;
            if (value instanceof Number number) id = new java.math.BigDecimal(number.toString()).longValueExact();
            else if (value instanceof String text) id = Long.parseLong(text.trim());
            else throw new NumberFormatException();
            if (id > 0) return id;
        } catch (NumberFormatException | ArithmeticException ignored) { }
        throw new BizException(2100, "文书编号须为正整数");
    }

    private static String deliveryText(Map<String, Object> body, String key, String fallback,
                                       String label, int max, boolean required) {
        Object value = body.getOrDefault(key, fallback);
        if (value == null && !required) return null;
        if (!(value instanceof String text) || required && text.isBlank()) throw new BizException(2100, "请填写正确的" + label);
        if (text.indexOf('\0') >= 0) throw new BizException(2100, label + "含无效字符");
        if (text.codePointCount(0, text.length()) > max) throw new BizException(2100, label + "不能超过" + max + "个字符");
        return text;
    }

    // ---------- 协议处理台账（联动经办） ----------

    @GetMapping("/agency-departments")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<List<AgencyAgreementService.Department>> agencyDepartments(Authentication auth) {
        return R.ok(agencyAgreements.departments(auth.getName()));
    }

    @PostMapping("/cases/{caseId}/agreement-actions")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> addAgreementAction(@PathVariable Long caseId, @RequestBody Map<String, String> body) {
        cn.ybcase.bureau.service.CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        if (body.get("action") == null || !List.of("INTERVIEW", "SUSPEND_AGREEMENT", "TERMINATE_AGREEMENT", "REFUSE_PAY", "RECOVER")
                .contains(body.get("action")))
            throw new BizException(2073, "协议处理类型须为 约谈/暂停协议/解除协议/拒付/追回");
        Long agencyDeptId = optionalAgencyDepartment(body.get("agencyDeptId"));
        LocalDate sentAt = dateOrToday(body, "sentAt", "发出日期");
        if (agencyDeptId == null) {
            jdbc.update("""
                    insert into agreement_action (case_id, action, org, sent_at, note)
                    values (?,?,?,?,?)""", caseId, body.get("action"), body.getOrDefault("org", "医保经办机构"),
                    sentAt, body.get("note"));
        } else {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            agencyAgreements.dispatch(caseId, body.get("action"), agencyDeptId, sentAt,
                    optDate(body, "dueAt", "办理期限"), body.get("note"), auth == null ? null : auth.getName());
        }
        return R.ok();
    }

    private static Long optionalAgencyDepartment(String raw) {
        if (raw == null) return null;
        try {
            long id = Long.parseLong(raw);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Return the same business error for every invalid department identifier.
        }
        throw new BizException(2073, "请选择有效的经办部门");
    }

    @GetMapping("/agreement-actions/{id}/history")
    @PreAuthorize("hasAnyRole('HANDLER','LEGAL','LEADER','ADMIN')")
    public R<List<AgencyAgreementService.Event>> agreementHistory(@PathVariable long id, Authentication auth) {
        return R.ok(agencyAgreements.history(id, auth.getName()));
    }

    @PostMapping("/agreement-actions/{id}/reassign")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> reassignAgreement(@PathVariable long id, @RequestBody AgencyAgreementService.Reassignment body,
                                      Authentication auth) {
        scopeByChild("agreement_action", id, 2073, "台账不存在", auth, true);
        agencyAgreements.reassign(id, body, auth.getName());
        return R.ok();
    }

    @PostMapping("/agreement-actions/{id}/remind")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> remindAgreement(@PathVariable long id, @RequestBody Map<String, String> body, Authentication auth) {
        scopeByChild("agreement_action", id, 2073, "台账不存在", auth, true);
        agencyAgreements.remind(id, reqInt(body, "cycle", "分派轮次"), auth.getName());
        return R.ok();
    }

    @PostMapping("/agreement-actions/{id}/reply")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> replyAgreementAction(@PathVariable Long id, @RequestBody Map<String, String> body,
                                        Authentication auth) {
        // 该路由按子表主键定位、不含 caseId，拦截器覆盖不到，此处按所属案件补范围校验
        scopeByChild("agreement_action", id, 2073, "台账不存在", auth);
        String result = body.get("result");
        // An empty receipt must not consume the one permitted reply and prevent later completion.
        if (result == null || result.isBlank()) throw new BizException(2073, "须填写经办办理结果");
        int n = jdbc.update("""
                update agreement_action set replied_at = ?, result = ?
                where id = ? and replied_at is null and agency_dept_id is null
                """, LocalDate.now(), result, id);
        if (n == 0) throw new BizException(2073, "台账不存在、已办结或应由经办部门回复");
        return R.ok();
    }

    /** Child routes must resolve an authorized parent; clue-only transfers use source handling scope. */
    private void scopeByChild(String table, Long id, int code, String notFound, Authentication auth) {
        scopeByChild(table, id, code, notFound, auth, false);
    }

    private void scopeByChild(String table, Long id, int code, String notFound, Authentication auth, boolean followup) {
        var rows = jdbc.queryForList("select case_id from " + table + " where id = ?", id);
        if (rows.isEmpty()) throw new BizException(code, notFound);
        Object cid = rows.get(0).get("case_id");
        if (cid != null) {
            lockChildCase(((Number) cid).longValue(), followup);
            materialAccess.requireCaseWriter(((Number) cid).longValue(), auth.getName());
            if ("case_transfer".equals(table)) {
                Integer consistent = jdbc.queryForObject("""
                        select count(*) from case_transfer t join case_file c on c.id=t.case_id
                        where t.id=? and t.case_id=? and (t.clue_id is null or t.clue_id=c.clue_id)
                        """, Integer.class, id, ((Number) cid).longValue());
                if (consistent == null || consistent != 1)
                    throw new BizException(2059, "移送关联线索与案件来源不一致");
            }
        } else if ("case_transfer".equals(table)) {
            var clueIds = jdbc.queryForList("select clue_id from case_transfer where id=?", id);
            if (!(clueIds.get(0).get("clue_id") instanceof Number clueId))
                throw new BizException(code, "移送记录缺少有效来源");
            sourceAccess.lockClueForHandling(clueId.longValue(), auth.getName());
            sourceAccess.assertRead("CLUE", clueId.longValue(), auth.getName());
            for (var linked : jdbc.queryForList("select id from case_file where clue_id=? order by id", clueId.longValue()))
                lockChildCase(((Number) linked.get("id")).longValue(), followup);
            materialAccess.lockActor(auth.getName(), true);
            // Source/case locks are held before the live actor lock; recheck any scope that relied on old roles.
            sourceAccess.lockClueForHandling(clueId.longValue(), auth.getName());
            sourceAccess.assertRead("CLUE", clueId.longValue(), auth.getName());
        } else {
            throw new BizException(code, "记录缺少有效案件来源");
        }
    }

    // ---------- 行刑衔接回执 ----------

    @PostMapping("/transfers/{id}/receipt")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> transferReceipt(@PathVariable Long id, @RequestBody Map<String, String> body,
                                   Authentication auth) {
        if (body.get("receiptNo") == null || body.get("receiptNo").isBlank())
            throw new BizException(2059, "须填写受案回执文号");
        scopeByChild("case_transfer", id, 2059, "移送记录不存在", auth);
        int n = jdbc.update("""
                update case_transfer set receipt_no = ?, receipt_at = ?
                where id = ? and receipt_no is null""",
                body.get("receiptNo"), LocalDate.now(), id);
        if (n == 0) throw new BizException(2059, "移送记录不存在或已登记受案回执（不可覆盖）");
        return R.ok();
    }

    private void lockChildCase(long caseId, boolean followup) {
        if (followup) cn.ybcase.bureau.service.CaseGuards.lock(jdbc, caseId);
        else cn.ybcase.bureau.service.CaseGuards.lockWritable(jdbc, caseId);
    }

    public record TransferJudgment(String number, LocalDate date, String result) {}

    @PostMapping("/transfers/{id}/judgment")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> transferJudgment(@PathVariable Long id, @RequestBody TransferJudgment body, Authentication auth) {
        if (!judgmentText(body.number(), 128) || !judgmentText(body.result(), 4000)
                || body.date() == null || body.date().isAfter(LocalDate.now()))
            throw new BizException(2059, "请填写有效的判决文号、日期及结果");
        scopeByChild("case_transfer", id, 2059, "移送记录不存在", auth, true);
        int updated = jdbc.update("""
                insert into case_transfer_judgment (transfer_id, judgment_no, judgment_at, judgment_result)
                select id, ?, ?, ? from case_transfer
                where id = ? and direction = 'OUT' and kind = 'JUDICIAL' and sent_at <= ?
                on conflict (transfer_id) do nothing
                """, body.number().strip(), body.date(), body.result().strip(), id, body.date());
        if (updated == 0)
            throw new BizException(2059, "仅可为司法移出记录登记一次判决，判决日期不得早于移送日期");
        return R.ok();
    }

    private static boolean judgmentText(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.indexOf(0) < 0;
    }

    // ---------- 全局搜索 ----------

    @GetMapping("/search")
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public R<Map<String, Object>> search(@RequestParam String q, Authentication auth) {
        if (q == null || q.trim().length() < 2) throw new BizException(2074, "关键词至少2个字符");
        var actor = materialAccess.readActor(auth.getName());
        if (actor.roles().stream().noneMatch(java.util.Set.of("HANDLER", "LEGAL", "LEADER", "ADMIN")::contains))
            throw new BizException(403, "当前岗位无权检索医保业务");
        boolean livePrivileged = actor.roles().stream().anyMatch(java.util.Set.of("LEGAL", "LEADER", "ADMIN")::contains);
        String like = "%" + q.trim() + "%";
        var clueScope = actor.district() ? sourceAccess.clueReadPredicate("case_clue.id", auth.getName())
                : new cn.ybcase.bureau.service.SourceAttachmentAccess.ReadPredicate("true", List.of());
        var clueArgs = new java.util.ArrayList<Object>(List.of(like, like, like));
        clueArgs.addAll(clueScope.args());
        return R.ok(Map.of(
                "cases", caseService.searchCases(like, auth.getName(), livePrivileged),
                "clues", jdbc.queryForList("""
                        select id, clue_no, suspect_name, status, received_at from case_clue
                        where (clue_no ilike ? or suspect_name ilike ? or content ilike ?) and
                        """ + clueScope.sql() + " order by id desc limit 20", clueArgs.toArray())));
    }

    // ---------- 审计导出（等保：日志定期归档，只追加不修改） ----------

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/audit/export")
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ResponseEntity<byte[]> auditExport(@RequestParam String month) {
        adminReads.requireCurrentAdmin();
        // 审计表只增不删、月导出是等保例行操作：to_char 包住列会使 idx_audit_created 失效而全表扫描，
        // 改月初区间谓词命中索引；月份格式在此显式校验（yyyy-MM）
        if (month == null || !month.matches("[0-9]{4}-[0-9]{2}"))
            throw new BizException(2100, "月份格式须为 yyyy-MM（收到：" + month + "）");
        LocalDate monthStart;
        try {
            var period=java.time.YearMonth.parse(month);
            if(period.getYear()<1) throw new java.time.DateTimeException("Invalid audit year");
            monthStart=period.atDay(1);
        }catch(java.time.DateTimeException ex){throw new BizException(2100,"月份须为有效的 yyyy-MM");}
        // request_id 是用户反馈直报 ↔ 服务端记录对账的钥匙（V11），导出缺它则报障请求号在唯一能拿到的审计文件里对不上
        var rows = jdbc.queryForList("""
                select id, username, method, path, http_status, client_ip, created_at, request_id
                from sys_audit_log where created_at >= ? and created_at < ? order by id""",
                monthStart, monthStart.plusMonths(1));
        StringBuilder sb = new StringBuilder("﻿id,用户,方法,路径,状态码,IP,时间,请求号\n");
        for (var r : rows) {
            sb.append(java.util.stream.Stream.of("id","username","method","path","http_status","client_ip","created_at","request_id")
                    .map(key -> cn.ybcase.bureau.common.Csv.cell(r.get(key))).collect(java.util.stream.Collectors.joining(","))).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit-" + month + ".csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=utf-8"))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 智能监控拉取（SPI，实施期换真实适配器） ----------

    @PostMapping("/clues/fetch-monitor")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Map<String, Object>> fetchMonitor(Authentication auth) {
        if (monitorFeedAdapter instanceof cn.ybcase.bureau.spi.GatewayMonitorFeedAdapter gateway)
            return R.ok(monitorGatewayImports.pull(gateway, auth.getName()));
        var suspects = monitorFeedAdapter.fetchSuspects();
        if (suspects.isEmpty()) return R.ok(Map.of("fetched",0,"clueNos",List.of(),"adapter",monitorFeedAdapter.getClass().getSimpleName()));
        var imported = clueService.importMonitorForCurrentActor(suspects, auth.getName());
        return R.ok(Map.of("fetched",imported.get("imported"),"clueNos",imported.get("clueNos"),
                "adapter",monitorFeedAdapter.getClass().getSimpleName()));
    }

    // ---------- 卷宗合成（全部文书全文+签章，前端一次打印成册） ----------

    @GetMapping("/cases/{caseId}/archive-full")
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public R<Map<String, Object>> archiveFull(@PathVariable Long caseId, Authentication auth) {
        materialAccess.requireCaseReader(caseId, auth.getName());
        var cf = jdbc.queryForList("select case_no, name, archive_no, closed_at from case_file where id = ?", caseId);
        if (cf.isEmpty()) throw new BizException(2043, "案件不存在");
        // 与卷内目录同一份数据（含由结构化记录渲染的立案审批表/法制审核意见/集体讨论/
        // 听证笔录/送达回证），且已按法定装订顺序排好序——否则合成打印出来的"卷宗"缺这五类必备件
        var docs = documentService.archiveEntries(caseId);
        var sigs = jdbc.queryForList("select * from doc_signature where case_id = ? order by id", caseId);
        return R.ok(Map.of("caseFile", cf.get(0), "documents", docs, "signatures", sigs,
                "evidenceCatalog", evidenceCatalogService.catalog(caseId, false),
                "orgName", config.str("org_name", "医疗保障局")));
    }

}
