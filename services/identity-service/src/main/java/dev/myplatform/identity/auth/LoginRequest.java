package dev.myplatform.identity.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

record LoginRequest(@NotBlank String email, @NotNull String password) {
}
