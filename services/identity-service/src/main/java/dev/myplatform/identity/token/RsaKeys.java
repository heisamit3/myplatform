package dev.myplatform.identity.token;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

/** Builds the RS256 signing key as a JWK. Plain JDK crypto, no BouncyCastle. */
final class RsaKeys {

    static final int MIN_KEY_BITS = 2048;

    private RsaKeys() {
    }

    /** Parses a PKCS#8 PEM private key and derives the public key from it. */
    static RSAKey fromPkcs8Pem(String pem) {
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalArgumentException(
                    "PKCS#1 key found; convert it with: openssl pkcs8 -topk8 -nocrypt -in old.pem -out new.pem");
        }
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        RSAPrivateCrtKey privateKey;
        RSAPublicKey publicKey;
        try {
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            privateKey = (RSAPrivateCrtKey) rsa.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            publicKey = (RSAPublicKey) rsa.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalArgumentException("Not a valid PKCS#8 RSA private key", e);
        }
        return toJwk(publicKey, privateKey);
    }

    static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MIN_KEY_BITS);
            KeyPair pair = generator.generateKeyPair();
            return toJwk((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA is unavailable in this JVM", e);
        }
    }

    private static RSAKey toJwk(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        if (publicKey.getModulus().bitLength() < MIN_KEY_BITS) {
            throw new IllegalArgumentException("RSA key must be at least " + MIN_KEY_BITS + " bits");
        }
        try {
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    // kid = RFC 7638 thumbprint: stable for the same key, new key => new kid (rotation-friendly).
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not compute key thumbprint", e);
        }
    }

}
