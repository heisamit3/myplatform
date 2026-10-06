package dev.myplatform.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Login allows 10 requests per minute per IP, register 5; other routes aren't limited. */
@GatewayIntegrationTest
class RateLimitTests {

    @Autowired
    WebTestClient http;

    @Autowired
    ReactiveRedisConnectionFactory redis;

    /** Other test classes share this Redis and also call /auth/login: start with full buckets. */
    @BeforeEach
    void emptyBuckets() {
        redis.getReactiveConnection().serverCommands().flushAll().block();
    }

    @Test
    void eleventhLoginWithinAMinuteIsRejected() {
        for (int i = 0; i < 10; i++) {
            post("/auth/login").expectStatus().isOk();
        }
        post("/auth/login")
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0");
    }

    @Test
    void sixthRegistrationWithinAMinuteIsRejected() {
        for (int i = 0; i < 5; i++) {
            post("/auth/register").expectStatus().isOk();
        }
        post("/auth/register").expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void loginAndRegisterHaveSeparateBuckets() {
        for (int i = 0; i < 5; i++) {
            post("/auth/register").expectStatus().isOk();
        }
        post("/auth/login").expectStatus().isOk();
    }

    @Test
    void otherAuthEndpointsAreNotLimited() {
        for (int i = 0; i < 15; i++) {
            post("/auth/refresh").expectStatus().isOk();
        }
    }

    private WebTestClient.ResponseSpec post(String path) {
        return http.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange();
    }

}
