package com.crm.foundation.Controller;

import com.crm.foundation.DTO.*;
import com.crm.foundation.Service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<CommonResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest httpRequest) {
        return switch (authService.login(loginRequest, httpRequest.getRemoteAddr())) {
            case LoginOutcome.Ok ok -> ResponseEntity.ok(
                CommonResponse.success("AUTH_LOGIN_OK", "Login successful", ok.response()));
            case LoginOutcome.Fail fail -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(CommonResponse.failure(fail.code(), fail.message()));
        };
    }

    @GetMapping("/me")
    public ResponseEntity<CommonResponse<MeResponse>> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(CommonResponse.failure("AUTH_UNAUTHENTICATED", "Not authenticated"));
        }
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(
            CommonResponse.success("AUTH_ME_OK", "Current user", authService.me(userId)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<CommonResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken())
            .map(r -> ResponseEntity.ok(
                CommonResponse.success("AUTH_REFRESH_OK", "Refresh successful", r)))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(CommonResponse.failure(
                    "AUTH_REFRESH_INVALID", "Refresh token is invalid or expired")));
    }

    @DeleteMapping("/revoke/{jti}")
    public ResponseEntity<CommonResponse<LogoutResponse>> revoke(@PathVariable UUID jti) {
        return toLogoutResponse(authService.revoke(jti));
    }

    @PostMapping("/logout")
    public ResponseEntity<CommonResponse<LogoutResponse>> logout(
            @Valid @RequestBody RefreshRequest request) {
        return toLogoutResponse(authService.revoke(request.refreshToken()));
    }

    private ResponseEntity<CommonResponse<LogoutResponse>> toLogoutResponse(LogoutStatus status) {
        LogoutResponse response = switch (status) {
            case REVOKED         -> new LogoutResponse(status, "Token revoked");
            case NOT_FOUND       -> new LogoutResponse(status, "Refresh token not found");
            case EXPIRED         -> new LogoutResponse(status, "Refresh token expired");
            case ALREADY_USED    -> new LogoutResponse(status, "Refresh token already used");
            case ALREADY_REVOKED -> new LogoutResponse(status, "Refresh token already revoked");
        };
        HttpStatus httpStatus = status == LogoutStatus.REVOKED ? HttpStatus.OK : HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(httpStatus).body(
            status == LogoutStatus.REVOKED
                ? CommonResponse.success("AUTH_LOGOUT_REVOKED", response.message(), response)
                : CommonResponse.failure("AUTH_LOGOUT_" + status.name(), response.message(), response));
    }
}
