package com.crm.foundation.Audit;

import com.crm.foundation.Domain.AuditEvent;
import com.crm.foundation.Repository.AuditEventRepository;
import com.crm.foundation.config.TestContainersConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code AuditEvent} persists and loads with its hash-chain bytes intact (T2.1),
 * and that ordering for chain-link lookups is well-defined.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest
@Import(TestContainersConfig.class)
class AuditEventRepositoryIT {

    @Autowired
    private AuditEventRepository repository;

    private static AuditEvent eventAt(Instant occurredAt, String op, byte[] prevHash, byte[] hash) {
        AuditEvent event = new AuditEvent();
        event.setOccurredAt(occurredAt);
        event.setOp(op);
        event.setSeverity("INFO");
        event.setPrevHash(prevHash);
        event.setHash(hash);
        return event;
    }

    @Test
    void should_persist_and_load_audit_event_with_hash_bytes() {
        byte[] prevHash = new byte[32];
        byte[] hash = {1, 2, 3, 4};
        AuditEvent event = eventAt(Instant.now(), "LOGIN", prevHash, hash);

        AuditEvent saved = repository.save(event);
        AuditEvent loaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getHash()).containsExactly(1, 2, 3, 4);
        assertThat(loaded.getPrevHash()).containsExactly(new byte[32]);
        assertThat(loaded.getOp()).isEqualTo("LOGIN");
    }

    @Test
    void should_order_events_by_occurred_at_then_id_for_chain_lookups() {
        Instant base = Instant.parse("2026-06-01T00:00:00Z");
        AuditEvent first = repository.save(eventAt(base, "OP1", new byte[32], new byte[]{1}));
        AuditEvent second = repository.save(eventAt(base.plusSeconds(1), "OP2", new byte[]{1}, new byte[]{2}));
        AuditEvent third = repository.save(eventAt(base.plusSeconds(2), "OP3", new byte[]{2}, new byte[]{3}));

        AuditEvent latest = repository.findTopByOrderByOccurredAtDescIdDesc().orElseThrow();
        assertThat(latest.getId()).isEqualTo(third.getId());

        List<AuditEvent> ordered = repository.findAllByOrderByOccurredAtAscIdAsc();
        assertThat(ordered).extracting(AuditEvent::getId)
            .containsExactly(first.getId(), second.getId(), third.getId());
    }
}
