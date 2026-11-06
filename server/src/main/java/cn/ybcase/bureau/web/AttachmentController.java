package cn.ybcase.bureau.web;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Case attachments share DB/FILE storage with source materials; case locks protect the sealed file. */
@RestController
@RequestMapping("/api/bureau/cases")
@RequiredArgsConstructor
public class AttachmentController {

    private final JdbcTemplate jdbc;
    private final cn.ybcase.bureau.service.AttachmentStorage storage;
    private final cn.ybcase.bureau.service.SourceAttachmentAccess access;
    private final cn.ybcase.bureau.service.MaterialAccess materials;

    @PostMapping("/{id}/attachments")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public R<Void> upload(@PathVariable Long id, @RequestParam("file") MultipartFile file,
                          @RequestParam(required = false) Long documentId,
                          @RequestParam(required = false) Long evidenceId,
                          @RequestParam(defaultValue = "DOC_SCAN") String category,
                          Authentication auth) throws IOException {
        cn.ybcase.bureau.service.CaseGuards.lockWritable(jdbc, id);
        materials.requireCaseWriter(id, auth.getName());
        if (documentId != null && jdbc.queryForObject("select count(*) from case_document where id = ? and case_id = ?", Integer.class, documentId, id) == 0)
            throw new BizException(2143, "关联文书不存在或不属于本案");
        if (evidenceId != null && jdbc.queryForObject("select count(*) from case_evidence where id = ? and case_id = ?", Integer.class, evidenceId, id) == 0)
            throw new BizException(2143, "关联证据不存在或不属于本案");
        storage.store(id, null, null, documentId, evidenceId, file, category, auth.getName());
        return R.ok();
    }

    /** 已作废附件默认不出列表；仅负责人/管理员可用 includeVoided=true 连同作废记录一起查（复核误传处置） */
    @GetMapping("/{id}/attachments")
    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public R<List<Map<String, Object>>> list(@PathVariable Long id,
                                             @RequestParam(defaultValue = "false") boolean includeVoided,
                                             Authentication auth) {
        materials.readActor(auth.getName());
        access.assertCaseScope(id, auth.getName());
        boolean withVoided = includeVoided && canManageVoided(auth);
        return R.ok(jdbc.queryForList("""
                select id, case_id, source_clue_id, source_inspection_id, document_id, evidence_id, filename, content_type, size_bytes, category,
                       (file_path is not null) as external, uploaded_by, uploaded_at,
                       voided, void_reason, voided_by, voided_at
                from case_attachment where case_id = ? and (voided = false or ?) order by id""", id, withVoided));
    }

    /**
     * 附件作废（误传他案材料/传错版本）：只打标不删文件与 bytea——执法留痕要求作废记录可追溯；
     * 已归档卷宗不得变动；作废权限限上传人本人或负责人/管理员。
     */
    @PostMapping("/{id}/attachments/{attachmentId}/void")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> voidAttachment(@PathVariable Long id, @PathVariable Long attachmentId,
                                  @RequestBody(required = false) Map<String, Object> body, Authentication auth) {
        cn.ybcase.bureau.service.CaseGuards.lockWritable(jdbc, id);
        materials.requireCaseWriter(id, auth.getName());
        if (body == null || !(body.get("reason") instanceof String reason) || reason.isBlank() || reason.length() > 2000 || reason.indexOf('\0') >= 0)
            throw new BizException(2130, "须填写2000字以内作废理由");
        var rows = jdbc.queryForList(
                "select case_id, uploaded_by, voided from case_attachment where id = ?", attachmentId);
        if (rows.isEmpty() || !(rows.get(0).get("case_id") instanceof Number caseId) || caseId.longValue() != id)
            throw new BizException(2134, "附件不存在或不属于本案");
        var cf = jdbc.queryForList("select status, archive_no from case_file where id = ?", id);
        if (cf.isEmpty()) throw new BizException(2043, "案件不存在");
        if ("CLOSED".equals(cf.get(0).get("status")) || cf.get(0).get("archive_no") != null)
            throw new BizException(2131, "案件已立卷归档，卷内材料不得再作废");
        if (Boolean.TRUE.equals(rows.get(0).get("voided"))) throw new BizException(2132, "该附件已作废");
        if (!auth.getName().equals(rows.get(0).get("uploaded_by")) && !canManageVoided(auth))
            throw new BizException(2133, "仅上传人本人或负责人/管理员可作废附件");
        int n = jdbc.update("""
                update case_attachment set voided = true, void_reason = ?, voided_by = ?, voided_at = now()
                where id = ? and voided = false""", reason.trim(), auth.getName(), attachmentId);
        if (n == 0) throw new BizException(2132, "该附件已作废");   // 并发双击兜底
        return R.ok();
    }

    /** 作废附件的查看/下载权限：负责人或管理员（LEGAL 虽可看案件，但不参与附件误传处置） */
    private boolean canManageVoided(Authentication auth) {
        return access.hasRole(auth.getName(), "LEADER", "ADMIN");
    }

    @GetMapping("/attachments/{attachmentId}/download")
    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ResponseEntity<org.springframework.core.io.Resource> download(
            @PathVariable Long attachmentId, Authentication auth) {
        materials.readActor(auth.getName());
        var rows = jdbc.queryForList(
                "select case_id, filename, content_type, size_bytes, data, file_path, voided from case_attachment where id = ?", attachmentId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        if (!(rows.get(0).get("case_id") instanceof Number caseId))
            throw new BizException(2146, "附件尚未归案，请从来源材料下载");
        access.assertCaseScope(caseId.longValue(), auth.getName());
        var row = rows.get(0);
        // 作废件（多为误传的他案个人信息）不再向办案员开放，只留给负责人/管理员复核
        if (Boolean.TRUE.equals(row.get("voided")) && !canManageVoided(auth))
            throw new BizException(2135, "该附件已作废，仅负责人/管理员可查看");

        return storage.response(row, requestedRange());
    }

    private static String requestedRange() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                ? servlet.getRequest().getHeader(org.springframework.http.HttpHeaders.RANGE) : null;
    }
}
