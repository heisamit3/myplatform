package dev.myplatform.identity.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.myplatform.identity.WebIntegrationTest;

/**
 * Registers over HTTP and reads the resulting event from a real Kafka. The event must match the
 * committed contract in contracts/events/, the same way OpenApiContractTests guards the REST API.
 */
@WebIntegrationTest
class UserRegisteredEventTests {

    private static final String TOPIC = "identity.user.registered.v1";
    private static final String SCHEMA_BASE = "https://github.com/heisamit3/myplatform/contracts/events/";
    private static final Path CONTRACTS = Path.of("../../contracts/events").toAbsolutePath().normalize();

    @Autowired
    RestTestClient http;

    @Autowired
    ConsumerFactory<String, String> consumers;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Autowired
    JsonMapper json;

    @Test
    void registrationPublishesEventMatchingTheContract() {
        String email = "event-" + UUID.randomUUID() + "@example.com";
        Instant before = Instant.now();

        String userId = http.post().uri("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email.toUpperCase(), "password", "correct horse battery",
                        "displayName", "Ada"))
                .exchange()
                .expectStatus().isCreated()
                .returnResult(JsonNode.class).getResponseBody().get("id").asString();

        ConsumerRecord<String, String> event = awaitEventWithKey(userId);

        List<Error> errors = contract("identity.user.registered.v1.schema.json")
                .validate(event.value(), InputFormat.JSON,
                        context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)));
        assertThat(errors).as("contract violations in %s", event.value()).isEmpty();

        JsonNode envelope = json.readTree(event.value());
        assertThat(envelope.get("payload").get("email").asString()).isEqualTo(email);
        assertThat(envelope.get("payload").get("userId").asString()).isEqualTo(userId);
        assertThat(Instant.parse(envelope.get("occurredAt").asString())).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(event.headers().toArray()).as("no Java type headers for non-Java consumers").isEmpty();
    }

    @Test
    void topicIsDeclaredByTheService() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            var topic = admin.describeTopics(List.of(TOPIC)).allTopicNames().get().get(TOPIC);
            assertThat(topic.partitions()).hasSize(3);
        }
    }

    /** Other tests register users too, so read the topic from the start and pick this user's record. */
    private ConsumerRecord<String, String> awaitEventWithKey(String key) {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = consumers.createConsumer("test-" + UUID.randomUUID(), null, null,
                overrides)) {
            consumer.subscribe(List.of(TOPIC));
            List<String> seenKeys = new ArrayList<>();
            Instant deadline = Instant.now().plusSeconds(20);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                    seenKeys.add(record.key());
                }
            }
            throw new AssertionError("No event with key " + key + " within 20 s; saw keys " + seenKeys);
        }
    }

    /** Loads schemas (and their $refs) from the repo by $id; returns null for anything else, so nothing is fetched. */
    private static Schema contract(String file) {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(UserRegisteredEventTests::readContract));
        return registry.getSchema(SchemaLocation.of(SCHEMA_BASE + file));
    }

    private static @Nullable String readContract(String id) {
        if (!id.startsWith(SCHEMA_BASE)) {
            return null;
        }
        try {
            return Files.readString(CONTRACTS.resolve(id.substring(SCHEMA_BASE.length())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
