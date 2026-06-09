package com.crm.foundation.Controller;

import com.crm.foundation.Audit.AuditPayload;
import com.crm.foundation.DTO.CreateUserRequest;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.AuditService;
import com.crm.foundation.Service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserControllerAuditTest {

    @Mock UserService userService;
    @Mock AuditService auditService;
    @Mock HttpServletRequest httpRequest;
    @Mock Authentication authentication;

    @InjectMocks UserController userController;

    @Test
    void createUser_recordsAuditEventWithActorAndCreatedUserId() {
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID newUserId = UUID.fromString("00000000-0000-0000-0000-000000000099");

        User created = new User();
        created.setId(newUserId);
        created.setUsername("bob");
        created.setEmail("bob@hotel.local");
        created.setEnabled(true);

        when(authentication.getName()).thenReturn(adminId.toString());
        when(httpRequest.getRemoteAddr()).thenReturn("192.168.1.5");
        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(created);

        userController.createUser(
            new CreateUserRequest("bob", "bob@hotel.local", "secret"),
            authentication,
            httpRequest);

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
