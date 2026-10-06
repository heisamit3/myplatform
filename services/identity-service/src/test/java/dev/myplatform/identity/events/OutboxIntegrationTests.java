package dev.myplatform.identity.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import dev.myplatform.identity.WebIntegrationTest;
import dev.myplatform.identity.user.UserRegistered;

/**
 * The outbox against a real Postgres and Kafka (the relay's scheduler runs as in production).
 * UserRegisteredEventTests checks what arrives on the topic; this checks the table side.
 */
@WebIntegrationTest
class OutboxIntegrationTests {

    @Autowired
    RestTestClient http;

    @Autowired
    JdbcClient db;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    OutboxRelay relay;

    @Test
    void registrationWritesAnOutboxRowThatTheRelayPublishes() {
        String userId = register("outbox-" + UUID.randomUUID() + "@example.com");

        Row row = awaitPublished(userId);

        assertThat(row.topic()).isEqualTo("identity.user.registered.v1");
        assertThat(row.envelopeEventId()).as("eventId in the envelope = the row's event_id").isEqualTo(row.eventId());
        assertThat(row.attempts()).isZero();
    }

    @Test
    void rolledBackTransactionLeavesNoEvent() {
        UUID userId = UUID.randomUUID();

        transaction.executeWithoutResult(status -> {
            events.publishEvent(new UserRegistered(userId, "ghost@example.com", "Ghost", Instant.now()));
            status.setRollbackOnly(); // e.g. a constraint violation after the event was raised
        });

        assertThat(find(userId.toString())).isEmpty();
    }

    @Test
    void eventOutsideATransactionIsRejected() {
        UserRegistered event = new UserRegistered(UUID.randomUUID(), "x@example.com", "X", Instant.now());

        // Without a transaction there is nothing to be atomic with: fail loudly instead of writing a stray row.
        assertThatThrownBy(() -> events.publishEvent(event)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void duplicateRegistrationAddsNoEvent() {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        register(email);

        http.post().uri("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "correct horse battery", "displayName", "Ada"))
                .exchange()
                .expectStatus().isEqualTo(409);

        long rows = db.sql("SELECT count(*) FROM outbox_events WHERE envelope->'payload'->>'email' = ?")
                .param(email).query(Long.class).single();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void cleanupDeletesOnlyPublishedRowsPastTheRetention() {
        // One transaction, rolled back: the relay never sees these rows, and nothing is left behind.
        transaction.executeWithoutResult(status -> {
            String old = insertRow("published 8 days ago", "now() - interval '8 days'");
            String recent = insertRow("published 1 hour ago", "now() - interval '1 hour'");
            String pending = insertRow("never published", "NULL");

            relay.cleanup(); // joins this transaction

            assertThat(find(old)).isEmpty();
            assertThat(find(recent)).isPresent();
            assertThat(find(pending)).isPresent();
            status.setRollbackOnly();
        });
    }

    private String register(String email) {
        return http.post().uri("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "correct horse battery", "displayName", "Ada"))
                .exchange()
                .expectStatus().isCreated()
                .returnResult(JsonNode.class).getResponseBody().get("id").asString();
    }

    private Row awaitPublished(String key) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            Optional<Row> row = find(key);
            if (row.isPresent() && row.get().publishedAt() != null) {
                return row.get();
            }
            sleep();
        }
        throw new AssertionError("outbox row for " + key + " not published within 20 s: " + find(key));
    }

    private Optional<Row> find(String key) {
        return db.sql("""
                SELECT event_id, envelope->>'eventId' AS envelope_event_id, topic, published_at, attempts
                FROM outbox_events WHERE record_key = ?
                """)
                .param(key)
                .query((rs, n) -> new Row(rs.getObject("event_id", UUID.class),
                        UUID.fromString(rs.getString("envelope_event_id")), rs.getString("topic"),
                        rs.getObject("published_at", OffsetDateTime.class), rs.getInt("attempts")))
                .optional();
    }

    private String insertRow(String key, String publishedAtSql) {
        UUID eventId = UUID.randomUUID();
        db.sql("INSERT INTO outbox_events (event_id, topic, record_key, envelope, published_at) "
                + "VALUES (?, 'test.outbox.cleanup.v1', ?, jsonb_build_object('eventId', ?::text), "
                + publishedAtSql + ")")
                .params(eventId, key + " " + eventId, eventId)
                .update();
        return key + " " + eventId;
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    record Row(UUID eventId, UUID envelopeEventId, String topic, @Nullable OffsetDateTime publishedAt, int attempts) {
    }

}
