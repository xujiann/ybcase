package cn.ybcase.bureau.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/** 执法全过程记录（局令第4/35条）+等保审计：全部写操作留痕 sys_audit_log */
@Component
@RequiredArgsConstructor
public class BureauAuditFilter extends OncePerRequestFilter {

    private final JdbcTemplate jdbc;

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            String uri = request.getRequestURI();
            // 全部写操作 + 敏感读取（附件下载/公示导出/审计查询）留痕
            boolean sensitiveRead = "GET".equals(request.getMethod())
                    && (uri.contains("/download") || uri.contains("/publish-export") || uri.startsWith("/api/audit"));
            if ((WRITE_METHODS.contains(request.getMethod()) || sensitiveRead)
                    && uri.startsWith("/api")
                    && !uri.equals("/api/auth/login")) {
                try {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    jdbc.update("""
                            insert into sys_audit_log (username, method, path, http_status, client_ip)
                            values (?,?,?,?,?)""",
                            auth == null ? null : auth.getName(), request.getMethod(),
                            uri, response.getStatus(), request.getRemoteAddr());
                } catch (Exception ignore) {
                    // 审计失败不影响业务
                }
            }
        }
    }
}
