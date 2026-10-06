package dev.myplatform.identity.events;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import dev.myplatform.identity.user.UserRegistered;

/**
 * Forwards in-process domain events to Kafka once their transaction has committed, so consumers never
 * see an event for data that was rolled back. Runs on a task-executor thread (@Async): a slow or
 * unreachable broker doesn't delay the HTTP response.
 */
@Component
class DomainEventRelay {

    private final KafkaEventPublisher publisher;

    DomainEventRelay(KafkaEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(UserRegistered event) {
        // Keyed by user: all events about one user land on one partition, in order. No org exists yet.
        publisher.publish(EventType.USER_REGISTERED, event.userId().toString(), null, event.occurredAt(),
                new UserRegisteredPayload(event.userId().toString(), event.email(), event.displayName()));
    }

    /** Payload of identity.user.registered v1 (contracts/events/identity.user.registered.v1.schema.json). */
    record UserRegisteredPayload(String userId, String email, String displayName) {
    }

}
