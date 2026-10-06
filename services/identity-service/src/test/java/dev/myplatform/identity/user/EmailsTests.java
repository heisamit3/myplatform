package dev.myplatform.identity.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class EmailsTests {

    @Test
    void trimsAndLowerCases() {
        assertThat(Emails.normalize("  Ada.Lovelace@Example.COM\n")).isEqualTo("ada.lovelace@example.com");
    }

    @Test
    void ignoresTheDefaultLocale() {
        // In Turkish, "I".toLowerCase() is a dotless i: the same email would get two spellings.
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(Emails.normalize("ADMIN@EXAMPLE.COM")).isEqualTo("admin@example.com");
        } finally {
            Locale.setDefault(previous);
        }
    }

}
