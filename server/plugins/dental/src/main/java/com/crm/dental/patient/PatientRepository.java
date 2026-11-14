package com.crm.dental.patient;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    @Query("select p from DentalPatient p where p.phone = :phone order by p.fullName, p.id")
    List<Patient> findByPhone(@Param("phone") String phone, Pageable page);

    /** {@code pattern} is a lower-case LIKE pattern whose literal {@code % _ \} are backslash-escaped. */
    @Query("select p from DentalPatient p where lower(p.fullName) like :pattern escape '\\' order by p.fullName, p.id")
    List<Patient> findByNameLike(@Param("pattern") String pattern, Pageable page);

    @Query("""
        select p from DentalPatient p
        where p.phone = :phone and lower(p.fullName) like :pattern escape '\\'
        order by p.fullName, p.id
        """)
    List<Patient> findByPhoneAndNameLike(
        @Param("phone") String phone, @Param("pattern") String pattern, Pageable page);
}
