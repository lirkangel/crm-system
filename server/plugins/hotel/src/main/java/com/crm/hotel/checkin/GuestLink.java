package com.crm.hotel.checkin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The personal check-in link of a reservation: one row per reservation, replaced when regenerated. Only the SHA-256
 * of the token is kept; the token itself is shown once and never logged or audited.
 */
@Entity(name = "HotelGuestLink")
@Table(schema = "hotel", name = "guest_link")
@Getter
@Setter
public class GuestLink {

    @Id
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "token_hash", nullable = false)
    private byte[] tokenHash;

    /** Foundation user id of the staff member who made the link (a plain id: no association across schemas). */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
