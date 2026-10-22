package com.crm.hotel.repo;

import com.crm.hotel.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    /**
     * Rows of {@code [Room, RoomType]}: in-service rooms of active types with {@code maxGuests >= adults} that
     * have no CONFIRMED reservation overlapping {@code [checkIn, checkOut)} (existing.checkIn &lt; checkOut and
     * existing.checkOut &gt; checkIn, so back-to-back stays don't overlap), by room type code then room number.
     */
    @Query("""
        select r, t from Room r, RoomType t
        where t.id = r.roomTypeId and r.inService = true and t.active = true and t.maxGuests >= :adults
          and not exists (
              select 1 from Reservation x
              where x.roomId = r.id and x.status = com.crm.hotel.domain.ReservationStatus.CONFIRMED
                and x.checkIn < :checkOut and x.checkOut > :checkIn)
        order by t.code, r.number
        """)
    List<Object[]> findAvailable(
        @Param("checkIn") LocalDate checkIn, @Param("checkOut") LocalDate checkOut, @Param("adults") int adults);
}
