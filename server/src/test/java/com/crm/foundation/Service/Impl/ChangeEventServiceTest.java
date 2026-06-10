package com.crm.foundation.Service.Impl;

import com.crm.foundation.Domain.ChangeEvent;
import com.crm.foundation.Repository.ChangeEventRepository;
import com.crm.foundation.Service.ChangeEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChangeEventServiceTest {

    @Mock
    private ChangeEventRepository changeEventRepository;

    @InjectMocks
    private ChangeEventServiceImpl changeEventService;

    @Test
    void changesSince_delegatesToRepositoryOrderedByOccurredAt() {
        Instant since = Instant.parse("2026-06-09T09:00:00Z");
        ChangeEvent e = new ChangeEvent();
        e.setOccurredAt(Instant.parse("2026-06-09T10:00:00Z"));
        org.mockito.Mockito.when(
                changeEventRepository.findByOccurredAtAfterOrderByOccurredAtAsc(since))
            .thenReturn(java.util.List.of(e));

        java.util.List<ChangeEvent> result = changeEventService.changesSince(since);

        assertThat(result).containsExactly(e);
        verify(changeEventRepository).findByOccurredAtAfterOrderByOccurredAtAsc(since);
    }

    @Test
    void record_persistsChangeEventWithAllFields() {
        UUID entityId = UUID.fromString("00000000-0000-0000-0000-000000000042");
        Instant now = Instant.parse("2026-06-09T10:00:00Z");

        changeEventService.record("core", "User", entityId, 1L, "CREATE", now);

        ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
        verify(changeEventRepository).save(captor.capture());
        ChangeEvent saved = captor.getValue();
        assertThat(saved.getPluginId()).isEqualTo("core");
        assertThat(saved.getEntityType()).isEqualTo("User");
        assertThat(saved.getEntityId()).isEqualTo(entityId);
        assertThat(saved.getVersion()).isEqualTo(1L);
        assertThat(saved.getOp()).isEqualTo("CREATE");
        assertThat(saved.getOccurredAt()).isEqualTo(now);
    }
}
