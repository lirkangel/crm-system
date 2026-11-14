package com.crm.cccd;

import java.time.Duration;
import java.util.Optional;

/**
 * The only way an upload is decoded: one image decode, under {@link QrDecoder}'s concurrency cap and deadline, that
 * yields both the QR text and the stored JPEG. (A separate normalisation pass would decode the image again outside
 * the cap.)
 */
public class ScanPipeline {

    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    /** {@code qrText} is personal data; the JPEG is the downscaled image to store. */
    public record Processed(String qrText, byte[] storedJpeg) {
        @Override
        public String toString() {
            return "Processed[redacted]";
        }
    }

    /**
     * @return empty when the image is unreadable or carries no QR code (no JPEG is produced then)
     * @throws ScanBusyException when no decode slot frees up within 5 seconds
     */
    public Optional<Processed> process(byte[] bytes) {
        return QrDecoder.run(bytes, TIMEOUT, System::nanoTime, true);
    }
}
