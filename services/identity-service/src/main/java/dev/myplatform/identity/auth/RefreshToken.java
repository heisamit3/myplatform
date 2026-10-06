package dev.myplatform.identity.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "refresh_tokens")
class RefreshToken {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID userId;

    private @Nullable UUID orgId;

    /** SHA-256 of the opaque token. The token itself is never stored. */
    private byte[] tokenHash;

    private UUID familyId;

    private Instant expiresAt;

    private @Nullable Instant revokedAt;

    private @Nullable UUID replacedBy;

    @CreationTimestamp
    private Instant createdAt;

    protected RefreshToken() {
    }

    RefreshToken(UUID userId, @Nullable UUID orgId, byte[] tokenHash, UUID familyId, Instant expiresAt) {
        this.userId = userId;
        this.orgId = orgId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

}
