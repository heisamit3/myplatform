package dev.myplatform.identity.token;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
class SigningKeyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyConfiguration.class);

    @Bean
    RSAKey signingKey(JwtProperties properties) {
        if (StringUtils.hasText(properties.privateKeyPath())) {
            Path path = Path.of(properties.privateKeyPath());
            try {
                RSAKey key = RsaKeys.fromPkcs8Pem(Files.readString(path));
                log.info("Loaded JWT signing key kid={} from {}", key.getKeyID(), path);
                return key;
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read JWT private key at " + path, e);
            }
        }
        if (properties.allowEphemeralKey()) {
            RSAKey key = RsaKeys.generate();
            log.warn("No JWT private key configured: using an ephemeral key kid={}. Tokens die on restart.",
                    key.getKeyID());
            return key;
        }
        throw new IllegalStateException(
                "No JWT signing key: set JWT_PRIVATE_KEY_PATH to a PKCS#8 PEM RSA key (see scripts/gen-jwt-key.sh)");
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

}
