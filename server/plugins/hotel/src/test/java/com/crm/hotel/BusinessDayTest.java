package com.crm.hotel;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessDayTest {

    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalTime SIX = LocalTime.of(6, 0);

    /** The business day at {@code localDateTime} (hotel-local, e.g. {@code 2026-11-11T00:30}). */
    private static BusinessDay at(String localDateTime, LocalTime cutoff) {
        Instant instant = LocalDateTime.parse(localDateTime).atZone(HOTEL_ZONE).toInstant();
        return new BusinessDay(Clock.fixed(instant, HOTEL_ZONE), cutoff);
    }

    @Test
    void half_past_midnight_is_still_the_previous_business_date() {
        BusinessDay day = at("2026-11-11T00:30", SIX);

        assertThat(day.today()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(day.calendarToday()).isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    void half_past_six_is_the_new_business_date() {
        BusinessDay day = at("2026-11-11T06:30", SIX);

        assertThat(day.today()).isEqualTo(LocalDate.of(2026, 11, 11));
        assertThat(day.calendarToday()).isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    void the_cutoff_itself_starts_the_new_business_date() {
        assertThat(at("2026-11-11T05:59:59", SIX).today()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(at("2026-11-11T06:00", SIX).today()).isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    void midnight_cutoff_is_the_calendar_date() {
        BusinessDay day = at("2026-11-11T00:30", LocalTime.MIDNIGHT);

        assertThat(day.today()).isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    void a_clock_in_another_zone_still_gives_the_hotel_date() {
        // 23:30 UTC on the 10th is 06:30 on the 11th in Ho Chi Minh City (UTC+7)
        BusinessDay day = new BusinessDay(Clock.fixed(Instant.parse("2026-11-10T23:30:00Z"), ZoneOffset.UTC), SIX);

        assertThat(day.today()).isEqualTo(LocalDate.of(2026, 11, 11));
        assertThat(day.calendarToday()).isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    void a_check_in_at_23_55_is_on_the_same_business_date_at_00_05() {
        BusinessDay fiveAfterMidnight = at("2026-11-11T00:05", SIX);
        Instant fiveToMidnight = LocalDateTime.parse("2026-11-10T23:55").atZone(HOTEL_ZONE).toInstant();
        Instant sevenInTheMorning = LocalDateTime.parse("2026-11-10T07:00").atZone(HOTEL_ZONE).toInstant();

        assertThat(fiveAfterMidnight.dateOf(fiveToMidnight)).isEqualTo(fiveAfterMidnight.today());
        assertThat(fiveAfterMidnight.dateOf(sevenInTheMorning)).isEqualTo(fiveAfterMidnight.today());
        // but not once the next business date has started
        assertThat(at("2026-11-11T06:05", SIX).dateOf(fiveToMidnight)).isEqualTo(LocalDate.of(2026, 11, 10))
            .isNotEqualTo(at("2026-11-11T06:05", SIX).today());
    }

    @Test
    void parses_the_cutoff_setting() {
        assertThat(BusinessDay.parseCutoff("06:00")).isEqualTo(SIX);
        assertThat(BusinessDay.parseCutoff(" 04:30 ")).isEqualTo(LocalTime.of(4, 30));
        assertThat(BusinessDay.parseCutoff("00:00")).isEqualTo(LocalTime.MIDNIGHT);
        for (String bad : new String[] {"6", "25:00", "06:00:30", "six", ""}) {
            assertThatThrownBy(() -> BusinessDay.parseCutoff(bad))
                .as(bad)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hotel.business-day-cutoff");
        }
    }
}
