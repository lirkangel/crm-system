package com.crm.hotel.repo;

import com.crm.hotel.domain.Guest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface GuestRepository extends JpaRepository<Guest, UUID> {

    /** {@code pattern} is a LIKE pattern over the search key whose literal {@code % _ \} are backslash-escaped. */
    @Query("select g from HotelGuest g where g.searchKey like :pattern escape '\\' order by g.fullName, g.id")
    List<Guest> search(@Param("pattern") String pattern, Pageable page);
}
