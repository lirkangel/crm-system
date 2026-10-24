package com.crm.hotel.repo;

import com.crm.hotel.folio.FolioCharge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FolioChargeRepository extends JpaRepository<FolioCharge, UUID> {

    /** All lines of the folio, voided ones included, oldest first. */
    List<FolioCharge> findByReservationIdOrderByCreatedAtAscIdAsc(UUID reservationId);

    /** Sum of the active (non-voided) charges; 0 when there are none. */
    @Query("select coalesce(sum(c.amountGrossVnd), 0) from HotelFolioCharge c "
        + "where c.reservationId = :reservationId and c.voidedAt is null")
    long sumActive(@Param("reservationId") UUID reservationId);
}
