package com.crm.foundation.Service;

import com.crm.foundation.Domain.Roles;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleService {
    Optional<Roles> findById(@NonNull UUID id);

    List<Roles> findByName(String name);

    List<Roles> findAll();
}
