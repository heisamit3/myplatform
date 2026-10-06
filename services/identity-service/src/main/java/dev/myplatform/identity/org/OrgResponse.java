package dev.myplatform.identity.org;

import java.util.UUID;

/** An organization as seen by one member: {@code role} is that member's role in it. */
public record OrgResponse(UUID id, String name, String slug, Role role) {
}
