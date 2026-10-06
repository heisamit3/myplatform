package dev.myplatform.identity.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** @param slug URL-safe handle, same rule as the CHECK on organizations.slug. Max 63, like a DNS label. */
record CreateOrgRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 63) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String slug) {

    CreateOrgRequest {
        name = name == null ? null : name.strip();
    }

}
