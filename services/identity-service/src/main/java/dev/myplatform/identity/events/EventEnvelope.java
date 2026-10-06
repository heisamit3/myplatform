package dev.myplatform.identity.events;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** Wire format of every event; mirrors contracts/events/envelope.v1.schema.json. */
record EventEnvelope(
        UUID eventId,
        String type,
        int version,
        Instant occurredAt,
        // Serialized as null, not omitted: the schema requires the field.
        @Nullable UUID orgId,
        Object payload) {
}
