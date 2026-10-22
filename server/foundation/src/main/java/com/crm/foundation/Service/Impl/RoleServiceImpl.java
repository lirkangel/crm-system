package com.crm.foundation.Service.Impl;

import com.crm.foundation.Domain.Role;
import com.crm.foundation.Repository.RoleRepository;
import com.crm.foundation.Service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;

    @Override
    public List<Role> findByName(String name) {
        return roleRepository.findByName(name);
    }

    @Override
    public Optional<Role> findById(@NonNull UUID id) {
        return roleRepository.findById(id);
    }

    @Override
    public List<Role> findAll() {
        return roleRepository.findAll();
    }

    @Override
    public Set<String> permissionKeysForUser(@NonNull UUID userId) {
        return roleRepository.findPermissionKeysForUser(userId);
    }
}
