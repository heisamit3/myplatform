package dev.myplatform.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/** Which IP becomes the rate-limit key, with and without a proxy in front. No Spring context. */
class ClientIpKeyResolverTests {

    private static final InetSocketAddress PROXY = new InetSocketAddress("10.0.0.5", 40000);

    @Test
    void withoutProxiesTheConnectionAddressIsTheKeyAndForwardedHeadersAreIgnored() {
        KeyResolver resolver = new RateLimitConfiguration().clientIpKeyResolver(0);
        var exchange = exchange("1.2.3.4");
        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:10.0.0.5");
    }

    @Test
    void behindOneProxyTheLastForwardedAddressIsTheKey() {
        KeyResolver resolver = new RateLimitConfiguration().clientIpKeyResolver(1);
        assertThat(resolver.resolve(exchange("203.0.113.7")).block()).isEqualTo("ip:203.0.113.7");
    }

    @Test
    void behindOneProxyAddressesTheClientMadeUpAreIgnored() {
        KeyResolver resolver = new RateLimitConfiguration().clientIpKeyResolver(1);
        // The client sent "X-Forwarded-For: 6.6.6.6"; the proxy appended the real address.
        assertThat(resolver.resolve(exchange("6.6.6.6, 203.0.113.7")).block()).isEqualTo("ip:203.0.113.7");
    }

    private static MockServerWebExchange exchange(String forwardedFor) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/auth/login")
                .remoteAddress(PROXY)
                .header("X-Forwarded-For", forwardedFor));
    }

}
