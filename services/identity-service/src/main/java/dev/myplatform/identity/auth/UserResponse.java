package dev.myplatform.identity.auth;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import dev.myplatform.identity.user.User;

@Schema(requiredProperties = {"id", "email", "displayName"})
record UserResponse(UUID id, String email, String displayName) {

    static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }

}
