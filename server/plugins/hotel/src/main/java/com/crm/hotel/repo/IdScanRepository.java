package com.crm.hotel.repo;

import com.crm.hotel.scan.IdScan;
import com.crm.hotel.scan.StoredImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface IdScanRepository extends JpaRepository<IdScan, UUID> {

    long countByReservationId(UUID reservationId);

    /** Id of the reservation's scan of exactly these original bytes (the image itself is not loaded). */
    @Query("select x.id from HotelIdScan x where x.reservationId = :reservationId and x.sha256 = :sha256")
    Optional<UUID> findIdByReservationIdAndSha256(
        @Param("reservationId") UUID reservationId, @Param("sha256") byte[] sha256);

    /** Who a scan belongs to, without loading its image. */
    interface Link {
        UUID getReservationId();

        UUID getStayGuestId();
    }

    @Query("select x.reservationId as reservationId, x.stayGuestId as stayGuestId from HotelIdScan x where x.id = :id")
    Optional<Link> findLinkById(@Param("id") UUID id);

    /** The only query that loads the image bytes: the scan linked to this stay guest of this reservation. */
    @Query("select new com.crm.hotel.scan.StoredImage(x.image, x.contentType) from HotelIdScan x "
        + "where x.reservationId = :reservationId and x.stayGuestId = :stayGuestId")
    Optional<StoredImage> findImage(
        @Param("reservationId") UUID reservationId, @Param("stayGuestId") UUID stayGuestId);

    /** Links the scan (by id, without loading its image) and bumps its version. */
    @Modifying(flushAutomatically = true)
    @Query("update HotelIdScan x set x.stayGuestId = :stayGuestId, x.version = x.version + 1 where x.id = :id")
    int linkTo(@Param("id") UUID id, @Param("stayGuestId") UUID stayGuestId);

    /** Deletes the stay guest's linked scan(s) other than {@code keepId}, flushing first and executing at once. */
    @Modifying(flushAutomatically = true)
    @Query("delete from HotelIdScan x where x.stayGuestId = :stayGuestId and x.id <> :keepId")
    int deleteLinkedToExcept(@Param("stayGuestId") UUID stayGuestId, @Param("keepId") UUID keepId);

    @Modifying(flushAutomatically = true)
    @Query("delete from HotelIdScan x where x.stayGuestId in :stayGuestIds")
    int deleteLinkedToAny(@Param("stayGuestIds") Collection<UUID> stayGuestIds);
}
