package cn.ybcase.bureau.web;

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
        Integer exists = jdbc.queryForObject("select count(*) from case_file where id = ?", Integer.class, id);
        if (exists == null || exists == 0) throw new BizException(2043, "案件不存在");

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

    @GetMapping("/{id}/attachments")
    public R<List<Map<String, Object>>> list(@PathVariable Long id) {
        return R.ok(jdbc.queryForList("""
                select id, document_id, evidence_id, filename, content_type, size_bytes, category,
                       (file_path is not null) as external, uploaded_by, uploaded_at
                from case_attachment where case_id = ? order by id""", id));
    }

    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long attachmentId) throws IOException {
        var rows = jdbc.queryForList(
                "select filename, content_type, data, file_path from case_attachment where id = ?", attachmentId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        var row = rows.get(0);
        byte[] body = row.get("file_path") != null
                ? java.nio.file.Files.readAllBytes(java.nio.file.Path.of((String) row.get("file_path")))
                : (byte[]) row.get("data");
        String fn = URLEncoder.encode((String) row.get("filename"), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fn)
                .contentType(row.get("content_type") == null ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType((String) row.get("content_type")))
                .body(body);
    }
}
