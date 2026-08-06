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

    @GetMapping
    public R<List<CaseCause>> list() {
        return R.ok(causeRepository.findAll(Sort.by("itemNo")));
    }

    /** 苏医保督〔2024〕1号第四条：法律更新可增补案由 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public R<CaseCause> create(@RequestBody CaseCause cause) {
        cause.setId(null);
        return R.ok(causeRepository.save(cause));
    }
}
