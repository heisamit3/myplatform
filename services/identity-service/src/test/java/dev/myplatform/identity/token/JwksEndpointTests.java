package dev.myplatform.identity.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.client.RestTestClient;

import dev.myplatform.identity.WebIntegrationTest;

@WebIntegrationTest
class JwksEndpointTests {

    @Autowired
    RestTestClient http;

    @Autowired
    AccessTokenIssuer issuer;

    @Test
    void publishesOnlyThePublicRs256Key() {
        http.get().uri("/.well-known/jwks.json").exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches(HttpHeaders.CACHE_CONTROL, ".*max-age=300.*")
                .expectBody()
                .jsonPath("$.keys.length()").isEqualTo(1)
                .jsonPath("$.keys[0].kty").isEqualTo("RSA")
                .jsonPath("$.keys[0].alg").isEqualTo("RS256")
                .jsonPath("$.keys[0].use").isEqualTo("sig")
                .jsonPath("$.keys[0].kid").isNotEmpty()
                .jsonPath("$.keys[0].n").isNotEmpty()
                // Private RSA parameters must never be published.
                .jsonPath("$.keys[0].d").doesNotExist()
                .jsonPath("$.keys[0].p").doesNotExist()
                .jsonPath("$.keys[0].q").doesNotExist();
    }

    @Test
    void tokensVerifyAgainstThePublishedKey() throws Exception {
        String json = http.get().uri("/.well-known/jwks.json").exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseBody();
        RSAKey published = (RSAKey) JWKSet.parse(json).getKeys().getFirst();
        UUID userId = UUID.randomUUID();

        String token = issuer.issue(userId, null, List.of());
        Jwt jwt = NimbusJwtDecoder.withPublicKey(published.toRSAPublicKey()).build().decode(token);

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getHeaders()).containsEntry("kid", published.getKeyID());
    }

}
