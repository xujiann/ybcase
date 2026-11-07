package cn.ybcase.bureau.web;

import cn.ybcase.bureau.service.NextStepTaskService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class NextStepTaskController {
    private final NextStepTaskService tasks;

    @GetMapping("/api/bureau/my/tasks")
    @PreAuthorize("hasAnyRole('HANDLER','LEGAL','LEADER','ADMIN')")
    public R<NextStepTaskService.TaskPage> bureau(Authentication auth,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return R.ok(tasks.bureau(auth.getName(), page, size));
    }

    @GetMapping("/api/agency/my/tasks")
    @PreAuthorize("hasRole('AGENCY')")
    public R<NextStepTaskService.TaskPage> agency(Authentication auth,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return R.ok(tasks.agency(auth.getName(), page, size));
    }
}