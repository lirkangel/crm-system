package com.crm.dental.appointment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /** The appointment with its row locked ({@code FOR UPDATE}) until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DentalAppointment a where a.id = :id")
    Optional<Appointment> lockById(@Param("id") UUID id);

    /** Rows of {@code [Appointment, patient full name, patient phone]} for one appointment (empty if unknown). */
    @Query("""
        select a, p.fullName, p.phone from DentalAppointment a, DentalPatient p
        where p.id = a.patientId and a.id = :id
        """)
    List<Object[]> findDetailed(@Param("id") UUID id);

    /** Same rows for appointments starting in {@code [from, to)}, by start then id. */
    @Query("""
        select a, p.fullName, p.phone from DentalAppointment a, DentalPatient p
        where p.id = a.patientId and a.startsAt >= :from and a.startsAt < :to
        order by a.startsAt, a.id
        """)
    List<Object[]> findDetailedBetween(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Deletes the appointment's pre-visit form (cancellation). The form has no entity yet (the public form arrives
     * with the form task, which may swap this for a repository delete), hence the native statement.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "delete from dental.previsit_form where appointment_id = :id", nativeQuery = true)
    int deleteFormOf(@Param("id") UUID id);
}
