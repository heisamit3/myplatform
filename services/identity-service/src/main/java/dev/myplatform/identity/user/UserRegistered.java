package dev.myplatform.identity.user;

import java.time.Instant;
import java.util.UUID;

/**
 * In-process domain event: a user account was created. Published inside the registration transaction;
 * {@code events.DomainEventRecorder} writes it to the outbox in that same transaction (ADR 0014).
 */
public record UserRegistered(UUID userId, String email, String displayName, Instant occurredAt) {

    public static UserRegistered of(User user, Instant occurredAt) {
        return new UserRegistered(user.getId(), user.getEmail(), user.getDisplayName(), occurredAt);
    }

}
