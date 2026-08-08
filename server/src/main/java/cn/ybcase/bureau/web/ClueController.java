package cn.ybcase.bureau.web;

import cn.ybcase.bureau.entity.CaseClue;
import cn.ybcase.bureau.repository.CaseClueRepository;
import cn.ybcase.bureau.service.ClueService;
import cn.ybcase.core.common.R;
import static cn.ybcase.bureau.common.ReqValues.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bureau/clues")
@RequiredArgsConstructor
public class ClueController {

    private final ClueService clueService;
    private final CaseClueRepository clueRepository;

    @GetMapping
    public R<List<CaseClue>> list(@RequestParam(required = false) String status) {
        return R.ok(status == null ? clueRepository.findTop200ByOrderByIdDesc()
                : clueRepository.findByStatusOrderByIdDesc(status));
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
