package com.turnos.auth;

import com.turnos.common.ProblemDetailAccessDeniedHandler;
import com.turnos.common.ProblemDetailAuthenticationEntryPoint;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemDetailAccessDeniedHandler accessDeniedHandler;
    private final List<String> allowedOrigins;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler,
            @Value("${app.cors.allowed-origins:}") List<String> allowedOrigins) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.allowedOrigins = allowedOrigins.stream().filter(o -> !o.isBlank()).toList();
    }

    // Returns no configuration at all when app.cors.allowed-origins is empty,
    // so a same-origin deployment behaves exactly as it did before this bean
    // existed. Credentials stay off: the SPA sends the JWT in a header, not
    // a cookie.
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        if (allowedOrigins.isEmpty()) {
            return request -> null;
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/webhooks/whatsapp",
                                // Only exists under the "demo" profile
                                // (DemoResetController); 404 otherwise. Its
                                // own env token is the authentication.
                                "/api/demo/reset",
                                "/api/demo/chat-tenant",
                                // Customer web chat (ConversationService):
                                // anonymous by design, tenant from the path.
                                "/api/public/**",
                                "/error",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/actuator/health/**")
                        .permitAll()
                        // Super Admin manages tenants/platform support only (spec section 2);
                        // every other authenticated endpoint belongs to a tenant's Admin.
                        .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
                        // The AI service's tools, with the short-lived AGENT
                        // token Spring issues per turn. AGENT gets nothing
                        // else: anyRequest() below requires ADMIN.
                        .requestMatchers("/internal/agent/**").hasRole("AGENT")
                        .anyRequest().hasRole("ADMIN"))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
