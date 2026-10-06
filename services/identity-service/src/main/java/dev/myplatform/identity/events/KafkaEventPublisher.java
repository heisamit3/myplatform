package dev.myplatform.identity.events;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wraps a payload in the envelope and sends it as a JSON string.
 * Best effort (ADR 0011): a failed send is logged, never thrown, because the database change it describes
 * is already committed. The transactional outbox (Phase 3 stretch) closes that gap.
 */
@Component
class KafkaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    KafkaEventPublisher(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    /**
     * @param key Kafka record key. Records with the same key go to the same partition, so they stay in order.
     */
    void publish(EventType type, String key, @Nullable UUID orgId, Instant occurredAt, Object payload) {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), type.name(), type.version(), occurredAt,
                orgId, payload);
        // Logs name the event and key only: payloads can hold personal data.
        try {
            kafka.send(type.topic(), key, json.writeValueAsString(envelope)).whenComplete((result, error) -> {
                if (error != null) {
                    log.error("Event {} {} (key {}) was not published: {}", type.topic(), envelope.eventId(), key,
                            error.toString());
                } else {
                    log.debug("Published {} {} to partition {}", type.topic(), envelope.eventId(),
                            result.getRecordMetadata().partition());
                }
            });
        } catch (RuntimeException e) {
            // send() itself throws when it can't get broker metadata within max.block.ms.
            log.error("Event {} {} (key {}) was not published: {}", type.topic(), envelope.eventId(), key,
                    e.toString());
        }
    }

}
