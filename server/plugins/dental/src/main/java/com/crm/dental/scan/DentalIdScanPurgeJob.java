package com.crm.dental.scan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/** Hourly retention: CCCD photos are deleted 30 days after upload (forms are kept). */
@Slf4j
@Component
class DentalIdScanPurgeJob {

    static final int RETENTION_DAYS = 30;

    private final DentalIdScanService scans;
    private final Clock dentalClock;

    DentalIdScanPurgeJob(DentalIdScanService scans, @Qualifier("dentalClock") Clock dentalClock) {
        this.scans = scans;
        this.dentalClock = dentalClock;
    }

    /** At minute 5, so it does not contend with the hotel's purge (minute 0) for the 2-thread scheduler. */
    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Ho_Chi_Minh")
    void run() {
        try {
            scans.purgeOlderThan(dentalClock.instant().minus(RETENTION_DAYS, ChronoUnit.DAYS));
        } catch (RuntimeException e) {
            // Never let the scheduler see it (the next hour retries). The bulk delete binds only the cutoff, so the
            // logged exception carries no personal data.
            log.error("ID scan purge failed", e);
        }
    }
}
