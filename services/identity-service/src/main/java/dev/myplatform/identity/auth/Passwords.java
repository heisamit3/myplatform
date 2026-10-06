package dev.myplatform.identity.auth;

import java.nio.charset.StandardCharsets;

final class Passwords {

    /** bcrypt only reads the first 72 bytes; Spring's encoder rejects longer input instead of truncating. */
    static final int BCRYPT_MAX_BYTES = 72;

    private Passwords() {
    }

    static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }

}
