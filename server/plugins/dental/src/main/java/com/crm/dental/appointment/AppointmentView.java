package com.crm.dental.appointment;

import com.crm.dental.DentalConfig;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An appointment as served to staff. {@code startsAt} carries the clinic's UTC offset. {@code previsit} is
 * {@code NONE} or {@code SUBMITTED} (a pre-visit form exists).
 */
public record AppointmentView(
        UUID id,
        UUID patientId,
        String patientName,
        String patientPhone,
        OffsetDateTime startsAt,
        int durationMinutes,
        String dentist,
        String reason,
        String status,
        String previsit,
        Instant createdAt,
        long version) {

    public static AppointmentView of(Appointment appointment, String patientName, String patientPhone) {
        return new AppointmentView(appointment.getId(), appointment.getPatientId(), patientName, patientPhone,
            appointment.getStartsAt().atZone(DentalConfig.CLINIC_ZONE).toOffsetDateTime(),
            appointment.getDurationMinutes(), appointment.getDentist(), appointment.getReason(),
            appointment.getStatus().name(), "NONE", appointment.getCreatedAt(), appointment.getVersion());
    }

    public AppointmentView withPrevisit(String previsit) {
        return new AppointmentView(id, patientId, patientName, patientPhone, startsAt, durationMinutes, dentist, reason,
            status, previsit, createdAt, version);
    }

    /** An {@code [Appointment, patient full name, patient phone]} row of the repository's detailed queries. */
    static AppointmentView of(Object[] row) {
        return of((Appointment) row[0], (String) row[1], (String) row[2]);
    }
}
