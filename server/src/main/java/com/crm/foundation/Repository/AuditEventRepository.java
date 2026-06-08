package com.crm.foundation.Repository;

import com.crm.foundation.Domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Optional<AuditEvent> findTopByOrderByOccurredAtDescIdDesc();

    List<AuditEvent> findAllByOrderByOccurredAtAscIdAsc();
}
