package com.crm.hotel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

@Entity(name = "HotelRoomType")
@Table(schema = "hotel", name = "room_type")
@Getter
@Setter
public class RoomType {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 32, unique = true)
    private String code;

    /** Display names by language tag, e.g. {@code {"vi": "Phòng đôi", "en": "Double room"}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "name_i18n", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> nameI18n;

    @Column(name = "max_guests", nullable = false)
    private int maxGuests;

    /** Whole VND per night, VAT and service charge included. */
    @Column(name = "nightly_rate_gross_vnd", nullable = false)
    private long nightlyRateGrossVnd;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private Long version;
}
