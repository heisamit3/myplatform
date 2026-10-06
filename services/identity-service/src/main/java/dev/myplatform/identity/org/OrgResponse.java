package dev.myplatform.identity.org;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** An organization as seen by one member: {@code role} is that member's role in it. */
@Schema(requiredProperties = {"id", "name", "slug", "role"})
public record OrgResponse(UUID id, String name, String slug, Role role) {
}
