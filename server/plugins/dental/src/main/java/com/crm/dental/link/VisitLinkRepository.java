package com.crm.dental.link;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface VisitLinkRepository extends JpaRepository<VisitLink, UUID> {

    /** The link whose token hashes to {@code tokenHash} (SHA-256), if any. */
    Optional<VisitLink> findByTokenHash(byte[] tokenHash);

    /** @return 1 if the appointment had a link, else 0 */
    @Modifying(flushAutomatically = true)
    @Query("delete from DentalVisitLink l where l.appointmentId = :appointmentId")
    int deleteByAppointment(@Param("appointmentId") UUID appointmentId);

    /** Moves the expiry of the appointment's link (if any), e.g. after a reschedule. */
    @Modifying(flushAutomatically = true)
    @Query("update DentalVisitLink l set l.expiresAt = :expiresAt where l.appointmentId = :appointmentId")
    int updateExpiry(@Param("appointmentId") UUID appointmentId, @Param("expiresAt") Instant expiresAt);

    /** Table hygiene of the retention purge: links that expired before {@code cutoff}. */
    @Modifying(flushAutomatically = true)
    @Query("delete from DentalVisitLink l where l.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
