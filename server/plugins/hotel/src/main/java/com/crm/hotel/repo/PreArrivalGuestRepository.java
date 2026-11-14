package com.crm.hotel.repo;

import com.crm.hotel.checkin.PreArrivalGuest;
import com.crm.hotel.stay.IdDocType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PreArrivalGuestRepository extends JpaRepository<PreArrivalGuest, UUID> {

    /** The reservation's submissions, oldest first. */
    List<PreArrivalGuest> findByReservationIdOrderBySubmittedAtAscIdAsc(UUID reservationId);

    long countByReservationId(UUID reservationId);

    Optional<PreArrivalGuest> findByReservationIdAndIdTypeAndIdNumber(
        UUID reservationId, IdDocType idType, String idNumber);

    /** Whether any submission holds this scan. */
    boolean existsByIdScanId(UUID idScanId);

    /** A submission and the scan it holds (null without a scan, or once the retention purge took it). */
    interface ScanRef {
        UUID getId();

        UUID getIdScanId();
    }

    @Query("select p.id as id, p.idScanId as idScanId from HotelPreArrivalGuest p "
        + "where p.id = :id and p.reservationId = :reservationId")
    Optional<ScanRef> findScanRef(@Param("id") UUID id, @Param("reservationId") UUID reservationId);

    /** Submissions per reservation, as {@code [reservationId, count]} rows (reservations without any are absent). */
    @Query("select p.reservationId, count(p) from HotelPreArrivalGuest p where p.reservationId in :reservationIds "
        + "group by p.reservationId")
    List<Object[]> countByReservationIds(@Param("reservationIds") Collection<UUID> reservationIds);

    /** Bulk delete of a reservation's submissions (at check-in and cancellation); scans are left alone. */
    @Modifying(flushAutomatically = true)
    @Query("delete from HotelPreArrivalGuest p where p.reservationId = :reservationId")
    int deleteByReservation(@Param("reservationId") UUID reservationId);

    /** The retention purge: submissions made before {@code cutoff}. */
    @Modifying(flushAutomatically = true)
    @Query("delete from HotelPreArrivalGuest p where p.submittedAt < :cutoff")
    int deleteSubmittedBefore(@Param("cutoff") Instant cutoff);

    /** @return 1 if the reservation had this submission, else 0 */
    @Modifying(flushAutomatically = true)
    @Query("delete from HotelPreArrivalGuest p where p.id = :id and p.reservationId = :reservationId")
    int deleteOne(@Param("id") UUID id, @Param("reservationId") UUID reservationId);
}
