package com.crm.hotel.repo;

import com.crm.hotel.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    /**
     * Rows of {@code [Room, RoomType]}: in-service rooms of active types with {@code maxGuests >= adults} that
     * have no blocking (CONFIRMED or CHECKED_IN) reservation overlapping {@code [checkIn, checkOut)} (existing.checkIn &lt; checkOut and
     * existing.checkOut &gt; checkIn, so back-to-back stays don't overlap), by room type code then room number.
     */
    @Query("""
        select r, t from HotelRoom r, HotelRoomType t
        where t.id = r.roomTypeId and r.inService = true and t.active = true and t.maxGuests >= :adults
          and not exists (
              select 1 from HotelReservation x
              where x.roomId = r.id and x.status in (
                    com.crm.hotel.domain.ReservationStatus.CONFIRMED,
                    com.crm.hotel.domain.ReservationStatus.CHECKED_IN)
                and x.checkIn < :checkOut and x.checkOut > :checkIn)
        order by t.code, r.number
        """)
    List<Object[]> findAvailable(
        @Param("checkIn") LocalDate checkIn, @Param("checkOut") LocalDate checkOut, @Param("adults") int adults);

    /**
     * The room with its row locked {@code FOR NO KEY UPDATE} until the transaction ends, so bookings of one
     * room queue up here instead of racing (and room edits wait for them). Unlike {@code FOR UPDATE} (what
     * JPA's {@code PESSIMISTIC_WRITE} gives) it does not block inserts that only reference the room.
     */
    @Query(value = "select * from hotel.room where id = :id for no key update", nativeQuery = true)
    Optional<Room> lockById(@Param("id") UUID id);
}
