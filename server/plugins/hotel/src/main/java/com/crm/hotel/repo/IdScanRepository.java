package com.crm.hotel.repo;

import com.crm.hotel.scan.IdScan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface IdScanRepository extends JpaRepository<IdScan, UUID> {

    long countByReservationId(UUID reservationId);

    /** Id of the reservation's scan of exactly these original bytes (the image itself is not loaded). */
    @Query("select x.id from HotelIdScan x where x.reservationId = :reservationId and x.sha256 = :sha256")
    Optional<UUID> findIdByReservationIdAndSha256(
        @Param("reservationId") UUID reservationId, @Param("sha256") byte[] sha256);
}
