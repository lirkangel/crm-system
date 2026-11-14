package com.crm.hotel.scan;

import com.crm.hotel.stay.StayGuestRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
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
    static final List<String> FIELDS = List.of("idType", "idNumber", "fullName", "dateOfBirth", "nationality", "address");

    private QrDigest() {
    }

    /** The digest of what the scan decoded to. */
    public static String of(ScanFields scan) {
        return join(Arrays.<Object>asList(
            scan.idType(), scan.idNumber(), scan.fullName(), scan.dateOfBirth(), scan.nationality(), scan.address()));
    }

    /**
     * Names of the fields of {@code request} that differ from the scan {@code digest} was made from, in digest order.
     * Empty when the scan has no digest (an older scan): then nothing can be said.
     */
    public static List<String> changed(String digest, StayGuestRequest request) {
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
        String[] current = join(Arrays.<Object>asList(request.idType().name(), request.idNumber(), request.fullName(),
            request.dateOfBirth(), request.nationality(), request.address())).split(";");
        List<String> changed = new ArrayList<>();
        for (int i = 0; i < FIELDS.size(); i++) {
            String name = FIELDS.get(i);
            if (!current[i].substring(name.length() + 1).equals(scanned.get(name))) {
                changed.add(name);
            }
        }
        return changed;
    }

    private static String join(List<Object> values) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < FIELDS.size(); i++) {
            parts.add(FIELDS.get(i) + "=" + sha256Hex(normalise(values.get(i))));
        }
        return String.join(";", parts);
    }

    /** Trimmed text; dates in ISO form ({@code toString}); nothing is the empty string. */
    private static String normalise(Object value) {
        return value == null ? "" : value.toString().trim();
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
