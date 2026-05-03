package com.crm.foundation.Service.Impl;

import com.crm.foundation.Domain.Permissions;
import com.crm.foundation.Repository.PermissionRepository;
import com.crm.foundation.Service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PermissionServiceImpl implements PermissionService {

    @Autowired
    private PermissionRepository permissionRepository;

    @Override
    public List<Permissions> findAll() {
        return permissionRepository.findAll();
    }

    @Override
    public Optional<Permissions> findById(@NonNull UUID uuid) {
        return permissionRepository.findById(uuid);
    }

    @Override
    public Optional<Permissions> findByKey(String key) {
        return permissionRepository.findByKey(key);
    }
}
