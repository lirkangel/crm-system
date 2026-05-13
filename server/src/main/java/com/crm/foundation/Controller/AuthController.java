package com.crm.foundation.Controller;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.foundation.DTO.LoginRequest;
import com.crm.foundation.DTO.UserResponse;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.TokenService;
import com.crm.foundation.Service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TokenService tokenService;
    private final UserService userService;

    @PostMapping("/login")
    public ResponseEntity<CommonResponse<String>> login(@Valid @RequestBody LoginRequest loginRequest) {
        if (!Boolean.TRUE.equals(userService.checkUserByUsernamePassword(loginRequest))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(CommonResponse.from(null, "Invalid username or password"));
        }
        String username = Objects.requireNonNull(loginRequest.getUsername(), "username");
        Optional<User> user = userService.findByUsername(username);
        return user
            .map(u -> ResponseEntity.ok(
                CommonResponse.from(tokenService.createToken(u).getJti().toString())))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(CommonResponse.from(null, "Invalid username or password")));
    }

    @PostMapping("/register")
    public ResponseEntity<CommonResponse<UserResponse>> register(@Valid @RequestBody LoginRequest loginRequest) {
        String username = Objects.requireNonNull(loginRequest.getUsername(), "username");
        Optional<User> existing = userService.findByUsername(username);
        if (existing.isPresent()) {
            return ResponseEntity.badRequest()
                .body(CommonResponse.from(UserResponse.from(existing.get()), "Username already taken"));
        }
        User saved = userService.register(loginRequest);
        return ResponseEntity.ok(CommonResponse.from(UserResponse.from(saved)));
    }

    @DeleteMapping("/revoke/{jti}")
    public ResponseEntity<CommonResponse<Boolean>> revoke(@PathVariable UUID jti) {
        boolean revoked = Boolean.TRUE.equals(tokenService.revokeToken(jti));
        String message = revoked ? "Token revoked" : "Token not found or already inactive";
        return ResponseEntity.ok(CommonResponse.from(revoked, message));
    }
}
