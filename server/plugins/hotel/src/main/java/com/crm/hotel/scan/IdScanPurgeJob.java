package com.crm.hotel.scan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/** Hourly retention: CCCD photos are deleted 30 days after upload (identity records are kept). */
@Slf4j
@Component
class IdScanPurgeJob {

    static final int RETENTION_DAYS = 30;

    private final IdScanService scans;
    private final Clock hotelClock;

    IdScanPurgeJob(IdScanService scans, @Qualifier("hotelClock") Clock hotelClock) {
        this.scans = scans;
        this.hotelClock = hotelClock;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Ho_Chi_Minh")
    void run() {
        try {
            scans.purgeOlderThan(hotelClock.instant().minus(RETENTION_DAYS, ChronoUnit.DAYS));
        } catch (RuntimeException e) {
            // Never let the scheduler see it (the next hour retries). The bulk delete binds only the cutoff, so the
            // logged exception carries no personal data.
            log.error("ID scan purge failed", e);
        }
    }
}
