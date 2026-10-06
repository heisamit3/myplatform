package dev.myplatform.identity.auth;

import java.util.UUID;

import dev.myplatform.identity.user.User;

record UserResponse(UUID id, String email, String displayName) {

    static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }

}
