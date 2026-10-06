package dev.myplatform.identity.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Tag(name = "auth", description = "Register, log in, and manage sessions (refresh tokens)")
@SecurityRequirements // public: these endpoints issue the tokens (spec shows security: [])
class AuthController {

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "register", summary = "Create a user account (no org yet)")
    @ApiResponse(responseCode = "201", description = "Account created; log in to get tokens")
    @ApiResponse(responseCode = "400", description = "Invalid email, password or display name")
    @ApiResponse(responseCode = "409", description = "Email is already registered")
    UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(request);
    }

    @PostMapping("/login")
    @Operation(operationId = "login", summary = "Log in; starts a new session in the first org the user joined")
    @ApiResponse(responseCode = "200", description = "Access token (15 min) and refresh token (7 days)")
    @ApiResponse(responseCode = "400", description = "Missing email or password")
    @ApiResponse(responseCode = "401", description = "Invalid email or password")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        // Tokens must never sit in a browser or proxy cache (RFC 6749 section 5.1).
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.login(request));
    }

    @PostMapping("/refresh")
    @Operation(operationId = "refreshTokens", summary = "Trade a refresh token for a new token pair (rotation)",
            description = "The presented refresh token is revoked. Presenting it again revokes the whole session.")
    @ApiResponse(responseCode = "200", description = "New access and refresh token")
    @ApiResponse(responseCode = "400", description = "Missing refresh token")
    @ApiResponse(responseCode = "401", description = "Unknown, expired, revoked or reused refresh token")
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.refresh(request));
    }

    /** Moves the session into another org the user belongs to: new token pair with the new org claim. */
    @PostMapping("/switch-org")
    @Operation(operationId = "switchOrg", summary = "Move the session into another org of the user",
            description = "Rotates the refresh token like /auth/refresh; the new access token carries the org.")
    @ApiResponse(responseCode = "200", description = "New token pair for the target org")
    @ApiResponse(responseCode = "400", description = "Missing refresh token or org id")
    @ApiResponse(responseCode = "401", description = "Unknown, expired, revoked or reused refresh token")
    @ApiResponse(responseCode = "403", description = "Not a member of this organization (or it doesn't exist)")
    ResponseEntity<TokenResponse> switchOrg(@Valid @RequestBody SwitchOrgRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.switchOrg(request));
    }

    /** Always 204, also for unknown or already revoked tokens: logging out twice is not an error. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "logout", summary = "End the session the refresh token belongs to (idempotent)")
    @ApiResponse(responseCode = "204", description = "Session ended (also for unknown or revoked tokens)")
    @ApiResponse(responseCode = "400", description = "Missing refresh token")
    void logout(@Valid @RequestBody RefreshTokenRequest request) {
        auth.logout(request);
    }

}
