package com.crm.foundation.Repository;

import com.crm.foundation.Domain.PluginRegistry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PluginRegistryRepository extends JpaRepository<PluginRegistry, UUID> {

    Optional<PluginRegistry> findByPluginId(String pluginId);

    Optional<PluginRegistry> findBySchemaName(String schemaName);
}
