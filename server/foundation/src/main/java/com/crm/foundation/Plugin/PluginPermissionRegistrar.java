package com.crm.foundation.Plugin;

import com.crm.foundation.Domain.Permission;
import com.crm.foundation.Domain.Role;
import com.crm.foundation.Repository.PermissionRepository;
import com.crm.foundation.Repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Registers a plugin's declared permissions (design 6.4). Each key not yet in
 * {@code permissions} is inserted with the plugin's id and granted to the
 * {@code admin} role; keys that already exist — Foundation's, another plugin's,
 * or this plugin's from an earlier start — are left untouched, so an admin who
 * removed a grant doesn't get it back on restart.
 */
@Component
@Transactional
@RequiredArgsConstructor
public class PluginPermissionRegistrar {

    private static final Logger log = LoggerFactory.getLogger(PluginPermissionRegistrar.class);

    static final String ADMIN_ROLE = "admin";

    private final PermissionRepository permissions;
    private final RoleRepository roles;

    public void register(String pluginId, List<String> keys) {
        List<Permission> inserted = new ArrayList<>();
        for (String key : new LinkedHashSet<>(keys)) {
            if (permissions.findByKey(key).isEmpty()) {
                Permission permission = new Permission();
                permission.setKey(key);
                permission.setPluginId(pluginId);
                inserted.add(permissions.saveAndFlush(permission));
            }
        }
        if (inserted.isEmpty()) {
            return;
        }
        Role admin = roles.findByCode(ADMIN_ROLE).orElse(null);
        if (admin == null) {
            log.warn("No '{}' role: permissions of plugin {} were inserted but not granted", ADMIN_ROLE, pluginId);
            return;
        }
        admin.getPermissions().addAll(inserted);
        roles.saveAndFlush(admin);
        log.info("Plugin {} registered permissions {} and granted them to {}",
            pluginId, inserted.stream().map(Permission::getKey).toList(), ADMIN_ROLE);
    }
}
