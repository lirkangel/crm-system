package com.crm.dental;

import com.crm.foundation.Plugin.PluginProblem;

/**
 * Stable error codes of the dental API and the problems carrying them (rendered by Foundation as problem+json
 * with the code as a property). Details never carry identity or health values.
 */
public final class DentalErrors {

    public static final String NOT_FOUND = "DENTAL_NOT_FOUND";
    public static final String INVALID_PHONE = "DENTAL_INVALID_PHONE";
    public static final String INVALID_PATIENT = "DENTAL_INVALID_PATIENT";
    public static final String INVALID_APPOINTMENT = "DENTAL_INVALID_APPOINTMENT";
    public static final String INVALID_QUERY = "DENTAL_INVALID_QUERY";
    public static final String INVALID_STATE = "DENTAL_INVALID_STATE";
    public static final String VERSION_REQUIRED = "DENTAL_VERSION_REQUIRED";
    public static final String PUBLIC_DISABLED = "DENTAL_PUBLIC_DISABLED";
    public static final String INVALID_FORM = "DENTAL_INVALID_FORM";
    public static final String VISIT_CLOSED = "DENTAL_VISIT_CLOSED";
    public static final String SCAN_UNREADABLE = "DENTAL_SCAN_UNREADABLE";
    public static final String SCAN_UNSUPPORTED = "DENTAL_SCAN_UNSUPPORTED";
    public static final String SCAN_LIMIT = "DENTAL_SCAN_LIMIT";
    public static final String SCAN_BUSY = "DENTAL_SCAN_BUSY";

    private DentalErrors() {
    }

    /** 404: no such patient, appointment or form. */
    public static PluginProblem notFound(String detail) {
        return new PluginProblem(404, NOT_FOUND, detail);
    }

    /** 400: patient data failed the clinic's rules (name, phone, date of birth). */
    public static PluginProblem invalidPatient(String detail) {
        return new PluginProblem(400, INVALID_PATIENT, detail);
    }

    /** 400: the phone is not a Vietnamese mobile number. The value is never echoed. */
    public static PluginProblem invalidPhone(String detail) {
        return new PluginProblem(400, INVALID_PHONE, detail);
    }

    /** 400: appointment data failed the clinic's rules (time, duration). */
    public static PluginProblem invalidAppointment(String detail) {
        return new PluginProblem(400, INVALID_APPOINTMENT, detail);
    }

    /** 400: malformed query parameters. */
    public static PluginProblem invalidQuery(String detail) {
        return new PluginProblem(400, INVALID_QUERY, detail);
    }

    /** 409: the appointment's status doesn't allow the requested change. */
    public static PluginProblem invalidState(String detail) {
        return new PluginProblem(409, INVALID_STATE, detail);
    }

    /** 428: an update arrived without the {@code If-Match} version. */
    public static PluginProblem versionRequired(String detail) {
        return new PluginProblem(428, VERSION_REQUIRED, detail);
    }

    /** 409: the public patient route is switched off, so no link can be created. */
    public static PluginProblem publicDisabled(String detail) {
        return new PluginProblem(409, PUBLIC_DISABLED, detail);
    }

    /** 400: the pre-visit form failed validation. */
    public static PluginProblem invalidForm(String detail) {
        return new PluginProblem(400, INVALID_FORM, detail);
    }

    /** 409: the pre-visit link or form is closed: the visit day is over or the appointment is no longer booked. */
    public static PluginProblem visitClosed(String detail) {
        return new PluginProblem(409, VISIT_CLOSED, detail);
    }

    /** 400: the upload is not a readable image, or its QR code is missing or not a CCCD's. */
    public static PluginProblem scanUnreadable(String detail) {
        return new PluginProblem(400, SCAN_UNREADABLE, detail);
    }

    /** 415: the upload is neither JPEG nor PNG. */
    public static PluginProblem scanUnsupportedType(String detail) {
        return new PluginProblem(415, SCAN_UNSUPPORTED, detail);
    }

    /** 413: the upload is over 10 MB (same code as the type error: the remedy is the same, retake). */
    public static PluginProblem scanTooLarge(String detail) {
        return new PluginProblem(413, SCAN_UNSUPPORTED, detail);
    }

    /** 409: the appointment already holds the maximum number of scans. */
    public static PluginProblem scanLimit(String detail) {
        return new PluginProblem(409, SCAN_LIMIT, detail);
    }

    /** 503: too many QR decodes are running; retry shortly. */
    public static PluginProblem scanBusy(String detail) {
        return new PluginProblem(503, SCAN_BUSY, detail);
    }
}
