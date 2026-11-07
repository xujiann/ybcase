package cn.ybcase.bureau.web;

import cn.ybcase.bureau.entity.CaseClue;
import cn.ybcase.bureau.repository.CaseClueRepository;
import cn.ybcase.bureau.service.ClueService;
import cn.ybcase.core.common.R;
import static cn.ybcase.bureau.common.ReqValues.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/bureau/clues")
@RequiredArgsConstructor
public class ClueController {

    private final ClueService clueService;
    private final CaseClueRepository clueRepository;
    private final cn.ybcase.bureau.service.ApprovalService approvalService;
    private final cn.ybcase.bureau.service.MaterialAccess materialAccess;

    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public R<?> list(@RequestParam(required = false) String status,
                     @RequestParam(required = false) String source,
                     @RequestParam(required = false) String q,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                     @RequestParam(required = false) Integer page,
                     @RequestParam(required = false) Long focusId,
                     @RequestParam(defaultValue = "20") int size, Authentication auth) {
        if (focusId != null) return R.ok(clueService.focus(auth.getName(), focusId));
        if (materialAccess.departmentScope(auth.getName()).district()) {
            var result = clueService.pageList(status, source, q, from, to,
                    page == null ? 1 : page, page == null ? 200 : Math.min(Math.max(size, 1), 100), auth.getName());
            return R.ok(page == null ? result.get("rows") : result);
        }
        // 未传 page 保持旧契约（返回数组，最近 200 条；E2E 依赖）；传了 page 走分页+检索
        if (page == null) {
            if (source != null && !source.isBlank()) {
                return R.ok(status == null ? clueRepository.findTop200BySourceOrderByIdDesc(source)
                        : clueRepository.findTop200ByStatusAndSourceOrderByIdDesc(status, source));
            }
            return R.ok(status == null ? clueRepository.findTop200ByOrderByIdDesc()
                    : clueRepository.findTop200ByStatusOrderByIdDesc(status));
        }
        return R.ok(clueService.pageList(status, source, q, from, to, page, Math.min(Math.max(size, 1), 100)));
    }

    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<CaseClue> create(@RequestBody ClueService.ClueCreateReq req, Authentication auth) {
        return R.ok(clueService.createForCurrentActor(req, auth.getName()));
    }

    @PostMapping("/{id}/extend")
    @org.springframework.transaction.annotation.Transactional
    public R<CaseClue> extend(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication auth) {
        CaseClue clue = clueService.extend(id, body.get("reason"), auth.getName());
        approvalService.recordClueDirect(id, body.get("reason"), auth.getName());
        return R.ok(clue);
    }

    @PostMapping("/{id}/reject")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<CaseClue> reject(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication auth) {
        return R.ok(clueService.reject(id, body.get("verifyResult"), auth.getName()));
    }

    /** 核查期限扣除（鉴定/检验时间不计入，辽15条） */
    @PostMapping("/{id}/exclusions")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<CaseClue> addExclusion(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        int days;
        try {
            Object value = body == null ? null : body.get("days");
            if (!(value instanceof Number || value instanceof String)) throw new IllegalArgumentException();
            days = new java.math.BigDecimal(value.toString().trim()).intValueExact();
        } catch (RuntimeException ex) {
            throw new cn.ybcase.bureau.common.BizException(2026, "不计入核查期限的天数须为正整数");
        }
        Object reason = body.get("reason");
        if (reason != null && (!(reason instanceof String text) || text.indexOf('\0') >= 0))
            throw new cn.ybcase.bureau.common.BizException(2026, "鉴定/检验事由须为文本且不能含空字符");
        return R.ok(clueService.addExclusion(id, days, (String) reason, auth.getName()));
    }
}
