package cn.ybcase.core.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/** Live administrator writes, with actor and affected accounts locked in one stable order. */
@Service
@RequiredArgsConstructor
public class AdminWriteAccess {
    private final JdbcTemplate jdbc;

    public String requireCurrentAdmin(Long... targetUserIds) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Administrator writes must be transactional");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Existing internal domain calls have no HTTP context; the security filter protects HTTP routes.
        if (auth == null) return null;
        if (!auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()))
            throw new AccessDeniedException("需要管理员登录");
        var targets = Arrays.stream(targetUserIds).filter(Objects::nonNull).distinct().toList();
        var args = new ArrayList<Object>();
        args.add(auth.getName());args.addAll(targets);
        String targetSql = targets.isEmpty() ? "" : " or id in ("
                + String.join(",", java.util.Collections.nCopies(targets.size(), "?")) + ")";
        var rows = jdbc.queryForList("select id, username, enabled, token_version, dept_id from sys_user where username=?"
                + targetSql + " order by id for update", args.toArray());
        var actor = rows.stream().filter(row -> auth.getName().equals(row.get("username"))).findFirst().orElse(null);
        if (actor == null || !Boolean.TRUE.equals(actor.get("enabled")))
            throw new AccessDeniedException("管理员账号不存在或已停用");
        if (actor.get("dept_id") instanceof Number department && !Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from sys_dept where id=? and enabled)", Boolean.class, department.longValue())))
            throw new AccessDeniedException("管理员所属机构已停用");
        long id = ((Number) actor.get("id")).longValue();
        if (auth.getPrincipal() instanceof AuthUser principal
                && ((Number) actor.get("token_version")).intValue() != principal.getTokenVersion())
            throw new AccessDeniedException("登录状态已变更，请重新登录");
        Integer count = jdbc.queryForObject("select count(*) from sys_user_role ur join sys_role r on r.id=ur.role_id "
                + "where ur.user_id=? and r.code='ADMIN'", Integer.class, id);
        if (count == null || count == 0) throw new AccessDeniedException("当前账号已不具备管理员岗位");
        return auth.getName();
    }
}
