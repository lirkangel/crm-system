package com.crm.foundation.Service.Impl;

import com.crm.foundation.Domain.Permission;
import com.crm.foundation.Repository.PermissionRepository;
import com.crm.foundation.Service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class PermissionServiceImpl implements PermissionService {

    private final PermissionRepository permissionRepository;

    @Override
    public List<Permission> findAll() {
        return permissionRepository.findAll();
    }

    @Override
    public Optional<Permission> findById(@NonNull UUID uuid) {
        return permissionRepository.findById(uuid);
    }

    @Override
    public Optional<Permission> findByKey(String key) {
        return permissionRepository.findByKey(key);
    }
}
