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

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code AuditService.record} keeps the hash chain intact under
 * concurrent writes (T2.3) — each event's {@code prevHash} must equal the
 * previous event's {@code hash}, with no gaps or interleaving.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest
@Import(TestContainersConfig.class)
class AuditServiceIT {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditEventRepository auditEventRepository;

    private static AuditPayload payloadFor(int n) {
        return new AuditPayload(Instant.now(), null, null, "foundation", null, null,
            "OP" + n, null, "{\"seq\":" + n + "}", "INFO");
    }

    @Test
    void should_maintain_chain_integrity_under_concurrent_writes() throws InterruptedException {
        int eventCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        for (int i = 0; i < eventCount; i++) {
            int n = i;
            executor.submit(() -> auditService.record(payloadFor(n)));
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<AuditEvent> all = auditEventRepository.findAllByOrderByOccurredAtAscIdAsc();
        assertThat(all).hasSizeGreaterThanOrEqualTo(eventCount);

        byte[] expectedPrev = all.get(0).getPrevHash();
        for (AuditEvent event : all) {
            assertThat(event.getPrevHash()).isEqualTo(expectedPrev);
            assertThat(HashChainComputer.compute(event.getPrevHash(), toPayload(event))).isEqualTo(event.getHash());
            expectedPrev = event.getHash();
        }
    }

    private static AuditPayload toPayload(AuditEvent event) {
        return new AuditPayload(
            event.getOccurredAt(),
            event.getUser() == null ? null : event.getUser().getId(),
            event.getSourceIp(),
            event.getPluginId(),
            event.getEntityType(),
            event.getEntityId(),
            event.getOp(),
            event.getBeforeJson(),
            event.getAfterJson(),
            event.getSeverity());
    }
}
