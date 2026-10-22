package com.crm.hotel;

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
}
