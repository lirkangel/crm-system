package com.crm.dental.previsit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrevisitFormRepository extends JpaRepository<PrevisitForm, UUID> {

    @Query("select f from DentalPrevisitForm f where f.appointmentId = :appointmentId")
    Optional<PrevisitForm> findByAppointment(@Param("appointmentId") UUID appointmentId);

    @Query("select count(f) > 0 from DentalPrevisitForm f where f.appointmentId = :appointmentId")
    boolean existsForAppointment(@Param("appointmentId") UUID appointmentId);

    /** Whether the patient has any form with health answers (the page may offer the date-of-birth check). */
    @Query("select count(f) > 0 from DentalPrevisitForm f where f.patientId = :patientId and f.allergiesEnc is not null")
    boolean existsAnswersOfPatient(@Param("patientId") UUID patientId);

    /** The patient's forms with health answers from other appointments, newest first (use a page of one). */
    @Query("""
        select f from DentalPrevisitForm f
        where f.patientId = :patientId and f.appointmentId <> :appointmentId and f.allergiesEnc is not null
        order by f.submittedAt desc, f.id
        """)
    List<PrevisitForm> findEarlierOfPatient(
        @Param("patientId") UUID patientId, @Param("appointmentId") UUID appointmentId, Pageable page);

    /** Deletes the appointment's form (cancellation), flushing first. */
    @Modifying(flushAutomatically = true)
    @Query("delete from DentalPrevisitForm f where f.appointmentId = :appointmentId")
    int deleteByAppointment(@Param("appointmentId") UUID appointmentId);
}
