package com.crm.foundation.Controller;

import com.crm.foundation.DTO.AuthResponse;
import com.crm.foundation.DTO.CommonResponse;
import com.crm.foundation.DTO.IssuedAccessToken;
import com.crm.foundation.DTO.LoginRequest;
import com.crm.foundation.DTO.LoginResult;
import com.crm.foundation.DTO.LogoutResponse;
import com.crm.foundation.DTO.LogoutStatus;
import com.crm.foundation.DTO.RefreshRequest;
import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.RoleService;
import com.crm.foundation.Service.TokenService;
import com.crm.foundation.Service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private TokenService tokenService;

    @Mock
    private UserService userService;

    @Mock
    private RoleService roleService;

    @InjectMocks
    private AuthController authController;

    @Test
    void login_returnsAccessAndRefreshTokensWithBearerType() {
        LoginRequest request = new LoginRequest("alice", "secret");

        User user = new User();
        user.setId(UUID.fromString("00000000-0000-0000-0000-000000000011"));
        user.setUsername("alice");
        user.setEmail("alice@example.com");

        Instant accessExpiresAt = Instant.parse("2030-01-01T00:15:00Z");
        IssuedAccessToken accessToken = new IssuedAccessToken("access.jwt.token", accessExpiresAt);

        RefreshToken refreshToken =
                RefreshToken.issueFor(
                        user,
                        Instant.parse("2030-01-08T00:00:00Z"));

        when(userService.attemptLogin(request)).thenReturn(new LoginResult.Success(user));
        when(roleService.permissionKeysForUser(user.getId())).thenReturn(java.util.Set.of());
        when(tokenService.createAccessToken(eq(user), any())).thenReturn(accessToken);
        when(tokenService.createToken(user)).thenReturn(refreshToken);

        ResponseEntity<CommonResponse<AuthResponse>> response = authController.login(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().code()).isEqualTo("AUTH_LOGIN_OK");
        assertThat(response.getBody().message()).isEqualTo("Login successful");
        assertThat(response.getBody().data()).isNotNull();
        assertThat(response.getBody().data().accessToken()).isEqualTo("access.jwt.token");
        assertThat(response.getBody().data().accessTokenExpiresAt()).isEqualTo(accessExpiresAt);
        assertThat(response.getBody().data().refreshToken()).isEqualTo(refreshToken.getJti().toString());
        assertThat(response.getBody().data().refreshTokenExpiresAt()).isEqualTo(refreshToken.getExpiresAt());
        assertThat(response.getBody().data().tokenType()).isEqualTo("Bearer");
        verify(tokenService).createAccessToken(eq(user), any());
        verify(tokenService).createToken(user);
    }

    @Test
    void refresh_returnsNewAccessAndRefreshTokensWhenRefreshTokenIsValid() {
        UUID refreshJti = UUID.fromString("00000000-0000-0000-0000-000000000022");
        RefreshRequest request = new RefreshRequest(refreshJti);

        User user = new User();
        user.setId(UUID.fromString("00000000-0000-0000-0000-000000000033"));
        user.setUsername("alice");
        user.setEmail("alice@example.com");

        Instant accessExpiresAt = Instant.parse("2030-01-01T00:15:00Z");
        IssuedAccessToken accessToken = new IssuedAccessToken("rotated.access.jwt", accessExpiresAt);

        RefreshToken refreshToken =
            RefreshToken.issueFor(
                user,
                Instant.parse("2030-01-08T00:00:00Z"));

        when(tokenService.refreshToken(refreshJti)).thenReturn(refreshToken);
        when(roleService.permissionKeysForUser(user.getId())).thenReturn(java.util.Set.of());
        when(tokenService.createAccessToken(eq(user), any())).thenReturn(accessToken);

        ResponseEntity<CommonResponse<AuthResponse>> response = authController.refresh(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().code()).isEqualTo("AUTH_REFRESH_OK");
        assertThat(response.getBody().message()).isEqualTo("Refresh successful");
        assertThat(response.getBody().data()).isNotNull();
        assertThat(response.getBody().data().accessToken()).isEqualTo("rotated.access.jwt");
        assertThat(response.getBody().data().accessTokenExpiresAt()).isEqualTo(accessExpiresAt);
        assertThat(response.getBody().data().refreshToken()).isEqualTo(refreshToken.getJti().toString());
        assertThat(response.getBody().data().refreshTokenExpiresAt()).isEqualTo(refreshToken.getExpiresAt());
        assertThat(response.getBody().data().tokenType()).isEqualTo("Bearer");
        verify(tokenService).refreshToken(refreshJti);
        verify(tokenService).createAccessToken(eq(user), any());
    }

    @Test
    void refresh_returnsUnauthorizedWhenRefreshTokenIsInvalid() {
        UUID refreshJti = UUID.fromString("00000000-0000-0000-0000-000000000044");
        RefreshRequest request = new RefreshRequest(refreshJti);

        when(tokenService.refreshToken(refreshJti)).thenReturn(null);

        ResponseEntity<CommonResponse<AuthResponse>> response = authController.refresh(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().code()).isEqualTo("AUTH_REFRESH_INVALID");
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().message()).isEqualTo("Refresh token is invalid or expired");
        verify(tokenService).refreshToken(refreshJti);
    }

    @Test
    void logout_returnsOkWhenTokenIsRevokedNow() {
        UUID refreshJti = UUID.fromString("00000000-0000-0000-0000-000000000045");
        RefreshRequest request = new RefreshRequest(refreshJti);

        when(tokenService.revokeToken(refreshJti)).thenReturn(LogoutStatus.REVOKED);

        ResponseEntity<CommonResponse<LogoutResponse>> response = authController.logout(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().code()).isEqualTo("AUTH_LOGOUT_REVOKED");
        assertThat(response.getBody().data()).isEqualTo(new LogoutResponse(LogoutStatus.REVOKED, "Token revoked"));
        assertThat(response.getBody().message()).isEqualTo("Token revoked");
        verify(tokenService).revokeToken(refreshJti);
    }

    @Test
    void logout_returnsUnauthorizedWhenTokenWasAlreadyUsed() {
        UUID refreshJti = UUID.fromString("00000000-0000-0000-0000-000000000046");
        RefreshRequest request = new RefreshRequest(refreshJti);

        when(tokenService.revokeToken(refreshJti)).thenReturn(LogoutStatus.ALREADY_USED);

        ResponseEntity<CommonResponse<LogoutResponse>> response = authController.logout(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().code()).isEqualTo("AUTH_LOGOUT_ALREADY_USED");
        assertThat(response.getBody().data()).isEqualTo(
            new LogoutResponse(LogoutStatus.ALREADY_USED, "Refresh token already used"));
        assertThat(response.getBody().message()).isEqualTo("Refresh token already used");
        verify(tokenService).revokeToken(refreshJti);
    }

    @Test
    void logout_returnsUnauthorizedWhenTokenWasAlreadyRevoked() {
        UUID refreshJti = UUID.fromString("00000000-0000-0000-0000-000000000047");
        RefreshRequest request = new RefreshRequest(refreshJti);

        when(tokenService.revokeToken(refreshJti)).thenReturn(LogoutStatus.ALREADY_REVOKED);

        ResponseEntity<CommonResponse<LogoutResponse>> response = authController.logout(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().code()).isEqualTo("AUTH_LOGOUT_ALREADY_REVOKED");
        assertThat(response.getBody().data()).isEqualTo(
            new LogoutResponse(LogoutStatus.ALREADY_REVOKED, "Refresh token already revoked"));
        assertThat(response.getBody().message()).isEqualTo("Refresh token already revoked");
        verify(tokenService).revokeToken(refreshJti);
    }

    @Test
    void logout_returnsUnauthorizedWhenTokenIsExpired() {
        UUID refreshJti = UUID.fromString("00000000-0000-0000-0000-000000000048");
        RefreshRequest request = new RefreshRequest(refreshJti);

        when(tokenService.revokeToken(refreshJti)).thenReturn(LogoutStatus.EXPIRED);

        ResponseEntity<CommonResponse<LogoutResponse>> response = authController.logout(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().code()).isEqualTo("AUTH_LOGOUT_EXPIRED");
        assertThat(response.getBody().data()).isEqualTo(
            new LogoutResponse(LogoutStatus.EXPIRED, "Refresh token expired"));
        assertThat(response.getBody().message()).isEqualTo("Refresh token expired");
        verify(tokenService).revokeToken(refreshJti);
    }

    @Test
    void logout_returnsUnauthorizedWhenTokenIsMissing() {
        UUID refreshJti = UUID.fromString("00000000-0000-0000-0000-000000000049");
        RefreshRequest request = new RefreshRequest(refreshJti);

        when(tokenService.revokeToken(refreshJti)).thenReturn(LogoutStatus.NOT_FOUND);

        ResponseEntity<CommonResponse<LogoutResponse>> response = authController.logout(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().code()).isEqualTo("AUTH_LOGOUT_NOT_FOUND");
        assertThat(response.getBody().data()).isEqualTo(
            new LogoutResponse(LogoutStatus.NOT_FOUND, "Refresh token not found"));
        assertThat(response.getBody().message()).isEqualTo("Refresh token not found");
        verify(tokenService).revokeToken(refreshJti);
    }
}
