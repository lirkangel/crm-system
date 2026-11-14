package com.crm.dental.scan;

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
 * the change feed. The image is loaded EAGERLY, so production code never loads this entity (see the repository).
 */
@Entity(name = "DentalIdScan")
@Table(schema = "dental", name = "id_scan")
@Getter
@Setter
public class DentalIdScan {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "appointment_id", nullable = false)
    private UUID appointmentId;

    @Column(name = "content_type", nullable = false, length = 32)
    private String contentType;

    @Column(nullable = false)
    private byte[] image;

    /** SHA-256 of the original upload. */
    @Column(nullable = false)
    private byte[] sha256;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    /** The CCCD QR text, digested so the submitted form can later be compared with it. */
    @Column(name = "qr_digest", length = 600)
    private String qrDigest;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Version
    private Long version;
}
