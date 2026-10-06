package dev.myplatform.identity;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    // Same image as infra/compose so tests run against the real Postgres version.
    static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.7-pg18-trixie")
            .asCompatibleSubstituteFor("postgres");

    static final long POSTGRES_MEMORY_BYTES = 256L * 1024 * 1024;

    // Same image as infra/compose (ADR 0009); smaller heap, since tests send only a few events.
    static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.3.1");

    static final long KAFKA_MEMORY_BYTES = 512L * 1024 * 1024;

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(POSTGRES_MEMORY_BYTES));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(KAFKA_IMAGE)
                .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
                // Like compose: topics must be declared by the app (NewTopic beans), not created on first use.
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(KAFKA_MEMORY_BYTES));
    }

}
