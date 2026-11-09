package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.core.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Material authorization checked within the same transaction as its data, after business locks for writes. */
@Service
@RequiredArgsConstructor
public class MaterialAccess {
    private final JdbcTemplate jdbc;
    private final WorkflowUserAccess workflowUsers;

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Material access must be transactional");
    }

    public WorkflowUserAccess.User lockActor(String username, boolean writer) {
        requireTransaction();
        var user = workflowUsers.lockUsers(username).get(username);
        validate(user, writer);
        return user;
    }

    /** No row lock: readers authorize and load all material data from their repeatable-read snapshot. */
    public WorkflowUserAccess.User readActor(String username) {
        requireTransaction();
        var rows = jdbc.queryForList("select id,username,enabled,dept_id,account_category from sys_user where username=?", username);
        if (rows.isEmpty()) throw new BizException(403, "账号不存在或已停用");
        var row = rows.get(0);
        Long id = ((Number) row.get("id")).longValue();
        var roles = jdbc.queryForList("select r.code from sys_role r join sys_user_role ur on ur.role_id=r.id where ur.user_id=?",
                String.class, id);
        var user = new WorkflowUserAccess.User(id, (String) row.get("username"), Boolean.TRUE.equals(row.get("enabled")),
                row.get("dept_id") instanceof Number dept ? dept.longValue() : null, Set.copyOf(roles),
                (String) row.get("account_category"));
        validate(user, false);
        return user;
    }

    private void validate(WorkflowUserAccess.User user, boolean writer) {
        if (user == null || !user.enabled()) throw new BizException(403, "账号不存在或已停用");
        workflowUsers.assertDepartmentEnabled(user);
        if (writer && user.roles().stream().noneMatch(Set.of("HANDLER", "LEADER", "ADMIN")::contains))
            throw new BizException(403, "当前岗位无权办理材料");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && user.username().equals(auth.getName()) && auth.getPrincipal() instanceof AuthUser principal) {
            Integer version = jdbc.queryForObject("select token_version from sys_user where id=?", Integer.class, user.id());
            if (version == null || version != principal.getTokenVersion())
                throw new BizException(403, "登录状态已变更，请重新登录");
        }
    }

    public void assertCaseScope(Long caseId, WorkflowUserAccess.User user) {
        boolean privileged = user.roles().stream().anyMatch(Set.of("LEADER", "ADMIN", "LEGAL")::contains);
        var scope = caseScope(user.username(), privileged, new DepartmentScope(user.district(), user.deptId()));
        assertCaseScope(caseId, scope);
    }

    public record DepartmentScope(boolean district, Long deptId) {}

    /** Capture the authenticated writer's organization before a new source is numbered. */
    Long creationDepartment(String username) {
        var actor = requireCurrentRoles("HANDLER", "LEADER", "ADMIN");
        if (actor == null || !actor.username().equals(username))
            throw new BizException(403, "登记人与当前账号不一致");
        if (actor.district() && actor.deptId() == null)
            throw new BizException(2080, "区市县账号尚未关联机构");
        return actor.deptId();
    }

    public DepartmentScope departmentScope(String username) {
        var rows = jdbc.queryForList("""
                select u.account_category, u.dept_id, coalesce(d.enabled, false) as dept_enabled
                from sys_user u left join sys_dept d on d.id=u.dept_id where u.username=?
                """, username);
        if (rows.isEmpty()) return new DepartmentScope(false, null);
        var row = rows.getFirst();
        boolean enabled = Boolean.TRUE.equals(row.get("dept_enabled"));
        return new DepartmentScope("DISTRICT".equals(row.get("account_category")),
                enabled && row.get("dept_id") instanceof Number dept ? dept.longValue() : null);
    }

    /** A suffix against the unaliased case_file table, shared by list, detail and material checks. */
    public record CaseScope(String sql, List<Object> args) {
        public boolean restricted() { return !sql.isEmpty(); }
    }

    public CaseScope caseScope(String username, boolean privileged) {
        return caseScope(username, privileged, departmentScope(username));
    }

    private CaseScope caseScope(String username, boolean privileged, DepartmentScope department) {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (department.district()) {
            if (department.deptId() == null) return new CaseScope(" and false", List.of());
            sql.append(" and case_file.owner_dept_id = ?");
            args.add(department.deptId());
        }
        if (!privileged && selfScope()) {
            sql.append(ParticipantAuthorizationService.SCOPE_SQL);
            args.add(username);
            args.add(username);
            args.add(username);
        }
        return new CaseScope(sql.toString(), List.copyOf(args));
    }

    public void assertCaseScope(Long caseId, CaseScope scope) {
        if (!scope.restricted()) return;
        List<Object> args = new ArrayList<>();
        args.add(caseId);
        args.addAll(scope.args());
        Integer hit = jdbc.queryForObject("select count(*) from case_file where id=?" + scope.sql(),
                Integer.class, args.toArray());
        if (hit == null || hit == 0) throw new BizException(2080, "该案件不在您的数据查看范围内");
    }

    /** Authorization policy must use the same DB snapshot as materials, rather than a process-wide config cache. */
    public boolean selfScope() {
        var values = jdbc.queryForList("select cfg_value from sys_config where cfg_key=?", String.class, "case_view_scope");
        return !values.isEmpty() && "SELF".equalsIgnoreCase(values.get(0));
    }

    /** Caller already holds the case lock. Do not call this before a business source lock. */
    public WorkflowUserAccess.User requireCaseWriter(Long caseId, String username) {
        var actor = lockActor(username, true);
        assertCaseScope(caseId, actor);
        return actor;
    }

    public WorkflowUserAccess.User requireCaseReader(Long caseId, String username) {
        var actor = readActor(username);
        assertCaseScope(caseId, actor);
        return actor;
    }

    /** Existing internal domain callers have no HTTP principal; HTTP paths always carry an authenticated context. */
    public void requireCurrentCaseWriter(Long caseId) {
        Authentication auth = current();
        if (auth != null) requireCaseWriter(caseId, auth.getName());
    }

    /** Caller holds every business-source lock; the action's existing role policy is checked live. */
    public WorkflowUserAccess.User requireCurrentRoles(String... allowedRoles) {
        Authentication auth = current();
        if (auth == null) return null;
        var actor = lockActor(auth.getName(), false);
        if (java.util.Arrays.stream(allowedRoles).noneMatch(actor.roles()::contains))
            throw new BizException(403, "当前岗位无权办理此项操作");
        return actor;
    }

    public WorkflowUserAccess.User requireCurrentCaseRoles(Long caseId, String... allowedRoles) {
        var actor = requireCurrentRoles(allowedRoles);
        if (actor != null) assertCaseScope(caseId, actor);
        return actor;
    }

    public WorkflowUserAccess.User requireCurrentCaseReader(Long caseId) {
        Authentication auth = current();
        return auth == null ? null : requireCaseReader(caseId, auth.getName());
    }

    /** Authorize a supervisory read from the same snapshot as the returned data. */
    public WorkflowUserAccess.User requireCurrentReadRoles(String... allowedRoles) {
        Authentication auth = current();
        if (auth == null) return null;
        var actor = readActor(auth.getName());
        if (java.util.Arrays.stream(allowedRoles).noneMatch(actor.roles()::contains))
            throw new BizException(403, "当前岗位无权查看此项业务");
        return actor;
    }

    private Authentication current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal()) ? auth : null;
    }
}
