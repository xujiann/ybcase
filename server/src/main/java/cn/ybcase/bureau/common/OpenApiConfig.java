package cn.ybcase.bureau.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 接口文档：外部对接（智能监控/经办/SSO 厂商）与实施联调的契约来源 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ybcaseOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("医保基金监管案件查办系统 API")
                        .description("""
                                依据《医疗保障行政处罚程序暂行规定》（国家医保局令第4号）。
                                认证：POST /api/auth/login 获取 JWT，后续请求 Authorization: Bearer <token>。
                                业务错误以 {code, message} 返回（code 2xxx 为法定守卫，403 为角色越权）。
                                对接重点：/api/bureau/clues/import（监控疑点批量导入）、
                                /api/bureau/clues/fetch-monitor（拉取式对接 SPI）、
                                /api/bureau/cases/*/agreement-actions（协议处理联动）。""")
                        .version("1.2.0"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
