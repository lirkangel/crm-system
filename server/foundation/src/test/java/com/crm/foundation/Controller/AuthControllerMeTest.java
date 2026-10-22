package com.crm.foundation.Controller;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.Config.WebSecurityConfig;
import com.crm.foundation.DTO.MeResponse;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.AuthService;
import com.crm.foundation.Service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(WebSecurityConfig.class)
class AuthControllerMeTest {

    @Autowired MockMvc mvc;

    @MockitoBean AuthService authService;
    @MockitoBean UserService userService;       // needed by JwtAuthenticationFilter
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    static final UUID ALICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void me_whenAuthenticated_returnsUserDetails() throws Exception {
        // Same principal shape JwtAuthenticationFilter installs: the User entity
        User alice = new User();
        alice.setId(ALICE_ID);
        alice.setUsername("alice");
        MeResponse me = new MeResponse(ALICE_ID, "alice", "alice@example.com",
            Set.of("core.users.read", "core.roles.read"));
        when(authService.me(ALICE_ID)).thenReturn(me);

        mvc.perform(get("/api/v1/auth/me")
                .with(authentication(new UsernamePasswordAuthenticationToken(alice, null, List.of()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("AUTH_ME_OK"))
            .andExpect(jsonPath("$.data.id").value(ALICE_ID.toString()))
            .andExpect(jsonPath("$.data.username").value("alice"))
            .andExpect(jsonPath("$.data.email").value("alice@example.com"));
    }

    @Test
    void me_whenUnauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized());
    }
}
