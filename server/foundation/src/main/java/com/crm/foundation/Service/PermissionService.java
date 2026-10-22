package com.crm.foundation.Service;

import com.crm.foundation.Domain.Permission;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionService {
    List<Permission> findAll();

    Optional<Permission> findById(@NonNull UUID uuid);

    Optional<Permission> findByKey(String key);

}
