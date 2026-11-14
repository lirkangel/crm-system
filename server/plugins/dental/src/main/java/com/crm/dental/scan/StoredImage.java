package com.crm.dental.scan;

/** The image bytes of a scan and their media type (personal data: only for the staff view endpoint). */
public record StoredImage(byte[] bytes, String contentType) {

    @Override
    public String toString() {
        return "StoredImage[redacted]";
    }
}
