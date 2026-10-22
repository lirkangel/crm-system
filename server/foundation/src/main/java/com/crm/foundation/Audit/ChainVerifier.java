package com.crm.foundation.Audit;

import com.crm.foundation.Domain.AuditEvent;
import com.crm.foundation.Repository.AuditEventRepository;
import com.crm.foundation.Service.AuditService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Recomputes the hash chain end-to-end and compares it to what's stored
 * (spec §12.3). Tampering with any past event changes that event's hash,
 * which cascades: every later {@code prevHash} link stops matching, so the
 * first divergence pinpoints where the chain broke.
 */
@RequiredArgsConstructor
@Component
public class ChainVerifier {

    private static final Logger log = LoggerFactory.getLogger(ChainVerifier.class);
    private static final byte[] GENESIS_HASH = new byte[HashChainComputer.HASH_LENGTH];
    private static final String CHAIN_BREAK_OP = "CHAIN_BREAK_DETECTED";
    private static final String CRITICAL_SEVERITY = "CRITICAL";

    private final AuditEventRepository auditEventRepository;
    private final AuditService auditService;

    @Scheduled(cron = "0 0 3 * * SUN")
    public void verifyWeekly() {
        Result result = verify();
        if (result.broken()) {
            log.error("Audit hash chain integrity check failed at index {}", result.firstBrokenIndex());
        } else {
            log.info("Audit hash chain integrity check passed");
        }
    }

    public Result verify() {
        List<AuditEvent> events = auditEventRepository.findAllByOrderByOccurredAtAscIdAsc();
        byte[] expectedPrev = GENESIS_HASH;
        for (int index = 0; index < events.size(); index++) {
            AuditEvent event = events.get(index);
            byte[] recomputedHash = HashChainComputer.compute(expectedPrev, toPayload(event));
            if (!Arrays.equals(expectedPrev, event.getPrevHash()) || !Arrays.equals(recomputedHash, event.getHash())) {
                auditService.record(chainBreakDetected(index, event.getId()));
                return Result.broken(index);
            }
            expectedPrev = event.getHash();
        }
        return Result.intact();
    }

    private AuditPayload toPayload(AuditEvent event) {
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

    private AuditPayload chainBreakDetected(int brokenIndex, java.util.UUID brokenEventId) {
        String afterJson = "{\"brokenIndex\":" + brokenIndex + ",\"brokenEventId\":\"" + brokenEventId + "\"}";
        return new AuditPayload(Instant.now(), null, null, "foundation", "audit_event", brokenEventId,
            CHAIN_BREAK_OP, null, afterJson, CRITICAL_SEVERITY);
    }

    public record Result(boolean broken, int firstBrokenIndex) {

        public static Result intact() {
            return new Result(false, -1);
        }

        public static Result broken(int firstBrokenIndex) {
            return new Result(true, firstBrokenIndex);
        }
    }
}
