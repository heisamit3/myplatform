package dev.myplatform.gateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** Points the identity route and the JWKS URL at {@link FakeIdentityService}. */
@TestConfiguration(proxyBeanMethods = false)
class FakeIdentityConfiguration {

    @Bean
    DynamicPropertyRegistrar fakeIdentityProperties() {
        String base = FakeIdentityService.instance().baseUri();
        return registry -> {
            registry.add("IDENTITY_URI", () -> base);
            registry.add("JWKS_URI", () -> base + "/.well-known/jwks.json");
        };
    }

}
