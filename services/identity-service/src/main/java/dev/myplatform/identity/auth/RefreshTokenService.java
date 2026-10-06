package dev.myplatform.identity.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Opaque refresh tokens: 256 random bits, base64url. Only the SHA-256 hash is stored (ADR 0002).
 * Rotation and reuse detection follow ADR 0003. Never log a token.
 * Every method must run inside the caller's transaction.
 */
@Service
class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository tokens;
    private final RefreshTokenProperties properties;
    private final Clock clock;

    RefreshTokenService(RefreshTokenRepository tokens, RefreshTokenProperties properties, Clock clock) {
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts a new token family (a fresh login). */
    String issueNewFamily(UUID userId, @Nullable UUID orgId) {
        String token = generate();
        tokens.save(new RefreshToken(userId, orgId, hash(token), UUID.randomUUID(),
                clock.instant().plus(properties.ttl())));
        return token;
    }

    /**
     * Locks and returns the token if it can be used. A token that was already rotated means someone holds
     * an old copy (stolen or replayed), so its whole family is revoked. The caller's transaction must not
     * roll back on {@link InvalidRefreshTokenException}, or that revocation is lost.
     */
    RefreshToken consume(String token) {
        RefreshToken current = tokens.findByTokenHash(hash(token)).orElseThrow(InvalidRefreshTokenException::new);
        Instant now = clock.instant();
        if (current.isRevoked()) {
            int revoked = tokens.revokeFamily(current.getFamilyId(), now);
            // revoked == 0: the family was already dead (logout, earlier detection). Not a new incident.
            if (revoked > 0) {
                log.warn("Refresh token reuse detected: revoked family {} of user {} ({} active tokens)",
                        current.getFamilyId(), current.getUserId(), revoked);
            }
            throw new InvalidRefreshTokenException();
        }
        if (current.isExpiredAt(now)) {
            throw new InvalidRefreshTokenException();
        }
        return current;
    }

    /** Revokes {@code current} and returns its successor in the same family, valid for a fresh TTL. */
    String rotate(RefreshToken current, @Nullable UUID orgId) {
        String token = generate();
        Instant now = clock.instant();
        RefreshToken successor = tokens.save(new RefreshToken(current.getUserId(), orgId, hash(token),
                current.getFamilyId(), now.plus(properties.ttl())));
        current.replaceWith(successor, now);
        return token;
    }

    /** Logout: ends the session (family) the token belongs to. Unknown tokens are ignored. */
    void revokeFamilyOf(String token) {
        tokens.findByTokenHash(hash(token))
                .ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), clock.instant()));
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }

}
