package com.crm.hotel;

import com.crm.foundation.Plugin.PluginProblem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HotelErrorsTest {

    private static void assertProblem(PluginProblem problem, int status, String code) {
        assertThat(problem.status()).isEqualTo(status);
        assertThat(problem.code()).isEqualTo(code);
        assertThat(problem.getMessage()).isEqualTo("detail");
    }

    @Test
    void factories_carry_status_and_stable_code() {
        assertProblem(HotelErrors.invalidStay("detail"), 400, "HOTEL_INVALID_STAY");
        assertProblem(HotelErrors.tooManyGuests("detail"), 400, "HOTEL_TOO_MANY_GUESTS");
        assertProblem(HotelErrors.roomNotBookable("detail"), 409, "HOTEL_ROOM_NOT_BOOKABLE");
        assertProblem(HotelErrors.roomUnavailable("detail"), 409, "HOTEL_ROOM_UNAVAILABLE");
        assertProblem(HotelErrors.notFound("detail"), 404, "HOTEL_NOT_FOUND");
        assertProblem(HotelErrors.alreadyCancelled("detail"), 409, "HOTEL_ALREADY_CANCELLED");
        assertProblem(HotelErrors.duplicate("detail"), 409, "HOTEL_DUPLICATE");
        assertProblem(HotelErrors.versionRequired("detail"), 428, "HOTEL_VERSION_REQUIRED");
        assertProblem(HotelErrors.invalidQuery("detail"), 400, "HOTEL_INVALID_QUERY");
        assertProblem(HotelErrors.invalidGuest("detail"), 400, "HOTEL_INVALID_GUEST");
        assertProblem(HotelErrors.roomOccupied("detail"), 409, "HOTEL_ROOM_OCCUPIED");
        assertProblem(HotelErrors.paymentsOnFolio("detail"), 409, "HOTEL_PAYMENTS_ON_FOLIO");
        assertProblem(HotelErrors.invalidState("detail"), 409, "HOTEL_INVALID_STATE");
        assertProblem(HotelErrors.notCheckInDay("detail"), 409, "HOTEL_NOT_CHECKIN_DAY");
        assertProblem(HotelErrors.balanceDue("detail"), 409, "HOTEL_BALANCE_DUE");
        assertProblem(HotelErrors.overpayment("detail"), 409, "HOTEL_OVERPAYMENT");
        assertProblem(HotelErrors.invalidIdDoc("detail"), 400, "HOTEL_INVALID_ID_DOC");
        assertProblem(HotelErrors.invalidPayment("detail"), 400, "HOTEL_INVALID_PAYMENT");
        assertProblem(HotelErrors.invalidSource("detail"), 400, "HOTEL_INVALID_SOURCE");
        assertProblem(HotelErrors.scanUnreadable("detail"), 400, "HOTEL_SCAN_UNREADABLE");
        assertProblem(HotelErrors.scanUnsupportedType("detail"), 415, "HOTEL_SCAN_UNSUPPORTED");
        assertProblem(HotelErrors.scanTooLarge("detail"), 413, "HOTEL_SCAN_UNSUPPORTED");
        assertProblem(HotelErrors.scanMismatch("detail"), 409, "HOTEL_SCAN_MISMATCH");
        assertProblem(HotelErrors.scanLimit("detail"), 409, "HOTEL_SCAN_LIMIT");
        assertProblem(HotelErrors.scanBusy("detail"), 503, "HOTEL_SCAN_BUSY");
        assertProblem(HotelErrors.checkinClosed("detail"), 409, "HOTEL_CHECKIN_CLOSED");
    }

    @Test
    void hotel_clock_runs_in_ho_chi_minh_time() {
        assertThat(new HotelConfig().hotelClock().getZone().getId()).isEqualTo("Asia/Ho_Chi_Minh");
    }
}
