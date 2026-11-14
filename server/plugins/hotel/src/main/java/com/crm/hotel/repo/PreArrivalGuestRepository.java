package com.crm.hotel.repo;

import com.crm.hotel.checkin.PreArrivalGuest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PreArrivalGuestRepository extends JpaRepository<PreArrivalGuest, UUID> {
}
