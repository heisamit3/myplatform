package dev.myplatform.identity.events;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import dev.myplatform.identity.events.OutboxRepository.OutboxEvent;

/**
 * Sends outbox rows to Kafka in insert order and marks them published (ADR 0014). Polls on the scheduler
 * thread; while Kafka or the database is down it backs off, so it neither spins nor floods the log.
 *
 * <p>Delivery is at-least-once: if the process dies after Kafka acked but before the commit, the row is
 * sent again with the same eventId. Consumers dedupe on it.
 */
@Component
class OutboxRelay implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final EventsProperties.Outbox settings;
    private final Clock clock;
    private final Counter published;
    private final Counter failures;

    // Only touched by the single scheduler thread.
    private int consecutiveFailures;
    private Instant nextAttemptAt = Instant.MIN;

    OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate transaction,
            EventsProperties properties, Clock clock, MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transaction = transaction;
        this.settings = properties.outbox();
        this.clock = clock;
        this.published = Counter.builder("identity.outbox.published")
                .description("Outbox events acknowledged by Kafka").register(meters);
        this.failures = Counter.builder("identity.outbox.failures")
                .description("Relay runs that stopped on a failed send").register(meters);
        // Alert on this growing: events are committed but not reaching Kafka.
        Gauge.builder("identity.outbox.pending", outbox, OutboxRepository::countPending)
                .description("Outbox events not yet published").register(meters);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar tasks) {
        tasks.addFixedDelayTask(this::poll, settings.pollInterval());
        tasks.addFixedDelayTask(this::cleanup, settings.cleanupInterval());
    }

    void poll() {
        if (clock.instant().isBefore(nextAttemptAt)) {
            return; // backing off
        }
        try {
            Run run;
            do {
                run = relayOnce();
            } while (run.failure() == null && run.sent() == settings.batchSize());
            if (run.failure() == null) {
                consecutiveFailures = 0;
            } else {
                backOff();
            }
        } catch (RuntimeException e) {
            // Database unreachable, or the transaction failed. Nothing was marked, so the rows stay pending.
            log.warn("Outbox relay run failed: {}", e.toString());
            backOff();
        }
    }

    /**
     * One relay run in one transaction: takes the relay lock, sends up to batchSize rows, waits for their
     * acks and marks the acknowledged prefix as published. Stops at the first failure so a later event
     * for the same key can't overtake an earlier one that is still pending.
     */
    Run relayOnce() {
        Run run = transaction.execute(status -> {
            if (!outbox.tryLockRelay()) {
                return new Run(0, null); // another replica is relaying
            }
            List<OutboxEvent> batch = outbox.findPending(settings.batchSize());
            List<CompletableFuture<?>> sends = send(batch);
            List<Long> acked = new ArrayList<>();
            Throwable failure = awaitAcks(sends, batch, acked);
            outbox.markPublished(acked);
            if (failure != null) {
                OutboxEvent failed = batch.get(acked.size());
                outbox.recordFailure(failed.id(), failure.toString());
                // eventId and topic only: the payload can hold personal data.
                log.warn("Outbox event {} {} not published (attempt {}): {}", failed.topic(), failed.eventId(),
                        failed.attempts() + 1, failure.toString());
            }
            return new Run(acked.size(), failure);
        });
        published.increment(run.sent());
        if (run.failure() != null) {
            failures.increment();
        }
        return run;
    }

    void cleanup() {
        try {
            int deleted = outbox.deletePublishedOlderThan(settings.retention());
            if (deleted > 0) {
                log.info("Deleted {} published outbox events older than {}", deleted, settings.retention());
            }
        } catch (RuntimeException e) {
            log.warn("Outbox cleanup failed: {}", e.toString());
        }
    }

    /** Sends in order without waiting, so one run costs one round trip, not one per event. */
    private List<CompletableFuture<?>> send(List<OutboxEvent> batch) {
        List<CompletableFuture<?>> sends = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            try {
                sends.add(kafka.send(event.topic(), event.key(), event.envelope()));
            } catch (RuntimeException e) {
                // send() throws when it gets no broker metadata within max.block.ms. The next ones would too.
                sends.add(CompletableFuture.failedFuture(e));
                break;
            }
        }
        return sends;
    }

    /** Adds the ids of the acknowledged prefix to {@code acked}; returns the first failure, or null. */
    private @Nullable Throwable awaitAcks(List<CompletableFuture<?>> sends, List<OutboxEvent> batch,
            List<Long> acked) {
        long deadline = System.nanoTime() + settings.sendTimeout().toNanos();
        for (int i = 0; i < sends.size(); i++) {
            try {
                sends.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                acked.add(batch.get(i).id());
            } catch (ExecutionException e) {
                return e.getCause();
            } catch (TimeoutException e) {
                // It may still be delivered later; then the retry is a duplicate with the same eventId.
                return new TimeoutException("no ack within " + settings.sendTimeout());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return e;
            }
        }
        return null;
    }

    private void backOff() {
        consecutiveFailures++;
        // pollInterval × 2^(failures-1), capped: 0.5 s, 1 s, 2 s ... 30 s.
        Duration delay = settings.pollInterval().multipliedBy(1L << Math.min(consecutiveFailures - 1, 16));
        if (delay.compareTo(settings.maxBackoff()) > 0) {
            delay = settings.maxBackoff();
        }
        nextAttemptAt = clock.instant().plus(delay);
    }

    /** Result of one relay run: events acknowledged, and the failure that stopped it (null if none). */
    record Run(int sent, @Nullable Throwable failure) {
    }

}
