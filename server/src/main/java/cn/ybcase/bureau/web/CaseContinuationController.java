package cn.ybcase.bureau.web;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.CaseContinuationService;
import cn.ybcase.core.common.R;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/bureau/cases/{caseId}/continuations")
@PreAuthorize("hasAnyRole('HANDLER','LEGAL','LEADER','ADMIN')")
@RequiredArgsConstructor
public class CaseContinuationController {
    private final CaseContinuationService service;
    private final ObjectMapper mapper;

    @GetMapping public R<?> state(@PathVariable long caseId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate cutoff,Authentication auth) {
        return R.ok(service.state(caseId,cutoff,auth.getName()));
    }
    @PostMapping("/remedies") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<?> remedy(@PathVariable long caseId,@RequestBody byte[] bytes,Authentication auth) {
        return R.ok(Map.of("id",service.registerRemedy(caseId,body(bytes),auth.getName())));
    }
    @PostMapping("/requests") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<?> request(@PathVariable long caseId,@RequestBody byte[] bytes,Authentication auth) {
        return R.ok(Map.of("id",service.request(caseId,body(bytes),auth.getName())));
    }
    @PostMapping("/requests/{id}/review") @PreAuthorize("hasAnyRole('LEGAL','LEADER','ADMIN')")
    public R<?> review(@PathVariable long caseId,@PathVariable long id,@RequestBody byte[] bytes,Authentication auth) {
        service.review(caseId,id,body(bytes),auth.getName());return R.ok();
    }
    @PostMapping("/requests/{id}/cancel")
    public R<?> cancel(@PathVariable long caseId,@PathVariable long id,@RequestBody byte[] bytes,Authentication auth) {
        service.cancel(caseId,id,body(bytes),auth.getName());return R.ok();
    }
    private Map<String,Object> body(byte[] bytes) {
        try {return mapper.readerFor(new TypeReference<Map<String,Object>>(){}).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);}
        catch(java.io.IOException e){throw new BizException(2193,"续办请求格式无效");}
    }
}
