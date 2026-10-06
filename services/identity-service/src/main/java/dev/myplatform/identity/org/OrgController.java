package dev.myplatform.identity.org;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.myplatform.identity.OpenApiConfiguration;

@RestController
@RequestMapping("/orgs")
@Tag(name = "organizations", description = "Organizations (tenants)")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
class OrgController {

    private final OrgService orgs;

    OrgController(OrgService orgs) {
        this.orgs = orgs;
    }

    /** Any signed-in user can create an org. Switch into it with POST /auth/switch-org. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createOrg", summary = "Create an organization; the caller becomes its OWNER",
            description = "The caller's session doesn't move into it automatically: call /auth/switch-org.")
    @ApiResponse(responseCode = "201", description = "Organization created")
    @ApiResponse(responseCode = "400", description = "Invalid name or slug")
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
    @ApiResponse(responseCode = "409", description = "Organization slug is already taken")
    OrgResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateOrgRequest request) {
        return orgs.create(UUID.fromString(jwt.getSubject()), request);
    }

}
