package com.crm.hotel.booking;

import com.crm.foundation.Plugin.PluginProblem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StayPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private static void assertProblem(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOf(PluginProblem.class);
        PluginProblem problem = (PluginProblem) thrown;
        assertThat(problem.status()).isEqualTo(400);
        assertThat(problem.code()).isEqualTo(code);
    }

    @Test
    void checkout_before_or_on_checkin_rejected() {
        LocalDate in = TODAY.plusDays(5);

        assertThatThrownBy(() -> StayPolicy.check(in, in, 1, 2, TODAY))
            .satisfies(e -> assertProblem(e, "HOTEL_INVALID_STAY"));
        assertThatThrownBy(() -> StayPolicy.check(in, in.minusDays(1), 1, 2, TODAY))
            .satisfies(e -> assertProblem(e, "HOTEL_INVALID_STAY"));
    }

    @Test
    void missing_dates_rejected() {
        assertThatThrownBy(() -> StayPolicy.check(null, TODAY.plusDays(1), 1, 2, TODAY))
            .satisfies(e -> assertProblem(e, "HOTEL_INVALID_STAY"));
        assertThatThrownBy(() -> StayPolicy.check(TODAY, null, 1, 2, TODAY))
            .satisfies(e -> assertProblem(e, "HOTEL_INVALID_STAY"));
    }

    @Test
    void sixty_nights_ok_sixty_one_rejected() {
        LocalDate in = TODAY.plusDays(3);

        assertThatCode(() -> StayPolicy.check(in, in.plusDays(60), 1, 2, TODAY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> StayPolicy.check(in, in.plusDays(61), 1, 2, TODAY))
            .satisfies(e -> assertProblem(e, "HOTEL_INVALID_STAY"));
        assertThatThrownBy(() -> StayPolicy.check(LocalDate.MIN.plusDays(1), LocalDate.MAX, 1, 2, TODAY))
            .satisfies(e -> assertProblem(e, "HOTEL_INVALID_STAY"));
    }

    @Test
    void past_checkin_rejected_today_ok() {
        assertThatCode(() -> StayPolicy.check(TODAY, TODAY.plusDays(1), 1, 2, TODAY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> StayPolicy.check(TODAY.minusDays(1), TODAY.plusDays(1), 1, 2, TODAY))
            .satisfies(e -> assertProblem(e, "HOTEL_INVALID_STAY"));
    }

    @Test
    void adults_over_capacity_rejected() {
        LocalDate in = TODAY.plusDays(1);
        LocalDate out = TODAY.plusDays(2);

        assertThatCode(() -> StayPolicy.check(in, out, 2, 2, TODAY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> StayPolicy.check(in, out, 3, 2, TODAY))
            .satisfies(e -> assertProblem(e, "HOTEL_TOO_MANY_GUESTS"));
        assertThatThrownBy(() -> StayPolicy.check(in, out, 0, 2, TODAY))
            .satisfies(e -> assertProblem(e, "HOTEL_TOO_MANY_GUESTS"));
    }

    @Test
    void invalid_dates_win_over_capacity() {
        assertThatThrownBy(() -> StayPolicy.check(TODAY, TODAY, 9, 2, TODAY))
            .satisfies(e -> assertProblem(e, "HOTEL_INVALID_STAY"));
    }

    @Test
    void total_is_rate_times_nights() {
        assertThat(StayPolicy.totalGross(1_200_000, 3)).isEqualTo(3_600_000L);
        assertThat(StayPolicy.totalGross(0, 7)).isZero();
    }

    @Test
    void total_overflow_is_a_400_not_a_silent_wrap() {
        assertThatThrownBy(() -> StayPolicy.totalGross(Long.MAX_VALUE, 2))
            .satisfies(e -> assertProblem(e, "HOTEL_INVALID_STAY"));
    }

    @Test
    void back_to_back_nights_count() {
        assertThat(StayPolicy.nights(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12))).isEqualTo(2);
        assertThat(StayPolicy.nights(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2))).isEqualTo(3);
    }
}
