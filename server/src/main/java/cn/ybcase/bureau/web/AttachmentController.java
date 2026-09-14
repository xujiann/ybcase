package cn.ybcase.bureau.web;

import static cn.ybcase.bureau.common.CaseScopeInterceptor.privileged;
import cn.ybcase.bureau.common.BizException;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 案件附件：笔录扫描件/影像证据等（库内存储，单文件限 10MB） */
@RestController
@RequestMapping("/api/bureau/cases")
@RequiredArgsConstructor
public class AttachmentController {

    private final JdbcTemplate jdbc;

    private static final long MAX_SIZE = 10 * 1024 * 1024;

    private final cn.ybcase.bureau.service.BureauConfig config;
    private final cn.ybcase.bureau.service.CaseService caseService;

    /** 音像类（执法记录仪等）建议 FILE 外置且限额放宽到 200MB */
    private static final long MAX_AV_SIZE = 200L * 1024 * 1024;


    @PostMapping("/{id}/attachments")
    public R<Void> upload(@PathVariable Long id, @RequestParam("file") MultipartFile file,
                          @RequestParam(required = false) Long documentId,
                          @RequestParam(required = false) Long evidenceId,
                          @RequestParam(defaultValue = "DOC_SCAN") String category,
                          Authentication auth) throws IOException {
        if (file.isEmpty()) throw new BizException(2054, "文件为空");
        if (!java.util.List.of("DOC_SCAN", "AV_RECORD", "OTHER").contains(category))
            throw new BizException(2054, "附件分类须为 扫描件/执法音像/其他");
        boolean fileMode = "FILE".equalsIgnoreCase(config.str("attachment_storage", "DB"));
        long limit = "AV_RECORD".equals(category) && fileMode ? MAX_AV_SIZE : MAX_SIZE;
        if (file.getSize() > limit) throw new BizException(2054, "附件超过大小限制（" + (limit / 1024 / 1024) + "MB）");
        // 附件是卷内材料，已立卷归档的案卷同样不得追加（此前只查案件存在性）
        var cf = jdbc.queryForList("select status, archive_no from case_file where id = ?", id);
        if (cf.isEmpty()) throw new BizException(2043, "案件不存在");
        if ("CLOSED".equals(cf.get(0).get("status")) || cf.get(0).get("archive_no") != null)
            throw new BizException(2031, "案件已立卷归档，不可再增删卷内材料");

        byte[] data = null;
        String filePath = null;
        if (fileMode) {
            // 外置文件存储：目录/案件ID/UUID_原名（路径由服务端生成，不受用户输入影响）
            java.nio.file.Path dir = java.nio.file.Path.of(config.str("attachment_dir", "./data/attachments"),
                    String.valueOf(id));
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path target = dir.resolve(java.util.UUID.randomUUID() + "_"
                    + java.nio.file.Path.of(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename())
                          .getFileName());
            file.transferTo(target.toAbsolutePath());
            filePath = target.toString();
        } else {
            data = file.getBytes();
        }
        jdbc.update("""
                insert into case_attachment (case_id, document_id, evidence_id, filename, content_type,
                                             size_bytes, data, file_path, category, uploaded_by)
                values (?,?,?,?,?,?,?,?,?,?)""",
                id, documentId, evidenceId, file.getOriginalFilename(), file.getContentType(),
                file.getSize(), data, filePath, category, auth.getName());
        return R.ok();
    }

    /** 已作废附件默认不出列表；仅负责人/管理员可用 includeVoided=true 连同作废记录一起查（复核误传处置） */
    @GetMapping("/{id}/attachments")
    public R<List<Map<String, Object>>> list(@PathVariable Long id,
                                             @RequestParam(defaultValue = "false") boolean includeVoided,
                                             Authentication auth) {
        caseService.assertInScope(id, auth.getName(), privileged(auth));
        boolean withVoided = includeVoided && canManageVoided(auth);
        return R.ok(jdbc.queryForList("""
                select id, document_id, evidence_id, filename, content_type, size_bytes, category,
                       (file_path is not null) as external, uploaded_by, uploaded_at,
                       voided, void_reason, voided_by, voided_at
                from case_attachment where case_id = ? and (voided = false or ?) order by id""", id, withVoided));
    }

    /**
     * 附件作废（误传他案材料/传错版本）：只打标不删文件与 bytea——执法留痕要求作废记录可追溯；
     * 已归档卷宗不得变动；作废权限限上传人本人或负责人/管理员。
     */
    @PostMapping("/{id}/attachments/{attachmentId}/void")
    public R<Void> voidAttachment(@PathVariable Long id, @PathVariable Long attachmentId,
                                  @RequestBody(required = false) Map<String, String> body, Authentication auth) {
        String reason = body == null ? null : body.get("reason");
        if (reason == null || reason.isBlank()) throw new BizException(2130, "须填写作废理由");
        var rows = jdbc.queryForList(
                "select case_id, uploaded_by, voided from case_attachment where id = ?", attachmentId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("case_id")).longValue() != id)
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
    private static boolean canManageVoided(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_LEADER") || a.getAuthority().equals("ROLE_ADMIN"));
    }

    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(
            @PathVariable Long attachmentId, Authentication auth) {
        var rows = jdbc.queryForList(
                "select case_id, filename, content_type, size_bytes, data, file_path, voided from case_attachment where id = ?", attachmentId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        caseService.assertInScope(((Number) rows.get(0).get("case_id")).longValue(),
                auth.getName(), privileged(auth));
        var row = rows.get(0);
        // 作废件（多为误传的他案个人信息）不再向办案员开放，只留给负责人/管理员复核
        if (Boolean.TRUE.equals(row.get("voided")) && !canManageVoided(auth))
            throw new BizException(2135, "该附件已作废，仅负责人/管理员可查看");

        // 外置文件走流式：音像件上限 200MB，若 readAllBytes 进堆，
        // 768MB 堆上两三个并发下载即 OOM（整个 JVM 挂掉，不只是这个请求失败）
        org.springframework.core.io.Resource body;
        if (row.get("file_path") != null) {
            java.nio.file.Path path = java.nio.file.Path.of((String) row.get("file_path"));
            if (!java.nio.file.Files.isReadable(path))
                // 卷未挂载或只恢复了库而没恢复附件：给 404 而非 500，前端能识别
                throw new BizException(2054, "附件文件不存在或不可读，请检查附件存储卷是否已挂载/恢复");
            body = new org.springframework.core.io.FileSystemResource(path);
        } else {
            body = new org.springframework.core.io.ByteArrayResource((byte[]) row.get("data"));
        }

        String rawName = (String) row.get("filename");  // multipart 可不带文件名，入库即为 null
        String fn = URLEncoder.encode(rawName == null || rawName.isBlank() ? "attachment" : rawName,
                StandardCharsets.UTF_8).replace("+", "%20");
        var builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fn)
                .contentType(row.get("content_type") == null ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType((String) row.get("content_type")));
        Object size = row.get("size_bytes");
        if (size instanceof Number n) builder = builder.contentLength(n.longValue());
        return builder.body(body);
    }
}
