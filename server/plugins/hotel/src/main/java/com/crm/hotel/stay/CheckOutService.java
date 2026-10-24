package com.crm.hotel.stay;

import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.foundation.Plugin.ChangeOp;
import com.crm.foundation.Plugin.ChangeRecorder;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.booking.ReservationView;
import com.crm.hotel.domain.Guest;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.domain.ReservationStatus;
import com.crm.hotel.domain.Room;
import com.crm.hotel.folio.FolioService;
import com.crm.hotel.repo.GuestRepository;
import com.crm.hotel.repo.ReservationRepository;
import com.crm.hotel.repo.RoomRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Check-out: a checked-in stay with a zero folio balance becomes {@code CHECKED_OUT}. Early and late check-outs are
 * both fine; the status change alone frees the room (checked-out stays don't block), and the folio keeps the full
 * booked room charge.
 */
@Service
@RequiredArgsConstructor
public class CheckOutService {

    static final String RESERVATION = "Reservation";
    static final String AUDIT_CHECK_OUT = "RESERVATION_CHECK_OUT";

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock hotelClock;
    private final ReservationRepository reservations;
    private final RoomRepository rooms;
    private final GuestRepository guests;
    private final FolioService folios;
    private final ChangeRecorder changes;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;

    /**
     * Checks a stay out, in one transaction.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND} (404); {@code HOTEL_INVALID_STATE}
     *         (409) unless {@code CHECKED_IN}; {@code HOTEL_BALANCE_DUE} (409) while the folio balance isn't zero
     */
    @Transactional
    public ReservationView checkOut(UUID reservationId) {
        // The reservation lock first: every folio write takes it too, so the balance can't change under us.
        Reservation reservation = reservations.lockById(reservationId)
            .orElseThrow(() -> HotelErrors.notFound("Reservation not found"));
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw HotelErrors.invalidState("Only a checked-in stay can be checked out; this one is " + reservation.getStatus());
        }
        long balance = folios.balance(reservationId);
        if (balance != 0) {
            throw HotelErrors.balanceDue("balance due: " + balance + " VND");
        }

        String guestName = guests.findById(reservation.getGuestId()).map(Guest::getFullName).orElse(null);
        String roomNumber = rooms.findById(reservation.getRoomId()).map(Room::getNumber).orElse(null);
        String before = json(ReservationView.of(reservation, guestName, roomNumber));

        reservation.setStatus(ReservationStatus.CHECKED_OUT);
        reservation.setCheckedOutAt(Instant.now(hotelClock).truncatedTo(ChronoUnit.MICROS));
        Reservation saved = reservations.saveAndFlush(reservation);
        changes.record(RESERVATION, saved.getId(), saved.getVersion(), ChangeOp.UPDATE);

        ReservationView view = ReservationView.of(saved, guestName, roomNumber);
        audit.record(RESERVATION, saved.getId(), AUDIT_CHECK_OUT, before, json(view));
        return view;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Check-out could not be serialized for the audit log", e);
        }
    }
}
