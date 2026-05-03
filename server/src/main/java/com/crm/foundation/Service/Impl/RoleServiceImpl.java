package com.crm.foundation.Service.Impl;

import com.crm.foundation.Domain.Roles;
import com.crm.foundation.Repository.RoleRepository;
import com.crm.foundation.Service.RoleService;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RoleServiceImpl implements RoleService {

    @Autowired
    private RoleRepository roleRepository;

    @Override
    public List<Roles> findByName(String name) {
        return roleRepository.findByName(name);
    }

    @Override
    public Optional<Roles> findById(@NotNull UUID id) {
        return roleRepository.findById(id);
    }

    @Override
    public List<Roles> findAll() {
        return roleRepository.findAll();
    }
}
