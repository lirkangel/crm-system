package com.crm.hotel.repo;

import com.crm.hotel.checkin.GuestLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GuestLinkRepository extends JpaRepository<GuestLink, UUID> {

    /** The link whose token hashes to {@code tokenHash} (SHA-256), if any. */
    Optional<GuestLink> findByTokenHash(byte[] tokenHash);

    /** @return 1 if the reservation had a link, else 0 */
    @Modifying(flushAutomatically = true)
    @Query("delete from HotelGuestLink l where l.reservationId = :reservationId")
    int deleteByReservation(@Param("reservationId") UUID reservationId);

    /** Table hygiene of the retention purge: links that expired before {@code cutoff}. */
    @Modifying(flushAutomatically = true)
    @Query("delete from HotelGuestLink l where l.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") java.time.Instant cutoff);
}
