package com.crm.foundation.Config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String JWT_SECURITY_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI foundationOpenApi() {
        return new OpenAPI()
            .info(
                new Info()
                    .title("Foundation API")
                    .description("CRM foundation — REST API")
                    .version("0.1.0"))
            .components(
                new Components()
                    .addSecuritySchemes(
                        JWT_SECURITY_SCHEME,
                        new SecurityScheme()
                            .name(JWT_SECURITY_SCHEME)
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                            .description(
                                "Valid access JWT in Authorization header (Bearer). "
                                    + "Login currently returns a refresh identifier; use the access token your client obtains for API calls.")));
    }
}
