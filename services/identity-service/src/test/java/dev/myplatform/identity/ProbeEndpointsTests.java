package dev.myplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

/** /health (liveness), /ready (readiness) and /metrics (Prometheus) are the contract with Kubernetes and Prometheus. */
@WebIntegrationTest
class ProbeEndpointsTests {

    @Autowired
    RestTestClient http;

    @Test
    void livenessIsUp() {
        http.get().uri("/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void readinessIsUpWhenDatabaseIsReachable() {
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
