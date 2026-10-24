package com.crm.hotel.repo;

import com.crm.hotel.folio.FolioPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FolioPaymentRepository extends JpaRepository<FolioPayment, UUID> {

    /** All lines of the folio, voided ones included, oldest first. */
    List<FolioPayment> findByReservationIdOrderByCreatedAtAscIdAsc(UUID reservationId);

    /** Sum of the active (non-voided) payments; 0 when there are none. */
    @Query("select coalesce(sum(p.amountGrossVnd), 0) from HotelFolioPayment p "
        + "where p.reservationId = :reservationId and p.voidedAt is null")
    long sumActive(@Param("reservationId") UUID reservationId);

    /** Whether the reservation has a payment that is not voided (it blocks cancelling). */
    boolean existsByReservationIdAndVoidedAtIsNull(UUID reservationId);
}
