package com.crm.hotel.checkin;

import com.crm.hotel.stay.IdDocType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Identity details a guest sent through their personal link before arriving. Personal data: never logged, never in
 * the audit log or the change feed.
 */
@Entity(name = "HotelPreArrivalGuest")
@Table(schema = "hotel", name = "pre_arrival_guest")
@Getter
@Setter
public class PreArrivalGuest {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_type", nullable = false, length = 16)
    private IdDocType idType;

    @Column(name = "id_number", nullable = false, length = 64)
    private String idNumber;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    /** ISO 3166-1 alpha-2 ({@code CHAR(2)}). */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 2)
    private String nationality;

    @Column(length = 500)
    private String address;

    @Column(name = "visa_number", length = 64)
    private String visaNumber;

    @Column(name = "entry_date")
    private LocalDate entryDate;

    /** Set while the scan exists; the retention purge nulls it (the typed details stay). */
    @Column(name = "id_scan_id")
    private UUID idScanId;

    /** Comma-separated names of the fields the guest changed after the scan filled them in. */
    @Column(name = "edited_fields", length = 200)
    private String editedFields;

    @Column(name = "consent_at", nullable = false)
    private Instant consentAt;

    @Column(name = "notice_version", nullable = false, length = 16)
    private String noticeVersion;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Version
    private Long version;
}
