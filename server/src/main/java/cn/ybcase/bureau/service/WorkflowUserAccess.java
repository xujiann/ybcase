package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Live workflow actors, locked after business sources and in stable user-ID order. */
@Service
@RequiredArgsConstructor
public class WorkflowUserAccess {
    private final JdbcTemplate jdbc;

    public record User(Long id, String username, boolean enabled, Long deptId, Set<String> roles, String accountCategory) {
        public boolean privileged() { return roles.contains("LEADER") || roles.contains("ADMIN"); }
        public boolean district() { return "DISTRICT".equals(accountCategory); }
    }

    public Map<String, User> lockUsers(String... usernames) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Workflow actor checks must be transactional");
        List<String> names = Arrays.stream(usernames).filter(Objects::nonNull).distinct().toList();
        if (names.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
        var rows = jdbc.queryForList("select id, username, enabled, dept_id, account_category from sys_user where username in ("
                + placeholders + ") order by id for update", names.toArray());
        Map<String, User> actors = new LinkedHashMap<>();
        // All actor rows are locked before roles are read; role changes lock the same user rows.
        for (var row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            String username = (String) row.get("username");
            var codes = jdbc.queryForList("select r.code from sys_role r join sys_user_role ur on ur.role_id=r.id where ur.user_id=?",
                    String.class, id);
            Long deptId = row.get("dept_id") == null ? null : ((Number) row.get("dept_id")).longValue();
            actors.put(username, new User(id, username, Boolean.TRUE.equals(row.get("enabled")), deptId,
                    Set.copyOf(codes), (String) row.get("account_category")));
        }
        return actors;
    }

    public User requireFiling(String username) { return requireFiling(lockUsers(username).get(username)); }
    public User requireFiling(User user) {
        if (user == null || !user.enabled() || user.roles().stream().noneMatch(Set.of("HANDLER", "LEADER", "ADMIN")::contains))
            throw new BizException(403, "申请人账号不存在、已停用或不具备立案申请岗位");
        assertDepartmentEnabled(user);
        return user;
    }
    public User requireApprover(User user) {
        if (user == null || !user.enabled() || user.roles().stream().noneMatch(Set.of("LEADER", "ADMIN")::contains))
            throw new BizException(403, "审批人账号不存在、已停用或不具备负责人审批岗位");
        assertDepartmentEnabled(user);
        return user;
    }

    public void assertDepartmentEnabled(User user) {
        if (user.deptId() != null
                && !Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from sys_dept where id=? and enabled)", Boolean.class, user.deptId())))
            throw new BizException(403, "账号所属机构已停用");
    }
}
