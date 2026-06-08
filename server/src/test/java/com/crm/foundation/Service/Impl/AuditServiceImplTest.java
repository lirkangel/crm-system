package com.crm.foundation.Service.Impl;

import com.crm.foundation.Audit.AuditPayload;
import com.crm.foundation.Audit.AuditWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceImplTest {

    @Mock
    private AuditWriter auditWriter;

    @Test
    void record_delegatesToAuditWriter() {
        AuditServiceImpl service = new AuditServiceImpl(auditWriter);
        AuditPayload payload = new AuditPayload(Instant.now(), null, null, "foundation", null, null,
            "LOGIN", null, null, "INFO");

        service.record(payload);

        verify(auditWriter).record(payload);
    }
}
