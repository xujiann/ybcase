package cn.ybcase.core.web;

import cn.ybcase.core.common.R;
import cn.ybcase.core.entity.SysRole;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.spi.UserDeactivationGuard;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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
    // 业务模块（server）注册的停用守卫；core 不依赖 server，故经 SPI 反向询问
    private final ObjectProvider<UserDeactivationGuard> deactivationGuards;
    private final cn.ybcase.core.security.AdminWriteAccess adminWrites;
    private final jakarta.persistence.EntityManager entityManager;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public record UserDto(Long id, String username, String realName, String title,
                          Long deptId, String phone, Boolean enabled, List<String> roleCodes, java.time.Instant lockedUntil,
                          String accountCategory) {
        static UserDto from(SysUser u) {
            return new UserDto(u.getId(), u.getUsername(), u.getRealName(), u.getTitle(),
                    u.getDeptId(), u.getPhone(), u.getEnabled(),
                    u.getRoles().stream().map(SysRole::getCode).toList(), u.getLockedUntil(), u.getAccountCategory());
        }
    }

    public record SaveUserRequest(@NotBlank String username, String password, @NotBlank String realName,
                                  String title, Long deptId, String phone, List<String> roleCodes,
                                  String accountCategory) {
        public SaveUserRequest(String username, String password, String realName, String title,
                               Long deptId, String phone, List<String> roleCodes) {
            this(username, password, realName, title, deptId, phone, roleCodes, null);
        }
    }

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
        String fieldError = fieldError(req, true);
        if (fieldError != null) return R.fail(1106, fieldError);
        // Distinct administrators can both observe an absent username. Serialize that absent key
        // before actor locking, so the loser returns the existing 1101 without touching the winner.
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended('sys_user.username:' || ?,0))",req.username());
        adminWrites.requireCurrentAdmin();
        String assignmentError = assignmentError(req, null);
        if (assignmentError != null) return R.fail(1106, assignmentError);
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
        adminWrites.requireCurrentAdmin(id);
        SysUser u = lockedUser(id);
        if (u == null) return R.fail(1103, "用户不存在");
        String fieldError = fieldError(req, false);
        if (fieldError != null) return R.fail(1106, fieldError);
        String assignmentError = assignmentError(req, u);
        if (assignmentError != null) return R.fail(1106, assignmentError);
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
        if (req.roleCodes() != null) {
            for (UserDeactivationGuard guard : deactivationGuards) {
                List<String> blockers = guard.roleChangeBlockers(u.getUsername(), req.roleCodes());
                if (blockers != null && !blockers.isEmpty()) return R.fail(1105, String.join("；", blockers));
            }
        }
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
        adminWrites.requireCurrentAdmin(id);
        SysUser u = lockedUser(id);
        if (u == null) return R.fail(1103, "用户不存在");
        u.setLockedUntil(null);
        u.setFailedAttempts(0);
        userRepository.save(u);
        return R.ok();
    }

    @PutMapping("/{id}/enabled")
    @Transactional
    public R<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        // Assignment locks this same row before validating receiver eligibility.
        adminWrites.requireCurrentAdmin(id);
        SysUser u = lockedUser(id);
        if (u == null) return R.fail(1103, "用户不存在");
        if ("admin".equals(u.getUsername()) && !enabled) return R.fail(1104, "不能停用内置管理员");
        // 名下尚有在办事项（如案件）的账号停用后事项无人接手、期限提醒进死信箱：先移交再停用。
        // 与 update 同理，必须在改任何字段之前返回，否则受管实体会被脏检查刷库
        if (!enabled) {
            for (UserDeactivationGuard guard : deactivationGuards) {
                List<String> blockers = guard.blockers(u.getUsername());
                if (blockers != null && !blockers.isEmpty()) return R.fail(1105, String.join("；", blockers));
            }
        }
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
        if (pwd.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) return "密码UTF-8编码不能超过72字节";
        return null;
    }

    private SysUser lockedUser(Long id) {
        // The joint lock has already established ID order. Refresh also drops an entity cached by
        // authentication/OSIV before a concurrent update committed while this request was waiting.
        SysUser user = userRepository.findById(id).orElse(null);
        if (user != null) entityManager.refresh(user, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return user;
    }

    /** Validate the entire request before mutating a managed user: R.fail commits the transaction. */
    private String fieldError(SaveUserRequest req, boolean creating) {
        if (creating && (req.username() == null || req.username().isBlank() || req.username().indexOf('\0') >= 0 || req.username().length() > 64))
            return "用户名不能为空且不能超过64字";
        if (req.realName() == null || req.realName().isBlank() || req.realName().length() > 64)
            return "姓名不能为空且不能超过64字";
        if (req.accountCategory() != null
                && !java.util.Set.of("DISTRICT", "BUREAU", "AGENCY").contains(req.accountCategory()))
            return "账号类别须为区市县办案人员、局机关各处室或医保中心经办人员";
        if (req.title() != null && req.title().length() > 32) return "职务不能超过32字";
        if (req.phone() != null && req.phone().length() > 32) return "联系电话不能超过32字";
        if (req.roleCodes() != null) {
            for (String code : req.roleCodes()) {
                if (code == null || roleRepository.findByCode(code).isEmpty())
                    return "包含不存在的角色，请刷新角色列表后重新选择";
            }
        }
        return null;
    }

    private String assignmentError(SaveUserRequest req, SysUser existing) {
        String category = req.accountCategory() != null || existing == null
                ? req.accountCategory() : existing.getAccountCategory();
        List<String> roles = req.roleCodes() != null || existing == null
                ? req.roleCodes() : existing.getRoles().stream().map(SysRole::getCode).toList();
        boolean agencyRole = roles != null && roles.contains("AGENCY");
        if ("AGENCY".equals(category)) {
            if (!agencyRole || roles.stream().anyMatch(role -> !"AGENCY".equals(role)))
                return "医保中心经办账号仅可配置医保中心经办角色";
        } else if (agencyRole) {
            return "医保中心经办角色仅可配置给医保中心经办账号";
        }
        if ("DISTRICT".equals(category) && roles != null && roles.contains("ADMIN"))
            return "区市县账号不能配置系统管理员角色";
        if (req.deptId() == null) {
            return category == null ? null : "请选择账号所属机构";
        }
        var departments = jdbc.queryForList("select org_kind, enabled from sys_dept where id = ?", req.deptId());
        if (departments.isEmpty()) return "所选机构不存在";
        var department = departments.getFirst();
        String kind = (String) department.get("org_kind");
        if (!Boolean.TRUE.equals(department.get("enabled")))
            return "请选择启用的所属机构";
        if ("ROOT".equals(kind)) return "机构分组不能用于分配账号";
        if (kind != null && !kind.equals(category) || "AGENCY".equals(category) && kind == null)
            return "账号类别与所属机构不匹配";
        return null;
    }

    private void applyFields(SysUser u, SaveUserRequest req) {
        u.setRealName(req.realName());
        u.setTitle(req.title());
        u.setDeptId(req.deptId());
        if (req.accountCategory() != null) u.setAccountCategory(req.accountCategory());
        u.setPhone(req.phone());
        if (req.roleCodes() != null) {
            u.getRoles().clear();
            req.roleCodes().forEach(code ->
                    roleRepository.findByCode(code).ifPresent(u.getRoles()::add));
        }
    }
}
