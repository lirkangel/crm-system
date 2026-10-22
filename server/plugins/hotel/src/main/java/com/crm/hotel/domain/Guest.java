package com.crm.hotel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity(name = "HotelGuest")
@Table(schema = "hotel", name = "guest")
@Getter
@Setter
public class Guest {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    /** Not unique: families and tour groups share a phone number. */
    @Column(nullable = false, length = 32)
    private String phone;

    @Column(length = 254)
    private String email;

    /** Accent-folded, lower-cased name and phone, matched by guest search ("Nguyen" finds "Nguyễn"). */
    @Column(name = "search_key", nullable = false, length = 500)
    private String searchKey;

    @Version
    private Long version;
}
