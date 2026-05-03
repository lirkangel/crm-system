package com.crm.foundation.Service;

import com.crm.foundation.Domain.Role;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleService {
    Optional<Role> findById(@NonNull UUID id);

    List<Role> findByName(String name);

    List<Role> findAll();
}
