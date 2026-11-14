package com.crm.hotel.frontdesk;

import com.crm.hotel.BusinessDay;
import com.crm.hotel.booking.ReservationView;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.repo.PreArrivalGuestRepository;
import com.crm.hotel.repo.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The receptionist's daily lists. Late arrivals and overstayers stay on them, flagged, rather than vanishing.
 * Each list is one query that joins the guest name and room number.
 */
@Service
@RequiredArgsConstructor
public class FrontDeskService {

    private final BusinessDay businessDay;
    private final ReservationRepository reservations;
    private final PreArrivalGuestRepository preArrivals;

    /** Confirmed stays with {@code checkIn <= date < checkOut}; {@code late} when {@code checkIn < date}. */
    @Transactional(readOnly = true)
    public List<FrontDeskEntry> arrivals(LocalDate date) {
        LocalDate day = dayOrToday(date);
        List<ReservationView> views = reservations.findDetailedArrivals(day).stream().map(FrontDeskService::view).toList();
        // One grouped query for the whole list, never one per row
        Map<UUID, Integer> submitted = new HashMap<>();
        if (!views.isEmpty()) {
            for (Object[] row : preArrivals.countByReservationIds(views.stream().map(ReservationView::id).toList())) {
                submitted.put((UUID) row[0], ((Number) row[1]).intValue());
            }
        }
        return views.stream()
            .map(view -> new FrontDeskEntry(
                view, view.checkIn().isBefore(day), false, submitted.getOrDefault(view.id(), 0)))
            .toList();
    }

    /** All checked-in stays; {@code overdue} when {@code checkOut < today} (the business date). */
    @Transactional(readOnly = true)
    public List<FrontDeskEntry> inHouse() {
        LocalDate today = businessDay.today();
        return reservations.findDetailedInHouse().stream()
            .map(row -> {
                ReservationView view = view(row);
                return new FrontDeskEntry(view, false, view.checkOut().isBefore(today), 0);
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
                return new FrontDeskEntry(view, false, view.checkOut().isBefore(day), 0);
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
