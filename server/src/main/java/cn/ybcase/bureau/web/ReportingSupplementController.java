package cn.ybcase.bureau.web;

import cn.ybcase.bureau.service.ReportingSupplementService;
import cn.ybcase.bureau.common.BizException;
import cn.ybcase.core.common.R;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/bureau")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HANDLER','LEADER','LEGAL','ADMIN')")
public class ReportingSupplementController {
    private final ReportingSupplementService supplements;
    private final ObjectMapper mapper;

    @GetMapping("/cases/{caseId}/report-supplements")
    public R<Map<String, Object>> state(@PathVariable long caseId, Authentication auth) {
        return R.ok(supplements.state(caseId, auth.getName()));
    }

    @PostMapping("/cases/{caseId}/report-supplements")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Map<String, Long>> request(@PathVariable long caseId, @RequestBody byte[] body,
                                        Authentication auth) {
        return R.ok(Map.of("id", supplements.request(caseId, requestBody(body), auth.getName())));
    }

    @PostMapping("/cases/{caseId}/report-supplements/{requestId}/review")
    @PreAuthorize("hasAnyRole('LEGAL','LEADER','ADMIN')")
    public R<Void> review(@PathVariable long caseId, @PathVariable long requestId,
                          @RequestBody Map<String, Object> body, Authentication auth) {
        supplements.review(caseId, requestId, body, auth.getName());
        return R.ok();
    }

    @PostMapping("/cases/{caseId}/report-supplements/{requestId}/cancel")
    public R<Void> cancel(@PathVariable long caseId, @PathVariable long requestId,
                          @RequestBody Map<String, Object> body, Authentication auth) {
        supplements.cancel(caseId, requestId, body, auth.getName());
        return R.ok();
    }

    @GetMapping("/report-supplements/pending")
    public R<Map<String, Object>> pending(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return R.ok(supplements.pending(auth.getName(), page, size));
    }

    private Map<String, Object> requestBody(byte[] bytes) {
        try {
            return mapper.readerFor(new TypeReference<Map<String, Object>>() {})
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);
        } catch (java.io.IOException e) {
            throw new BizException(2191, "补录请求格式无效");
        }
    }
}
