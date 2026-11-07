package com.crm.hotel.scan;

import java.time.LocalDate;

/** Identity fields decoded from the QR code on the back of a Vietnamese CCCD. Never log an instance. */
public record CccdData(
    String idNumber,
    String oldIdNumber,
    String fullName,
    LocalDate dateOfBirth,
    String sex,
    String address,
    LocalDate issueDate) {

    @Override
    public String toString() {
        return "CccdData[redacted]";
    }
}
