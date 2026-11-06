package cn.ybcase.bureau.web;

import cn.ybcase.bureau.service.PrecaseAttachmentService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bureau")
@RequiredArgsConstructor
public class PrecaseAttachmentController {
    private final PrecaseAttachmentService attachments;

    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PostMapping("/clues/{id}/attachments")
    public R<Map<String, Object>> uploadClue(@PathVariable Long id, @RequestParam("file") MultipartFile file,
                                           @RequestParam(defaultValue = "DOC_SCAN") String category, Authentication auth) throws IOException {
        return R.ok(Map.of("id", attachments.upload("CLUE", id, file, category, auth)));
    }

    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PostMapping("/inspections/{id}/attachments")
    public R<Map<String, Object>> uploadInspection(@PathVariable Long id, @RequestParam("file") MultipartFile file,
                                                 @RequestParam(defaultValue = "DOC_SCAN") String category, Authentication auth) throws IOException {
        return R.ok(Map.of("id", attachments.upload("INSPECTION", id, file, category, auth)));
    }

    @GetMapping("/clues/{id}/attachments")
    public R<List<Map<String, Object>>> listClue(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean includeVoided,
                                                Authentication auth) {
        return R.ok(attachments.list("CLUE", id, includeVoided, auth));
    }

    @GetMapping("/inspections/{id}/attachments")
    public R<List<Map<String, Object>>> listInspection(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean includeVoided,
                                                      Authentication auth) {
        return R.ok(attachments.list("INSPECTION", id, includeVoided, auth));
    }

    @GetMapping("/precase-attachments/{attachmentId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long attachmentId, Authentication auth) {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        String range = attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                ? servlet.getRequest().getHeader(org.springframework.http.HttpHeaders.RANGE) : null;
        return attachments.download(attachmentId, auth, range);
    }

    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @PostMapping("/precase-attachments/{attachmentId}/void")
    public R<Void> voidAttachment(@PathVariable Long attachmentId, @RequestBody(required = false) Map<String, Object> body,
                                 Authentication auth) {
        if (body == null || !(body.get("reason") instanceof String reason))
            throw new cn.ybcase.bureau.common.BizException(2130, "须填写2000字以内作废理由");
        attachments.voidAttachment(attachmentId, reason, auth);
        return R.ok();
    }
}
