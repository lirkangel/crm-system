package com.crm.foundation.Controller;

import com.crm.foundation.DTO.AuthResponse;
import com.crm.foundation.DTO.CommonResponse;
import com.crm.foundation.DTO.IssuedAccessToken;
import com.crm.foundation.DTO.LoginRequest;
import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.TokenService;
import com.crm.foundation.Service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private TokenService tokenService;

    @Mock
    private UserService userService;

    @InjectMocks
    private AuthController authController;

    @Test
    void login_returnsAccessAndRefreshTokensWithBearerType() {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "username", "alice");
        ReflectionTestUtils.setField(request, "password", "secret");

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

        when(userService.checkUserByUsernamePassword(request)).thenReturn(true);
        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(tokenService.createAccessToken(user)).thenReturn(accessToken);
        when(tokenService.createToken(user)).thenReturn(refreshToken);

        ResponseEntity<CommonResponse<AuthResponse>> response = authController.login(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isNotNull();
        assertThat(response.getBody().data().accessToken()).isEqualTo("access.jwt.token");
        assertThat(response.getBody().data().accessTokenExpiresAt()).isEqualTo(accessExpiresAt);
        assertThat(response.getBody().data().refreshToken()).isEqualTo(refreshToken.getJti().toString());
        assertThat(response.getBody().data().refreshTokenExpiresAt()).isEqualTo(refreshToken.getExpiresAt());
        assertThat(response.getBody().data().tokenType()).isEqualTo("Bearer");
        verify(tokenService).createAccessToken(user);
        verify(tokenService).createToken(user);
    }
}
