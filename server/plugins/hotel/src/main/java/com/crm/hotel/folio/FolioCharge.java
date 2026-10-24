package com.crm.hotel.folio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** An extra charge on a stay's folio (minibar, laundry, ...). Voided instead of deleted. */
@Entity(name = "HotelFolioCharge")
@Table(schema = "hotel", name = "folio_charge")
@Getter
@Setter
public class FolioCharge {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "amount_gross_vnd", nullable = false)
    private long amountGrossVnd;

    /** Foundation user id of the staff member (a plain id: no association across schemas). */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @Version
    private Long version;

    public boolean isVoided() {
        return voidedAt != null;
    }
}
