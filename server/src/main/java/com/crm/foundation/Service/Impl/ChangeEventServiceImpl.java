package com.crm.foundation.Service.Impl;

import com.crm.foundation.Domain.ChangeEvent;
import com.crm.foundation.Repository.ChangeEventRepository;
import com.crm.foundation.Service.ChangeEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChangeEventServiceImpl implements ChangeEventService {

    private final ChangeEventRepository changeEventRepository;

    @Override
    @Transactional
    public void record(String pluginId, String entityType, UUID entityId, long version, String op, Instant occurredAt) {
        ChangeEvent event = new ChangeEvent();
        event.setPluginId(pluginId);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setVersion(version);
        event.setOp(op);
        event.setOccurredAt(occurredAt);
        changeEventRepository.save(event);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChangeEvent> changesSince(Instant since) {
        return changeEventRepository.findByOccurredAtAfterOrderByOccurredAtAsc(since);
    }
}
