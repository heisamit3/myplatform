package dev.myplatform.identity.token;

import java.time.Duration;
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public signing key. The gateway and the other services fetch it to verify tokens
 * themselves, so they never call identity-service per request.
 */
@RestController
@Tag(name = "keys", description = "Public keys for verifying access tokens")
@SecurityRequirements // public
class JwksController {

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final Map<String, Object> jwks;

    JwksController(RSAKey signingKey) {
        // toPublicJWK() drops the private parts; a leaked "d" here would let anyone mint tokens.
        this.jwks = new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    @Operation(operationId = "getJwks", summary = "JSON Web Key Set (RFC 7517) with the RS256 signing key")
    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(CACHE).body(jwks);
    }

}
