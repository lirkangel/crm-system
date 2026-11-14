package com.crm.dental.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The personal pre-visit link of an appointment: one row per appointment, replaced when regenerated. Only the
 * SHA-256 of the token is kept; the token itself is shown once and never logged or audited.
 */
@Entity(name = "DentalVisitLink")
@Table(schema = "dental", name = "visit_link")
@Getter
@Setter
public class VisitLink {

    @Id
    @Column(name = "appointment_id")
    private UUID appointmentId;

    @Column(name = "token_hash", nullable = false)
    private byte[] tokenHash;

    /** Foundation user id of the staff member who made the link (a plain id: no association across schemas). */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Wrong date-of-birth answers on the previous-answers check; the lock at 5 is the public form's rule. */
    @Column(name = "dob_attempts", nullable = false)
    private int dobAttempts;
}
