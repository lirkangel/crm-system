package com.crm.hotel.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The stored (downscaled JPEG) photo of the QR side of a CCCD. Personal data: never logged, never in the audit log or
 * the change feed.
 */
@Entity(name = "HotelIdScan")
@Table(schema = "hotel", name = "id_scan")
@Getter
@Setter
public class IdScan {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    /** Set when the scan is linked to an adult's identity record. */
    @Column(name = "stay_guest_id")
    private UUID stayGuestId;

    @Column(name = "content_type", nullable = false, length = 32)
    private String contentType;

    @Column(nullable = false)
    private byte[] image;

    /** SHA-256 of the original upload. */
    @Column(nullable = false)
    private byte[] sha256;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    /** The CCCD QR text, digested for the pre-arrival match (filled by the guest submission). */
    @Column(name = "qr_digest", length = 600)
    private String qrDigest;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Version
    private Long version;
}
