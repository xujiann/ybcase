package cn.ybcase.bureau.web;

import cn.ybcase.bureau.service.DepartmentInterviewService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bureau/interviews")
@RequiredArgsConstructor
public class DepartmentInterviewController {
    private final DepartmentInterviewService interviews;

    @GetMapping
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','LEGAL','ADMIN')")
    public R<List<DepartmentInterviewService.InterviewRow>> list(@RequestParam int year, Authentication auth) {
        return R.ok(interviews.list(year, auth.getName()));
    }

    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','LEGAL','ADMIN')")
    public R<DepartmentInterviewService.InterviewPage> page(
            @RequestParam int year, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size, Authentication auth) {
        return R.ok(interviews.page(year, page, size, auth.getName()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Void> register(@RequestBody DepartmentInterviewService.InterviewInput input, Authentication auth) {
        interviews.register(input, auth.getName());
        return R.ok();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Void> edit(@PathVariable long id, @RequestBody DepartmentInterviewService.InterviewInput input,
                        Authentication auth) {
        interviews.edit(id, input, auth.getName());
        return R.ok();
    }
}
