package dev.myplatform.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.StatusAssertions;

import dev.myplatform.identity.WebIntegrationTest;

/** Bearer-token protection, GET /me, POST /orgs and POST /auth/switch-org against a real Postgres. */
@WebIntegrationTest
class TenancyFlowTests {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    RestTestClient http;

    @Autowired
    JdbcClient db;

    @Autowired
    RSAKey signingKey;

    @Test
    void protectedEndpointsNeedAValidAccessToken() throws Exception {
        http.get().uri("/me").exchange().expectStatus().isUnauthorized();
        http.get().uri("/me").header("Authorization", "Bearer not-a-jwt").exchange()
                .expectStatus().isUnauthorized();
        http.post().uri("/orgs").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Acme", "slug", uniqueSlug()))
                .exchange().expectStatus().isUnauthorized();

        // Right claims and issuer, but signed by somebody else's key.
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("attacker").generate();
        String forged = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(otherKey))).encode(
                JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("attacker").build(),
                        JwtClaimsSet.builder().issuer("http://identity-service").subject(UUID.randomUUID().toString())
                                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build()))
                .getTokenValue();
        http.get().uri("/me").header("Authorization", "Bearer " + forged).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void meShowsANewUserWithoutOrgs() {
        String email = uniqueEmail();
        UUID userId = register(email);
        TokenResponse tokens = login(email);

        me(tokens.accessToken())
                .jsonPath("$.id").isEqualTo(userId.toString())
                .jsonPath("$.email").isEqualTo(email)
                .jsonPath("$.displayName").isEqualTo("Test")
                .jsonPath("$.activeOrgId").isEmpty()
                .jsonPath("$.organizations.length()").isEqualTo(0);
    }

    @Test
    void createOrgThenSwitchIntoIt() throws Exception {
        String email = uniqueEmail();
        register(email);
        TokenResponse tokens = login(email);
        String slug = uniqueSlug();

        http.post().uri("/orgs").header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", " Acme Inc ", "slug", slug))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Acme Inc")
                .jsonPath("$.slug").isEqualTo(slug)
                .jsonPath("$.role").isEqualTo("OWNER");
        String orgId = orgIdBySlug(slug).toString();

        // The old token predates the org: it is listed, but not active yet.
        me(tokens.accessToken())
                .jsonPath("$.activeOrgId").isEmpty()
                .jsonPath("$.organizations[0].id").isEqualTo(orgId)
                .jsonPath("$.organizations[0].role").isEqualTo("OWNER");

        TokenResponse switched = switchOrg(tokens.refreshToken(), UUID.fromString(orgId))
                .expectStatus().isOk()
                .returnResult(TokenResponse.class).getResponseBody();

        Jwt jwt = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build().decode(switched.accessToken());
        assertThat(jwt.getClaimAsString("org")).isEqualTo(orgId);
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("OWNER");
        me(switched.accessToken()).jsonPath("$.activeOrgId").isEqualTo(orgId);
        // Switching rotated the session's refresh token. The new one works; the old one is now a reuse
        // (which also revokes the family, so it has to be checked last).
        refreshStatus(switched.refreshToken()).isOk();
        refreshStatus(tokens.refreshToken()).isUnauthorized();
    }

    @Test
    void slugsAreUniqueAndUrlSafe() {
        String email = uniqueEmail();
        register(email);
        String accessToken = login(email).accessToken();
        String slug = uniqueSlug();

        createOrg(accessToken, slug).expectStatus().isCreated();
        createOrg(accessToken, slug).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").isEqualTo("Organization slug is already taken");
        createOrg(accessToken, "Not A Slug").expectStatus().isBadRequest();
    }

    @Test
    void cannotSwitchIntoAForeignOrg() {
        String owner = uniqueEmail();
        register(owner);
        String slug = uniqueSlug();
        createOrg(login(owner).accessToken(), slug).expectStatus().isCreated();
        String outsider = uniqueEmail();
        register(outsider);
        TokenResponse outsiderTokens = login(outsider);

        switchOrg(outsiderTokens.refreshToken(), orgIdBySlug(slug)).expectStatus().isForbidden();
        switchOrg(outsiderTokens.refreshToken(), UUID.randomUUID()).expectStatus().isForbidden();

        // A refused switch changes nothing: the session's refresh token still works.
        refreshStatus(outsiderTokens.refreshToken()).isOk();
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

    private RestTestClient.BodyContentSpec me(String accessToken) {
        return http.get().uri("/me").header("Authorization", "Bearer " + accessToken).exchange()
                .expectStatus().isOk()
                .expectBody();
    }

    private RestTestClient.ResponseSpec createOrg(String accessToken, String slug) {
        return http.post().uri("/orgs").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Acme", "slug", slug))
                .exchange();
    }

    private RestTestClient.ResponseSpec switchOrg(String refreshToken, UUID orgId) {
        return http.post().uri("/auth/switch-org").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken, "orgId", orgId.toString()))
                .exchange();
    }

    private StatusAssertions refreshStatus(String refreshToken) {
        return http.post().uri("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus();
    }

    private UUID orgIdBySlug(String slug) {
        return db.sql("SELECT id FROM organizations WHERE slug = ?").param(slug).query(UUID.class).single();
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static String uniqueSlug() {
        return "org-" + UUID.randomUUID();
    }

}
