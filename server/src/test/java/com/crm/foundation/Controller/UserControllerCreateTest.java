package com.crm.foundation.Controller;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.Config.WebSecurityConfig;
import com.crm.foundation.DTO.CreateUserRequest;
import com.crm.foundation.DTO.UserResponse;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Security.CorePermissions;
import com.crm.foundation.Service.RoleService;
import com.crm.foundation.Service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import(WebSecurityConfig.class)
class UserControllerCreateTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    com.crm.foundation.Service.AuditService auditService;

    @MockitoBean
    UserService userService;

    @MockitoBean
    RoleService roleService;

    @MockitoBean
    JwtTokenProvider jwtTokenProvider;

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001", authorities = CorePermissions.USERS_WRITE)
    void createUser_withUsersWriteAuthority_returns201() throws Exception {
        User created = new User();
        created.setId(UUID.fromString("00000000-0000-0000-0000-000000000077"));
        created.setUsername("charlie");
        created.setEmail("charlie@hotel.local");
        created.setEnabled(true);

        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(created);

        mvc.perform(post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new CreateUserRequest("charlie", "charlie@hotel.local", "pass123"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("USER_CREATE_OK"))
            .andExpect(jsonPath("$.data.username").value("charlie"));
    }

    @Test
    @WithMockUser
    void createUser_withoutUsersWriteAuthority_returns403() throws Exception {
        mvc.perform(post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new CreateUserRequest("eve", "eve@hotel.local", "pass123"))))
            .andExpect(status().isForbidden());
    }
}
