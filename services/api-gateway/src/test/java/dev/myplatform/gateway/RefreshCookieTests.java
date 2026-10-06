package dev.myplatform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The refresh token travels as an HttpOnly cookie between browser and gateway, as JSON behind it. */
@GatewayIntegrationTest
class RefreshCookieTests {

    private static final String COOKIE = "refresh_token";

    private final FakeIdentityService identity = FakeIdentityService.instance();

    @Autowired
    WebTestClient http;

    @Autowired
    ReactiveRedisConnectionFactory redis;

    /** Login is rate limited; start with a full bucket. */
    @BeforeEach
    void emptyBuckets() {
        redis.getReactiveConnection().serverCommands().flushAll().block();
    }

    @Test
    void loginMovesTheRefreshTokenFromTheBodyIntoAnHttpOnlyCookie() {
        http.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"a@example.com\",\"password\":\"x\"}").exchange()
                .expectStatus().isOk()
                .expectCookie().valueEquals(COOKIE, FakeIdentityService.ISSUED_REFRESH_TOKEN)
                .expectCookie().httpOnly(COOKIE, true)
                .expectCookie().secure(COOKIE, true)
                .expectCookie().sameSite(COOKIE, "Strict")
                .expectCookie().path(COOKIE, "/auth")
                .expectCookie().maxAge(COOKIE, Duration.ofDays(7))
                .expectBody()
                .jsonPath("$.accessToken").exists()
                .jsonPath("$.expiresIn").isEqualTo(900)
                .jsonPath("$.refreshToken").doesNotExist();
    }

    @Test
    void refreshTakesTheTokenFromTheCookieAndRotatesIt() {
        http.post().uri("/auth/refresh").cookie(COOKIE, "rt-old")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isOk()
                .expectCookie().valueEquals(COOKIE, FakeIdentityService.ISSUED_REFRESH_TOKEN)
                .expectBody().jsonPath("$.refreshToken").doesNotExist();
        assertThat(identity.lastBody()).isEqualTo("{\"refreshToken\":\"rt-old\"}");
    }

    @Test
    void refreshWorksWithoutAnyBody() {
        http.post().uri("/auth/refresh").cookie(COOKIE, "rt-old").exchange()
                .expectStatus().isOk();
        assertThat(identity.lastBody()).isEqualTo("{\"refreshToken\":\"rt-old\"}");
    }

    @Test
    void switchOrgKeepsTheBodyAndAddsTheToken() {
        http.post().uri("/auth/switch-org").cookie(COOKIE, "rt-old")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"orgId\":\"o-1\"}").exchange()
                .expectStatus().isOk()
                .expectCookie().valueEquals(COOKIE, FakeIdentityService.ISSUED_REFRESH_TOKEN);
        assertThat(identity.lastBody()).isEqualTo("{\"orgId\":\"o-1\",\"refreshToken\":\"rt-old\"}");
    }

    @Test
    void aRejectedRefreshTokenDeletesTheCookie() {
        http.post().uri("/auth/refresh").cookie(COOKIE, FakeIdentityService.REVOKED_REFRESH_TOKEN).exchange()
                .expectStatus().isUnauthorized()
                .expectCookie().valueEquals(COOKIE, "")
                .expectCookie().maxAge(COOKIE, Duration.ZERO)
                .expectBody().jsonPath("$.status").isEqualTo(401);
    }

    @Test
    void logoutSendsTheTokenAndDeletesTheCookie() {
        http.post().uri("/auth/logout").cookie(COOKIE, "rt-old").exchange()
                .expectStatus().isNoContent()
                .expectCookie().maxAge(COOKIE, Duration.ZERO);
        assertThat(identity.lastBody()).isEqualTo("{\"refreshToken\":\"rt-old\"}");
    }

    @Test
    void withoutACookieTheBodyIsForwardedUnchanged() {
        http.post().uri("/auth/refresh").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isOk();
        assertThat(identity.lastBody()).isEqualTo("{}");
    }

    @Test
    void theCookieWorksCrossOriginFromTheWebApp() {
        http.post().uri("/auth/refresh").cookie(COOKIE, "rt-old")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }

}
