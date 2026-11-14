package com.crm.dental;

import com.crm.foundation.Plugin.PluginProblem;

/**
 * Stable error codes of the dental API and the problems carrying them (rendered by Foundation as problem+json
 * with the code as a property). Details never carry identity or health values.
 */
public final class DentalErrors {

    public static final String NOT_FOUND = "DENTAL_NOT_FOUND";
    public static final String INVALID_PATIENT = "DENTAL_INVALID_PATIENT";
    public static final String INVALID_APPOINTMENT = "DENTAL_INVALID_APPOINTMENT";
    public static final String INVALID_QUERY = "DENTAL_INVALID_QUERY";
    public static final String INVALID_STATE = "DENTAL_INVALID_STATE";
    public static final String VERSION_REQUIRED = "DENTAL_VERSION_REQUIRED";
    public static final String PUBLIC_DISABLED = "DENTAL_PUBLIC_DISABLED";
    public static final String INVALID_FORM = "DENTAL_INVALID_FORM";

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
}
