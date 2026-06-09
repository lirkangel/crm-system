package com.crm.foundation.Controller;

import com.crm.foundation.DTO.*;
import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.TokenService;
import com.crm.foundation.Service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TokenService tokenService;
    private final UserService userService;

    @PostMapping("/login")
    public ResponseEntity<CommonResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest loginRequest) {
        LoginResult result = userService.attemptLogin(loginRequest);
        if (result instanceof LoginResult.Failure failure) {
            String code = switch (failure.reason()) {
                case BAD_CREDENTIALS -> "AUTH_LOGIN_INVALID_CREDENTIALS";
                case ACCOUNT_LOCKED -> "AUTH_LOGIN_ACCOUNT_LOCKED";
                case ACCOUNT_DISABLED -> "AUTH_LOGIN_ACCOUNT_DISABLED";
            };
            String message = switch (failure.reason()) {
                case BAD_CREDENTIALS -> "Invalid username or password";
                case ACCOUNT_LOCKED -> "Account is temporarily locked";
                case ACCOUNT_DISABLED -> "Account is disabled";
            };
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(CommonResponse.failure(code, message));
        }
        User u = ((LoginResult.Success) result).user();
        var access = tokenService.createAccessToken(u);
        var refresh = tokenService.createToken(u);
        return ResponseEntity.ok(
            CommonResponse.success(
                "AUTH_LOGIN_OK",
                "Login successful",
                AuthResponse.from(access.token(), access.expiresAt(), refresh)));
    }

    @PostMapping("/register")
    public ResponseEntity<CommonResponse<UserResponse>> register(@Valid @RequestBody LoginRequest loginRequest) {
        String username = Objects.requireNonNull(loginRequest.getUsername(), "username");
        Optional<User> existing = userService.findByUsername(username);
        if (existing.isPresent()) {
            return ResponseEntity.badRequest()
                .body(CommonResponse.failure(
                    "AUTH_REGISTER_USERNAME_TAKEN",
                    "Username already taken",
                    UserResponse.from(existing.get())));
        }
        User saved = userService.register(loginRequest);
        return ResponseEntity.ok(
            CommonResponse.success(
                "AUTH_REGISTER_OK",
                "User registered",
                UserResponse.from(saved)));
    }

    @DeleteMapping("/revoke/{jti}")
    public ResponseEntity<CommonResponse<LogoutResponse>> revoke(@PathVariable UUID jti) {
        return toLogoutResponse(tokenService.revokeToken(jti));
    }

    @PostMapping("/refresh")
    public ResponseEntity<CommonResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshRequest
                                                                    request) {
        RefreshToken refresh = tokenService.refreshToken(request.refreshToken());
        if (refresh == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(CommonResponse.failure("AUTH_REFRESH_INVALID", "Refresh token is invalid or expired"));
        }

        User user = refresh.getUser();
        var access = tokenService.createAccessToken(user);

        return ResponseEntity.ok(
            CommonResponse.success(
                "AUTH_REFRESH_OK",
                "Refresh successful",
                AuthResponse.from(access.token(), access.expiresAt(), refresh)
            )
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<CommonResponse<LogoutResponse>> logout(@Valid @RequestBody RefreshRequest request) {
        return toLogoutResponse(tokenService.revokeToken(request.refreshToken()));
    }

    private ResponseEntity<CommonResponse<LogoutResponse>> toLogoutResponse(LogoutStatus status) {
        LogoutResponse response = switch (status) {
            case REVOKED -> new LogoutResponse(status, "Token revoked");
            case NOT_FOUND -> new LogoutResponse(status, "Refresh token not found");
            case EXPIRED -> new LogoutResponse(status, "Refresh token expired");
            case ALREADY_USED -> new LogoutResponse(status, "Refresh token already used");
            case ALREADY_REVOKED -> new LogoutResponse(status, "Refresh token already revoked");
        };

        HttpStatus httpStatus = status == LogoutStatus.REVOKED ? HttpStatus.OK : HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(httpStatus).body(
            status == LogoutStatus.REVOKED
                ? CommonResponse.success("AUTH_LOGOUT_REVOKED", response.message(), response)
                : CommonResponse.failure("AUTH_LOGOUT_" + status.name(), response.message(), response)
        );
    }
}
