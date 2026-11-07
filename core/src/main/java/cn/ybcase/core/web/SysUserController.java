package cn.ybcase.core.web;

import cn.ybcase.core.common.R;
import cn.ybcase.core.entity.SysRole;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.AdminReadAccess;
import cn.ybcase.core.spi.UserDeactivationGuard;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/system/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SysUserController {

    private static final int MAX_ROSTER_SIZE = 100;
    private static final Set<String> ACCOUNT_CATEGORIES = Set.of("DISTRICT", "BUREAU", "AGENCY");
    private static final Set<String> BUILT_IN_ACCOUNTS = Set.of("admin", "banban", "fazhi", "juzhang");
    private static final SecureRandom ROSTER_RANDOM = new SecureRandom();

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    // 业务模块（server）注册的停用守卫；core 不依赖 server，故经 SPI 反向询问
    private final ObjectProvider<UserDeactivationGuard> deactivationGuards;
    private final AdminReadAccess adminReads;
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

    public record RosterRequest(List<RosterRow> rows) {}
    public record RosterRow(String action, String username, String realName, String departmentCode,
                            String accountCategory, List<String> roleCodes) {}
    public record RosterPreviewRow(int row, String username, String status, String message) {}
    public record RosterAppliedRow(int row, String username,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String initialPassword) {}
    private record RosterDepartment(long id, String code, String kind, boolean enabled) {}
    private record RosterCheck(RosterPreviewRow preview, RosterDepartment department, SysUser existing) {}

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
        lockUsername(req.username());
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
        return R.ok(UserDto.from(createAccount(req, req.password())));
    }

    @PostMapping("/roster/preview")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public R<Map<String, List<RosterPreviewRow>>> previewRoster(@RequestBody RosterRequest request,
                                                               Principal principal, HttpServletResponse response) {
        adminReads.requireCurrentAdmin();
        response.setHeader("Cache-Control", "no-store");
        if (invalidRosterSize(request)) return R.fail(1106, "名册须包含1至100行");
        var rows = checkRoster(request.rows(), rosterDepartments(request.rows(), false), principal.getName(), false);
        return R.ok(Map.of("rows", rows.stream().map(RosterCheck::preview).toList()));
    }

    @PostMapping("/roster/apply")
    @Transactional
    public R<Map<String, List<RosterAppliedRow>>> applyRoster(@RequestBody RosterRequest request,
                                                             HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (invalidRosterSize(request)) return R.fail(1106, "名册须包含1至100行");
        List<RosterRow> rows = request.rows();
        var names = rows.stream().filter(Objects::nonNull).map(RosterRow::username)
                .filter(name -> rosterText(name, 64)).distinct().sorted().toList();
        // Match single-account creation's absent-key lock, then lock department and user rows in ID order.
        names.forEach(this::lockUsername);
        var departments = rosterDepartments(rows, true);
        Long[] userIds = names.isEmpty() ? new Long[0] : jdbc.queryForList(
                "select id from sys_user where username in (" + placeholders(names.size()) + ")",
                Long.class, names.toArray()).toArray(Long[]::new);
        String operator = adminWrites.requireCurrentAdmin(userIds);
        var checked = checkRoster(rows, departments, operator, true);
        for (var row : checked) {
            if ("ERROR".equals(row.preview().status()))
                return R.fail(1106, "第" + row.preview().row() + "行：" + row.preview().message());
        }

        var applied = new ArrayList<RosterAppliedRow>(rows.size());
        var passwords = new HashSet<String>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var check = checked.get(i);
            String initialPassword = null;
            if (check.existing() == null) {
                initialPassword = initialPassword(passwords);
                createAccount(rosterAccount(row, check.department().id()), initialPassword);
            } else {
                var existing = check.existing();
                existing.setDeptId(check.department().id());
                existing.setAccountCategory(row.accountCategory());
                existing.setTokenVersion(existing.getTokenVersion() + 1);
            }
            applied.add(new RosterAppliedRow(i + 1, row.username(), initialPassword));
        }
        return R.ok(Map.of("rows", applied));
    }

    private List<RosterCheck> checkRoster(List<RosterRow> rows, Map<String, RosterDepartment> departments,
                                          String operator, boolean forWrite) {
        var counts = rows.stream().filter(Objects::nonNull).map(RosterRow::username).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        var checked = new ArrayList<RosterCheck>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            String error = rosterInputError(row, operator);
            SysUser existing = null;
            RosterDepartment dept = null;
            if (error == null && counts.get(row.username()) > 1)
                error = "同一用户名在名册中重复";
            if (error == null) {
                dept = departments.get(row.departmentCode());
                if (dept == null || !dept.enabled() || !row.accountCategory().equals(dept.kind())) {
                    error = "机构编码须对应已启用且类别匹配的正式机构";
                } else {
                    existing = userRepository.findByUsername(row.username()).orElse(null);
                    if (forWrite && existing != null) existing = lockedUser(existing.getId());
                    error = rosterAccountError(row, dept, existing);
                }
            }
            checked.add(new RosterCheck(new RosterPreviewRow(i + 1, row == null || row.username() == null ? "" : row.username(),
                    error == null ? "READY" : "ERROR", error == null ? "校验通过" : error), dept, existing));
        }
        return checked;
    }

    private String rosterInputError(RosterRow row, String operator) {
        if (row == null || !("CREATE".equals(row.action()) || "CLASSIFY".equals(row.action())))
            return "操作须为新建或归类";
        if (!rosterText(row.username(), 64) || !rosterText(row.realName(), 64)
                || !rosterText(row.departmentCode(), 32))
            return "请填写有效的用户名、姓名和机构编码，且首尾不得有空格";
        if (BUILT_IN_ACCOUNTS.contains(row.username())) return "内置账号不可通过名册处理";
        if ("CLASSIFY".equals(row.action()) && row.username().equals(operator))
            return "请由另一位管理员归类当前登录账号";
        if (row.accountCategory() == null || !ACCOUNT_CATEGORIES.contains(row.accountCategory()))
            return "请选择有效的账号类别";
        var roles = row.roleCodes();
        if ("CLASSIFY".equals(row.action()))
            return roles != null && !roles.isEmpty() ? "旧账号归类沿用现有岗位" : null;
        if (roles == null || roles.isEmpty() || roles.size() > 32 || roles.stream().anyMatch(code -> !rosterText(code, 32))
                || roles.stream().distinct().count() != roles.size())
            return "新建账号须填写有效且不重复的岗位编码";
        return null;
    }

    private String rosterAccountError(RosterRow row, RosterDepartment dept, SysUser existing) {
        if ("CREATE".equals(row.action())) {
            if (existing != null) return "用户名已存在";
            String fields = fieldError(rosterAccount(row, dept.id()), true);
            return fields != null ? fields : roleAssignmentError(row.accountCategory(), row.roleCodes());
        }
        if (existing == null) return "待归类账号不存在";
        if (existing.getAccountCategory() != null) return "账号已归类";
        if (!row.realName().equals(existing.getRealName())) return "姓名与现有账号不一致";
        if (existing.getDeptId() != null && existing.getDeptId() != dept.id())
            return "已有其他所属机构，请使用正常的人员调动流程";
        return roleAssignmentError(row.accountCategory(), existing.getRoles().stream().map(SysRole::getCode).toList());
    }

    private Map<String, RosterDepartment> rosterDepartments(List<RosterRow> rows, boolean lock) {
        var codes = rows.stream().filter(Objects::nonNull).map(RosterRow::departmentCode)
                .filter(code -> rosterText(code, 32)).distinct().toList();
        if (codes.isEmpty()) return Map.of();
        return jdbc.query("select id, code, org_kind, enabled from sys_dept where code in (" + placeholders(codes.size())
                        + ") order by id" + (lock ? " for share" : ""),
                (rs, index) -> new RosterDepartment(rs.getLong("id"), rs.getString("code"),
                        rs.getString("org_kind"), rs.getBoolean("enabled")), codes.toArray()).stream()
                .collect(Collectors.toMap(RosterDepartment::code, Function.identity()));
    }

    private static SaveUserRequest rosterAccount(RosterRow row, long departmentId) {
        return new SaveUserRequest(row.username(), null, row.realName(), null, departmentId, null,
                row.roleCodes(), row.accountCategory());
    }

    private static boolean invalidRosterSize(RosterRequest request) {
        return request == null || request.rows() == null || request.rows().isEmpty() || request.rows().size() > MAX_ROSTER_SIZE;
    }

    private static boolean rosterText(String value, int length) {
        return value != null && !value.isBlank() && value.length() <= length
                && value.equals(value.strip()) && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static String initialPassword(Set<String> generated) {
        byte[] random = new byte[18];
        String password;
        do {
            ROSTER_RANDOM.nextBytes(random);
            password = "R7!" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        } while (!generated.add(password));
        return password;
    }

    private void lockUsername(String username) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended('sys_user.username:' || ?,0))", username);
    }

    private static String placeholders(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    private SysUser createAccount(SaveUserRequest request, String password) {
        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(password));
        user.setMustChangePassword(true);
        applyFields(user, request);
        return userRepository.save(user);
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
                && !ACCOUNT_CATEGORIES.contains(req.accountCategory()))
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
        String roleError = roleAssignmentError(category, roles);
        if (roleError != null) return roleError;
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

    private String roleAssignmentError(String category, List<String> roles) {
        boolean agencyRole = roles != null && roles.contains("AGENCY");
        if ("AGENCY".equals(category)) {
            if (!agencyRole || roles.stream().anyMatch(role -> !"AGENCY".equals(role)))
                return "医保中心经办账号仅可配置医保中心经办角色";
        } else if (agencyRole) {
            return "医保中心经办角色仅可配置给医保中心经办账号";
        }
        if ("DISTRICT".equals(category) && roles != null && roles.contains("ADMIN"))
            return "区市县账号不能配置系统管理员角色";
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
