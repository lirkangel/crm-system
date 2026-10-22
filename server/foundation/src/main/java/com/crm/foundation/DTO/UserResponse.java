package com.crm.foundation.DTO;

import com.crm.foundation.Domain.User;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String username,
        String email,
        Boolean enabled) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getEnabled());
    }
}
