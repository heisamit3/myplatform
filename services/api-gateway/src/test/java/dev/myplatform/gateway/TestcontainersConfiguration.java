package dev.myplatform.gateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    // Same image as infra/compose.
    static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:8.10.2-alpine");

    static final long REDIS_MEMORY_BYTES = 64L * 1024 * 1024;

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(REDIS_IMAGE)
                .withExposedPorts(6379)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(REDIS_MEMORY_BYTES));
    }

}
