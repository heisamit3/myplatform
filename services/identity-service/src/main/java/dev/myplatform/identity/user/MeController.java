package dev.myplatform.identity.user;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.myplatform.identity.org.MembershipRepository;
import dev.myplatform.identity.org.OrgResponse;

@RestController
class MeController {

    private final UserRepository users;
    private final MembershipRepository memberships;

    MeController(UserRepository users, MembershipRepository memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    /** @param activeOrgId the org in the caller's access token, or null if they have none yet. */
    record MeResponse(UUID id, String email, String displayName, @Nullable UUID activeOrgId,
            List<OrgResponse> organizations) {
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
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
