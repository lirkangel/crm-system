package com.crm.dental.previsit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One appointment's pre-visit form. Identity and health values: never logged, audited or put in the change feed.
 * The three health columns hold {@code FieldCipher} output (IV || AES-GCM) of a JSON answer.
 */
@Entity(name = "DentalPrevisitForm")
@Table(schema = "dental", name = "previsit_form")
@Getter
@Setter
public class PrevisitForm {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "appointment_id", nullable = false)
    private UUID appointmentId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "full_name", length = 200)
    private String fullName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "id_type", length = 16)
    private String idType;

    @Column(name = "id_number", length = 64)
    private String idNumber;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 2)
    private String nationality;

    @Column(length = 500)
    private String address;

    @Column(name = "parent_name", length = 200)
    private String parentName;

    @Column(name = "parent_phone", length = 20)
    private String parentPhone;

    @Column(length = 20)
    private String phone;

    @Column(name = "allergies_enc")
    private byte[] allergiesEnc;

    @Column(name = "medicines_enc")
    private byte[] medicinesEnc;

    @Column(name = "reason_enc")
    private byte[] reasonEnc;

    @Column(name = "id_scan_id")
    private UUID idScanId;

    /** Comma-separated names of the identity fields that differ from the CCCD's QR (names only). */
    @Column(name = "edited_fields")
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
