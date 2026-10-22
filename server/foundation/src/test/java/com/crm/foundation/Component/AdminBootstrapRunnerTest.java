package com.crm.foundation.Component;

import com.crm.foundation.Audit.AuditPayload;
import com.crm.foundation.DTO.CreateUserRequest;
import com.crm.foundation.Domain.Role;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.RoleRepository;
import com.crm.foundation.Repository.UserRepository;
import com.crm.foundation.Service.AuditService;
import com.crm.foundation.Service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    @Mock UserService userService;
    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock AuditService auditService;

    private AdminBootstrapRunner runner(String username, String email, String password) {
        return new AdminBootstrapRunner(
            username, email, password, userService, userRepository, roleRepository, auditService);
    }

    private static Role adminRole() {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("admin");
        return role;
    }

    @Test
    void noAdminAndAllVarsSet_createsAdminWithRoleAndAudits() {
        Role admin = adminRole();
        User created = new User();
        created.setId(UUID.randomUUID());
        when(roleRepository.lockByCode("admin")).thenReturn(Optional.of(admin));
        when(userRepository.existsByRoles_Code("admin")).thenReturn(false);
        when(userRepository.existsByUsernameOrEmail("root", "root@hotel.local")).thenReturn(false);
        when(userService.createUser(any(), any(), any())).thenReturn(created);

        runner("root", "root@hotel.local", "s3cret-pass").bootstrap();

        // Hashing, change event and USER_CREATE audit all come from UserServiceImpl.createUser
        verify(userService).createUser(new CreateUserRequest("root", "root@hotel.local", "s3cret-pass"), null, null);
        assertThat(created.getRoles()).containsExactly(admin);
        ArgumentCaptor<AuditPayload> audit = ArgumentCaptor.forClass(AuditPayload.class);
        verify(auditService).record(audit.capture());
        assertThat(audit.getValue().op()).isEqualTo("ADMIN_BOOTSTRAP");
        assertThat(audit.getValue().entityType()).isEqualTo("User");
        assertThat(audit.getValue().entityId()).isEqualTo(created.getId());
        assertThat(audit.getValue().userId()).isNull();
    }

    @Test
    void adminAlreadyExists_createsNothing() {
        when(roleRepository.lockByCode("admin")).thenReturn(Optional.of(adminRole()));
        when(userRepository.existsByRoles_Code("admin")).thenReturn(true);

        runner("root", "root@hotel.local", "s3cret-pass").bootstrap();

        verifyNoInteractions(userService, auditService);
        verify(userRepository, never()).save(any());
    }

    @Test
    void usernameOrEmailTakenByNonAdmin_doesNotHijackTheAccount() {
        when(roleRepository.lockByCode("admin")).thenReturn(Optional.of(adminRole()));
        when(userRepository.existsByRoles_Code("admin")).thenReturn(false);
        when(userRepository.existsByUsernameOrEmail("root", "root@hotel.local")).thenReturn(true);

        runner("root", "root@hotel.local", "s3cret-pass").bootstrap();

        verifyNoInteractions(userService, auditService);
        verify(userRepository, never()).save(any());
    }

    @Test
    void varsUnset_doesNothing() {
        runner("", "", "").bootstrap();
        runner(null, null, null).bootstrap();

        verifyNoInteractions(userService, auditService, roleRepository);
    }

    @Test
    void varsPartiallySet_doesNothing() {
        runner("root", "", "s3cret-pass").bootstrap();
        runner("root", "root@hotel.local", "  ").bootstrap();

        verifyNoInteractions(userService, auditService, roleRepository);
    }

    @Test
    void seededAdminRoleMissing_failsLoudly() {
        when(roleRepository.lockByCode("admin")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runner("root", "root@hotel.local", "s3cret-pass").bootstrap())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("admin");
        verifyNoInteractions(userService, auditService);
    }
}
