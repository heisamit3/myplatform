package dev.myplatform.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The browser app (Vite, localhost:5173) may call the gateway with credentials; other origins may not. */
@GatewayIntegrationTest
class CorsTests {

    private static final String WEB = "http://localhost:5173";

    @Autowired
    WebTestClient http;

    @Test
    void preflightFromTheWebAppIsAllowed() {
        http.options().uri("/me")
                .header(HttpHeaders.ORIGIN, WEB)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }

    @Test
    void preflightFromAnotherOriginIsRejected() {
        http.options().uri("/me")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void a401StillCarriesCorsHeadersSoTheAppCanReadIt() {
        http.get().uri("/me").header(HttpHeaders.ORIGIN, WEB).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB);
    }

}
