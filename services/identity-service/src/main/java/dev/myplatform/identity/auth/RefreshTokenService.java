package dev.myplatform.identity.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Opaque refresh tokens: 256 random bits, base64url. Only the SHA-256 hash is stored (ADR 0002).
 * Never log the returned token.
 */
@Service
class RefreshTokenService {

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

    /** Starts a new token family (a fresh login). Refresh rotation will add tokens to the same family. */
    String issueNewFamily(UUID userId, @Nullable UUID orgId) {
        String token = generate();
        tokens.save(new RefreshToken(userId, orgId, hash(token), UUID.randomUUID(),
                clock.instant().plus(properties.ttl())));
        return token;
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }

}
