package dev.myplatform.identity.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("identity.refresh-token")
record RefreshTokenProperties(@DefaultValue("7d") Duration ttl) {
}
