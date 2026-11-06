package cn.ybcase.core.web;

import cn.ybcase.core.common.R;
import cn.ybcase.core.entity.SysMenu;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final SysUserRepository userRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final jakarta.persistence.EntityManager entityManager;

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    private static final int MAX_FAILED = 5;
    private static final java.time.Duration LOCK_DURATION = java.time.Duration.ofMinutes(15);

    @PostMapping("/login")
    @org.springframework.transaction.annotation.Transactional
    public R<Map<String, Object>> login(@RequestBody LoginRequest req, jakarta.servlet.http.HttpServletRequest http) {
        // 登录时 SecurityContext 里没有用户，审计过滤器拿不到用户名；把尝试登录的用户名与结果码
        // 放到请求属性里，让审计能区分"谁在爆破谁"。属性名与 server 侧 BureauAuditFilter 约定一致。
        http.setAttribute("ybcaseAuditUser", req.username());
        // Authentication and its counters share the same row lock as administrator account updates.
        // A failed attempt is counted from the latest committed row, rather than a stale detached entity.
        var userOpt = lockUser(req.username());
        if (userOpt.isEmpty()) {
            // The absent-row lookup is this request's authentication boundary. A later account
            // creation cannot turn it into an unlocked login, but still use the normal provider
            // so unknown usernames retain its password-check timing.
            try {
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(req.username(), req.password()));
            } catch (BadCredentialsException | org.springframework.security.authentication.DisabledException expected) {
                // An account created after the lookup does not belong to this login attempt.
            }
            http.setAttribute("ybcaseBizCode", 1001);
            return R.fail(1001, "用户名或密码错误");
        }
        if (userOpt.isPresent()) {
            var u = userOpt.get();
            if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(java.time.Instant.now())) {
                http.setAttribute("ybcaseBizCode", 1002);
                return R.fail(1002, "账号已锁定，请 15 分钟后重试");
            }
        }
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        } catch (org.springframework.security.authentication.DisabledException e) {
            // 停用账号是正常业务分支，不是系统故障：此前落到兜底 handler 返回 500，
            // 前端据此弹出"点顶栏反馈可一键报告"，把管理员停用账号的正常动作变成故障单。
            // 子类分支必须写在 BadCredentialsException 之前。
            http.setAttribute("ybcaseBizCode", 1005);
            return R.fail(1005, "账号已停用，请联系管理员");
        } catch (BadCredentialsException e) {
            // 防爆破：连续失败 5 次锁定 15 分钟
            userOpt.ifPresent(u -> {
                u.setFailedAttempts(u.getFailedAttempts() + 1);
                if (u.getFailedAttempts() >= MAX_FAILED) {
                    u.setLockedUntil(java.time.Instant.now().plus(LOCK_DURATION));
                    u.setFailedAttempts(0);
                }
                userRepository.save(u);
            });
            http.setAttribute("ybcaseBizCode", 1001);
            return R.fail(1001, "用户名或密码错误");
        }
        userOpt.ifPresent(u -> {
            u.setFailedAttempts(0);
            u.setLockedUntil(null);
            userRepository.save(u);
        });
        String token = jwtService.issue(req.username(),
                userOpt.map(SysUser::getTokenVersion).orElse(0));
        // 等保：口令使用时长与定期更换提醒（超过 90 天提示）
        long pwdAgeDays = userOpt
                .map(u -> java.time.Duration.between(u.getPasswordUpdatedAt(), java.time.Instant.now()).toDays())
                .orElse(0L);
        return R.ok(Map.of("token", token,
                "passwordAgeDays", pwdAgeDays,
                "passwordExpireWarning", pwdAgeDays >= 90,
                "mustChangePassword", userOpt.map(SysUser::getMustChangePassword).orElse(false)));
    }

    /** 自助修改密码（等保：口令 90 天更换提醒的配套动作） */
    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {}

    @PostMapping("/change-password")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> changePassword(@RequestBody ChangePasswordRequest req, Authentication authentication) {
        if (req.oldPassword() == null || req.oldPassword().isBlank()) return R.fail(1003, "原密码不能为空");
        if (req.newPassword() == null || req.newPassword().isBlank()) return R.fail(1004, "新密码不能为空");
        if (authentication == null || !authentication.isAuthenticated())
            throw new org.springframework.security.access.AccessDeniedException("需要登录后修改密码");
        SysUser user = lockUser(authentication.getName()).orElseThrow(() ->
                new org.springframework.security.access.AccessDeniedException("账号不存在或已停用"));
        if (!Boolean.TRUE.equals(user.getEnabled())
                || (authentication.getPrincipal() instanceof cn.ybcase.core.security.AuthUser principal
                    && principal.getTokenVersion() != user.getTokenVersion()))
            throw new org.springframework.security.access.AccessDeniedException("登录状态已变更，请重新登录");
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(user.getUsername(), req.oldPassword()));
        } catch (org.springframework.security.authentication.DisabledException e) {
            throw new org.springframework.security.access.AccessDeniedException("账号或所属机构已停用");
        } catch (BadCredentialsException e) {
            return R.fail(1003, "原密码错误");
        }
        if (req.newPassword().length() < 8
                || !req.newPassword().matches(".*[a-zA-Z].*") || !req.newPassword().matches(".*\\d.*")
                || req.newPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            return R.fail(1004, "新密码须至少8位、包含字母和数字，且UTF-8编码不超过72字节");
        }
        user.setPassword(passwordEncoder.encode(req.newPassword()));
        user.setPasswordUpdatedAt(java.time.Instant.now());
        user.setMustChangePassword(false);   // 本人已设口令
        // 改密即吊销全部旧令牌（含已泄露的），用户须重新登录
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        return R.ok();
    }

    private java.util.Optional<SysUser> lockUser(String username) {
        // Lock only sys_user, without eager role/menu joins. No other account row is acquired here.
        var rows = jdbc.queryForList("select id from sys_user where username=? for update", username);
        if (rows.isEmpty()) return java.util.Optional.empty();
        var user = userRepository.findById(((Number) rows.get(0).get("id")).longValue());
        user.ifPresent(entityManager::refresh);
        return user;
    }

    @GetMapping("/me")
    public R<Map<String, Object>> me(Authentication authentication) {
        SysUser user = userRepository.findByUsername(authentication.getName()).orElseThrow();
        List<Map<String, Object>> menus = user.getRoles().stream()
                .flatMap(r -> r.getMenus().stream())
                .filter(SysMenu::getEnabled)
                .distinct()
                .sorted(Comparator.comparing(SysMenu::getSortNo))
                .map(m -> Map.<String, Object>of(
                        "id", m.getId(),
                        "parentId", m.getParentId() == null ? 0 : m.getParentId(),
                        "name", m.getName(),
                        "type", m.getType(),
                        "path", m.getPath() == null ? "" : m.getPath(),
                        "perm", m.getPerm() == null ? "" : m.getPerm(),
                        "icon", m.getIcon() == null ? "" : m.getIcon()))
                .toList();
        return R.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "realName", user.getRealName(),
                "accountCategory", java.util.Objects.toString(user.getAccountCategory(), ""),
                "roles", user.getRoles().stream().map(r -> r.getCode()).toList(),
                "mustChangePassword", Boolean.TRUE.equals(user.getMustChangePassword()),
                "menus", menus));
    }
}
