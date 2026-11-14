package com.crm.dental.appointment;

/** Only {@code BOOKED} can still change; the other three are final. */
public enum AppointmentStatus {
    BOOKED, ARRIVED, CANCELLED, NO_SHOW
}
