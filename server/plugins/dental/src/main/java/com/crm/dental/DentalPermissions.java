package com.crm.dental;

/** Permission keys; they must match {@code permissions-declared} in {@code plugin.yaml}. */
public final class DentalPermissions {

    public static final String PATIENTS_READ = "dental.patients.read";
    public static final String PATIENTS_WRITE = "dental.patients.write";
    public static final String APPOINTMENTS_READ = "dental.appointments.read";
    public static final String APPOINTMENTS_WRITE = "dental.appointments.write";
    /** Pre-visit forms hold health answers and identity data, hence their own permission. */
    public static final String PREVISIT_READ = "dental.previsit.read";

    private DentalPermissions() {
    }
}
