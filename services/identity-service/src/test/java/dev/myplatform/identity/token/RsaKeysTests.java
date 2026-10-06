package dev.myplatform.identity.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;

class RsaKeysTests {

    @Test
    void parsesPkcs8PemAndDerivesThePublicKey() throws Exception {
        KeyPair pair = rsaKeyPair(2048);

        RSAKey jwk = RsaKeys.fromPkcs8Pem(pkcs8Pem(pair));

        assertThat(jwk.isPrivate()).isTrue();
        assertThat(jwk.toRSAPublicKey()).isEqualTo(pair.getPublic());
        assertThat(jwk.getKeyID()).isEqualTo(jwk.computeThumbprint().toString());
    }

    @Test
    void sameKeyAlwaysGetsTheSameKid() throws Exception {
        String pem = pkcs8Pem(rsaKeyPair(2048));

        assertThat(RsaKeys.fromPkcs8Pem(pem).getKeyID()).isEqualTo(RsaKeys.fromPkcs8Pem(pem).getKeyID());
    }

    @Test
    void rejectsKeysShorterThan2048Bits() throws Exception {
        String pem = pkcs8Pem(rsaKeyPair(1024));

        assertThatThrownBy(() -> RsaKeys.fromPkcs8Pem(pem)).hasMessageContaining("2048");
    }

    @Test
    void rejectsPkcs1PemWithAConversionHint() {
        assertThatThrownBy(() -> RsaKeys.fromPkcs8Pem("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"))
                .hasMessageContaining("openssl pkcs8");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> RsaKeys.fromPkcs8Pem("-----BEGIN PRIVATE KEY-----\nbm9wZQ==\n-----END PRIVATE KEY-----"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static KeyPair rsaKeyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String pkcs8Pem(KeyPair pair) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

}
