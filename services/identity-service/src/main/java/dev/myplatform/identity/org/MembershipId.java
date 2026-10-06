package dev.myplatform.identity.org;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Embeddable;

@Embeddable
public record MembershipId(UUID orgId, UUID userId) implements Serializable {
}
