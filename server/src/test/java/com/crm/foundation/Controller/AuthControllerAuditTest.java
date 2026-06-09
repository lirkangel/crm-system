package com.crm.foundation.Controller;

import com.crm.foundation.Audit.AuditPayload;
import com.crm.foundation.DTO.IssuedAccessToken;
import com.crm.foundation.DTO.LoginRequest;
import com.crm.foundation.DTO.LoginResult;
import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.AuditService;
import com.crm.foundation.Service.RoleService;
import com.crm.foundation.Service.TokenService;
import com.crm.foundation.Service.UserService;
import com.crm.foundation.Service.Impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that login audit events are recorded by AuthServiceImpl
 * (audit responsibility moved from controller to service layer).
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerAuditTest {

    @Mock UserService userService;
    @Mock TokenService tokenService;
    @Mock RoleService roleService;
    @Mock AuditService auditService;

    @InjectMocks AuthServiceImpl authService;

    @Test
    void login_success_recordsAuditEventWithUserAndIp() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        User user = new User();
        user.setId(userId);
        user.setUsername("alice");
        user.setEmail("alice@example.com");

        when(userService.attemptLogin(any())).thenReturn(new LoginResult.Success(user));
        when(roleService.permissionKeysForUser(userId)).thenReturn(Set.of());
        when(tokenService.createAccessToken(eq(user), any()))
            .thenReturn(new IssuedAccessToken("tok", Instant.now().plusSeconds(900)));
        when(tokenService.createToken(user))
            .thenReturn(RefreshToken.issueFor(user, Instant.now().plusSeconds(86400)));

        authService.login(new LoginRequest("alice", "pw"), "10.0.0.1");

        ArgumentCaptor<AuditPayload> captor = ArgumentCaptor.forClass(AuditPayload.class);
        verify(auditService).record(captor.capture());
        AuditPayload p = captor.getValue();
        assertThat(p.op()).isEqualTo("AUTH_LOGIN_SUCCESS");
        assertThat(p.userId()).isEqualTo(userId);
        assertThat(p.sourceIp()).isEqualTo("10.0.0.1");
        assertThat(p.entityType()).isEqualTo("User");
        assertThat(p.entityId()).isEqualTo(userId);
        assertThat(p.severity()).isEqualTo("INFO");
    }

    @Test
    void login_failure_recordsAuditEventWithWarnSeverityAndNullUser() {
        when(userService.attemptLogin(any()))
            .thenReturn(new LoginResult.Failure(LoginResult.FailureReason.BAD_CREDENTIALS));

        authService.login(new LoginRequest("nobody", "bad"), "10.0.0.2");

        ArgumentCaptor<AuditPayload> captor = ArgumentCaptor.forClass(AuditPayload.class);
        verify(auditService).record(captor.capture());
        AuditPayload p = captor.getValue();
        assertThat(p.op()).isEqualTo("AUTH_LOGIN_FAILURE");
        assertThat(p.userId()).isNull();
        assertThat(p.sourceIp()).isEqualTo("10.0.0.2");
        assertThat(p.severity()).isEqualTo("WARN");
    }
}
