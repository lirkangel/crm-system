package com.crm.hotel.booking;

import com.crm.hotel.HotelErrors;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.repo.ReservationRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

/**
 * Writes of a reservation that may collide with another blocking stay of its room: the database's
 * {@code reservation_no_overlap} exclusion constraint is the guarantee, and its violation (a conflicting stay
 * committed after the caller's checks) becomes the friendly {@code HOTEL_ROOM_UNAVAILABLE} (409).
 */
public final class OverlapGuard {

    static final String UNAVAILABLE = "The room is already booked for some of these nights";

    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String NO_OVERLAP_CONSTRAINT = "reservation_no_overlap";

    private OverlapGuard() {
    }

    /**
     * Saves and flushes {@code reservation} (an insert, or an update that moves it to another room).
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_ROOM_UNAVAILABLE} (409) on a
     *         {@code reservation_no_overlap} violation; other violations propagate
     */
    public static Reservation saveAndFlush(ReservationRepository reservations, Reservation reservation) {
        try {
            return reservations.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            if (isNoOverlapViolation(e)) {
                throw HotelErrors.roomUnavailable(UNAVAILABLE);
            }
            throw e;
        }
    }

    static boolean isNoOverlapViolation(Throwable error) {
        boolean exclusionViolation = false;
        boolean namesConstraint = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof ConstraintViolationException hibernate
                    && NO_OVERLAP_CONSTRAINT.equalsIgnoreCase(hibernate.getConstraintName())) {
                namesConstraint = true;
            }
            if (cause instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
                exclusionViolation = true;
                namesConstraint |= sql.getMessage() != null
                    && sql.getMessage().contains('"' + NO_OVERLAP_CONSTRAINT + '"');
            }
        }
        return exclusionViolation && namesConstraint;
    }
}
