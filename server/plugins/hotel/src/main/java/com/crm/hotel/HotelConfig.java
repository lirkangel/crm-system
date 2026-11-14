package com.crm.hotel;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** Beans of the hotel plugin's child context. */
@Configuration(proxyBeanMethods = false)
public class HotelConfig {

    /** The hotel's time zone: "today" for check-in rules is the local date here, not the server's. */
    public static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Injected wherever the current date matters, so tests can pin it. */
    @Bean
    Clock hotelClock() {
        return Clock.system(HOTEL_ZONE);
    }

    /** Where guests open their personal check-in link, and the hotel name shown to them. */
    @Bean
    GuestSite guestSite(
            @Value("${hotel.guest-base-url:https://checkin.mathisterben.org}") String baseUrl,
            @Value("${hotel.display-name:Khách sạn}") String displayName) {
        return new GuestSite(baseUrl.trim().replaceAll("/+$", ""), displayName);
    }

    /** The cutoff comes from Foundation's environment; a malformed one fails the plugin's start (LOAD_FAILED). */
    @Bean
    BusinessDay businessDay(
            @Qualifier("hotelClock") Clock hotelClock,
            @Value("${" + BusinessDay.CUTOFF_PROPERTY + ":" + BusinessDay.DEFAULT_CUTOFF + "}") String cutoff) {
        return new BusinessDay(hotelClock, BusinessDay.parseCutoff(cutoff));
    }
}
