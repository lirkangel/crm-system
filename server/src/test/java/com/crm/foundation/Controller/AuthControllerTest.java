package com.crm.foundation.Controller;

import com.crm.foundation.DTO.*;
import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock private AuthService authService;
    @Mock private HttpServletRequest httpRequest;

    @InjectMocks private AuthController authController;

    private static AuthResponse stubAuthResponse() {
        User u = new User();
        u.setId(UUID.fromString("00000000-0000-0000-0000-000000000011"));
        u.setUsername("alice");
        RefreshToken rt = RefreshToken.issueFor(u, Instant.parse("2030-01-08T00:00:00Z"));
        return AuthResponse.from("access.jwt.token", Instant.parse("2030-01-01T00:15:00Z"), rt);
    }

    // ── login ─────────────────────────────────────────────────────────────

    @Test
    void login_success_returns200WithAuthResponse() {
        AuthResponse ar = stubAuthResponse();
        when(authService.login(any(), any())).thenReturn(new LoginOutcome.Ok(ar));

        ResponseEntity<CommonResponse<AuthResponse>> res =
            authController.login(new LoginRequest("alice", "secret"), httpRequest);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().success()).isTrue();
        assertThat(res.getBody().code()).isEqualTo("AUTH_LOGIN_OK");
        assertThat(res.getBody().data().accessToken()).isEqualTo("access.jwt.token");
        assertThat(res.getBody().data().tokenType()).isEqualTo("Bearer");
    }

    @Test
    void login_failure_returns401WithFailCode() {
        when(authService.login(any(), any()))
            .thenReturn(new LoginOutcome.Fail("AUTH_LOGIN_INVALID_CREDENTIALS", "Invalid username or password"));

        ResponseEntity<CommonResponse<AuthResponse>> res =
            authController.login(new LoginRequest("x", "y"), httpRequest);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().success()).isFalse();
        assertThat(res.getBody().code()).isEqualTo("AUTH_LOGIN_INVALID_CREDENTIALS");
        assertThat(res.getBody().message()).isEqualTo("Invalid username or password");
    }

    @Test
    void login_passesRemoteAddrToService() {
        when(httpRequest.getRemoteAddr()).thenReturn("9.9.9.9");
        when(authService.login(any(), any()))
            .thenReturn(new LoginOutcome.Fail("AUTH_LOGIN_INVALID_CREDENTIALS", "msg"));

        authController.login(new LoginRequest("x", "y"), httpRequest);

        verify(authService).login(any(LoginRequest.class), org.mockito.ArgumentMatchers.eq("9.9.9.9"));
    }

    // ── refresh ───────────────────────────────────────────────────────────

    @Test
    void refresh_valid_returns200WithAuthResponse() {
        UUID jti = UUID.fromString("00000000-0000-0000-0000-000000000022");
        AuthResponse ar = stubAuthResponse();
        when(authService.refresh(jti)).thenReturn(Optional.of(ar));

        ResponseEntity<CommonResponse<AuthResponse>> res =
            authController.refresh(new RefreshRequest(jti));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().success()).isTrue();
        assertThat(res.getBody().code()).isEqualTo("AUTH_REFRESH_OK");
        assertThat(res.getBody().data().accessToken()).isEqualTo("access.jwt.token");
        verify(authService).refresh(jti);
    }

    @Test
    void refresh_invalid_returns401() {
        UUID jti = UUID.fromString("00000000-0000-0000-0000-000000000033");
        when(authService.refresh(jti)).thenReturn(Optional.empty());

        ResponseEntity<CommonResponse<AuthResponse>> res =
            authController.refresh(new RefreshRequest(jti));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().success()).isFalse();
        assertThat(res.getBody().code()).isEqualTo("AUTH_REFRESH_INVALID");
        assertThat(res.getBody().message()).isEqualTo("Refresh token is invalid or expired");
    }

    // ── logout ────────────────────────────────────────────────────────────

    @Test
    void logout_returnsOkWhenRevoked() {
        UUID jti = UUID.fromString("00000000-0000-0000-0000-000000000045");
        when(authService.revoke(jti)).thenReturn(LogoutStatus.REVOKED);

        ResponseEntity<CommonResponse<LogoutResponse>> res =
            authController.logout(new RefreshRequest(jti));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().success()).isTrue();
        assertThat(res.getBody().code()).isEqualTo("AUTH_LOGOUT_REVOKED");
    }

    @Test
    void logout_returns401WhenAlreadyUsed() {
        UUID jti = UUID.fromString("00000000-0000-0000-0000-000000000046");
        when(authService.revoke(jti)).thenReturn(LogoutStatus.ALREADY_USED);

        ResponseEntity<CommonResponse<LogoutResponse>> res =
            authController.logout(new RefreshRequest(jti));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().code()).isEqualTo("AUTH_LOGOUT_ALREADY_USED");
    }

    @Test
    void logout_returns401WhenAlreadyRevoked() {
        UUID jti = UUID.fromString("00000000-0000-0000-0000-000000000047");
        when(authService.revoke(jti)).thenReturn(LogoutStatus.ALREADY_REVOKED);

        assertThat(authController.logout(new RefreshRequest(jti)).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logout_returns401WhenExpired() {
        UUID jti = UUID.fromString("00000000-0000-0000-0000-000000000048");
        when(authService.revoke(jti)).thenReturn(LogoutStatus.EXPIRED);

        assertThat(authController.logout(new RefreshRequest(jti)).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logout_returns401WhenNotFound() {
        UUID jti = UUID.fromString("00000000-0000-0000-0000-000000000049");
        when(authService.revoke(jti)).thenReturn(LogoutStatus.NOT_FOUND);

        assertThat(authController.logout(new RefreshRequest(jti)).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
