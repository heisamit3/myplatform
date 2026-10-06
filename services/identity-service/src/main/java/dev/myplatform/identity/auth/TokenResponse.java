package dev.myplatform.identity.auth;

/** @param expiresIn access-token lifetime in seconds. */
record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken) {
}
