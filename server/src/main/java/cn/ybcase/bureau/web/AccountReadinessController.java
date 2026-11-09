package cn.ybcase.bureau.web;
import cn.ybcase.bureau.service.AccountReadinessService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
public class AccountReadinessController {
 private final AccountReadinessService service;
 @GetMapping({"/api/bureau/account-readiness","/api/agency/account-readiness"})
 public R<?> readiness(Authentication a){return R.ok(service.readiness(a.getName()));}
}
