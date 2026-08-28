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

    /** 异常处理器把业务错误码放到这里，供审计记录真实结果 */
    public static final String BIZ_CODE_ATTR = "ybcaseBizCode";

    /**
     * 反代后 getRemoteAddr() 拿到的是 Caddy 容器内网 IP，所有审计记录来源相同，
     * 执法全过程记录与等保审计都无法溯源到实际操作终端。取 X-Forwarded-For 首个地址
     * （Caddy 默认追加；该头由我们自己的反代写入，未经反代直连时回退 remoteAddr）。
     */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty() && first.length() <= 45) return first;
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank() && real.length() <= 45) return real.trim();
        return request.getRemoteAddr();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            String uri = request.getRequestURI();
            // 全部写操作 + 敏感读取（附件下载/公示导出/审计查询/反馈截图/卷宗合成）留痕
            boolean sensitiveRead = "GET".equals(request.getMethod())
                    && (uri.contains("/download") || uri.contains("/publish-export") || uri.startsWith("/api/audit")
                        || uri.contains("/screenshot") || uri.contains("/archive-full"));
            if ((WRITE_METHODS.contains(request.getMethod()) || sensitiveRead)
                    && uri.startsWith("/api")
                    && !uri.equals("/api/auth/login")) {
                try {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    // 业务异常统一以 HTTP 200 + 业务码返回，若只记 http_status，
                    // 越权尝试在审计里与成功操作完全同形；故把业务码并入状态列
                    Object bizCode = request.getAttribute(BIZ_CODE_ATTR);
                    int status = bizCode instanceof Integer c && c != 0 ? c : response.getStatus();
                    jdbc.update("""
                            insert into sys_audit_log (username, method, path, http_status, client_ip, request_id)
                            values (?,?,?,?,?,?)""",
                            auth == null ? null : auth.getName(), request.getMethod(),
                            uri, status, clientIp(request),
                            request.getAttribute(RequestIdFilter.ATTR));
                } catch (Exception ignore) {
                    // 审计失败不影响业务
                }
            }
        }
    }
}
