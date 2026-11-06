package cn.ybcase.bureau.web;

import cn.ybcase.bureau.service.AgencyAgreementService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agency/agreement-actions")
@PreAuthorize("hasRole('AGENCY')")
@RequiredArgsConstructor
public class AgencyAgreementController {
    private final AgencyAgreementService agreements;

    public record Cycle(Integer cycle) {}
    public record ReturnRequest(Integer cycle, String reason) {}

    @GetMapping
    public R<List<AgencyAgreementService.Handoff>> list(Authentication auth) {
        return R.ok(agreements.inbox(auth.getName()));
    }

    @PostMapping("/{id}/receive")
    public R<Void> receive(@PathVariable long id, @RequestBody Cycle body, Authentication auth) {
        agreements.receive(id, body.cycle(), auth.getName());
        return R.ok();
    }

    @PostMapping("/{id}/return")
    public R<Void> returnToSender(@PathVariable long id, @RequestBody ReturnRequest body, Authentication auth) {
        agreements.returnToSender(id, body.cycle(), body.reason(), auth.getName());
        return R.ok();
    }

    @PostMapping("/{id}/reply")
    public R<Void> reply(@PathVariable long id, @RequestBody AgencyAgreementService.Reply body, Authentication auth) {
        agreements.reply(id, body, auth.getName());
        return R.ok();
    }
}
