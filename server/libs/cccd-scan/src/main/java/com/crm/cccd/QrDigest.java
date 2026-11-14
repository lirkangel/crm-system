package com.crm.cccd;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A digest of what a CCCD's QR code said, kept on the scan so the guest's form can later be compared with it: per
 * field the SHA-256 of its normalised value ({@code name=hex}, {@code ;}-joined, in the order of {@link #FIELDS}). The
 * values themselves are not kept, so the column holds no readable personal data.
 */
public final class QrDigest {

    /** The compared fields, in digest order; also the names listed as edited. */
    public static final List<String> FIELDS = List.of("idType", "idNumber", "fullName", "dateOfBirth", "nationality", "address");

    private QrDigest() {
    }

    /**
     * The digest of what the scan decoded to. {@code fields} is keyed by the names in {@link #FIELDS}; a missing or
     * null value counts as empty; dates are given in ISO form ({@code LocalDate.toString()}).
     */
    public static String of(Map<String, String> fields) {
        return join(fields);
    }

    /**
     * Names of the {@code submitted} fields that differ from the scan {@code digest} was made from, in digest order.
     * Empty when the scan has no digest (an older scan): then nothing can be said.
     */
    public static List<String> changed(String digest, Map<String, String> submitted) {
        if (digest == null || digest.isBlank()) {
            return List.of();
        }
        Map<String, String> scanned = new HashMap<>();
        for (String part : digest.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                scanned.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        List<String> changed = new ArrayList<>();
        for (String name : FIELDS) {
            if (!sha256Hex(normalise(submitted.get(name))).equals(scanned.get(name))) {
                changed.add(name);
            }
        }
        return changed;
    }

    private static String join(Map<String, String> fields) {
        List<String> parts = new ArrayList<>();
        for (String name : FIELDS) {
            parts.add(name + "=" + sha256Hex(normalise(fields.get(name))));
        }
        return String.join(";", parts);
    }

    /**
     * Trimmed, Unicode NFC text (a keyboard may send the same Vietnamese letter composed or decomposed); dates arrive in ISO
     * form; nothing is the empty string.
     */
    private static String normalise(String value) {
        return value == null ? "" : Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
    }

    private static String sha256Hex(String text) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
