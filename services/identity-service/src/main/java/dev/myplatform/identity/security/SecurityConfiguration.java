package dev.myplatform.identity.security;

import java.security.interfaces.RSAPublicKey;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import dev.myplatform.identity.token.JwtProperties;

/**
 * Stateless Bearer-token API. Public: auth endpoints (they issue tokens), JWKS and probes.
 * Everything else needs a valid access token.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/auth/**", "/.well-known/jwks.json").permitAll()
                        .requestMatchers("/health", "/ready", "/metrics", "/actuator/health/**").permitAll()
                        // Error responses of public endpoints must not turn into 401s.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF attacks ride on cookies the browser sends automatically. A Bearer header is never
                // sent automatically, so CSRF protection only gets in the way here.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * This service signed the tokens, so it verifies them with its own public key: no JWKS fetch.
     * The other services use the JWKS URL instead. Signature, expiry and issuer are all checked.
     */
    @Bean
    JwtDecoder jwtDecoder(RSAKey signingKey, JwtProperties properties) throws JOSEException {
        RSAPublicKey publicKey = signingKey.toRSAPublicKey();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

}
