package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Storage and download mechanics shared by case and pre-filing attachments; callers authorize and lock first. */
@Service
@RequiredArgsConstructor
public class AttachmentStorage {
    private final JdbcTemplate jdbc;
    private final BureauConfig config;

    public long store(Long caseId, Long clueId, Long inspectionId, Long documentId, Long evidenceId,
                      MultipartFile file, String category, String username) throws IOException {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Attachment upload must be transactional");
        if (file == null || file.isEmpty()) throw new BizException(2054, "文件为空");
        if (category == null || !List.of("DOC_SCAN", "AV_RECORD", "OTHER").contains(category))
            throw new BizException(2054, "附件分类须为 扫描件/执法音像/其他");
        boolean external = "FILE".equalsIgnoreCase(config.str("attachment_storage", "DB"));
        long limit = "AV_RECORD".equals(category) && external ? 200L * 1024 * 1024 : 10L * 1024 * 1024;
        long expectedSize = file.getSize();
        if (expectedSize <= 0) throw new BizException(2054, "文件为空");
        if (expectedSize > limit) throw new BizException(2054, "附件超过大小限制（" + limit / 1024 / 1024 + "MB）");
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) filename = "attachment";
        if (filename.codePointCount(0, filename.length()) > 255) throw new BizException(2054, "附件文件名不能超过255字");
        if (filename.indexOf('\0') >= 0) throw new BizException(2054, "附件文件名无效");
        String contentType = file.getContentType();
        if (contentType != null && contentType.length() > 128) throw new BizException(2054, "附件类型过长");
        if (contentType != null && contentType.indexOf('\0') >= 0) throw new BizException(2054, "附件类型无效");
        byte[] bytes = null;
        String filePath = null;
        if (external) {
            String bucket = caseId != null ? String.valueOf(caseId) : clueId != null ? "clue-" + clueId : "inspection-" + inspectionId;
            Path dir;
            try { dir = Path.of(config.str("attachment_dir", "./data/attachments"), bucket); }
            catch (java.nio.file.InvalidPathException e) { throw new BizException(2054, "附件存储路径无效，请联系管理员检查配置"); }
            // The original name belongs in metadata and Content-Disposition. Prefixing it with
            // a UUID can exceed the filesystem's single-component limit despite a valid DB name.
            Path target = dir.resolve(UUID.randomUUID().toString());
            if (target.toString().length() > 512) throw new BizException(2054, "附件存储路径过长");
            Files.createDirectories(dir);
            Path stored = target.toAbsolutePath();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try { Files.deleteIfExists(stored); }
                        catch (IOException e) { org.slf4j.LoggerFactory.getLogger(AttachmentStorage.class).error("附件回滚清理失败 {}", stored, e); }
                    } else if (status == STATUS_UNKNOWN) {
                        // The database may have committed even if its acknowledgement was lost.
                        // Preserve the bytes rather than breaking a possibly committed attachment row.
                        org.slf4j.LoggerFactory.getLogger(AttachmentStorage.class).error("附件事务结果未知，保留文件待核对 {}", stored);
                    }
                }
            });
            file.transferTo(stored);
            if (!Files.isRegularFile(stored) || Files.size(stored) != expectedSize)
                throw new BizException(2054, "附件写入不完整，请重新上传");
            filePath = target.toString();
        } else {
            bytes = file.getBytes();
            if (bytes.length != expectedSize) throw new BizException(2054, "附件写入不完整，请重新上传");
        }
        return jdbc.queryForObject("""
                insert into case_attachment(case_id,source_clue_id,source_inspection_id,document_id,evidence_id,
                 filename,content_type,size_bytes,data,file_path,category,uploaded_by)
                values(?,?,?,?,?,?,?,?,?,?,?,?) returning id""", Long.class,
                caseId, clueId, inspectionId, documentId, evidenceId, filename, contentType,
                expectedSize, bytes, filePath, category, username);
    }

    public ResponseEntity<Resource> response(Map<String, Object> row) {
        return response(row, null);
    }

    /** Valid byte ranges are serialized by Spring; do not attach a full-file length to multipart bodies. */
    public ResponseEntity<Resource> response(Map<String, Object> row, String range) {
        Resource resource;
        long actualSize;
        if (row.get("file_path") != null) {
            Path path;
            try { path = Path.of((String) row.get("file_path")); }
            catch (java.nio.file.InvalidPathException e) { throw new BizException(2054, "附件存储路径无效，请检查附件存储卷是否已挂载/恢复"); }
            if (!Files.isRegularFile(path) || !Files.isReadable(path))
                throw new BizException(2054, "附件文件不存在或不可读，请检查附件存储卷是否已挂载/恢复");
            try { actualSize = Files.size(path); }
            catch (IOException e) { throw new BizException(2054, "附件文件不可读，请检查附件存储卷是否已挂载/恢复"); }
            resource = new org.springframework.core.io.FileSystemResource(path);
        } else {
            if (!(row.get("data") instanceof byte[] bytes))
                throw new BizException(2054, "附件内容缺失，请检查附件数据是否已完整恢复");
            actualSize = bytes.length;
            resource = new org.springframework.core.io.ByteArrayResource(bytes);
        }
        if (!(row.get("size_bytes") instanceof Number size) || size.longValue() != actualSize)
            throw new BizException(2054, "附件内容长度与登记信息不一致，请检查附件是否已完整恢复");
        if (range != null) {
            try {
                var ranges = HttpRange.parseRanges(range);
                if (ranges.isEmpty()) throw new IllegalArgumentException("Empty attachment byte range");
                HttpRange.toResourceRegions(ranges, resource);
            } catch (IllegalArgumentException e) {
                // Spring otherwise retains the original Resource when it changes status to 416,
                // so a malformed Range can still cause the entire attachment to be transferred.
                return ResponseEntity.status(416).header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + actualSize).contentLength(0).build();
            }
        }
        String name = (String) row.get("filename");
        String encoded = URLEncoder.encode(name == null || name.isBlank() ? "attachment" : name, StandardCharsets.UTF_8).replace("+", "%20");
        MediaType type = MediaType.APPLICATION_OCTET_STREAM;
        if (row.get("content_type") != null) {
            try {
                MediaType parsed = MediaType.parseMediaType((String) row.get("content_type"));
                if (parsed.isConcrete()) type = parsed;
            }
            catch (IllegalArgumentException ignored) { /* Legacy unparseable MIME remains downloadable as bytes. */ }
        }
        var builder = ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded).contentType(type);
        return (range == null ? builder.contentLength(actualSize) : builder).body(resource);
    }
}
