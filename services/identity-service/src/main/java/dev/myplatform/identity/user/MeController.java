package dev.myplatform.identity.user;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.myplatform.identity.OpenApiConfiguration;
import dev.myplatform.identity.org.MembershipRepository;
import dev.myplatform.identity.org.OrgResponse;

@RestController
@Tag(name = "me", description = "The signed-in user")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
class MeController {

    private final UserRepository users;
    private final MembershipRepository memberships;

    MeController(UserRepository users, MembershipRepository memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    /** @param activeOrgId the org in the caller's access token, or null if they have none yet. */
    @Schema(requiredProperties = {"id", "email", "displayName", "activeOrgId", "organizations"})
    record MeResponse(UUID id, String email, String displayName,
            @Schema(types = {"string", "null"}, format = "uuid",
                    description = "Org of the current access token; null until the user has one") @Nullable UUID activeOrgId,
            @Schema(description = "All orgs of the user, oldest membership first") List<OrgResponse> organizations) {
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    @Operation(operationId = "getMe", summary = "The caller's profile, active org and all their orgs")
    @ApiResponse(responseCode = "200", description = "The signed-in user")
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
    @ApiResponse(responseCode = "404", description = "The account was deleted after the token was issued")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        // A valid token for a deleted user: the token outlives the account by up to 15 minutes.
        User user = users.findById(userId).orElseThrow(() -> new ErrorResponseException(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "User no longer exists"), null));
        String org = jwt.getClaimAsString("org");
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(),
                org == null ? null : UUID.fromString(org), memberships.findOrgsOfUser(userId));
    }

}
