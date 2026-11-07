package com.crm.hotel.scan;

import java.util.Optional;

/** Upload formats the scan feature accepts, detected from magic bytes (never from the client's content type). */
public enum ImageFormat {
    JPEG,
    PNG;

    public static Optional<ImageFormat> detect(byte[] bytes) {
        if (bytes == null) {
            return Optional.empty();
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return Optional.of(PNG);
        }
        return Optional.empty();
    }
}
