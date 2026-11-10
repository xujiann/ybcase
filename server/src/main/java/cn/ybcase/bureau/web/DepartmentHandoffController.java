package cn.ybcase.bureau.web;

import cn.ybcase.bureau.service.DepartmentHandoffService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/bureau/department-handoffs")
@PreAuthorize("hasAnyRole('LEADER','ADMIN')")
@RequiredArgsConstructor
public class DepartmentHandoffController {
    private final DepartmentHandoffService handoffs;

    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) Long focusId,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return R.ok(handoffs.list(auth.getName(), status, page, size, focusId));
    }

    @PostMapping
    public R<Map<String, Long>> request(@RequestBody DepartmentHandoffService.Request request, Authentication auth) {
        return R.ok(Map.of("id", handoffs.request(request, auth.getName())));
    }

    @PostMapping("/{id}/decision")
    public R<Void> decide(@PathVariable long id, @RequestBody DepartmentHandoffService.Decision decision,
                          Authentication auth) {
        handoffs.decide(id, decision, auth.getName());
        return R.ok();
    }

    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable long id, @RequestBody DepartmentHandoffService.Cancellation cancellation,
                          Authentication auth) {
        handoffs.cancel(id, cancellation, auth.getName());
        return R.ok();
    }

    @GetMapping("/{id}/inspection-assignees")
    public R<Map<String, Object>> inspectionAssignees(@PathVariable long id, Authentication auth) {
        return R.ok(handoffs.inspectionAssignees(id, auth.getName()));
    }

    @PostMapping("/{id}/inspection-assignment")
    public R<Void> assignInspection(@PathVariable long id, @RequestBody DepartmentHandoffService.InspectionAssignment assignment,
                                    Authentication auth) {
        handoffs.assignInspection(id, assignment, auth.getName());
        return R.ok();
    }

    @GetMapping("/{id}/clue-assignees")
    public R<Map<String, Object>> clueAssignees(@PathVariable long id, Authentication auth) {
        return R.ok(handoffs.clueAssignees(id, auth.getName()));
    }

    @PostMapping("/{id}/clue-assignment")
    public R<Void> assignClue(@PathVariable long id, @RequestBody DepartmentHandoffService.ClueAssignment assignment,
                              Authentication auth) {
        handoffs.assignClue(id, assignment, auth.getName());
        return R.ok();
    }
}
