package com.crm.foundation.Controller;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.Config.WebSecurityConfig;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Security.CorePermissions;
import com.crm.foundation.Service.RoleService;
import com.crm.foundation.Service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that @PreAuthorize gates on UserController enforce core.users.read (T006).
 */
@WebMvcTest(UserController.class)
@Import(WebSecurityConfig.class)
class UserControllerSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    com.crm.foundation.Service.AuditService auditService;

    @MockitoBean
    UserService userService;

    @MockitoBean
    RoleService roleService;

    @MockitoBean
    JwtTokenProvider jwtTokenProvider;

    @Test
    @WithMockUser(authorities = CorePermissions.USERS_READ)
    void getUserById_withUsersReadAuthority_returns200() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000099");
        User user = new User();
        user.setId(id);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setEnabled(true);
        when(userService.findById(id)).thenReturn(Optional.of(user));

        mvc.perform(get("/api/v1/users/{id}", id))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void getUserById_withoutUsersReadAuthority_returns403() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000099");

        mvc.perform(get("/api/v1/users/{id}", id))
                .andExpect(status().isForbidden());
    }
}
