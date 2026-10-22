package com.crm.hotel.booking;

import com.crm.hotel.repo.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** Human-readable reservation codes such as {@code R-2026-000123}. */
@Component
@RequiredArgsConstructor
public class ReservationCodeGenerator {

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock hotelClock;
    private final ReservationRepository reservations;

    /** {@code "R-" + year of the hotel's today + "-" + the next sequence number, zero-padded to six digits}. */
    public String next() {
        return String.format("R-%d-%06d", LocalDate.now(hotelClock).getYear(), reservations.nextCodeNumber());
    }
}
