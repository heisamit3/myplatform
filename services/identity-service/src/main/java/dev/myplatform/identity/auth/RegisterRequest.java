package dev.myplatform.identity.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Size(min = 8) String password,
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
