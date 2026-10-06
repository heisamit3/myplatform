package dev.myplatform.gateway.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param allowedOrigins exact origins (scheme://host:port) the browser app is served from. */
@ConfigurationProperties("gateway.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
