package com.crm.foundation.Service.Impl;

import com.crm.foundation.Audit.AuditPayload;
import com.crm.foundation.Audit.AuditWriter;
import com.crm.foundation.Service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class AuditServiceImpl implements AuditService {

    private final AuditWriter auditWriter;

    @Override
    public void record(AuditPayload payload) {
        auditWriter.record(payload);
    }
}
