package dev.myplatform.identity.token;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.jwk.RSAKey;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Mints RS256 access tokens. Claims follow CLAUDE.md: {@code sub} = userId, {@code org} = active orgId,
 * {@code roles} = roles in that org. Never log the returned token.
 */
@Component
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;
    private final String keyId;

    AccessTokenIssuer(JwtEncoder encoder, RSAKey signingKey, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
        this.keyId = signingKey.getKeyID();
    }

    /** @param orgId active organization, or {@code null} for a user without one (then no {@code org} claim). */
    public String issue(UUID userId, @Nullable UUID orgId, List<String> roles) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim("roles", List.copyOf(roles));
        if (orgId != null) {
            claims.claim("org", orgId.toString());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

}
