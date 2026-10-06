package dev.myplatform.gateway.session;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param name   cookie name
 * @param secure send only over HTTPS. Browsers treat http://localhost as secure, so true works locally too.
 * @param maxAge should match identity-service's refresh-token TTL
 */
@ConfigurationProperties("gateway.refresh-cookie")
public record RefreshCookieProperties(String name, boolean secure, Duration maxAge) {
}
