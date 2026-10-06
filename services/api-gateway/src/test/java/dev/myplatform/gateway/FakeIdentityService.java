package dev.myplatform.gateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stands in for identity-service: serves a JWKS, mints tokens with the matching key, answers the session
 * endpoints like the real service, and echoes every other request so tests can see what the gateway
 * forwarded. Uses the JDK's built-in HTTP server
 * (no extra dependency, no container). One instance per JVM, shared by all tests.
 */
final class FakeIdentityService {

    static final String ISSUER = "http://identity-service";

    /** A refresh token the fake treats as revoked: 401 like identity-service. */
    static final String REVOKED_REFRESH_TOKEN = "revoked";

    static final String ISSUED_REFRESH_TOKEN = "rt-from-identity";

    private static final FakeIdentityService INSTANCE = new FakeIdentityService();

    private final RSAKey key = generateKey();
    private final HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");

    private FakeIdentityService() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        String jwks = new JWKSet(key.toPublicJWK()).toString();
        server.createContext("/.well-known/jwks.json", exchange -> respond(exchange, 200, jwks));
        for (String path : List.of("/auth/login", "/auth/refresh", "/auth/switch-org")) {
            server.createContext(path, this::issueTokens);
        }
        server.createContext("/auth/logout", this::logout);
        server.createContext("/", FakeIdentityService::echo);
        server.start();
    }

    static FakeIdentityService instance() {
        return INSTANCE;
    }

    /** The raw body of the last session-endpoint request, as the gateway forwarded it; null after a reset. */
    String lastBody() {
        return lastBody.get();
    }

    void resetLastBody() {
        lastBody.set(null);
    }

    String baseUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A valid access token like identity-service issues. */
    String accessToken() {
        return token(key, ISSUER, Instant.now().plusSeconds(900));
    }

    String token(String issuer, Instant expiresAt) {
        return token(key, issuer, expiresAt);
    }

    /** Signed by a key the JWKS doesn't contain, e.g. an attacker's. */
    String tokenSignedByAnotherKey() {
        return token(generateKey(), ISSUER, Instant.now().plusSeconds(900));
    }

    private static String token(RSAKey signingKey, String issuer, Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(UUID.randomUUID().toString())
                .issueTime(new Date())
                .expirationTime(Date.from(expiresAt))
                .claim("org", UUID.randomUUID().toString())
                .claim("roles", List.of("OWNER"))
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID()).type(JOSEObjectType.JWT).build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new RSASSASigner(signingKey));
        }
        catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
        return jwt.serialize();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        }
        catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Like /auth/login, /auth/refresh, /auth/switch-org: a token pair in JSON. */
    private void issueTokens(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastBody.set(body);
        if (body.contains("\"" + REVOKED_REFRESH_TOKEN + "\"")) {
            exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
            respondRaw(exchange, 401, """
                    {"type":"about:blank","title":"Unauthorized","status":401}""");
            return;
        }
        respond(exchange, 200, """
                {"accessToken":"%s","tokenType":"Bearer","expiresIn":900,"refreshToken":"%s"}"""
                .formatted(accessToken(), ISSUED_REFRESH_TOKEN));
    }

    private void logout(HttpExchange exchange) throws IOException {
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    /** Echoes method, path and whether an Authorization header arrived. */
    private static void echo(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String body = """
                {"method":"%s","path":"%s","authorization":%b}""".formatted(
                exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().containsKey("Authorization"));
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        respondRaw(exchange, status, body);
    }

    private static void respondRaw(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

}
