package com.crm.foundation.Audit;

import com.crm.foundation.Domain.AuditEvent;
import com.crm.foundation.Repository.AuditEventRepository;
import com.crm.foundation.Repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Appends audit events one at a time so each {@code prevHash} reliably links
 * to the previous event's {@code hash} (spec §12.3: "sequential write through
 * a single dedicated bean"). A {@link ReentrantLock} serializes the
 * read-latest / compute / persist sequence; at 10–50 user scale this is not a
 * throughput concern.
 */
@Component
public class AuditWriter {

    private static final byte[] GENESIS_HASH = new byte[HashChainComputer.HASH_LENGTH];
    private static final String DEFAULT_SEVERITY = "INFO";

    private final AuditEventRepository auditEventRepository;
    private final UserRepository userRepository;
    private final ReentrantLock lock = new ReentrantLock();

    public AuditWriter(AuditEventRepository auditEventRepository, UserRepository userRepository) {
        this.auditEventRepository = auditEventRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public void record(AuditPayload payload) {
        lock.lock();
        try {
            byte[] prevHash = auditEventRepository.findTopByOrderByOccurredAtDescIdDesc()
                .map(AuditEvent::getHash)
                .orElse(GENESIS_HASH);
            byte[] hash = HashChainComputer.compute(prevHash, payload);
            auditEventRepository.save(toEntity(payload, prevHash, hash));
        } finally {
            lock.unlock();
        }
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
        event.setSeverity(payload.severity() == null ? DEFAULT_SEVERITY : payload.severity());
        event.setPrevHash(prevHash);
        event.setHash(hash);
        return event;
    }
}
