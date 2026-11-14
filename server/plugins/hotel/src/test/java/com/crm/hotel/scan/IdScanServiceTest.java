package com.crm.hotel.scan;

import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.foundation.Plugin.PluginProblem;
import com.crm.hotel.BusinessDay;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.domain.ReservationStatus;
import com.crm.hotel.repo.IdScanRepository;
import com.crm.hotel.repo.PreArrivalGuestRepository;
import com.crm.hotel.repo.ReservationRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IdScanServiceTest {

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final IdScanStore store = mock(IdScanStore.class);
    private final ScanPipeline pipeline = mock(ScanPipeline.class);
    private final IdScanService service = new IdScanService(
        new BusinessDay(Clock.systemUTC(), LocalTime.of(6, 0)), reservations, store, mock(IdScanRepository.class),
        mock(PreArrivalGuestRepository.class), pipeline, mock(AuditRecorder.class));
    private final UUID id = UUID.randomUUID();

    private void confirmed() {
        Reservation reservation = new Reservation();
        reservation.setStatus(ReservationStatus.CONFIRMED);
        when(reservations.findById(id)).thenReturn(Optional.of(reservation));
    }

    @Test
    void scanner_busy_maps_to_503_hotel_scan_busy() {
        confirmed();
        when(pipeline.process(any())).thenThrow(new ScanBusyException());

        assertThatThrownBy(() -> service.upload(id, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}))
            .isInstanceOfSatisfying(PluginProblem.class, problem -> {
                assertThat(problem.status()).isEqualTo(503);
                assertThat(problem.code()).isEqualTo("HOTEL_SCAN_BUSY");
            });
        verifyNoInteractions(store);
    }

    @Test
    void over_10_mb_is_413_without_decoding() {
        confirmed();

        assertThatThrownBy(() -> service.upload(id, new byte[10 * 1024 * 1024 + 1]))
            .isInstanceOfSatisfying(PluginProblem.class, problem -> {
                assertThat(problem.status()).isEqualTo(413);
                assertThat(problem.code()).isEqualTo("HOTEL_SCAN_UNSUPPORTED");
            });
        verifyNoInteractions(pipeline, store);
    }
}
