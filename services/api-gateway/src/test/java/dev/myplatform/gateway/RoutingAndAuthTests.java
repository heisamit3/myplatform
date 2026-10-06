package dev.myplatform.gateway;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Which requests reach identity-service, and which the gateway stops with a 401. */
@GatewayIntegrationTest
class RoutingAndAuthTests {

    private final FakeIdentityService identity = FakeIdentityService.instance();

    @Autowired
    WebTestClient http;

    @Test
    void authEndpointsAreRoutedWithoutAToken() {
        http.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.path").isEqualTo("/auth/login")
                .jsonPath("$.method").isEqualTo("POST");
    }

    @Test
    void protectedRouteWithoutTokenIs401ProblemJson() {
        http.get().uri("/me").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody().jsonPath("$.status").isEqualTo(401);
    }

    @Test
    void validTokenIsRoutedAndForwardedForTheServiceToCheckAgain() {
        http.get().uri("/me").headers(h -> h.setBearerAuth(identity.accessToken())).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.path").isEqualTo("/me")
                .jsonPath("$.authorization").isEqualTo(true);
    }

    @Test
    void orgRoutesAreProtectedToo() {
        http.post().uri("/orgs").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isUnauthorized();
        http.post().uri("/orgs").headers(h -> h.setBearerAuth(identity.accessToken()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/orgs");
    }

    @Test
    void expiredTokenIsRejected() {
        String expired = identity.token(FakeIdentityService.ISSUER, Instant.now().minusSeconds(300));
        http.get().uri("/me").headers(h -> h.setBearerAuth(expired)).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        String foreign = identity.token("http://evil.example", Instant.now().plusSeconds(900));
        http.get().uri("/me").headers(h -> h.setBearerAuth(foreign)).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tokenSignedWithAnUnknownKeyIsRejected() {
        http.get().uri("/me").headers(h -> h.setBearerAuth(identity.tokenSignedByAnotherKey())).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void unknownPathsNeedATokenAndThenAre404() {
        http.get().uri("/nope").exchange().expectStatus().isUnauthorized();
        http.get().uri("/nope").headers(h -> h.setBearerAuth(identity.accessToken())).exchange()
                .expectStatus().isNotFound();
    }

}
