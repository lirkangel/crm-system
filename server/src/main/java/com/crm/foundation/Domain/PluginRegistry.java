package com.crm.foundation.Domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "plugin_registry")
public class PluginRegistry {

    @Id
    @UuidGenerator
    private UUID id;

    @NotNull
    @Column(name = "plugin_id", nullable = false, unique = true)
    private String pluginId;

    @NotNull
    @Column(nullable = false, length = 64)
    private String version;

    @NotNull
    @Column(name = "schema_name", nullable = false, unique = true, length = 64)
    private String schemaName;

    @NotNull
    @Column(nullable = false, length = 32)
    private String state;

    @Column(name = "last_loaded_at")
    private LocalDateTime lastLoadedAt;

    @Column(name = "error_message")
    private String errorMessage;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest_json", nullable = false, columnDefinition = "jsonb")
    private String manifestJson;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public LocalDateTime getLastLoadedAt() {
        return lastLoadedAt;
    }

    public void setLastLoadedAt(LocalDateTime lastLoadedAt) {
        this.lastLoadedAt = lastLoadedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getManifestJson() {
        return manifestJson;
    }

    public void setManifestJson(String manifestJson) {
        this.manifestJson = manifestJson;
    }
}
