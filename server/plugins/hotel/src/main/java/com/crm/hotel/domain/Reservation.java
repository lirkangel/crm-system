package com.crm.hotel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A stay of one guest in one room over {@code [checkIn, checkOut)}. The database
 * ({@code reservation_no_overlap}) guarantees confirmed and checked-in stays of a room never overlap.
 */
@Entity(name = "HotelReservation")
@Table(schema = "hotel", name = "reservation")
@Getter
@Setter
public class Reservation {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 20, unique = true)
    private String code;

    @Column(name = "guest_id", nullable = false)
    private UUID guestId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;

    @Column(nullable = false)
    private int adults;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status = ReservationStatus.CONFIRMED;

    /** Room type rate at booking time (whole VND, gross); later rate changes don't alter it. */
    @Column(name = "nightly_rate_gross_vnd", nullable = false)
    private long nightlyRateGrossVnd;

    @Column(name = "total_gross_vnd", nullable = false)
    private long totalGrossVnd;

    @Column(columnDefinition = "text")
    private String notes;

    /** Foundation user id of the booking staff member (a plain id: no association across schemas). */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingSource source = BookingSource.WALK_IN;

    /** Required iff {@link #source} is {@code OTA}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ota_channel", length = 16)
    private OtaChannel otaChannel;

    @Column(name = "ota_reference", length = 64)
    private String otaReference;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "checked_out_at")
    private Instant checkedOutAt;

    @Version
    private Long version;
}
