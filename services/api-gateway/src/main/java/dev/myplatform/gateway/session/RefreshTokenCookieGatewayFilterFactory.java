package dev.myplatform.gateway.session;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Keeps the refresh token out of reach of JavaScript (ADR 0008). identity-service speaks JSON
 * ({@code refreshToken} in bodies); the browser only ever holds it in an HttpOnly cookie:
 * <ul>
 * <li>Response: a {@code refreshToken} field is removed from the body and set as the cookie.
 * A 401 (revoked or reused token) deletes the cookie.</li>
 * <li>Request: if the JSON body has no {@code refreshToken}, the cookie's value is put in.</li>
 * <li>{@code endSession: true} (logout): the cookie is deleted whatever identity-service answers.</li>
 * <li>Neither a cookie nor a body: there is no session to act on, so the gateway answers itself
 * (401, or 204 for logout) instead of letting identity-service reject a request without a token.</li>
 * </ul>
 * Use in a route: {@code - RefreshTokenCookie} or {@code - name: RefreshTokenCookie, args: {endSession: true}}.
 */
@Component
class RefreshTokenCookieGatewayFilterFactory
        extends AbstractGatewayFilterFactory<RefreshTokenCookieGatewayFilterFactory.Config> {

    static final String FIELD = "refreshToken";

    private final RefreshCookieProperties cookie;
    private final GatewayFilter requestFilter;
    private final GatewayFilter responseFilter;

    RefreshTokenCookieGatewayFilterFactory(ModifyRequestBodyGatewayFilterFactory modifyRequest,
            ModifyResponseBodyGatewayFilterFactory modifyResponse, RefreshCookieProperties cookie) {
        super(Config.class);
        this.cookie = cookie;
        this.requestFilter = modifyRequest.apply(config -> config
                .setRewriteFunction(Map.class, Map.class, this::addTokenFromCookie)
                .setContentType(MediaType.APPLICATION_JSON_VALUE));
        this.responseFilter = modifyResponse.apply(config -> config
                .setRewriteFunction(Map.class, Map.class, this::moveTokenToCookie));
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("endSession");
    }

    /**
     * Must run before the gateway's NettyWriteResponseFilter (order -1), like ModifyResponseBody itself:
     * that filter writes the proxied response to the response it saw, so a response decorated after it
     * would never see the body.
     */
    @Override
    public GatewayFilter apply(Config config) {
        int order = NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1;
        if (config.isEndSession()) {
            return new OrderedGatewayFilter((exchange, chain) -> {
                // Before proxying, so the cookie goes away even if identity-service is down.
                exchange.getResponse().addCookie(cookie(""));
                if (hasNoToken(exchange)) {
                    exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
                    return exchange.getResponse().setComplete();
                }
                return requestFilter.filter(exchange, chain);
            }, order);
        }
        return new OrderedGatewayFilter((exchange, chain) -> {
            if (hasNoToken(exchange)) {
                return noSession(exchange.getResponse());
            }
            return requestFilter.filter(exchange, modified -> responseFilter.filter(modified, chain));
        }, order);
    }

    /** No cookie and no body (so no refreshToken field either). A browser on its first visit, typically. */
    private boolean hasNoToken(ServerWebExchange exchange) {
        var request = exchange.getRequest();
        HttpCookie sent = request.getCookies().getFirst(cookie.name());
        boolean hasBody = request.getHeaders().getContentLength() > 0
                || request.getHeaders().containsHeader(HttpHeaders.TRANSFER_ENCODING);
        return (sent == null || sent.getValue().isEmpty()) && !hasBody;
    }

    private static Mono<Void> noSession(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body = """
                {"type":"about:blank","title":"Unauthorized","status":401,"detail":"No session"}"""
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Mono<Map> addTokenFromCookie(ServerWebExchange exchange, @Nullable Map body) {
        Map<String, Object> result = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        HttpCookie sent = exchange.getRequest().getCookies().getFirst(cookie.name());
        if (!result.containsKey(FIELD) && sent != null && !sent.getValue().isEmpty()) {
            result.put(FIELD, sent.getValue());
        }
        return Mono.just(result);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Mono<Map> moveTokenToCookie(ServerWebExchange exchange, @Nullable Map body) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.getStatusCode() == HttpStatus.UNAUTHORIZED) {
            response.addCookie(cookie(""));
        }
        if (body == null || !(body.get(FIELD) instanceof String token)) {
            return Mono.justOrEmpty(body);
        }
        Map<String, Object> withoutToken = new LinkedHashMap<>(body);
        withoutToken.remove(FIELD);
        response.addCookie(cookie(token));
        return Mono.just(withoutToken);
    }

    /** An empty value means "delete": Max-Age=0. */
    private ResponseCookie cookie(String value) {
        return ResponseCookie.from(cookie.name(), value)
                // Not readable by JavaScript, so an XSS bug can't steal the session.
                .httpOnly(true)
                .secure(cookie.secure())
                // Never sent on cross-site requests: the CSRF protection for the cookie endpoints.
                .sameSite("Strict")
                // Only the session endpoints get it, not every API call.
                .path("/auth")
                .maxAge(value.isEmpty() ? Duration.ZERO : cookie.maxAge())
                .build();
    }

    public static class Config {

        private boolean endSession;

        public boolean isEndSession() {
            return endSession;
        }

        public void setEndSession(boolean endSession) {
            this.endSession = endSession;
        }

    }

}
