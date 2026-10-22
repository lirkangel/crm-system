package com.crm.foundation.DTO;

import com.crm.foundation.Domain.User;

import java.util.Set;
import java.util.UUID;

public record MeResponse(
        UUID id,
        String username,
        String email,
        Set<String> permissions) {

    public static MeResponse from(User user, Set<String> permissions) {
        return new MeResponse(user.getId(), user.getUsername(), user.getEmail(), permissions);
    }
}
