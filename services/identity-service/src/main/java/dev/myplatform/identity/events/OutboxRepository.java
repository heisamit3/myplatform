package dev.myplatform.identity.events;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outbox_events table (V2). Plain SQL instead of JPA: the relay needs an advisory lock and jsonb casts,
 * and nothing here benefits from entity tracking. JdbcClient joins the surrounding JPA transaction.
 */
@Repository
class OutboxRepository {

    // Arbitrary constant naming "the outbox relay" for pg_try_advisory_xact_lock. Unique within this database.
    private static final long RELAY_LOCK_KEY = 0x6f7574626f78L; // "outbox"

    private final JdbcClient db;

    OutboxRepository(JdbcClient db) {
        this.db = db;
    }

    /** MANDATORY: an outbox row written outside the business transaction would defeat the point. */
    @Transactional(propagation = Propagation.MANDATORY)
    void append(UUID eventId, String topic, String key, String envelopeJson) {
        db.sql("INSERT INTO outbox_events (event_id, topic, record_key, envelope) VALUES (?, ?, ?, CAST(? AS jsonb))")
                .params(eventId, topic, key, envelopeJson)
                .update();
    }

    /**
     * Takes the relay lock for the current transaction. Only one instance relays at a time, which keeps
     * events in insert order across replicas. Returns false if another instance holds it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean tryLockRelay() {
        return db.sql("SELECT pg_try_advisory_xact_lock(?)").param(RELAY_LOCK_KEY).query(Boolean.class).single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    List<OutboxEvent> findPending(int limit) {
        return db.sql("""
                SELECT id, event_id, topic, record_key, envelope::text AS envelope, attempts
                FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT ?
                """)
                .param(limit)
                .query((rs, row) -> new OutboxEvent(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                        rs.getString("topic"), rs.getString("record_key"), rs.getString("envelope"),
                        rs.getInt("attempts")))
                .list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        // Database time, like created_at, so both columns come from one clock.
        db.sql("UPDATE outbox_events SET published_at = now(), last_error = NULL WHERE id IN (:ids)")
                .param("ids", ids)
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void recordFailure(long id, String error) {
        db.sql("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?")
                .params(error, id)
                .update();
    }

    long countPending() {
        return db.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL").query(Long.class).single();
    }

    /** Published rows are kept for a while for debugging and manual replays, then deleted. */
    @Transactional
    int deletePublishedOlderThan(Duration retention) {
        return db.sql("DELETE FROM outbox_events WHERE published_at < now() - make_interval(secs => ?)")
                .param(retention.toSeconds())
                .update();
    }

    /** An unpublished row, as the relay sends it. */
    record OutboxEvent(long id, UUID eventId, String topic, String key, String envelope, int attempts) {
    }

}
