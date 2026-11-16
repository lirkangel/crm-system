package com.crm.dental.patient;

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

/** A patient of the clinic. Identity values: never copied into audit, logs or the change feed. */
@Entity(name = "DentalPatient")
@Table(schema = "dental", name = "patient")
@Getter
@Setter
public class Patient {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    /** Accent-folded, lower-cased name ({@code Names.fold}), matched by name search. */
    @Column(name = "name_search", nullable = false)
    private String nameSearch;

    /** Normalised {@code +84...}; not unique, since a parent may book a child under the parent's phone. */
    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "date_of_birth", nullable = false)
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Long version;
}
