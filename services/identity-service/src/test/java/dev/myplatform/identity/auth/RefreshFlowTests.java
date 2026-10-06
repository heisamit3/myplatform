package dev.myplatform.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.client.RestTestClient;

import dev.myplatform.identity.WebIntegrationTest;

/** Refresh-token rotation, reuse detection and logout (ADR 0003) over HTTP against a real Postgres. */
@WebIntegrationTest
class RefreshFlowTests {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    RestTestClient http;

    @Autowired
    JdbcClient db;

    @Autowired
    RSAKey signingKey;

    @Test
    void refreshRotatesTheTokenWithinItsFamily() throws Exception {
        String email = uniqueEmail();
        UUID userId = register(email);
        String first = login(email).refreshToken();

        TokenResponse refreshed = refresh(first);

        assertThat(refreshed.refreshToken()).isNotEqualTo(first);
        assertThat(decode(refreshed.accessToken()).getSubject()).isEqualTo(userId.toString());
        Map<String, Object> old = row(first);
        Map<String, Object> successor = row(refreshed.refreshToken());
        assertThat(old.get("revoked_at")).isNotNull();
        assertThat(old.get("replaced_by")).isEqualTo(successor.get("id"));
        assertThat(successor.get("family_id")).isEqualTo(old.get("family_id"));
        assertThat(successor.get("revoked_at")).isNull();
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() {
        String email = uniqueEmail();
        register(email);
        String stolen = login(email).refreshToken();
        String current = refresh(stolen).refreshToken();

        refreshRejected(stolen);

        // The legitimate client's newest token died with the family: everyone has to log in again.
        assertThat(row(current).get("revoked_at")).isNotNull();
        refreshRejected(current);
    }

    @Test
    void otherSessionsSurviveAReuse() {
        String email = uniqueEmail();
        register(email);
        String stolen = login(email).refreshToken();
        String otherDevice = login(email).refreshToken();
        refresh(stolen);

        refreshRejected(stolen);

        assertThat(refresh(otherDevice).refreshToken()).isNotBlank();
    }

    @Test
    void expiredAndUnknownTokensAreRejected() {
        String email = uniqueEmail();
        register(email);
        String token = login(email).refreshToken();
        db.sql("UPDATE refresh_tokens SET expires_at = now() - interval '1 second' WHERE token_hash = ?")
                .param(RefreshTokenService.hash(token)).update();

        refreshRejected(token);
        refreshRejected(RefreshTokenService.generate());

        http.post().uri("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", ""))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void refreshKeepsTheOrgAndPicksUpRoleChanges() throws Exception {
        String email = uniqueEmail();
        UUID userId = register(email);
        UUID orgId = insertOrgWithMember(userId, "OWNER");
        String token = login(email).refreshToken();
        db.sql("UPDATE memberships SET role = 'ADMIN' WHERE org_id = ? AND user_id = ?")
                .params(orgId, userId).update();

        Jwt jwt = decode(refresh(token).accessToken());

        assertThat(jwt.getClaimAsString("org")).isEqualTo(orgId.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
    }

    @Test
    void refreshPicksUpAFirstOrgCreatedAfterLogin() throws Exception {
        String email = uniqueEmail();
        UUID userId = register(email);
        String token = login(email).refreshToken();
        UUID orgId = insertOrgWithMember(userId, "OWNER");

        TokenResponse refreshed = refresh(token);

        assertThat(decode(refreshed.accessToken()).getClaimAsString("org")).isEqualTo(orgId.toString());
        assertThat(row(refreshed.refreshToken()).get("org_id")).isEqualTo(orgId);
    }

    @Test
    void logoutEndsTheSessionAndIsIdempotent() {
        String email = uniqueEmail();
        register(email);
        String token = login(email).refreshToken();

        logout(token);
        logout(token);
        logout(RefreshTokenService.generate());

        refreshRejected(token);
    }

    private UUID register(String email) {
        return http.post().uri("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", PASSWORD, "displayName", "Test"))
                .exchange()
                .expectStatus().isCreated()
                .returnResult(UserResponse.class).getResponseBody().id();
    }

    private TokenResponse login(String email) {
        return http.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .returnResult(TokenResponse.class).getResponseBody();
    }

    private TokenResponse refresh(String refreshToken) {
        return http.post().uri("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .returnResult(TokenResponse.class).getResponseBody();
    }

    private void refreshRejected(String refreshToken) {
        http.post().uri("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("Invalid refresh token");
    }

    private void logout(String refreshToken) {
        http.post().uri("/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus().isNoContent();
    }

    private Jwt decode(String accessToken) throws Exception {
        return NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build().decode(accessToken);
    }

    private Map<String, Object> row(String refreshToken) {
        return db.sql("SELECT id, family_id, org_id, revoked_at, replaced_by FROM refresh_tokens WHERE token_hash = ?")
                .param(RefreshTokenService.hash(refreshToken)).query().singleRow();
    }

    private UUID insertOrgWithMember(UUID userId, String role) {
        UUID orgId = db.sql("INSERT INTO organizations (name, slug) VALUES ('Test org', ?) RETURNING id")
                .param("org-" + UUID.randomUUID()).query(UUID.class).single();
        db.sql("INSERT INTO memberships (org_id, user_id, role) VALUES (?, ?, ?)")
                .params(orgId, userId, role).update();
        return orgId;
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

}
