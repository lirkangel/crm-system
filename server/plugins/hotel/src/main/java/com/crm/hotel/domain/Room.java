package com.crm.hotel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(schema = "hotel", name = "room")
@Getter
@Setter
public class Room {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 16, unique = true)
    private String number;

    @Column(name = "room_type_id", nullable = false)
    private UUID roomTypeId;

    private Integer floor;

    @Column(name = "in_service", nullable = false)
    private boolean inService = true;

    @Version
    private Long version;
}
