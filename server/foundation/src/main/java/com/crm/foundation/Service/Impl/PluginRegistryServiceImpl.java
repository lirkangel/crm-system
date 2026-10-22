package com.crm.foundation.Service.Impl;

import com.crm.foundation.Domain.PluginRegistry;
import com.crm.foundation.Exception.BadRequestException;
import com.crm.foundation.Exception.NotFoundException;
import com.crm.foundation.Plugin.PluginManifest;
import com.crm.foundation.Plugin.PluginState;
import com.crm.foundation.Repository.PluginRegistryRepository;
import com.crm.foundation.Service.PluginRegistryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PluginRegistryServiceImpl implements PluginRegistryService {

    private final PluginRegistryRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public PluginRegistry register(PluginManifest manifest) {
        repository.findByPluginId(manifest.id()).ifPresent(existing -> {
            throw new BadRequestException("Plugin already registered: " + manifest.id());
        });

        PluginRegistry entry = new PluginRegistry();
        entry.setPluginId(manifest.id());
        entry.setVersion(manifest.version());
        entry.setSchemaName(manifest.schema());
        entry.setState(PluginState.REGISTERED.name());
        entry.setManifestJson(toJson(manifest));
        return repository.save(entry);
    }

    @Override
    @Transactional
    public PluginRegistry activate(String pluginId) {
        PluginRegistry entry = required(pluginId);
        rejectIfUninstallPending(entry, "activate");
        entry.setState(PluginState.ACTIVE.name());
        entry.setLastLoadedAt(Instant.now());
        entry.setErrorMessage(null);
        return repository.save(entry);
    }

    @Override
    @Transactional
    public PluginRegistry disable(String pluginId) {
        PluginRegistry entry = required(pluginId);
        rejectIfUninstallPending(entry, "disable");
        entry.setState(PluginState.DISABLED.name());
        return repository.save(entry);
    }

    @Override
    @Transactional
    public PluginRegistry markLoadFailed(String pluginId, String reason) {
        PluginRegistry entry = required(pluginId);
        entry.setState(PluginState.LOAD_FAILED.name());
        entry.setErrorMessage(reason);
        return repository.save(entry);
    }

    @Override
    @Transactional
    public PluginRegistry markUninstallPending(String pluginId) {
        PluginRegistry entry = required(pluginId);
        entry.setState(PluginState.UNINSTALL_PENDING.name());
        return repository.save(entry);
    }

    @Override
    public Optional<PluginRegistry> findByPluginId(String pluginId) {
        return repository.findByPluginId(pluginId);
    }

    @Override
    public List<PluginRegistry> findAll() {
        return repository.findAll();
    }

    private PluginRegistry required(String pluginId) {
        return repository.findByPluginId(pluginId)
            .orElseThrow(() -> new NotFoundException("Plugin not found: " + pluginId));
    }

    private static void rejectIfUninstallPending(PluginRegistry entry, String action) {
        if (PluginState.UNINSTALL_PENDING.name().equals(entry.getState())) {
            throw new BadRequestException(
                "Cannot " + action + " plugin " + entry.getPluginId() + ": uninstall pending");
        }
    }

    private String toJson(PluginManifest manifest) {
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize manifest for " + manifest.id(), e);
        }
    }
}
