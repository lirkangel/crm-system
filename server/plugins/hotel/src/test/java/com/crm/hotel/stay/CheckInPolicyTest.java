package com.crm.hotel.stay;

import com.crm.foundation.Plugin.PluginProblem;
import com.crm.hotel.domain.ReservationStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

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

    private static LocalDate today(String instant) {
        return CheckInPolicy.today(Clock.fixed(Instant.parse(instant), HOTEL_ZONE));
    }

    @Test
    void checks_in_on_check_in_day() {
        LocalDate today = today("2026-11-10T03:00:00Z");

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
    void vietnam_day_starts_before_utc_midnight() {
        // 23:30 UTC on the 9th is 06:30 on the 10th in Ho Chi Minh City (UTC+7)
        LocalDate today = today("2026-11-09T23:30:00Z");

        assertThat(today).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThatCode(() -> CheckInPolicy.check(ReservationStatus.CONFIRMED, IN, OUT, today))
            .doesNotThrowAnyException();
        // a clock in another zone still gives the hotel-local date
        assertThat(CheckInPolicy.today(Clock.fixed(Instant.parse("2026-11-09T23:30:00Z"), ZoneOffset.UTC)))
            .isEqualTo(LocalDate.of(2026, 11, 10));
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
