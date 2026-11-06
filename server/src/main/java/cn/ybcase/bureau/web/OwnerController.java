package cn.ybcase.bureau.web;

import cn.ybcase.bureau.service.OwnerTransferService;
import cn.ybcase.bureau.common.BizException;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 承办人视角的案件清单与批量移交（人员离职/调动交接） */
@RestController
@RequestMapping("/api/bureau/owners")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('LEADER','LEGAL','ADMIN')")
public class OwnerController {

    private final OwnerTransferService ownerTransferService;

    @GetMapping
    public R<List<Map<String, Object>>> owners() {
        return R.ok(ownerTransferService.owners());
    }

    @GetMapping("/candidates")
    public R<List<Map<String, Object>>> candidates() {
        return R.ok(ownerTransferService.candidates());
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    @PostMapping("/transfer")
    public R<Map<String, Object>> transfer(@RequestBody Map<String, Object> body, Authentication auth) {
        String fromOwner = ownerName(body, "fromOwner", 2110);
        String toOwner = ownerName(body, "toOwner", 2081);
        return R.ok(ownerTransferService.transfer(fromOwner, toOwner, caseIds(body.get("caseIds")), auth.getName()));
    }

    private String ownerName(Map<String, Object> body, String key, int code) {
        Object value = body.get(key);
        if (value == null) return null; // Existing service preserves the required/missing-recipient errors.
        if (!(value instanceof String name) || name.indexOf('\0') >= 0 || name.codePointCount(0, name.length()) > 64)
            throw new BizException(code, "承办人账号须为有效文本");
        return name;
    }

    private List<Long> caseIds(Object value) {
        if (value == null) return null; // Null and [] intentionally retain the whole-active-owner batch contract.
        if (!(value instanceof List<?> ids)) throw new BizException(2112, "案件编号须为正整数数组");
        var parsed = new java.util.ArrayList<Long>(ids.size());
        for (Object id : ids) {
            try {
                long exact;
                if (id instanceof String text) exact = Long.parseLong(text.trim());
                else if (id instanceof Byte || id instanceof Short || id instanceof Integer || id instanceof Long)
                    exact = ((Number) id).longValue();
                else if (id instanceof java.math.BigInteger integer) exact = integer.longValueExact();
                else throw new IllegalArgumentException();
                if (exact <= 0) throw new IllegalArgumentException();
                parsed.add(exact);
            } catch (RuntimeException ex) {
                // Reject all floating-point JSON numbers before they can be rounded or truncated to another case.
                throw new BizException(2112, "案件编号须为支持范围内的精确正整数");
            }
        }
        return parsed;
    }
}
