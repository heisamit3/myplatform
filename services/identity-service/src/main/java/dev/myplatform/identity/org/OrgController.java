package dev.myplatform.identity.org;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orgs")
class OrgController {

    private final OrgService orgs;

    OrgController(OrgService orgs) {
        this.orgs = orgs;
    }

    /** Any signed-in user can create an org. Switch into it with POST /auth/switch-org. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrgResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateOrgRequest request) {
        return orgs.create(UUID.fromString(jwt.getSubject()), request);
    }

}
