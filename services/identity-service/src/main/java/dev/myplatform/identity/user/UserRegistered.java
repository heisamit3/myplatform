package dev.myplatform.identity.user;

import java.time.Instant;
import java.util.UUID;

/**
 * In-process domain event: a user account was created. Published inside the registration transaction;
 * {@code events.DomainEventRelay} forwards it to Kafka only after that transaction commits.
 */
public record UserRegistered(UUID userId, String email, String displayName, Instant occurredAt) {

    public static UserRegistered of(User user, Instant occurredAt) {
        return new UserRegistered(user.getId(), user.getEmail(), user.getDisplayName(), occurredAt);
    }

}
