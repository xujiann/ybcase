package cn.ybcase.bureau.web;
import cn.ybcase.bureau.service.ParticipantAuthorizationService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequiredArgsConstructor @PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/system/participant-access")
public class ParticipantAuthorizationController {
 private final ParticipantAuthorizationService service;
 @GetMapping("/preview") public R<?> preview(@RequestParam long caseId,@RequestParam long officerId,@RequestParam String username){return R.ok(service.preview(caseId,officerId,username));}
 @GetMapping public R<?> history(@RequestParam long caseId){return R.ok(service.history(caseId));}
 @PostMapping("/{grantId}/revoke") public R<?> revoke(@PathVariable long grantId,@RequestBody Map<String,Object>b,Authentication a){service.revoke(grantId,b,a.getName());return R.ok();}
 @PostMapping("/{caseId}/{officerId}") public R<?> approve(@PathVariable long caseId,@PathVariable long officerId,@RequestBody Map<String,Object>b,Authentication a){return R.ok(Map.of("id",service.approve(caseId,officerId,b,a.getName())));}
}
