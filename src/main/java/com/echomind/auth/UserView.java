package com.echomind.auth;

import com.echomind.store.UserEntity;

import java.time.LocalDateTime;

public record UserView(Long id, String username, String displayName, String role, boolean enabled,
                       LocalDateTime createdAt, LocalDateTime lastLoginAt) {

    public static UserView of(UserEntity u) {
        return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(),
                Boolean.TRUE.equals(u.getEnabled()), u.getCreatedAt(), u.getLastLoginAt());
    }
}
