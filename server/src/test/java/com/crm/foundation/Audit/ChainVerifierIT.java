package com.crm.foundation.Audit;

import com.crm.foundation.Domain.AuditEvent;
import com.crm.foundation.Repository.AuditEventRepository;
import com.crm.foundation.Service.AuditService;
import com.crm.foundation.config.TestContainersConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link ChainVerifier} detects tampering (T2.5): recompute each link
 * from the stored payload and prevHash, and report the first index where the
 * recomputed hash diverges from what's stored.
 * <p>
 * {@code @Transactional} rolls each test back — these deliberately corrupt rows
 * via raw JDBC, which would otherwise permanently break the shared chain (the
 * Postgres container is reused across test classes) for every test that runs
 * after it, including re-runs.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest
@Import(TestContainersConfig.class)
@Transactional
class ChainVerifierIT {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private ChainVerifier verifier;

    @Autowired
    private JdbcTemplate jdbc;

    private static AuditPayload payloadFor(String marker, int n) {
        return new AuditPayload(Instant.now(), null, null, "foundation", "TamperProbe", null,
            marker, null, "{\"seq\":" + n + "}", "INFO");
    }

    @Test
    void should_detect_tampering_partway_through_the_chain() {
        String marker = "TAMPER_PROBE_" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            auditService.record(payloadFor(marker, i));
        }

        List<AuditEvent> recorded = auditEventRepository.findAllByOrderByOccurredAtAscIdAsc().stream()
            .filter(e -> marker.equals(e.getOp()))
            .toList();
        assertThat(recorded).hasSize(5);

        UUID tamperedId = recorded.get(2).getId();
        jdbc.update("UPDATE audit_event SET after_json = '{\"x\":1}'::jsonb WHERE id = ?", tamperedId);

        ChainVerifier.Result result = verifier.verify();

        assertThat(result.broken()).isTrue();
        assertThat(result.firstBrokenIndex()).isGreaterThanOrEqualTo(0);

        List<AuditEvent> all = auditEventRepository.findAllByOrderByOccurredAtAscIdAsc();
        assertThat(all.get(result.firstBrokenIndex()).getId()).isEqualTo(tamperedId);
    }

    @Test
    void should_record_chain_break_detected_event_with_critical_severity_when_broken() {
        String marker = "TAMPER_PROBE_" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            auditService.record(payloadFor(marker, i));
        }
        List<AuditEvent> recorded = auditEventRepository.findAllByOrderByOccurredAtAscIdAsc().stream()
            .filter(e -> marker.equals(e.getOp()))
            .toList();
        UUID tamperedId = recorded.get(1).getId();
        jdbc.update("UPDATE audit_event SET after_json = '{\"x\":2}'::jsonb WHERE id = ?", tamperedId);

        verifier.verify();

        AuditEvent breakEvent = auditEventRepository.findTopByOrderByOccurredAtDescIdDesc().orElseThrow();
        assertThat(breakEvent.getOp()).isEqualTo("CHAIN_BREAK_DETECTED");
        assertThat(breakEvent.getSeverity()).isEqualTo("CRITICAL");
    }

    @Test
    void should_report_intact_when_chain_has_not_been_tampered_with() {
        String marker = "TAMPER_PROBE_" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            auditService.record(payloadFor(marker, i));
        }

        ChainVerifier.Result result = verifier.verify();

        assertThat(result.broken()).isFalse();
        assertThat(result.firstBrokenIndex()).isEqualTo(-1);
    }
}
