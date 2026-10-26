package com.crm.hotel.stay;

import com.crm.foundation.Plugin.PluginProblem;
import com.crm.hotel.BusinessDay;
import com.crm.hotel.domain.ReservationStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckInPolicyTest {

    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate IN = LocalDate.of(2026, 11, 10);
    private static final LocalDate OUT = IN.plusDays(3);

    private static void assertProblem(Throwable thrown, int status, String code) {
        assertThat(thrown).isInstanceOf(PluginProblem.class);
        PluginProblem problem = (PluginProblem) thrown;
        assertThat(problem.status()).isEqualTo(status);
        assertThat(problem.code()).isEqualTo(code);
    }

    /** The business date (06:00 cutoff) at {@code localDateTime}, hotel-local. */
    private static LocalDate today(String localDateTime) {
        Instant instant = LocalDateTime.parse(localDateTime).atZone(HOTEL_ZONE).toInstant();
        return new BusinessDay(Clock.fixed(instant, HOTEL_ZONE), LocalTime.of(6, 0)).today();
    }

    @Test
    void checks_in_on_check_in_day() {
        LocalDate today = today("2026-11-10T10:00");

        assertThat(today).isEqualTo(IN);
        assertThatCode(() -> CheckInPolicy.check(ReservationStatus.CONFIRMED, IN, OUT, today))
            .doesNotThrowAnyException();
    }

    @Test
    void late_arrival_allowed() {
        assertThatCode(() -> CheckInPolicy.check(ReservationStatus.CONFIRMED, IN, OUT, IN.plusDays(1)))
            .doesNotThrowAnyException();
        assertThatCode(() -> CheckInPolicy.check(ReservationStatus.CONFIRMED, IN, OUT, OUT.minusDays(1)))
            .doesNotThrowAnyException();
    }

    @Test
    void early_is_not_checkin_day() {
        assertThatThrownBy(() -> CheckInPolicy.check(ReservationStatus.CONFIRMED, IN, OUT, IN.minusDays(1)))
            .satisfies(e -> assertProblem(e, 409, "HOTEL_NOT_CHECKIN_DAY"));
    }

    @Test
    void on_or_after_check_out_is_not_checkin_day() {
        assertThatThrownBy(() -> CheckInPolicy.check(ReservationStatus.CONFIRMED, IN, OUT, OUT))
            .satisfies(e -> assertProblem(e, 409, "HOTEL_NOT_CHECKIN_DAY"));
        assertThatThrownBy(() -> CheckInPolicy.check(ReservationStatus.CONFIRMED, IN, OUT, OUT.plusDays(2)))
            .satisfies(e -> assertProblem(e, 409, "HOTEL_NOT_CHECKIN_DAY"));
    }

    @Test
    void only_confirmed() {
        for (ReservationStatus status : ReservationStatus.values()) {
            if (status == ReservationStatus.CONFIRMED) {
                continue;
            }
            assertThatThrownBy(() -> CheckInPolicy.check(status, IN, OUT, IN))
                .as(status.name())
                .satisfies(e -> assertProblem(e, 409, "HOTEL_INVALID_STATE"));
        }
        // the state is reported before the day
        assertThatThrownBy(() -> CheckInPolicy.check(ReservationStatus.CHECKED_IN, IN, OUT, IN.minusDays(5)))
            .satisfies(e -> assertProblem(e, 409, "HOTEL_INVALID_STATE"));
    }

    @Test
    void midnight_arrival_on_the_check_out_date_still_checks_in() {
        // a one-night stay of the 10th; the guest arrives at 00:30 on the 11th, the business date is still the 10th
        LocalDate today = today("2026-11-11T00:30");

        assertThat(today).isEqualTo(IN);
        assertThatCode(() -> CheckInPolicy.check(ReservationStatus.CONFIRMED, IN, IN.plusDays(1), today))
            .doesNotThrowAnyException();
    }

    @Test
    void after_the_cutoff_the_check_out_date_is_not_checkin_day() {
        LocalDate today = today("2026-11-11T06:30");

        assertThat(today).isEqualTo(IN.plusDays(1));
        assertThatThrownBy(() -> CheckInPolicy.check(ReservationStatus.CONFIRMED, IN, IN.plusDays(1), today))
            .satisfies(e -> assertProblem(e, 409, "HOTEL_NOT_CHECKIN_DAY"));
    }

    @Test
    void before_the_cutoff_the_check_in_date_has_not_come() {
        // 05:30 on the 10th is still business date the 9th: too early for a stay starting the 10th
        assertThatThrownBy(() -> CheckInPolicy.check(ReservationStatus.CONFIRMED, IN, OUT, today("2026-11-10T05:30")))
            .satisfies(e -> assertProblem(e, 409, "HOTEL_NOT_CHECKIN_DAY"));
    }

    @Test
    void vietnamese_adult_needs_an_address() {
        LocalDate today = LocalDate.of(2026, 11, 10);

        assertThatThrownBy(() -> CheckInPolicy.checkIdentity(adult("VN", null, null), today))
            .satisfies(e -> assertProblem(e, 400, "HOTEL_INVALID_ID_DOC"));
        assertThatThrownBy(() -> CheckInPolicy.checkIdentity(adult("VN", "   ", null), today))
            .satisfies(e -> assertProblem(e, 400, "HOTEL_INVALID_ID_DOC"));
        assertThatCode(() -> CheckInPolicy.checkIdentity(adult("VN", "1 Lê Lợi, Huế", null), today))
            .doesNotThrowAnyException();
        assertThatCode(() -> CheckInPolicy.checkIdentity(adult("FR", null, today), today))
            .doesNotThrowAnyException();
    }

    @Test
    void entry_date_must_not_be_in_the_future() {
        LocalDate today = LocalDate.of(2026, 11, 10);

        assertThatThrownBy(() -> CheckInPolicy.checkIdentity(adult("FR", null, today.plusDays(1)), today))
            .satisfies(e -> assertProblem(e, 400, "HOTEL_INVALID_ID_DOC"));
    }

    private static StayGuestRequest adult(String nationality, String address, LocalDate entryDate) {
        return new StayGuestRequest(IdDocType.PASSPORT, "P1234567", "Test Adult", LocalDate.of(1990, 1, 1),
            nationality, address, null, entryDate);
    }
}
