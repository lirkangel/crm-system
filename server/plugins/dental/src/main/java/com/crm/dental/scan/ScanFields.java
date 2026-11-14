package com.crm.dental.scan;

import com.crm.cccd.CccdData;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Identity fields read from a CCCD's QR code, as offered to the pre-visit form. Personal data. */
public record ScanFields(
        String idType,
        String idNumber,
        String fullName,
        LocalDate dateOfBirth,
        String nationality,
        String address) {

    static ScanFields of(CccdData data) {
        return new ScanFields("CCCD", data.idNumber(), data.fullName(), data.dateOfBirth(), "VN", data.address());
    }

    /** The fields {@code QrDigest} compares, under its names and with dates in ISO form. */
    Map<String, String> digestFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put("idType", idType);
        fields.put("idNumber", idNumber);
        fields.put("fullName", fullName);
        fields.put("dateOfBirth", dateOfBirth == null ? null : dateOfBirth.toString());
        fields.put("nationality", nationality);
        fields.put("address", address);
        return fields;
    }

    @Override
    public String toString() {
        return "ScanFields[redacted]";
    }
}
