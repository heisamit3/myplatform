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

/** Register and login over HTTP against a real Postgres. HTTP tests don't roll back, so emails are unique. */
@WebIntegrationTest
class AuthFlowTests {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    RestTestClient http;

    @Autowired
    JdbcClient db;

    @Autowired
    RSAKey signingKey;

    @Test
    void registerStoresLowerCaseEmailAndBcryptHash() {
        String email = uniqueEmail();

        http.post().uri("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "  " + email.toUpperCase() + " ", "password", PASSWORD,
                        "displayName", " Ada "))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.email").isEqualTo(email)
                .jsonPath("$.displayName").isEqualTo("Ada")
                .jsonPath("$.password").doesNotExist()
                .jsonPath("$.passwordHash").doesNotExist();

        String hash = db.sql("SELECT password_hash FROM users WHERE email = ?").param(email)
                .query(String.class).single();
        assertThat(hash).startsWith("$2a$10$").doesNotContain(PASSWORD);
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() {
        String email = uniqueEmail();
        register(email);

        http.post().uri("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email.toUpperCase(), "password", PASSWORD, "displayName", "Other"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("Email is already registered");
    }

    @Test
    void invalidRegistrationsAreRejected() {
        assertRegisterRejected(Map.of("email", "not-an-email", "password", PASSWORD, "displayName", "Ada"));
        assertRegisterRejected(Map.of("email", uniqueEmail(), "password", "short", "displayName", "Ada"));
        assertRegisterRejected(Map.of("email", uniqueEmail(), "password", PASSWORD, "displayName", " "));
        // 25 x 3-byte characters = 75 bytes: over bcrypt's 72-byte limit although only 25 characters.
        assertRegisterRejected(Map.of("email", uniqueEmail(), "password", "\u20AC".repeat(25), "displayName", "Ada"));
    }

    @Test
    void loginIssuesVerifiableTokensWithoutOrgForANewUser() throws Exception {
        String email = uniqueEmail();
        UUID userId = register(email);

        TokenResponse tokens = login(email, PASSWORD);

        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.expiresIn()).isEqualTo(900);
        Jwt jwt = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build().decode(tokens.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.hasClaim("org")).isFalse();
        assertThat(jwt.getClaimAsStringList("roles")).isEmpty();

        // Only the SHA-256 hash of the refresh token is stored, in a new family that expires in ~7 days.
        Map<String, Object> row = db.sql("""
                SELECT org_id, revoked_at, expires_at > now() + interval '6 days 23 hours' AS seven_days
                FROM refresh_tokens WHERE token_hash = ?
                """).param(RefreshTokenService.hash(tokens.refreshToken())).query().singleRow();
        assertThat(row.get("org_id")).isNull();
        assertThat(row.get("revoked_at")).isNull();
        assertThat(row.get("seven_days")).isEqualTo(true);
    }

    @Test
    void loginStartsInTheFirstOrgTheUserJoined() throws Exception {
        String email = uniqueEmail();
        UUID userId = register(email);
        UUID firstOrg = insertOrgWithMember(userId, "OWNER");
        insertOrgWithMember(userId, "MEMBER");

        TokenResponse tokens = login(email.toUpperCase(), PASSWORD);

        Jwt jwt = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build().decode(tokens.accessToken());
        assertThat(jwt.getClaimAsString("org")).isEqualTo(firstOrg.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("OWNER");
        UUID storedOrg = db.sql("SELECT org_id FROM refresh_tokens WHERE token_hash = ?")
                .param(RefreshTokenService.hash(tokens.refreshToken())).query(UUID.class).single();
        assertThat(storedOrg).isEqualTo(firstOrg);
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameResponse() {
        String email = uniqueEmail();
        register(email);

        String wrongPassword = loginRejected(email, "wrong password!");
        String unknownEmail = loginRejected(uniqueEmail(), PASSWORD);

        assertThat(wrongPassword).isEqualTo(unknownEmail).contains("Invalid email or password");
    }

    private UUID register(String email) {
        return http.post().uri("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", PASSWORD, "displayName", "Test"))
                .exchange()
                .expectStatus().isCreated()
                .returnResult(UserResponse.class).getResponseBody().id();
    }

    private TokenResponse login(String email, String password) {
        return http.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .returnResult(TokenResponse.class).getResponseBody();
    }

    private String loginRejected(String email, String password) {
        return http.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .returnResult(String.class).getResponseBody();
    }

    private void assertRegisterRejected(Map<String, String> body) {
        http.post().uri("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
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
