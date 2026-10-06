package dev.myplatform.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/** Request rules without Spring: the same Bean Validation the controllers run with @Valid. */
class RequestValidationTests {

    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validRegistrationPasses() {
        assertThat(violations(new RegisterRequest("ada@example.com", "correct horse", "Ada"))).isEmpty();
    }

    @Test
    void emailAndDisplayNameAreTrimmedBeforeValidation() {
        RegisterRequest request = new RegisterRequest("  ada@example.com\t", "correct horse", "  Ada ");

        assertThat(request.email()).isEqualTo("ada@example.com");
        assertThat(request.displayName()).isEqualTo("Ada");
        assertThat(violations(request)).isEmpty();
    }

    @Test
    void passwordsAreNeverTrimmed() {
        assertThat(new RegisterRequest("ada@example.com", "  spaced  ", "Ada").password()).isEqualTo("  spaced  ");
    }

    @Test
    void passwordLengthIsCheckedInCharactersAndBytes() {
        assertThat(violations(new RegisterRequest("ada@example.com", "short", "Ada"))).containsExactly("password");
        assertThat(violations(new RegisterRequest("ada@example.com", "a".repeat(72), "Ada"))).isEmpty();
        // 24 x 3-byte "€" = 72 bytes: exactly at bcrypt's limit.
        assertThat(violations(new RegisterRequest("ada@example.com", "€".repeat(24), "Ada"))).isEmpty();
        // 25 x "€" = 75 bytes: only 25 characters, but bcrypt would ignore the end.
        assertThat(violations(new RegisterRequest("ada@example.com", "€".repeat(25), "Ada")))
                .containsExactly("passwordWithinBcryptLimit");
    }

    @Test
    void missingAndMalformedFieldsAreReported() {
        assertThat(violations(new RegisterRequest(null, null, null)))
                .containsExactlyInAnyOrder("email", "password", "displayName");
        assertThat(violations(new RegisterRequest("not-an-email", "correct horse", " ")))
                .containsExactlyInAnyOrder("email", "displayName");
    }

    @Test
    void bcryptLimitIsInUtf8Bytes() {
        assertThat(Passwords.fitsBcrypt("a".repeat(72))).isTrue();
        assertThat(Passwords.fitsBcrypt("a".repeat(73))).isFalse();
        assertThat(Passwords.fitsBcrypt("🔑".repeat(18))).isTrue();  // emoji = 4 bytes: 72
        assertThat(Passwords.fitsBcrypt("🔑".repeat(19))).isFalse(); // 76
    }

    private static Set<String> violations(Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

}
