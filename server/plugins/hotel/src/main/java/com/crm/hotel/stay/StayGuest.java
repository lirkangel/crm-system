package com.crm.hotel.stay;

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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity record of one adult of a stay (for the residence declaration). Personal data: readable only with
 * {@code hotel.stays.read} and never written to the audit log.
 */
@Entity(name = "HotelStayGuest")
@Table(schema = "hotel", name = "stay_guest")
@Getter
@Setter
public class StayGuest {

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

    /** Required for Vietnamese citizens. */
    @Column(length = 500)
    private String address;

    @Column(name = "visa_number", length = 64)
    private String visaNumber;

    @Column(name = "entry_date")
    private LocalDate entryDate;

    @Version
    private Long version;

    /** Copies the (already validated) request, trimming text and storing blank optional text as null. */
    void apply(StayGuestRequest request) {
        idType = request.idType();
        idNumber = request.idNumber().trim();
        fullName = request.fullName().trim();
        dateOfBirth = request.dateOfBirth();
        nationality = request.nationality();
        address = blankToNull(request.address());
        visaNumber = blankToNull(request.visaNumber());
        entryDate = request.entryDate();
    }

    /**
     * Names of the fields that applying {@code request} would change, compared after the same normalisation
     * {@link #apply} does. Names only: the audit log must never see the values.
     */
    List<String> changedFields(StayGuestRequest request) {
        StayGuest next = new StayGuest();
        next.apply(request);
        List<String> changed = new ArrayList<>();
        addIfChanged(changed, "idType", idType, next.idType);
        addIfChanged(changed, "idNumber", idNumber, next.idNumber);
        addIfChanged(changed, "fullName", fullName, next.fullName);
        addIfChanged(changed, "dateOfBirth", dateOfBirth, next.dateOfBirth);
        addIfChanged(changed, "nationality", nationality, next.nationality);
        addIfChanged(changed, "address", address, next.address);
        addIfChanged(changed, "visaNumber", visaNumber, next.visaNumber);
        addIfChanged(changed, "entryDate", entryDate, next.entryDate);
        return changed;
    }

    private static void addIfChanged(List<String> changed, String field, Object current, Object next) {
        if (!Objects.equals(current, next)) {
            changed.add(field);
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
