package dev.myplatform.identity.auth;

import jakarta.validation.constraints.NotBlank;

record RefreshTokenRequest(@NotBlank String refreshToken) {
}
