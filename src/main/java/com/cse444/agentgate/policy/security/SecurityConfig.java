package com.cse444.agentgate.policy.security;

import com.cse444.agentgate.shared.config.AgentGateProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Spring Security OAuth2 Resource Server, RBAC & Custom AuthorizationManager Configuration (C3, FR2, FR3).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Resource-server chain for every request not handled by the Spring Authorization Server chain
     * (identity.security.AuthorizationServerConfig, order 1), which issues the tokens validated here.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AgentGateAuthorizationManager agentGateAuthorizationManager,
            ObjectMapper objectMapper
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/css/**",
                                "/js/**",
                                "/favicon.svg",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        .requestMatchers("/mcp/**", "/api/v1/gateway/**")
                        .access(agentGateAuthorizationManager)
                        .requestMatchers("/api/v1/approvals/**", "/approvals/**")
                        .hasAnyRole("ADMIN", "APPROVER")
                        .requestMatchers("/api/v1/audit/**")
                        .hasAnyRole("ADMIN", "AUDITOR")
                        .requestMatchers(
                                "/api/v1/tools/**",
                                "/api/v1/agents/**",
                                "/api/v1/policies/**",
                                "/api/v1/limits/**",
                                "/actuator/**"
                        ).hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            String accept = request.getHeader("Accept");
                            if (accept != null && accept.contains(MediaType.TEXT_HTML_VALUE)) {
                                response.setHeader("WWW-Authenticate", "Basic realm=\"AgentGate\"");
                            }
                            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                                    HttpStatus.UNAUTHORIZED, authException.getMessage());
                            problem.setTitle("Authentication Required");
                            problem.setType(URI.create("https://agentgate.cse444.com/problems/unauthorized"));
                            problem.setProperty("timestamp", Instant.now().toString());
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            objectMapper.writeValue(response.getWriter(), problem);
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                                    HttpStatus.FORBIDDEN, accessDeniedException.getMessage());
                            problem.setTitle("Access Denied");
                            problem.setType(URI.create("https://agentgate.cse444.com/problems/forbidden"));
                            problem.setProperty("timestamp", Instant.now().toString());
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            objectMapper.writeValue(response.getWriter(), problem);
                        })
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(
            AgentGateProperties properties,
            PasswordEncoder passwordEncoder
    ) {
        AgentGateProperties.SecurityProperties sec = properties.security();
        UserDetails admin = User.builder()
                .username(sec.adminUsername())
                .password(passwordEncoder.encode(sec.adminPassword()))
                .roles("ADMIN", "APPROVER", "AUDITOR")
                .build();
        UserDetails approver = User.builder()
                .username(sec.approverUsername())
                .password(passwordEncoder.encode(sec.approverPassword()))
                .roles("APPROVER")
                .build();
        UserDetails auditor = User.builder()
                .username(sec.auditorUsername())
                .password(passwordEncoder.encode(sec.auditorPassword()))
                .roles("AUDITOR")
                .build();
        return new InMemoryUserDetailsManager(admin, approver, auditor);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtEncoder jwtEncoder(AgentGateProperties properties) {
        SecretKey key = buildHmacSecretKey(properties.security().jwtSecret());
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    public JwtDecoder jwtDecoder(AgentGateProperties properties) {
        SecretKey key = buildHmacSecretKey(properties.security().jwtSecret());
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * AgentGateProperties validates the secret to at least 32 characters (>= 256 bits),
     * so the key is used as-is instead of being zero-padded into a weak key.
     */
    private SecretKey buildHmacSecretKey(String rawSecret) {
        return new SecretKeySpec(rawSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
