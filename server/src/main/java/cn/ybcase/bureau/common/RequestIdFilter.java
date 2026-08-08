package cn.ybcase.bureau.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 每个请求分配 X-Request-Id（响应头返回，审计日志落库）：
 * 用户反馈自动携带失败请求的 id → 服务端日志/审计一键对账，定位从"猜"变成"查"。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String ATTR = "ybcaseRequestId";
    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String rid = UUID.randomUUID().toString().substring(0, 18);
        request.setAttribute(ATTR, rid);
        response.setHeader(HEADER, rid);
        filterChain.doFilter(request, response);
    }
}
