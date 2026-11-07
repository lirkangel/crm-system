package com.crm.hotel.scan;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdScanPurgeJobTest {

    private final IdScanService service = mock(IdScanService.class);
    private final Instant now = Instant.parse("2026-11-07T10:00:00Z");
    private final IdScanPurgeJob job =
        new IdScanPurgeJob(service, Clock.fixed(now, ZoneId.of("Asia/Ho_Chi_Minh")));

    @Test
    void cutoff_is_thirty_days_before_now() {
        job.run();

        verify(service).purgeOlderThan(Instant.parse("2026-10-08T10:00:00Z"));
    }

    @Test
    void failure_does_not_escape_the_scheduler() {
        when(service.purgeOlderThan(any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(job::run).doesNotThrowAnyException();
    }
}
