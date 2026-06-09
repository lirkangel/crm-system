package com.crm.foundation.Controller;

import com.crm.foundation.Config.OpenApiConfig;
import com.crm.foundation.DTO.CommonResponse;
import com.crm.foundation.DTO.UserResponse;
import com.crm.foundation.Security.CorePermissions;
import com.crm.foundation.Service.UserService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/users")
@SecurityRequirement(name = OpenApiConfig.JWT_SECURITY_SCHEME)
public class UserController {

    private final UserService userService;

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + CorePermissions.USERS_READ + "')")
    public ResponseEntity<CommonResponse<UserResponse>> getUserById(@PathVariable @NonNull UUID id) {
        return userService.findById(id)
                .map(UserResponse::from)
                .map(response -> ResponseEntity.ok(CommonResponse.success("USER_GET_OK", "User found", response)))
                .orElseGet(() -> ResponseEntity.status(404)
                    .body(CommonResponse.failure("USER_NOT_FOUND", "User not found")));
    }
}
