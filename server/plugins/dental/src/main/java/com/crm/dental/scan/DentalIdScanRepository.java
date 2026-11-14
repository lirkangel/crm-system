package com.crm.dental.scan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code DentalIdScan} entity carries the image bytes eagerly, so production code must never load it: use the
 * projections and bulk queries below, never {@code findAll}, {@code findById} or {@code delete(entity)}.
 */
public interface DentalIdScanRepository extends JpaRepository<DentalIdScan, UUID> {

    @Query("select count(x) from DentalIdScan x where x.appointmentId = :appointmentId")
    long countByAppointment(@Param("appointmentId") UUID appointmentId);

    /** Id of the appointment's scan of exactly these original bytes (the image itself is not loaded). */
    @Query("select x.id from DentalIdScan x where x.appointmentId = :appointmentId and x.sha256 = :sha256")
    Optional<UUID> findIdByAppointmentAndSha256(
        @Param("appointmentId") UUID appointmentId, @Param("sha256") byte[] sha256);

    /** The QR digest of a scan (empty for an unknown scan or one without a digest), without loading its image. */
    @Query("select x.qrDigest from DentalIdScan x where x.id = :id")
    Optional<String> findQrDigestById(@Param("id") UUID id);

    /** Deletes every scan of the appointment (cancellation), flushing first. */
    @Modifying(flushAutomatically = true)
    @Query("delete from DentalIdScan x where x.appointmentId = :appointmentId")
    int deleteByAppointment(@Param("appointmentId") UUID appointmentId);

    /** The retention purge: one bulk delete, flushing first; returns the number of deleted scans. */
    @Modifying(flushAutomatically = true)
    @Query("delete from DentalIdScan x where x.uploadedAt < :cutoff")
    int deleteUploadedBefore(@Param("cutoff") Instant cutoff);
}
