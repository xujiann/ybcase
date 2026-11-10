package cn.ybcase.bureau.web;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.ReportExport;
import cn.ybcase.bureau.service.ReportingVersionService;
import cn.ybcase.core.common.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;

@RestController
@RequestMapping("/api/bureau/report-versions/batches")
@PreAuthorize("hasAnyRole('LEGAL','LEADER','ADMIN')")
@RequiredArgsConstructor
public class ReportingVersionController {
    private final ReportingVersionService versions;

    public record Confirmation(String opinion) {}
    public record Transition(String action,String opinion) {}

    @PostMapping("/{id}/transition")
    public ResponseEntity<R<Map<String,Object>>> transition(@PathVariable long id,@RequestBody Transition body,Authentication auth) {
        return response(versions.transition(id,body.action(),body.opinion(),auth.getName()));
    }

    @GetMapping
    public ResponseEntity<R<Map<String, Object>>> list(Authentication auth,
            @RequestParam(required = false) ReportExport.Kind type,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return response(versions.list(auth.getName(), type, page, size));
    }

    @PostMapping
    public ResponseEntity<R<Map<String, Object>>> generate(@RequestBody ReportingVersionService.GenerateRequest body,
            Authentication auth) {
        if (!ReportExport.GENERATION_SLOTS.tryAcquire()) throw new BizException(2195, "报表生成繁忙，请稍后重试");
        try { return response(versions.generate(body, auth.getName())); }
        catch (org.springframework.dao.DuplicateKeyException failure) {
            // The service transaction has already rolled back. Only the idempotency key
            // constraint is retryable here; row/source and other integrity failures remain errors.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())
                        && sql.getMessage() != null && sql.getMessage().contains(
                            "\"report_frozen_batch_generated_by_request_key_key\""))
                    throw new BizException(2193, "同一报表请求正在生成或已生成，请保留原请求号重试");
            }
            throw failure;
        }
        finally { ReportExport.GENERATION_SLOTS.release(); }
    }

    @GetMapping("/{id}")
    public ResponseEntity<R<Map<String, Object>>> detail(@PathVariable long id, Authentication auth) {
        return response(versions.detail(id, auth.getName()));
    }

    @GetMapping("/{id}/rows")
    public ResponseEntity<R<Map<String, Object>>> rows(@PathVariable long id, Authentication auth,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "30") int size) {
        return response(versions.rows(id, auth.getName(), page, size));
    }

    @GetMapping("/{id}/changes")
    public ResponseEntity<R<Map<String, Object>>> changes(@PathVariable long id, Authentication auth,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "30") int size) {
        return response(versions.changes(id, auth.getName(), page, size));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<R<Map<String, Object>>> confirm(@PathVariable long id, @RequestBody Confirmation body,
            Authentication auth) {
        return response(versions.confirm(id, body.opinion(), auth.getName()));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id, Authentication auth, HttpServletRequest request) {
        var file = versions.download(id, auth.getName());
        request.setAttribute(ReportExport.AUDIT_ATTRIBUTE, file.audit());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.audit().kind() == ReportExport.Kind.MONTHLY_SUBMISSION
                        ? "text/csv;charset=UTF-8" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header("X-Content-SHA256", file.sha256())
                .header("Content-Disposition", ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }

    @PostMapping("/{id}/receipt")
    public ResponseEntity<R<Map<String,Object>>> recordReceipt(@PathVariable long id,
            @RequestBody ReportingVersionService.Receipt body, Authentication auth) {
        return response(versions.recordReceipt(id,body,auth.getName()));
    }

    private static ResponseEntity<R<Map<String, Object>>> response(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(body));
    }
}
