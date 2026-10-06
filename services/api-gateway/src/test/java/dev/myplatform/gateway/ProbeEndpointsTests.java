package dev.myplatform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/** /health, /ready and /metrics are public and look the same as on every service. */
@GatewayIntegrationTest
class ProbeEndpointsTests {

    @Autowired
    WebTestClient http;

    @Test
    void livenessIsUp() {
        http.get().uri("/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void readinessIsUp() {
        http.get().uri("/ready").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void metricsAreInPrometheusFormat() {
        http.get().uri("/metrics").exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("jvm_memory_used_bytes"));
    }

}
