package dev.myplatform.identity.events;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import dev.myplatform.identity.user.UserRegistered;

/**
 * Turns in-process domain events into outbox rows (ADR 0014). A plain {@code @EventListener} runs
 * synchronously on the caller's thread, inside its transaction: the row commits or rolls back together with
 * the change it describes. If the insert fails, the business operation fails too. {@link OutboxRelay}
 * sends the rows to Kafka later.
 */
@Component
class DomainEventRecorder {

    private final OutboxRepository outbox;
    private final JsonMapper json;

    DomainEventRecorder(OutboxRepository outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(UserRegistered event) {
        // Keyed by user: all events about one user land on one partition, in order. No org exists yet.
        record(EventType.USER_REGISTERED, event.userId().toString(), null, event.occurredAt(),
                new UserRegisteredPayload(event.userId().toString(), event.email(), event.displayName()));
    }

    /**
     * @param key Kafka record key. Records with the same key go to the same partition, so they stay in order.
     */
    private void record(EventType type, String key, @Nullable UUID orgId, Instant occurredAt, Object payload) {
        // The eventId is fixed here, so every redelivery of this row carries the same one (consumers dedupe on it).
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), type.name(), type.version(), occurredAt,
                orgId, payload);
        outbox.append(envelope.eventId(), type.topic(), key, json.writeValueAsString(envelope));
    }

    /** Payload of identity.user.registered v1 (contracts/events/identity.user.registered.v1.schema.json). */
    record UserRegisteredPayload(String userId, String email, String displayName) {
    }

}
