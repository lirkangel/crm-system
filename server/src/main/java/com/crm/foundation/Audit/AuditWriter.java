package com.crm.foundation.Audit;

import com.crm.foundation.Domain.AuditEvent;
import com.crm.foundation.Repository.AuditEventRepository;
import com.crm.foundation.Repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Appends audit events one at a time so each {@code prevHash} reliably links
 * to the previous event's {@code hash} (spec §12.3: "sequential write through
 * a single dedicated bean").
 *
 * <p>Serialization uses a Postgres transaction-scoped advisory lock rather than
 * an in-JVM lock: it is held until the surrounding transaction commits, so the
 * next writer always reads the committed tail of the chain (an in-JVM lock is
 * released before commit and lets two writers link to the same predecessor).
 *
 * <p>The hash covers exactly what Postgres will hand back to
 * {@link ChainVerifier}: {@code occurred_at} is assigned here (microsecond
 * precision, strictly increasing, so chain order equals sort order) and
 * {@code jsonb} / {@code inet} values are normalized through the database
 * before hashing. The caller's {@code occurredAt} is therefore ignored.
 */
@Component
public class AuditWriter {

    private static final byte[] GENESIS_HASH = new byte[HashChainComputer.HASH_LENGTH];
    private static final String DEFAULT_SEVERITY = "INFO";
    /** Arbitrary app-wide key for {@code pg_advisory_xact_lock}; "AUDITCHN" in ASCII. */
    private static final long CHAIN_LOCK_KEY = 0x41554449_5443484EL;

    private final AuditEventRepository auditEventRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;

    public AuditWriter(AuditEventRepository auditEventRepository, UserRepository userRepository, JdbcTemplate jdbc) {
        this.auditEventRepository = auditEventRepository;
        this.userRepository = userRepository;
        this.jdbc = jdbc;
    }

    @Transactional
    public void record(AuditPayload payload) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", CHAIN_LOCK_KEY);

        Optional<AuditEvent> latest = auditEventRepository.findTopByOrderByOccurredAtDescIdDesc();
        byte[] prevHash = latest.map(AuditEvent::getHash).orElse(GENESIS_HASH);
        AuditPayload stored = asStored(payload, nextOccurredAt(latest));
        byte[] hash = HashChainComputer.compute(prevHash, stored);
        auditEventRepository.save(toEntity(stored, prevHash, hash));
    }

    private static Instant nextOccurredAt(Optional<AuditEvent> latest) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return latest
            .map(AuditEvent::getOccurredAt)
            .filter(previous -> !now.isAfter(previous))
            .map(previous -> previous.plus(1, ChronoUnit.MICROS))
            .orElse(now);
    }

    /** The payload as it will read back from Postgres (jsonb/inet text output forms). */
    private AuditPayload asStored(AuditPayload payload, Instant occurredAt) {
        String[] normalized = jdbc.queryForObject(
            "SELECT CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS inet)",
            (rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)},
            payload.beforeJson(), payload.afterJson(), payload.sourceIp());
        return new AuditPayload(
            occurredAt,
            payload.userId(),
            normalized[2],
            payload.pluginId(),
            payload.entityType(),
            payload.entityId(),
            payload.op(),
            normalized[0],
            normalized[1],
            payload.severity() == null ? DEFAULT_SEVERITY : payload.severity());
    }

    private AuditEvent toEntity(AuditPayload payload, byte[] prevHash, byte[] hash) {
        AuditEvent event = new AuditEvent();
        event.setOccurredAt(payload.occurredAt());
        event.setUser(payload.userId() == null ? null : userRepository.getReferenceById(payload.userId()));
        event.setSourceIp(payload.sourceIp());
        event.setPluginId(payload.pluginId());
        event.setEntityType(payload.entityType());
        event.setEntityId(payload.entityId());
        event.setOp(payload.op());
        event.setBeforeJson(payload.beforeJson());
        event.setAfterJson(payload.afterJson());
        event.setSeverity(payload.severity());
        event.setPrevHash(prevHash);
        event.setHash(hash);
        return event;
    }
}
