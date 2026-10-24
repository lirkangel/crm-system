package com.crm.hotel.repo;

import com.crm.hotel.stay.StayGuest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StayGuestRepository extends JpaRepository<StayGuest, UUID> {

    /** Identity records of one stay, by full name then id. */
    List<StayGuest> findByReservationIdOrderByFullNameAscIdAsc(UUID reservationId);
}
