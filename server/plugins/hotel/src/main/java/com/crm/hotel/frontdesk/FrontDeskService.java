package com.crm.hotel.frontdesk;

import com.crm.hotel.BusinessDay;
import com.crm.hotel.booking.ReservationView;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.repo.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * The receptionist's daily lists. Late arrivals and overstayers stay on them, flagged, rather than vanishing.
 * Each list is one query that joins the guest name and room number.
 */
@Service
@RequiredArgsConstructor
public class FrontDeskService {

    private final BusinessDay businessDay;
    private final ReservationRepository reservations;

    /** Confirmed stays with {@code checkIn <= date < checkOut}; {@code late} when {@code checkIn < date}. */
    @Transactional(readOnly = true)
    public List<FrontDeskEntry> arrivals(LocalDate date) {
        LocalDate day = dayOrToday(date);
        return reservations.findDetailedArrivals(day).stream()
            .map(row -> {
                ReservationView view = view(row);
                return new FrontDeskEntry(view, view.checkIn().isBefore(day), false);
            })
            .toList();
    }

    /** All checked-in stays; {@code overdue} when {@code checkOut < today} (the business date). */
    @Transactional(readOnly = true)
    public List<FrontDeskEntry> inHouse() {
        LocalDate today = businessDay.today();
        return reservations.findDetailedInHouse().stream()
            .map(row -> {
                ReservationView view = view(row);
                return new FrontDeskEntry(view, false, view.checkOut().isBefore(today));
            })
            .toList();
    }

    /** Checked-in stays with {@code checkOut <= date}; {@code overdue} when {@code checkOut < date}. */
    @Transactional(readOnly = true)
    public List<FrontDeskEntry> departures(LocalDate date) {
        LocalDate day = dayOrToday(date);
        return reservations.findDetailedDepartures(day).stream()
            .map(row -> {
                ReservationView view = view(row);
                return new FrontDeskEntry(view, false, view.checkOut().isBefore(day));
            })
            .toList();
    }

    private LocalDate dayOrToday(LocalDate date) {
        return date != null ? date : businessDay.today();
    }

    private static ReservationView view(Object[] row) {
        return ReservationView.of((Reservation) row[0], (String) row[1], (String) row[2]);
    }
}
