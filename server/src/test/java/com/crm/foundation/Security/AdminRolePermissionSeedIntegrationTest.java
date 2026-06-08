package com.crm.foundation.Security;

import com.crm.foundation.Domain.Permission;
import com.crm.foundation.Domain.Role;
import com.crm.foundation.Repository.PermissionRepository;
import com.crm.foundation.Repository.RoleRepository;
import com.crm.foundation.config.TestContainersConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Flyway-seeded {@code admin} role is granted every built-in permission (T006a).
 * Excluded from default {@code mvn test}; run with {@code mvn verify -Pintegration}.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest
@Import(TestContainersConfig.class)
class AdminRolePermissionSeedIntegrationTest {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Test
    void adminRoleIsGrantedEveryBuiltInPermission() {
        Role admin = roleRepository.findByCode("admin").orElseThrow();
        Set<String> adminPermissionKeys =
            admin.getPermissions().stream().map(Permission::getKey).collect(Collectors.toSet());

        Set<String> allPermissionKeys =
            permissionRepository.findAll().stream().map(Permission::getKey).collect(Collectors.toSet());

        assertThat(adminPermissionKeys).containsExactlyInAnyOrderElementsOf(allPermissionKeys);
        assertThat(adminPermissionKeys).containsAll(CorePermissions.all());
    }
}
