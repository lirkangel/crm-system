package com.crm.foundation.Controller;

import com.crm.foundation.Audit.AuditPayload;
import com.crm.foundation.DTO.CreateUserRequest;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import com.crm.foundation.Service.AuditService;
import com.crm.foundation.Service.ChangeEventService;
import com.crm.foundation.Service.Impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that createUser audit events are recorded by UserServiceImpl
 * (audit responsibility moved from controller to service layer).
 */
@ExtendWith(MockitoExtension.class)
class UserControllerAuditTest {

    @Mock UserRepository userRepository;
    @Mock ChangeEventService changeEventService;
    @Mock AuditService auditService;

    @InjectMocks UserServiceImpl userService;

    @Test
    void createUser_recordsAuditEventWithActorAndCreatedUserId() {
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID newUserId = UUID.fromString("00000000-0000-0000-0000-000000000099");

        User created = new User();
        created.setId(newUserId);
        created.setUsername("bob");
        created.setEmail("bob@hotel.local");
        created.setEnabled(true);
        when(userRepository.save(any(User.class))).thenReturn(created);

        userService.createUser(
            new CreateUserRequest("bob", "bob@hotel.local", "secret"),
            adminId,
            "192.168.1.5");

        ArgumentCaptor<AuditPayload> captor = ArgumentCaptor.forClass(AuditPayload.class);
        verify(auditService).record(captor.capture());
        AuditPayload p = captor.getValue();
        assertThat(p.op()).isEqualTo("USER_CREATE");
        assertThat(p.userId()).isEqualTo(adminId);
        assertThat(p.entityType()).isEqualTo("User");
        assertThat(p.entityId()).isEqualTo(newUserId);
        assertThat(p.sourceIp()).isEqualTo("192.168.1.5");
        assertThat(p.severity()).isEqualTo("INFO");
    }
}
