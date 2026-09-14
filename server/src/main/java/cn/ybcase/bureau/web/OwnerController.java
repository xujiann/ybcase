package cn.ybcase.bureau.web;

import cn.ybcase.bureau.service.OwnerTransferService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 承办人视角的案件清单与批量移交（人员离职/调动交接） */
@RestController
@RequestMapping("/api/bureau/owners")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('LEADER','LEGAL','ADMIN')")
public class OwnerController {

    private final OwnerTransferService ownerTransferService;

    public record TransferReq(String fromOwner, String toOwner, List<Long> caseIds) {}

    @GetMapping
    public R<List<Map<String, Object>>> owners() {
        return R.ok(ownerTransferService.owners());
    }

    @GetMapping("/candidates")
    public R<List<Map<String, Object>>> candidates() {
        return R.ok(ownerTransferService.candidates());
    }

    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    @PostMapping("/transfer")
    public R<Map<String, Object>> transfer(@RequestBody TransferReq req, Authentication auth) {
        return R.ok(ownerTransferService.transfer(req.fromOwner(), req.toOwner(), req.caseIds(), auth.getName()));
    }
}
