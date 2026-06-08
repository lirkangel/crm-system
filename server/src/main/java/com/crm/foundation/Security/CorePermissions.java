package com.crm.foundation.Security;

import java.util.Set;

/** Permission keys built into Foundation (seeded in {@code permissions}, referenced by {@code @PreAuthorize}). */
public final class CorePermissions {

    public static final String USERS_READ = "core.users.read";
    public static final String USERS_WRITE = "core.users.write";
    public static final String USERS_DELETE = "core.users.delete";
    public static final String ROLES_READ = "core.roles.read";
    public static final String ROLES_WRITE = "core.roles.write";
    public static final String AUDIT_READ = "core.audit.read";
    public static final String PLUGINS_MANAGE = "core.plugins.manage";

    private CorePermissions() {
    }

    public static Set<String> all() {
        return Set.of(
            USERS_READ,
            USERS_WRITE,
            USERS_DELETE,
            ROLES_READ,
            ROLES_WRITE,
            AUDIT_READ,
            PLUGINS_MANAGE);
    }
}
