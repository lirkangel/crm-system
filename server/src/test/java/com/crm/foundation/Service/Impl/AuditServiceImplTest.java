package com.crm.foundation.Service.Impl;

import com.crm.foundation.Audit.AuditPayload;
import com.crm.foundation.Audit.AuditWriter;
import com.crm.foundation.Repository.AuditEventRepository;
import com.crm.foundation.Repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuditServiceImplTest {

    @Test
    void record_delegatesToAuditWriter() {
        CapturingAuditWriter capturingWriter = new CapturingAuditWriter();
        AuditServiceImpl service = new AuditServiceImpl(capturingWriter);
        AuditPayload payload = new AuditPayload(Instant.now(), null, null, "foundation", null, null,
            "LOGIN", null, null, "INFO");

        service.record(payload);

        assertThat(capturingWriter.recorded).containsExactly(payload);
    }

    /** Non-Mockito test double: avoids JDK 26 inline-mock incompatibility with concrete classes. */
    private static final class CapturingAuditWriter extends AuditWriter {
        final List<AuditPayload> recorded = new ArrayList<>();

        CapturingAuditWriter() {
            super(mock(AuditEventRepository.class), mock(UserRepository.class));
        }

        @Override
        public void record(AuditPayload payload) {
            recorded.add(payload);
        }
    }
}
