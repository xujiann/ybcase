package cn.ybcase.bureau.common;

import cn.ybcase.core.common.R;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Capture all R.fail paths, including controller returns and every exception handler. */
@RestControllerAdvice(basePackages = "cn.ybcase")
public class BusinessResultAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) { return true; }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter parameter, MediaType type,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof R<?> result && request instanceof ServletServerHttpRequest servlet)
            servlet.getServletRequest().setAttribute(BureauAuditFilter.BIZ_CODE_ATTR, result.getCode());
        return body;
    }
}
