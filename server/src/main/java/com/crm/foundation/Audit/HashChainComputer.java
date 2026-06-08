package com.crm.foundation.Audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes hash-chain links per spec §12.2:
 * {@code hash_n = SHA-256(prev_hash || canonical_json(payload_n))}.
 * <p>
 * "Canonical JSON" means sorted object keys and no whitespace, so the same
 * payload always serializes to the same bytes regardless of field order —
 * a prerequisite for third parties to recompute and verify the chain.
 */
public final class HashChainComputer {

    public static final int HASH_LENGTH = 32;

    private static final String ALGORITHM = "SHA-256";

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private HashChainComputer() {
    }

    public static byte[] compute(byte[] prevHash, AuditPayload payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            digest.update(prevHash);
            digest.update(CANONICAL_MAPPER.writeValueAsBytes(payload));
            return digest.digest();
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("Unable to compute audit hash chain link", e);
        }
    }
}
