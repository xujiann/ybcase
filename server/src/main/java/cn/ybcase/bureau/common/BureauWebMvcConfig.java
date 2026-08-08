package cn.ybcase.bureau.common;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class BureauWebMvcConfig implements WebMvcConfigurer {

    private final CaseScopeInterceptor caseScopeInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(caseScopeInterceptor).addPathPatterns("/api/bureau/cases/**");
    }
}
