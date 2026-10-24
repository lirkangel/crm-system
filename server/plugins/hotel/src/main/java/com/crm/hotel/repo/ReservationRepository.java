package com.crm.hotel.repo;

import com.crm.hotel.domain.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /**
     * Blocking (CONFIRMED or CHECKED_IN) reservations of the room overlapping {@code [checkIn, checkOut)}
     * (back-to-back stays don't).
     */
    @Query("""
        select count(x) from HotelReservation x
        where x.roomId = :roomId and x.status in (
              com.crm.hotel.domain.ReservationStatus.CONFIRMED, com.crm.hotel.domain.ReservationStatus.CHECKED_IN)
          and x.checkIn < :checkOut and x.checkOut > :checkIn
        """)
    long countBlockingOverlaps(
        @Param("roomId") UUID roomId, @Param("checkIn") LocalDate checkIn, @Param("checkOut") LocalDate checkOut);

    /** Next number of the reservation code ({@code hotel.reservation_code_seq}; gaps are harmless). */
    @Query(value = "select nextval('hotel.reservation_code_seq')", nativeQuery = true)
    long nextCodeNumber();

    /** The reservation with its row locked ({@code FOR UPDATE}) until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from HotelReservation x where x.id = :id")
    Optional<Reservation> lockById(@Param("id") UUID id);

    /** Rows of {@code [Reservation, guest full name, room number]} for one reservation (empty if unknown). */
    @Query("""
        select x, g.fullName, r.number from HotelReservation x, HotelGuest g, HotelRoom r
        where g.id = x.guestId and r.id = x.roomId and x.id = :id
        """)
    List<Object[]> findDetailed(@Param("id") UUID id);

    /**
     * Rows of {@code [Reservation, guest full name, room number]} for reservations of any status overlapping
     * {@code [from, to)}, by check-in then code.
     */
    @Query("""
        select x, g.fullName, r.number from HotelReservation x, HotelGuest g, HotelRoom r
        where g.id = x.guestId and r.id = x.roomId and x.checkIn < :to and x.checkOut > :from
        order by x.checkIn, x.code
        """)
    List<Object[]> findDetailedOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
