package cn.ybcase.core.web;

import cn.ybcase.core.common.R;
import cn.ybcase.core.entity.SysRole;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SysUserController {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public record UserDto(Long id, String username, String realName, String title,
                          Long deptId, String phone, Boolean enabled, List<String> roleCodes, java.time.Instant lockedUntil) {
        static UserDto from(SysUser u) {
            return new UserDto(u.getId(), u.getUsername(), u.getRealName(), u.getTitle(),
                    u.getDeptId(), u.getPhone(), u.getEnabled(),
                    u.getRoles().stream().map(SysRole::getCode).toList(), u.getLockedUntil());
        }
    }

    public record SaveUserRequest(@NotBlank String username, String password, @NotBlank String realName,
                                  String title, Long deptId, String phone, List<String> roleCodes) {}

    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        var p = userRepository.findAll(PageRequest.of(page, size, Sort.by("id")));
        return R.ok(Map.of(
                "total", p.getTotalElements(),
                "records", p.getContent().stream().map(UserDto::from).toList()));
    }

    @PostMapping
    @Transactional
    public R<UserDto> create(@RequestBody SaveUserRequest req) {
        if (userRepository.findByUsername(req.username()).isPresent()) {
            return R.fail(1101, "用户名已存在");
        }
        String pwdError = passwordPolicyError(req.password());
        if (pwdError != null) {
            return R.fail(1102, pwdError);
        }
        SysUser u = new SysUser();
        u.setUsername(req.username());
        u.setPassword(passwordEncoder.encode(req.password()));
        u.setMustChangePassword(true);   // 管理员设的初始口令，首次登录须改
        applyFields(u, req);
        return R.ok(UserDto.from(userRepository.save(u)));
    }

    @PutMapping("/{id}")
    @Transactional
    public R<UserDto> update(@PathVariable Long id, @RequestBody SaveUserRequest req) {
        SysUser u = userRepository.findById(id).orElse(null);
        if (u == null) return R.fail(1103, "用户不存在");
        // 全部校验必须在 applyFields 之前：R.fail 不是异常，@Transactional 不会回滚，
        // 而 u 是受管实体，改完字段即便返回失败也会在事务提交时被脏检查刷库——
        // 结果是"口令没改成、姓名/科室/角色却已经改了"，且界面提示的是失败。
        if (req.password() != null && !req.password().isBlank()) {
            String pwdError = passwordPolicyError(req.password());
            if (pwdError != null) return R.fail(1102, pwdError);
        }
        // 与 setEnabled 的"不能停用内置管理员"同源：摘掉 admin 的 ADMIN 角色，
        // 全系统将无人可管理用户与参数，且无法自救。
        if ("admin".equals(u.getUsername())
                && (req.roleCodes() == null || !req.roleCodes().contains("ADMIN")))
            return R.fail(1104, "不能移除内置管理员的 ADMIN 角色");
        applyFields(u, req);
        if (req.password() != null && !req.password().isBlank()) {
            u.setPassword(passwordEncoder.encode(req.password()));
            u.setPasswordUpdatedAt(java.time.Instant.now());
            u.setMustChangePassword(true);   // 管理员重置的口令同样须由本人改一次
            // 管理员改他人口令（账号泄露处置路径）须吊销该账号旧令牌，否则旧 JWT 仍可用满有效期
            u.setTokenVersion(u.getTokenVersion() + 1);
        }
        return R.ok(UserDto.from(userRepository.save(u)));
    }

    /** 解除登录锁定：公网上任何人对着用户名错 5 次即可锁死账号，须给管理员一个解锁通道 */
    @PutMapping("/{id}/unlock")
    @Transactional
    public R<Void> unlock(@PathVariable Long id) {
        SysUser u = userRepository.findById(id).orElse(null);
        if (u == null) return R.fail(1103, "用户不存在");
        u.setLockedUntil(null);
        u.setFailedAttempts(0);
        userRepository.save(u);
        return R.ok();
    }

    @PutMapping("/{id}/enabled")
    @Transactional
    public R<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        SysUser u = userRepository.findById(id).orElse(null);
        if (u == null) return R.fail(1103, "用户不存在");
        if ("admin".equals(u.getUsername()) && !enabled) return R.fail(1104, "不能停用内置管理员");
        u.setEnabled(enabled);
        // 停用须立即断开在线会话，否则对方手中的令牌可继续使用至过期
        if (!enabled) u.setTokenVersion(u.getTokenVersion() + 1);
        userRepository.save(u);
        return R.ok();
    }

    /** 等保密码策略：至少 8 位且同时含字母与数字 */
    private String passwordPolicyError(String pwd) {
        if (pwd == null || pwd.length() < 8) return "密码不能少于 8 位";
        if (!pwd.matches(".*[A-Za-z].*") || !pwd.matches(".*\\d.*")) return "密码须同时包含字母和数字";
        return null;
    }

    private void applyFields(SysUser u, SaveUserRequest req) {
        u.setRealName(req.realName());
        u.setTitle(req.title());
        u.setDeptId(req.deptId());
        u.setPhone(req.phone());
        if (req.roleCodes() != null) {
            u.getRoles().clear();
            req.roleCodes().forEach(code ->
                    roleRepository.findByCode(code).ifPresent(u.getRoles()::add));
        }
    }
}
