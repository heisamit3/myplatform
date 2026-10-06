package dev.myplatform.identity.events;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

/** Kafka being down must never surface as an error: the change the event describes is already committed. */
class KafkaEventPublisherTests {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final KafkaEventPublisher publisher = new KafkaEventPublisher(kafka, JsonMapper.builder().build());

    @Test
    void sendThatThrowsIsSwallowed() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenThrow(new KafkaException("no metadata", new TimeoutException("max.block.ms")));

        assertThatNoException().isThrownBy(this::publish);
    }

    @Test
    void sendThatFailsLaterIsSwallowed() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>failedFuture(new TimeoutException("x")));

        assertThatNoException().isThrownBy(this::publish);
    }

    private void publish() {
        publisher.publish(EventType.USER_REGISTERED, "key", null, Instant.parse("2026-10-06T00:00:00Z"),
                Map.of("userId", "u"));
    }

}
