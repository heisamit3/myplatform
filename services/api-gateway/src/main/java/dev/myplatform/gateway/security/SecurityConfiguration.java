package dev.myplatform.gateway.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * The edge check: every request except the auth endpoints and probes needs a valid access token
 * (RS256 signature from the JWKS, expiry, issuer). Services check the token again behind us.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityWebFilterChain gateway(ServerHttpSecurity http) {
        http.authorizeExchange(exchanges -> exchanges
                        // CORS preflights carry no token; the CORS filter answers them first anyway.
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        // Login, register, refresh, logout and switch-org issue or end sessions:
                        // they can't require an access token.
                        .pathMatchers("/auth/**").permitAll()
                        .pathMatchers("/health", "/ready", "/metrics", "/actuator/health/**").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(server -> server
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problemDetailEntryPoint()))
                // Runs before authentication, so preflights and 401s still get CORS headers.
                .cors(Customizer.withDefaults())
                // No server-side session: every request brings its own token.
                .requestCache(cache -> cache.disable())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                // CSRF rides on cookies the browser sends automatically. The only cookie here is the refresh
                // cookie (SameSite=Strict, path /auth); everything else uses a Bearer header.
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());
        return http.build();
    }

    /** 401 as problem+json, like identity-service. The Bearer entry point sets WWW-Authenticate (RFC 6750). */
    private static ServerAuthenticationEntryPoint problemDetailEntryPoint() {
        BearerTokenServerAuthenticationEntryPoint bearer = new BearerTokenServerAuthenticationEntryPoint();
        byte[] body = """
                {"type":"about:blank","title":"Unauthorized","status":401,"detail":"Missing or invalid access token"}"""
                .getBytes(StandardCharsets.UTF_8);
        // The Bearer entry point sets status and header, then completes the response. Writing the body
        // in place of that completion keeps both (headers are read-only once the response is committed).
        return (exchange, exception) -> {
            ServerHttpResponse withBody = new ServerHttpResponseDecorator(exchange.getResponse()) {
                @Override
                public Mono<Void> setComplete() {
                    getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
                    return writeWith(Mono.just(bufferFactory().wrap(body)));
                }
            };
            return bearer.commence(exchange.mutate().response(withBody).build(), exception);
        };
    }

    /**
     * The browser app runs on another origin (Vite on 5173), so it may call us only if we say so.
     * Credentials are allowed because the refresh token travels in an HttpOnly cookie.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setExposedHeaders(List.of("Retry-After", "WWW-Authenticate"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

}
