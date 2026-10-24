package com.crm.hotel.folio;

import com.crm.hotel.domain.OtaChannel;
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

/** A payment on a stay's folio. Voided instead of deleted; refunds happen outside the system. */
@Entity(name = "HotelFolioPayment")
@Table(schema = "hotel", name = "folio_payment")
@Getter
@Setter
public class FolioPayment {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentMethod method;

    /** Required iff {@link #method} is {@code OTA}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ota_channel", length = 16)
    private OtaChannel otaChannel;

    @Column(length = 100)
    private String reference;

    @Column(name = "amount_gross_vnd", nullable = false)
    private long amountGrossVnd;

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
