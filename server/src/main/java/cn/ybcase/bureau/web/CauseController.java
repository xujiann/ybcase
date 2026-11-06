package cn.ybcase.bureau.web;

import cn.ybcase.bureau.entity.CaseCause;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bureau/causes")
@RequiredArgsConstructor
public class CauseController {

    private final CaseCauseRepository causeRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final cn.ybcase.bureau.service.MaterialAccess materialAccess;

    @GetMapping
    public R<List<CaseCause>> list() {
        return R.ok(causeRepository.findAll(Sort.by("itemNo")));
    }

    /** 苏医保督〔2024〕1号第四条：法律更新可增补案由 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<CaseCause> create(@RequestBody java.util.Map<String, Object> body) {
        String subject = text(body, "subjectType", 16);
        if (!java.util.Set.of("AGENCY", "PROVIDER", "INDIVIDUAL", "OTHER").contains(subject))
            throw new cn.ybcase.bureau.common.BizException(2100, "违法主体类型无效");
        String category = text(body, "category", 64);
        String description = text(body, "description", 512);
        Integer itemNo;
        try {
            Object number = body.get("itemNo");
            if (!(number instanceof Number || number instanceof String)) throw new IllegalArgumentException();
            itemNo = new java.math.BigDecimal(number.toString().trim()).intValueExact();
            if (itemNo <= 0) throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new cn.ybcase.bureau.common.BizException(2100, "序号须为正整数");
        }
        Object enabled = body.containsKey("enabled") ? body.get("enabled") : Boolean.TRUE;
        if (!(enabled instanceof Boolean)) throw new cn.ybcase.bureau.common.BizException(2100, "启用状态须为布尔值");
        // Serialize absent-key creation before locking the actor, so duplicate additions return a business error.
        jdbc.execute("select pg_advisory_xact_lock(2026102802)");
        materialAccess.requireCurrentRoles("ADMIN");
        if (jdbc.queryForObject("select count(*) from case_cause where item_no=?", Integer.class, itemNo) > 0)
            throw new cn.ybcase.bureau.common.BizException(2100, "案由序号已存在");
        CaseCause cause = new CaseCause();
        cause.setSubjectType(subject);cause.setCategory(category);cause.setDescription(description);
        cause.setItemNo(itemNo);cause.setEnabled((Boolean) enabled);
        return R.ok(causeRepository.saveAndFlush(cause));
    }

    private String text(java.util.Map<String, Object> body, String key, int max) {
        Object value = body == null ? null : body.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.indexOf('\0') >= 0
                || text.codePointCount(0, text.length()) > max)
            throw new cn.ybcase.bureau.common.BizException(2100, key + "须为1至" + max + "字的文本");
        return text;
    }
}
