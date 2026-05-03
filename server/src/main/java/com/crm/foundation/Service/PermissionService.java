package com.crm.foundation.Service;

import com.crm.foundation.Domain.Permissions;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionService {
    List<Permissions> findAll();

    Optional<Permissions> findById(@NotNull UUID uuid);

    Optional<Permissions> findByKey(String key);

}
