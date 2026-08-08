package cn.ybcase.bureau.common;

import cn.ybcase.bureau.service.CaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 案件数据范围统一拦截（case_view_scope=SELF）。
 * 逐个接口加 assertInScope 会漏（第二轮补了读侧，写侧仍全线敞开），
 * 故在 /api/bureau/cases/{数字id}/** 上统一拦截读写，新增接口自动受保护。
 */
@Component
@RequiredArgsConstructor
public class CaseScopeInterceptor implements HandlerInterceptor {

    private static final String PREFIX = "/api/bureau/cases/";

    private final CaseService caseService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long caseId = caseIdOf(request.getRequestURI());
        if (caseId == null) return true;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return true;  // 未登录交给授权规则拦截
        caseService.assertInScope(caseId, auth.getName(), privileged(auth));
        return true;
    }

    /** 取 /api/bureau/cases/{id}… 中的数字段；非数字（如 /cases/reviews/1）返回 null */
    private static Long caseIdOf(String uri) {
        if (uri == null || !uri.startsWith(PREFIX)) return null;
        int end = uri.indexOf('/', PREFIX.length());
        String seg = end < 0 ? uri.substring(PREFIX.length()) : uri.substring(PREFIX.length(), end);
        if (seg.isEmpty() || !seg.chars().allMatch(Character::isDigit)) return null;
        try {
            return Long.parseLong(seg);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean privileged(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_LEADER") || a.getAuthority().equals("ROLE_LEGAL")
                        || a.getAuthority().equals("ROLE_ADMIN"));
    }
}
