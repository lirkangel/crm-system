package com.crm.foundation.Service.Impl;

import com.crm.foundation.Domain.PluginRegistry;
import com.crm.foundation.Exception.BadRequestException;
import com.crm.foundation.Exception.NotFoundException;
import com.crm.foundation.Plugin.PluginManifest;
import com.crm.foundation.Plugin.PluginState;
import com.crm.foundation.Repository.PluginRegistryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PluginRegistryServiceTest {

    @Mock PluginRegistryRepository repository;

    PluginRegistryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PluginRegistryServiceImpl(repository, new ObjectMapper());
    }

    private static PluginManifest demoManifest() {
        return new PluginManifest(
            "com.crm.demo",
            "0.1.0",
            Map.of("en", "Demo", "vi", "Demo"),
            Map.of("foundation", ">=0.1.0,<2.0.0"),
            List.of("demo.widget.read", "demo.widget.write"),
            List.of("persistence", "rest"),
            "demo",
            "com.crm.demo.DemoPlugin");
    }

    private static PluginRegistry entryInState(PluginState state) {
        PluginRegistry e = new PluginRegistry();
        e.setPluginId("com.crm.demo");
        e.setVersion("0.1.0");
        e.setSchemaName("demo");
        e.setState(state.name());
        e.setManifestJson("{}");
        return e;
    }

    // ── register ──────────────────────────────────────────────────────────

    @Test
    void register_newPlugin_persistsRegisteredStateWithManifestJson() {
        when(repository.findByPluginId("com.crm.demo")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PluginRegistry saved = service.register(demoManifest());

        ArgumentCaptor<PluginRegistry> cap = ArgumentCaptor.forClass(PluginRegistry.class);
        verify(repository).save(cap.capture());
        PluginRegistry e = cap.getValue();
        assertThat(e.getPluginId()).isEqualTo("com.crm.demo");
        assertThat(e.getVersion()).isEqualTo("0.1.0");
        assertThat(e.getSchemaName()).isEqualTo("demo");
        assertThat(e.getState()).isEqualTo(PluginState.REGISTERED.name());
        assertThat(e.getManifestJson()).contains("\"id\":\"com.crm.demo\"");
        assertThat(saved).isSameAs(e);
    }

    @Test
    void register_duplicatePluginId_throwsBadRequest() {
        when(repository.findByPluginId("com.crm.demo"))
            .thenReturn(Optional.of(entryInState(PluginState.ACTIVE)));

        assertThatThrownBy(() -> service.register(demoManifest()))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("com.crm.demo");
        verify(repository, never()).save(any());
    }

    // ── activate ──────────────────────────────────────────────────────────

    @Test
    void activate_registeredPlugin_becomesActiveWithLoadTimestamp() {
        PluginRegistry e = entryInState(PluginState.REGISTERED);
        e.setErrorMessage("old failure");
        when(repository.findByPluginId("com.crm.demo")).thenReturn(Optional.of(e));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PluginRegistry result = service.activate("com.crm.demo");

        assertThat(result.getState()).isEqualTo(PluginState.ACTIVE.name());
        assertThat(result.getLastLoadedAt()).isNotNull();
        assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    void activate_disabledPlugin_becomesActive() {
        when(repository.findByPluginId("com.crm.demo"))
            .thenReturn(Optional.of(entryInState(PluginState.DISABLED)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.activate("com.crm.demo").getState())
            .isEqualTo(PluginState.ACTIVE.name());
    }

    @Test
    void activate_uninstallPendingPlugin_throwsBadRequest() {
        when(repository.findByPluginId("com.crm.demo"))
            .thenReturn(Optional.of(entryInState(PluginState.UNINSTALL_PENDING)));

        assertThatThrownBy(() -> service.activate("com.crm.demo"))
            .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void activate_unknownPlugin_throwsNotFound() {
        when(repository.findByPluginId("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activate("ghost"))
            .isInstanceOf(NotFoundException.class);
    }

    // ── disable ───────────────────────────────────────────────────────────

    @Test
    void disable_activePlugin_becomesDisabled() {
        when(repository.findByPluginId("com.crm.demo"))
            .thenReturn(Optional.of(entryInState(PluginState.ACTIVE)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.disable("com.crm.demo").getState())
            .isEqualTo(PluginState.DISABLED.name());
    }

    @Test
    void disable_uninstallPendingPlugin_throwsBadRequest() {
        when(repository.findByPluginId("com.crm.demo"))
            .thenReturn(Optional.of(entryInState(PluginState.UNINSTALL_PENDING)));

        assertThatThrownBy(() -> service.disable("com.crm.demo"))
            .isInstanceOf(BadRequestException.class);
    }

    // ── markLoadFailed ────────────────────────────────────────────────────

    @Test
    void markLoadFailed_recordsReasonAndState() {
        when(repository.findByPluginId("com.crm.demo"))
            .thenReturn(Optional.of(entryInState(PluginState.REGISTERED)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PluginRegistry result = service.markLoadFailed("com.crm.demo", "bad entrypoint");

        assertThat(result.getState()).isEqualTo(PluginState.LOAD_FAILED.name());
        assertThat(result.getErrorMessage()).isEqualTo("bad entrypoint");
    }

    // ── markUninstallPending ──────────────────────────────────────────────

    @Test
    void markUninstallPending_setsState() {
        when(repository.findByPluginId("com.crm.demo"))
            .thenReturn(Optional.of(entryInState(PluginState.DISABLED)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.markUninstallPending("com.crm.demo").getState())
            .isEqualTo(PluginState.UNINSTALL_PENDING.name());
    }

    @Test
    void markUninstallPending_unknownPlugin_throwsNotFound() {
        when(repository.findByPluginId("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markUninstallPending("ghost"))
            .isInstanceOf(NotFoundException.class);
    }
}
