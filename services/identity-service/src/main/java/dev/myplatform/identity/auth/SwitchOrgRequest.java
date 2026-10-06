package dev.myplatform.identity.auth;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** The refresh token identifies the session to move; it is rotated like on a normal refresh. */
record SwitchOrgRequest(@NotBlank String refreshToken, @NotNull UUID orgId) {
}
