package cn.ybcase.bureau.common;

import cn.ybcase.bureau.service.CaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 案件数据范围统一拦截（case_view_scope=SELF）。
 * 逐个接口加 assertInScope 会漏（第二轮补了读侧，写侧仍全线敞开），
 * 故在 /api/bureau/cases/{id 或 caseId}/** 上统一拦截读写，新增接口自动受保护。
 *
 * 用 Spring 解析后的路径变量（已解码、已剥 matrix 参数与 context-path），
 * 而不是原始 request.getRequestURI()——后者会被 /cases/%31%32/… 或 /cases/12;x=1/…
 * 绕过（原始段非纯数字→跳过校验，但控制器 @PathVariable 仍解码成 12）。
 */
@Component
@RequiredArgsConstructor
public class CaseScopeInterceptor implements HandlerInterceptor {

    private final CaseService caseService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attr instanceof Map<?, ?> vars)) return true;  // 非 @RequestMapping 处理器（静态资源等）
        // 案件路由的路径变量名只有 id / caseId 两种（已 grep 全量确认）
        Object raw = vars.containsKey("caseId") ? vars.get("caseId") : vars.get("id");
        Long caseId = parseId(raw);
        if (caseId == null) return true;  // 该接口不以案件 id 为首个路径变量（如 /reviews/{reviewId}）

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return true;  // 未登录交给授权规则拦截
        caseService.assertInScope(caseId, auth.getName(), privileged(auth));
        return true;
    }

    private static Long parseId(Object raw) {
        if (raw == null) return null;
        String s = raw.toString();
        if (s.isEmpty() || !s.chars().allMatch(Character::isDigit)) return null;
        try {
            return Long.parseLong(s);
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
