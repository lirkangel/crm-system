package com.crm.foundation.Plugin;

import com.crm.foundation.Audit.AuditPayload;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.AuditService;
import com.crm.foundation.Service.ChangeEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PluginRecordersTest {

    private static final String PLUGIN_ID = "com.crm.hotel";

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static User userWithId(UUID id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, AuthorityUtils.NO_AUTHORITIES));
    }

    @Test
    void change_recorder_binds_plugin_id_and_op_name() {
        ChangeEventService changes = mock(ChangeEventService.class);
        UUID id = UUID.randomUUID();

        new PluginChangeRecorder(PLUGIN_ID, changes)
            .record("Room", id, 3, ChangeOp.UPDATE);

        verify(changes).record(eq(PLUGIN_ID), eq("Room"), eq(id), eq(3L), eq("UPDATE"), any());
    }

    @Test
    void audit_recorder_fills_plugin_and_user() {
        AuditService audit = mock(AuditService.class);
        UUID userId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        authenticateAs(userWithId(userId));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        new PluginAuditRecorder(PLUGIN_ID, audit, new SecurityContextCurrentUser())
            .record("Reservation", entityId, "RESERVATION_CREATE", null, "{\"a\":1}");

        ArgumentCaptor<AuditPayload> captor = ArgumentCaptor.forClass(AuditPayload.class);
        verify(audit).record(captor.capture());
        AuditPayload payload = captor.getValue();
        assertThat(payload.pluginId()).isEqualTo(PLUGIN_ID);
        assertThat(payload.userId()).isEqualTo(userId);
        assertThat(payload.sourceIp()).isEqualTo("10.1.2.3");
        assertThat(payload.entityType()).isEqualTo("Reservation");
        assertThat(payload.entityId()).isEqualTo(entityId);
        assertThat(payload.op()).isEqualTo("RESERVATION_CREATE");
        assertThat(payload.beforeJson()).isNull();
        assertThat(payload.afterJson()).isEqualTo("{\"a\":1}");
        assertThat(payload.severity()).isEqualTo("INFO");
        assertThat(payload.occurredAt()).isNotNull();
    }

    @Test
    void audit_recorder_has_null_ip_and_user_outside_a_request() {
        AuditService audit = mock(AuditService.class);

        new PluginAuditRecorder(PLUGIN_ID, audit, new SecurityContextCurrentUser())
            .record("Room", UUID.randomUUID(), "ROOM_CREATE", null, null);

        ArgumentCaptor<AuditPayload> captor = ArgumentCaptor.forClass(AuditPayload.class);
        verify(audit).record(captor.capture());
        assertThat(captor.getValue().sourceIp()).isNull();
        assertThat(captor.getValue().userId()).isNull();
    }

    @Test
    void current_user_reads_user_principal() {
        UUID userId = UUID.randomUUID();
        authenticateAs(userWithId(userId));

        assertThat(new SecurityContextCurrentUser().id()).contains(userId);
    }

    @Test
    void current_user_empty_when_anonymous() {
        assertThat(new SecurityContextCurrentUser().id()).isEmpty();

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
            "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(new SecurityContextCurrentUser().id()).isEmpty();
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
