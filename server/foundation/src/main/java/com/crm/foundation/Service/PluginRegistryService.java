package com.crm.foundation.Service;

import com.crm.foundation.Domain.PluginRegistry;
import com.crm.foundation.Exception.BadRequestException;
import com.crm.foundation.Exception.NotFoundException;
import com.crm.foundation.Plugin.PluginManifest;

import java.util.List;
import java.util.Optional;

/**
 * Owns the {@code plugin_registry} lifecycle (T013). All state transitions go
 * through this service so they stay explicit and auditable — see
 * {@link com.crm.foundation.Plugin.PluginState} for the transition diagram.
 *
 * <p>Methods throw {@link NotFoundException} for unknown plugin ids and
 * {@link BadRequestException} for illegal transitions.
 */
public interface PluginRegistryService {

    /** Create a {@code REGISTERED} row from a parsed manifest. Throws if the plugin id already exists. */
    PluginRegistry register(PluginManifest manifest);

    /** {@code REGISTERED|DISABLED|LOAD_FAILED → ACTIVE}; stamps {@code lastLoadedAt}, clears {@code errorMessage}. */
    PluginRegistry activate(String pluginId);

    /** {@code REGISTERED|ACTIVE|LOAD_FAILED → DISABLED}; skipped at next startup. */
    PluginRegistry disable(String pluginId);

    /** Any state {@code → LOAD_FAILED}; records the failure reason. */
    PluginRegistry markLoadFailed(String pluginId, String reason);

    /** Any state {@code → UNINSTALL_PENDING}; the cleanup job removes the row later. */
    PluginRegistry markUninstallPending(String pluginId);

    Optional<PluginRegistry> findByPluginId(String pluginId);

    List<PluginRegistry> findAll();
}
