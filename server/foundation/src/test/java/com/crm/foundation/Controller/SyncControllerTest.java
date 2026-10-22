package com.crm.foundation.Controller;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.Config.WebSecurityConfig;
import com.crm.foundation.Domain.ChangeEvent;
import com.crm.foundation.Security.CorePermissions;
import com.crm.foundation.Service.ChangeEventService;
import com.crm.foundation.Service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SyncController.class)
@Import(WebSecurityConfig.class)
class SyncControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean ChangeEventService changeEventService;
    @MockitoBean UserService userService;       // needed by JwtAuthenticationFilter
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    private static ChangeEvent event(String entityType, UUID entityId, String op, Instant occurredAt) {
        ChangeEvent e = new ChangeEvent();
        e.setId(UUID.randomUUID());
        e.setPluginId("core");
        e.setEntityType(entityType);
        e.setEntityId(entityId);
        e.setVersion(1L);
        e.setOp(op);
        e.setOccurredAt(occurredAt);
        return e;
    }

    @Test
    @WithMockUser(authorities = CorePermissions.SYNC_READ)
    void changes_withSyncReadAuthority_returnsOrderedEvents() throws Exception {
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(changeEventService.changesSince(any(Instant.class))).thenReturn(List.of(
            event("User", id1, "CREATE", Instant.parse("2026-06-09T10:00:00Z")),
            event("User", id2, "UPDATE", Instant.parse("2026-06-09T11:00:00Z"))));

        mvc.perform(get("/api/v1/sync/changes").param("since", "2026-06-09T09:00:00Z"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("SYNC_CHANGES_OK"))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].entityId").value(id1.toString()))
            .andExpect(jsonPath("$.data[0].op").value("CREATE"))
            .andExpect(jsonPath("$.data[0].pluginId").value("core"))
            .andExpect(jsonPath("$.data[0].entityType").value("User"))
            .andExpect(jsonPath("$.data[0].version").value(1))
            .andExpect(jsonPath("$.data[0].occurredAt").value("2026-06-09T10:00:00Z"))
            .andExpect(jsonPath("$.data[1].entityId").value(id2.toString()))
            .andExpect(jsonPath("$.data[1].op").value("UPDATE"));
    }

    @Test
    @WithMockUser(authorities = CorePermissions.SYNC_READ)
    void changes_missingSinceParam_returns400() throws Exception {
        mvc.perform(get("/api/v1/sync/changes"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void changes_withoutSyncReadAuthority_returns403() throws Exception {
        mvc.perform(get("/api/v1/sync/changes").param("since", "2026-06-09T09:00:00Z"))
            .andExpect(status().isForbidden());
    }

    @Test
    void changes_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/v1/sync/changes").param("since", "2026-06-09T09:00:00Z"))
            .andExpect(status().isUnauthorized());
    }
}
