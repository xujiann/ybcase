package cn.ybcase.core.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Administrator reads authorize from the same repeatable-read snapshot as their payload. */
@Service
@RequiredArgsConstructor
public class AdminReadAccess {
    private final JdbcTemplate jdbc;

    public void requireCurrentAdmin() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Administrator reads must be transactional");
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()))
            throw new AccessDeniedException("需要管理员登录");
        var rows=jdbc.queryForList("""
                select u.enabled,u.token_version,
                  (u.dept_id is null or exists(select 1 from sys_dept d where d.id=u.dept_id and d.enabled)) as dept_enabled,
                  exists(select 1 from sys_user_role ur join sys_role r on r.id=ur.role_id
                         where ur.user_id=u.id and r.code='ADMIN') as is_admin
                from sys_user u where u.username=?""",auth.getName());
        if(rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("enabled"))
                || !Boolean.TRUE.equals(rows.get(0).get("dept_enabled"))
                || !Boolean.TRUE.equals(rows.get(0).get("is_admin")))
            throw new AccessDeniedException("账号已停用或不再具备管理员岗位");
        if(auth.getPrincipal() instanceof AuthUser principal
                && ((Number)rows.get(0).get("token_version")).intValue()!=principal.getTokenVersion())
            throw new AccessDeniedException("登录状态已变更，请重新登录");
    }
}
