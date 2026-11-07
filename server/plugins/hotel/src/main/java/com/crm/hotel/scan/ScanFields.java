package com.crm.hotel.scan;

import java.time.LocalDate;

/** Identity fields read from a CCCD's QR code, as offered to the check-in form. Personal data. */
public record ScanFields(
        String idType,
        String idNumber,
        String fullName,
        LocalDate dateOfBirth,
        String nationality,
        String address,
        String sex,
        LocalDate issueDate) {

    static ScanFields of(CccdData data) {
        return new ScanFields("CCCD", data.idNumber(), data.fullName(), data.dateOfBirth(), "VN", data.address(),
            data.sex(), data.issueDate());
    }

    @Override
    public String toString() {
        return "ScanFields[redacted]";
    }
}
