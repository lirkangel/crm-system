package com.crm.foundation.Audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HashChainComputerTest {

    private static final byte[] GENESIS = new byte[32];
    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static AuditPayload payload(String op, String afterJson) {
        return new AuditPayload(NOW, USER_ID, "1.1.1.1", "foundation", "User", null, op, null, afterJson, "INFO");
    }

    @Test
    void should_produce_deterministic_chain_for_same_inputs() {
        AuditPayload p1 = payload("LOGIN", null);
        AuditPayload p2 = payload("INSERT", "{\"entity\":\"User\",\"id\":\"abc\"}");
        AuditPayload p3 = payload("LOGOUT", null);

        byte[] h1 = HashChainComputer.compute(GENESIS, p1);
        byte[] h2 = HashChainComputer.compute(h1, p2);
        byte[] h3 = HashChainComputer.compute(h2, p3);

        assertThat(h1).hasSize(32);
        assertThat(h2).isNotEqualTo(h1);
        assertThat(h3).isNotEqualTo(h2);

        assertThat(HashChainComputer.compute(GENESIS, p1)).isEqualTo(h1);
    }

    @Test
    void should_change_hash_when_payload_is_tampered() {
        AuditPayload original = payload("INSERT", "{\"entity\":\"User\",\"id\":\"abc\"}");
        AuditPayload tampered = payload("INSERT", "{\"entity\":\"User\",\"id\":\"xyz\"}");

        byte[] originalHash = HashChainComputer.compute(GENESIS, original);
        byte[] tamperedHash = HashChainComputer.compute(GENESIS, tampered);

        assertThat(tamperedHash).isNotEqualTo(originalHash);
    }
}
