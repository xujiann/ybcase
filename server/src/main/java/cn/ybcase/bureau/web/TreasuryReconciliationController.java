package cn.ybcase.bureau.web;

import cn.ybcase.bureau.service.TreasuryReconciliationService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bureau/cases/{caseId}/executions/{executionId}/receipt-check")
@RequiredArgsConstructor
public class TreasuryReconciliationController {
    private final TreasuryReconciliationService service;

    @PostMapping
    public R<TreasuryReconciliationService.Result> check(@PathVariable long caseId, @PathVariable long executionId) {
        return R.ok(service.reconcile(caseId, executionId));
    }
}
