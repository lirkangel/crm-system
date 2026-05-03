package com.crm.foundation.Service;

import com.crm.foundation.Domain.Permissions;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionService {
    List<Permissions> findAll();

    Optional<Permissions> findById(@NonNull UUID uuid);

    Optional<Permissions> findByKey(String key);

}
