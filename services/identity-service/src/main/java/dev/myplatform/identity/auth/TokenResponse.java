package dev.myplatform.identity.auth;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"accessToken", "tokenType", "expiresIn", "refreshToken"})
record TokenResponse(
        @Schema(description = "RS256 JWT with sub (user), org (active org, if any) and roles") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "Access-token lifetime in seconds", example = "900") long expiresIn,
        @Schema(description = "Opaque, single-use: every refresh returns a new one") String refreshToken) {
}
