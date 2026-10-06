package dev.myplatform.gateway.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
class RateLimitConfiguration {

    /**
     * Rate-limit key = the client's IP. Login and register have no user yet, so the IP is all we have.
     * With trusted proxies, the IP comes from X-Forwarded-For, counted from the right: the entries a
     * client wrote itself are on the left and are ignored.
     */
    @Bean
    KeyResolver clientIpKeyResolver(@Value("${gateway.rate-limit.trusted-proxies}") int trustedProxies) {
        RemoteAddressResolver resolver = trustedProxies > 0
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxies)
                : new RemoteAddressResolver() { };
        return exchange -> {
            var address = resolver.resolve(exchange);
            // An empty key makes the limiter deny the request (deny-empty-key), rather than share one bucket.
            return address == null ? Mono.empty() : Mono.just("ip:" + address.getAddress().getHostAddress());
        };
    }

}
