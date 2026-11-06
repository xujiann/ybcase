package cn.ybcase.core.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 最小放行面：登录、登录页机构信息、健康检查；接口文档（生产以 springdoc.api-docs.enabled=false 整体关闭）
                        .requestMatchers("/api/auth/login", "/api/config/public", "/actuator/health",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/api/agency/**").hasRole("AGENCY")
                        .requestMatchers("/api/bureau/**").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAnyRole("HANDLER", "LEGAL", "LEADER", "ADMIN"),
                                AuthorizationManagers.not(AuthorityAuthorizationManager.hasRole("AGENCY"))))
                        .requestMatchers("/api/**").access(AuthorizationManagers.allOf(
                                AuthenticatedAuthorizationManager.authenticated(),
                                AuthorizationManagers.not(AuthorityAuthorizationManager.hasRole("AGENCY"))))
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh.authenticationEntryPoint(
                        (req, res, e) -> res.sendError(HttpStatus.UNAUTHORIZED.value(), "未登录或登录已过期"))
                        .accessDeniedHandler((req, res, e) -> {
                            res.setStatus(HttpStatus.FORBIDDEN.value());
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"code\":403,\"message\":\"无权限访问医保业务\",\"data\":null}");
                        }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
