package com.crm.foundation.Security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorePermissionsTest {

    @Test
    void constantsMatchSeededPermissionKeys() {
        assertThat(CorePermissions.USERS_READ).isEqualTo("core.users.read");
        assertThat(CorePermissions.USERS_WRITE).isEqualTo("core.users.write");
        assertThat(CorePermissions.USERS_DELETE).isEqualTo("core.users.delete");
        assertThat(CorePermissions.ROLES_READ).isEqualTo("core.roles.read");
        assertThat(CorePermissions.ROLES_WRITE).isEqualTo("core.roles.write");
        assertThat(CorePermissions.AUDIT_READ).isEqualTo("core.audit.read");
        assertThat(CorePermissions.PLUGINS_MANAGE).isEqualTo("core.plugins.manage");
        assertThat(CorePermissions.SYNC_READ).isEqualTo("core.sync.read");
    }

    @Test
    void allReturnsEveryConstant() {
        assertThat(CorePermissions.all()).containsExactlyInAnyOrder(
            CorePermissions.USERS_READ,
            CorePermissions.USERS_WRITE,
            CorePermissions.USERS_DELETE,
            CorePermissions.ROLES_READ,
            CorePermissions.ROLES_WRITE,
            CorePermissions.AUDIT_READ,
            CorePermissions.PLUGINS_MANAGE,
            CorePermissions.SYNC_READ);
    }
}
