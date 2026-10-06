package dev.myplatform.identity.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.myplatform.identity.events.OutboxRepository.OutboxEvent;

/** Relay logic without Docker: Kafka and the outbox table are mocks. */
class OutboxRelayTests {

    private static final EventsProperties.Outbox SETTINGS = new EventsProperties.Outbox(Duration.ofMillis(500), 3,
            Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofDays(7), Duration.ofHours(1));

    private final OutboxRepository outbox = mock(OutboxRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final OutboxRelay relay = new OutboxRelay(outbox, kafka,
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            new EventsProperties(3, (short) 1, SETTINGS), clock, meters);

    private final OutboxEvent first = event(1);
    private final OutboxEvent second = event(2);

    @BeforeEach
    void relayLockIsFree() {
        when(outbox.tryLockRelay()).thenReturn(true);
    }

    @Test
    void sendsPendingEventsInOrderAndMarksThemPublished() {
        when(outbox.findPending(3)).thenReturn(List.of(first, second));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(acked());

        OutboxRelay.Run run = relay.relayOnce();

        assertThat(run.sent()).isEqualTo(2);
        assertThat(run.failure()).isNull();
        InOrder order = inOrder(kafka, outbox);
        order.verify(kafka).send(first.topic(), first.key(), first.envelope());
        order.verify(kafka).send(second.topic(), second.key(), second.envelope());
        order.verify(outbox).markPublished(List.of(1L, 2L));
        verify(outbox, never()).recordFailure(anyLong(), anyString());
        assertThat(meters.get("identity.outbox.published").counter().count()).isEqualTo(2);
    }

    @Test
    void stopsAtTheFirstFailedSendSoLaterEventsCannotOvertakeIt() {
        OutboxEvent third = event(3);
        when(outbox.findPending(3)).thenReturn(List.of(first, second, third));
        when(kafka.send(anyString(), eq("key-1"), anyString())).thenReturn(acked());
        when(kafka.send(anyString(), eq("key-2"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("expired")));
        when(kafka.send(anyString(), eq("key-3"), anyString())).thenReturn(acked());

        OutboxRelay.Run run = relay.relayOnce();

        assertThat(run.sent()).isEqualTo(1);
        assertThat(run.failure()).isInstanceOf(TimeoutException.class);
        verify(outbox).markPublished(List.of(1L)); // not 3, although Kafka acked it: it stays pending
        verify(outbox).recordFailure(eq(2L), contains("expired"));
        assertThat(meters.get("identity.outbox.failures").counter().count()).isEqualTo(1);
    }

    @Test
    void sendThatThrowsStopsTheRunWithoutTryingTheRest() {
        when(outbox.findPending(3)).thenReturn(List.of(first, second));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenThrow(new KafkaException("no metadata", new TimeoutException("max.block.ms")));

        OutboxRelay.Run run = relay.relayOnce();

        assertThat(run.sent()).isZero();
        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
        verify(outbox).markPublished(List.of());
        verify(outbox).recordFailure(eq(1L), contains("no metadata"));
    }

    @Test
    void anotherReplicaHoldingTheLockMeansNothingToDo() {
        when(outbox.tryLockRelay()).thenReturn(false);

        assertThat(relay.relayOnce().sent()).isZero();
        verify(outbox, never()).findPending(anyInt());
    }

    @Test
    void fullBatchIsFollowedByAnotherRunAtOnce() {
        List<OutboxEvent> fullBatch = List.of(event(1), event(2), event(3));
        when(outbox.findPending(3)).thenReturn(fullBatch, List.of(event(4)));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(acked());

        relay.poll();

        verify(outbox, times(2)).findPending(3);
        verify(outbox).markPublished(List.of(4L));
    }

    @Test
    void backsOffExponentiallyWhileKafkaIsDownAndRecovers() {
        when(outbox.findPending(3)).thenReturn(List.of(first));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("down")));

        relay.poll(); // failure 1 → wait 500 ms
        clock.advance(Duration.ofMillis(400));
        relay.poll(); // skipped
        verify(outbox, times(1)).findPending(3);

        clock.advance(Duration.ofMillis(100));
        relay.poll(); // failure 2 → wait 1 s
        clock.advance(Duration.ofMillis(900));
        relay.poll(); // skipped
        verify(outbox, times(2)).findPending(3);

        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(acked());
        clock.advance(Duration.ofMillis(100));
        relay.poll(); // success → back to the normal interval
        relay.poll();
        verify(outbox, times(4)).findPending(3);
    }

    @Test
    void backoffIsCapped() {
        when(outbox.findPending(3)).thenThrow(new DataAccessResourceFailureException("db down"));

        for (int i = 0; i < 20; i++) {
            relay.poll();
            clock.advance(Duration.ofSeconds(30));
        }

        verify(outbox, times(20)).findPending(3);
    }

    @Test
    void databaseOutageIsSwallowed() {
        when(outbox.tryLockRelay()).thenThrow(new DataAccessResourceFailureException("db down"));
        when(outbox.deletePublishedOlderThan(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatNoException().isThrownBy(relay::poll);
        assertThatNoException().isThrownBy(relay::cleanup);
    }

    private static OutboxEvent event(long id) {
        return new OutboxEvent(id, UUID.randomUUID(), "identity.user.registered.v1", "key-" + id,
                "{\"n\":" + id + "}", 0);
    }

    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture(null);
    }

    /** A clock the test moves by hand, to check backoff without sleeping. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

    }

}
