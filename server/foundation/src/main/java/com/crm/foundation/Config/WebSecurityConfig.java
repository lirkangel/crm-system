package com.crm.foundation.Config;

import com.crm.foundation.Middleware.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class WebSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final PublicRateLimitFilter publicRateLimitFilter;

    public WebSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                             PublicRateLimitFilter publicRateLimitFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.publicRateLimitFilter = publicRateLimitFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(
                session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(
                    (req, res, e) -> res.sendError(401, "Unauthorized")))
            .authorizeHttpRequests(
                auth ->
                    // The ERROR dispatch of an already-filtered request (sendError) runs without the
                    // JWT filter; permit it so 400/405/… keep their status instead of becoming 401.
                    // Direct requests to /error are REQUEST dispatches and still need authentication.
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                            "/api/v1/auth/login",
                            "/api/v1/auth/refresh",
                            "/api/v1/auth/logout",
                            "/api/v1/auth/revoke/**",
                            // WS upgrade: token validated by SyncHandshakeInterceptor (browsers can't set headers here)
                            "/ws/sync",
                            "/actuator/health/**",
                            "/v3/api-docs/**",
                            "/swagger-ui/**",
                            "/swagger-ui.html",
                            // Login-free plugin endpoints (@PublicEndpoint); PluginRouteRegistry only
                            // allows @PublicEndpoint handlers under this prefix, and nothing else there
                            "/api/v1/plugins/*/public/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // Rate limit and no-store/noindex for public paths run before anything else can reject
            .addFilterBefore(publicRateLimitFilter, JwtAuthenticationFilter.class);
        return http.build();
    }
}
