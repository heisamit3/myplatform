package dev.myplatform.identity.user;

import java.util.Locale;

public final class Emails {

    private Emails() {
    }

    /** Emails are stored lower-case; the users table has a CHECK for it (ADR 0002). */
    public static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

}
