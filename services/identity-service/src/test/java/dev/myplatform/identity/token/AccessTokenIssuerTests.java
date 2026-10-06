package dev.myplatform.identity.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Plain unit test: no Spring context, no database. */
class AccessTokenIssuerTests {

    static final RSAKey KEY = RsaKeys.generate();
    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    static final JwtProperties PROPERTIES =
            new JwtProperties(null, true, "http://test-issuer", Duration.ofMinutes(15));

    final AccessTokenIssuer issuer = new AccessTokenIssuer(
            new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY))),
            KEY, PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void tokenCarriesTheAgreedClaims() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        Jwt jwt = decoderFor(KEY).decode(issuer.issue(userId, orgId, List.of("OWNER")));

        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", KEY.getKeyID());
        assertThat(jwt.getIssuer()).hasToString("http://test-issuer");
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("org")).isEqualTo(orgId.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("OWNER");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(jwt.getId()).isNotBlank();
    }

    @Test
    void orgClaimIsOmittedWhenUserHasNoActiveOrg() throws Exception {
        Jwt jwt = decoderFor(KEY).decode(issuer.issue(UUID.randomUUID(), null, List.of()));

        assertThat(jwt.hasClaim("org")).isFalse();
    }

    @Test
    void tokenIsRejectedByAnotherKey() throws Exception {
        String token = issuer.issue(UUID.randomUUID(), null, List.of());

        assertThatThrownBy(() -> decoderFor(RsaKeys.generate()).decode(token))
                .isInstanceOf(JwtException.class);
    }

    private static JwtDecoder decoderFor(RSAKey key) throws Exception {
        return NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    }

}
