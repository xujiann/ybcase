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

    @PostMapping("/{id}/attachments")
    public R<Void> upload(@PathVariable Long id, @RequestParam("file") MultipartFile file,
                          @RequestParam(required = false) Long documentId,
                          @RequestParam(required = false) Long evidenceId,
                          Authentication auth) throws IOException {
        if (file.isEmpty()) throw new BizException(2054, "文件为空");
        if (file.getSize() > MAX_SIZE) throw new BizException(2054, "附件不得超过 10MB");
        Integer exists = jdbc.queryForObject("select count(*) from case_file where id = ?", Integer.class, id);
        if (exists == null || exists == 0) throw new BizException(2043, "案件不存在");
        jdbc.update("""
                insert into case_attachment (case_id, document_id, evidence_id, filename, content_type, size_bytes, data, uploaded_by)
                values (?,?,?,?,?,?,?,?)""",
                id, documentId, evidenceId, file.getOriginalFilename(), file.getContentType(),
                file.getSize(), file.getBytes(), auth.getName());
        return R.ok();
    }

    @GetMapping("/{id}/attachments")
    public R<List<Map<String, Object>>> list(@PathVariable Long id) {
        return R.ok(jdbc.queryForList("""
                select id, document_id, evidence_id, filename, content_type, size_bytes, uploaded_by, uploaded_at
                from case_attachment where case_id = ? order by id""", id));
    }

    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long attachmentId) {
        var rows = jdbc.queryForList("select filename, content_type, data from case_attachment where id = ?", attachmentId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        var row = rows.get(0);
        String fn = URLEncoder.encode((String) row.get("filename"), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fn)
                .contentType(row.get("content_type") == null ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType((String) row.get("content_type")))
                .body((byte[]) row.get("data"));
    }
}
