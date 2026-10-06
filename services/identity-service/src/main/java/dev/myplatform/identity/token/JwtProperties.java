package dev.myplatform.identity.token;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access-token settings.
 *
 * @param privateKeyPath    RSA private key, PKCS#8 PEM ("BEGIN PRIVATE KEY"). In Kubernetes: a mounted Secret.
 * @param allowEphemeralKey generate an in-memory key when no path is set. Tests only: tokens die on restart
 *                          and differ per replica.
 * @param issuer            {@code iss} claim. Must be a URL: Spring Security parses it as one when validating.
 */
@ConfigurationProperties("identity.jwt")
public record JwtProperties(
        String privateKeyPath,
        @DefaultValue("false") boolean allowEphemeralKey,
        @DefaultValue("http://identity-service") String issuer,
        @DefaultValue("15m") Duration accessTokenTtl) {
}
