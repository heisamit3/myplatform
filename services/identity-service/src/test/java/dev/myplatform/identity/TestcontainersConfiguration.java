package dev.myplatform.identity;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    // Same image as infra/compose so tests run against the real Postgres version.
    static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.7-pg18-trixie")
            .asCompatibleSubstituteFor("postgres");

    static final long POSTGRES_MEMORY_BYTES = 256L * 1024 * 1024;

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(POSTGRES_MEMORY_BYTES));
    }

}
