package dev.myplatform.identity.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        // max = 72 characters is implied by the 72-byte check (a character is at least one byte).
        @NotNull @Size(min = 8, max = 72) @Schema(description = "8 to 72 characters, at most 72 bytes in UTF-8")
        String password,
        @NotBlank @Size(max = 100) String displayName) {

    // Runs before validation, so "  ada@example.com " passes @Email. Passwords are never trimmed.
    RegisterRequest {
        email = email == null ? null : email.strip();
        displayName = displayName == null ? null : displayName.strip();
    }

    @AssertTrue(message = "password must be at most 72 bytes in UTF-8")
    boolean isPasswordWithinBcryptLimit() {
        return password == null || Passwords.fitsBcrypt(password);
    }

}
