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

    @GetMapping
    public R<?> list(@RequestParam(required = false) String status,
                     @RequestParam(required = false) String q,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                     @RequestParam(required = false) Integer page,
                     @RequestParam(defaultValue = "20") int size) {
        // 未传 page 保持旧契约（返回数组，最近 200 条；E2E 依赖）；传了 page 走分页+检索
        if (page == null) {
            return R.ok(status == null ? clueRepository.findTop200ByOrderByIdDesc()
                    : clueRepository.findTop200ByStatusOrderByIdDesc(status));
        }
        return R.ok(clueService.pageList(status, q, from, to, page, Math.min(Math.max(size, 1), 100)));
    }

    @PostMapping
    public R<CaseClue> create(@RequestBody ClueService.ClueCreateReq req, Authentication auth) {
        return R.ok(clueService.create(req, auth.getName()));
    }

    @PostMapping("/{id}/extend")
    public R<CaseClue> extend(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(clueService.extend(id, body.get("reason")));
    }

    @PostMapping("/{id}/reject")
    public R<CaseClue> reject(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(clueService.reject(id, body.get("verifyResult")));
    }

    /** 核查期限扣除（鉴定/检验时间不计入，辽15条） */
    @PostMapping("/{id}/exclusions")
    public R<CaseClue> addExclusion(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return R.ok(clueService.addExclusion(id, reqInt(body, "days", "不计入核查期限的天数"),
                (String) body.get("reason")));
    }
}
