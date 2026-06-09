package com.crm.foundation.Service.Impl;

import com.crm.foundation.Audit.AuditPayload;
import com.crm.foundation.DTO.*;
import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Exception.AuthException;
import com.crm.foundation.Service.AuditService;
import com.crm.foundation.Service.RoleService;
import com.crm.foundation.Service.TokenService;
import com.crm.foundation.Service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserService userService;
    @Mock TokenService tokenService;
    @Mock RoleService roleService;
    @Mock AuditService auditService;

    @InjectMocks AuthServiceImpl authService;

    private User alice() {
        User u = new User();
        u.setId(UUID.fromString("00000000-0000-0000-0000-000000000011"));
        u.setUsername("alice");
        u.setEmail("alice@example.com");
        return u;
    }

    // ── login success ─────────────────────────────────────────────────────

    @Test
    void login_success_returnsAuthResponse() {
        User u = alice();
        IssuedAccessToken access = new IssuedAccessToken("tok", Instant.parse("2030-01-01T00:15:00Z"));
        RefreshToken refresh = RefreshToken.issueFor(u, Instant.parse("2030-01-08T00:00:00Z"));

        when(userService.attemptLogin(any())).thenReturn(new LoginResult.Success(u));
        when(roleService.permissionKeysForUser(u.getId())).thenReturn(Set.of());
        when(tokenService.createAccessToken(eq(u), any())).thenReturn(access);
        when(tokenService.createToken(u)).thenReturn(refresh);

        AuthResponse response = authService.login(new LoginRequest("alice", "secret"), "1.2.3.4");

        assertThat(response.accessToken()).isEqualTo("tok");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        verify(tokenService).createAccessToken(eq(u), any());
        verify(tokenService).createToken(u);
    }

    @Test
    void login_success_recordsInfoAuditEvent() {
        User u = alice();
        when(userService.attemptLogin(any())).thenReturn(new LoginResult.Success(u));
        when(roleService.permissionKeysForUser(u.getId())).thenReturn(Set.of());
        when(tokenService.createAccessToken(eq(u), any()))
            .thenReturn(new IssuedAccessToken("t", Instant.now().plusSeconds(900)));
        when(tokenService.createToken(u))
            .thenReturn(RefreshToken.issueFor(u, Instant.now().plusSeconds(86400)));

        authService.login(new LoginRequest("alice", "pw"), "10.0.0.1");

        ArgumentCaptor<AuditPayload> cap = ArgumentCaptor.forClass(AuditPayload.class);
        verify(auditService).record(cap.capture());
        AuditPayload p = cap.getValue();
        assertThat(p.op()).isEqualTo("AUTH_LOGIN_SUCCESS");
        assertThat(p.userId()).isEqualTo(u.getId());
        assertThat(p.sourceIp()).isEqualTo("10.0.0.1");
        assertThat(p.severity()).isEqualTo("INFO");
    }

    // ── login failure ─────────────────────────────────────────────────────

    @Test
    void login_badCredentials_throwsAuthExceptionWithCorrectCode() {
        when(userService.attemptLogin(any()))
            .thenReturn(new LoginResult.Failure(LoginResult.FailureReason.BAD_CREDENTIALS));

        assertThatThrownBy(() -> authService.login(new LoginRequest("x", "y"), "1.2.3.4"))
            .isInstanceOf(AuthException.class)
            .satisfies(e -> {
                assertThat(((AuthException) e).getCode()).isEqualTo("AUTH_LOGIN_INVALID_CREDENTIALS");
                assertThat(e.getMessage()).isEqualTo("Invalid username or password");
            });
    }

    @Test
    void login_accountLocked_throwsAuthExceptionWithCorrectCode() {
        when(userService.attemptLogin(any()))
            .thenReturn(new LoginResult.Failure(LoginResult.FailureReason.ACCOUNT_LOCKED));

        assertThatThrownBy(() -> authService.login(new LoginRequest("x", "y"), "1.2.3.4"))
            .isInstanceOf(AuthException.class)
            .extracting(e -> ((AuthException) e).getCode())
            .isEqualTo("AUTH_LOGIN_ACCOUNT_LOCKED");
    }

    @Test
    void login_accountDisabled_throwsAuthExceptionWithCorrectCode() {
        when(userService.attemptLogin(any()))
            .thenReturn(new LoginResult.Failure(LoginResult.FailureReason.ACCOUNT_DISABLED));

        assertThatThrownBy(() -> authService.login(new LoginRequest("x", "y"), "1.2.3.4"))
            .isInstanceOf(AuthException.class)
            .extracting(e -> ((AuthException) e).getCode())
            .isEqualTo("AUTH_LOGIN_ACCOUNT_DISABLED");
    }

    @Test
    void login_failure_recordsWarnAuditEvent() {
        when(userService.attemptLogin(any()))
            .thenReturn(new LoginResult.Failure(LoginResult.FailureReason.BAD_CREDENTIALS));

        assertThatThrownBy(() -> authService.login(new LoginRequest("bad", "bad"), "10.0.0.2"))
            .isInstanceOf(AuthException.class);

        ArgumentCaptor<AuditPayload> cap = ArgumentCaptor.forClass(AuditPayload.class);
        verify(auditService).record(cap.capture());
        AuditPayload p = cap.getValue();
        assertThat(p.op()).isEqualTo("AUTH_LOGIN_FAILURE");
        assertThat(p.userId()).isNull();
        assertThat(p.sourceIp()).isEqualTo("10.0.0.2");
        assertThat(p.severity()).isEqualTo("WARN");
    }

    // ── refresh ───────────────────────────────────────────────────────────

    @Test
    void refresh_validToken_returnsAuthResponse() {
        User u = alice();
        UUID jti = UUID.randomUUID();
        RefreshToken refresh = RefreshToken.issueFor(u, Instant.parse("2030-01-08T00:00:00Z"));
        IssuedAccessToken access = new IssuedAccessToken("new-tok", Instant.parse("2030-01-01T00:15:00Z"));

        when(tokenService.refreshToken(jti)).thenReturn(refresh);
        when(roleService.permissionKeysForUser(u.getId())).thenReturn(Set.of());
        when(tokenService.createAccessToken(eq(u), any())).thenReturn(access);

        AuthResponse response = authService.refresh(jti);

        assertThat(response.accessToken()).isEqualTo("new-tok");
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void refresh_invalidToken_throwsAuthException() {
        UUID jti = UUID.randomUUID();
        when(tokenService.refreshToken(jti)).thenReturn(null);

        assertThatThrownBy(() -> authService.refresh(jti))
            .isInstanceOf(AuthException.class)
            .extracting(e -> ((AuthException) e).getCode())
            .isEqualTo("AUTH_REFRESH_INVALID");
    }

    // ── me ────────────────────────────────────────────────────────────────

    @Test
    void me_aggregatesUserAndPermissions() {
        User u = alice();
        when(userService.findById(u.getId())).thenReturn(Optional.of(u));
        when(roleService.permissionKeysForUser(u.getId())).thenReturn(Set.of("core.users.read"));

        MeResponse me = authService.me(u.getId());

        assertThat(me.id()).isEqualTo(u.getId());
        assertThat(me.username()).isEqualTo("alice");
        assertThat(me.permissions()).containsExactly("core.users.read");
    }

    @Test
    void me_userNotFound_throwsAuthException() {
        UUID id = UUID.randomUUID();
        when(userService.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.me(id))
            .isInstanceOf(AuthException.class)
            .extracting(e -> ((AuthException) e).getCode())
            .isEqualTo("AUTH_USER_NOT_FOUND");
    }

    // ── revoke ────────────────────────────────────────────────────────────

    @Test
    void revoke_delegatesToTokenService() {
        UUID jti = UUID.randomUUID();
        when(tokenService.revokeToken(jti)).thenReturn(LogoutStatus.REVOKED);

        assertThat(authService.revoke(jti)).isEqualTo(LogoutStatus.REVOKED);
        verify(tokenService).revokeToken(jti);
    }
}
