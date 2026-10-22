package com.crm.foundation.DTO;

import com.crm.foundation.Domain.Role;

import java.util.UUID;

public record RoleResponse(
    UUID code,
    String name,
    Boolean builtin,
    Long version
) {
    public static RoleResponse from(Role role) {
        return new RoleResponse(
            role.getId(),
            role.getNameI18n(),
            role.getBuiltin(),
            role.getVersion()
        );
    }
}
