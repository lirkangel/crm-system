package com.crm.dental.appointment;

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
import java.util.UUID;

/** A phone booking of one patient. {@code startsAt} is an instant; the clinic day is derived in Asia/Ho_Chi_Minh. */
@Entity(name = "DentalAppointment")
@Table(schema = "dental", name = "appointment")
@Getter
@Setter
public class Appointment {

    @Id
    private UUID id = UUID.randomUUID();

    /** A plain id, as in hotel: no association, so no implicit join. */
    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 30;

    @Column(length = 200)
    private String dentist;

    /** Why the patient is coming, as told on the phone; staff-only. */
    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AppointmentStatus status = AppointmentStatus.BOOKED;

    /** Foundation user id of the booking staff member (a plain id: no association across schemas). */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Long version;
}
