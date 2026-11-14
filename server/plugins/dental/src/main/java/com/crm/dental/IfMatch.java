package com.crm.dental;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** The dental API's {@code If-Match} contract for updates, in one place. */
public final class IfMatch {

    private IfMatch() {
    }

    /**
     * Compares the {@code If-Match} version (a bare or quoted number) with the loaded entity's own.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_VERSION_REQUIRED} (428) when the header is
     *         missing, blank or not a number
     * @throws ObjectOptimisticLockingFailureException (409) when it differs from {@code current}
     */
    public static void requireVersion(String ifMatch, Long current, Class<?> entityType, Object id) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw DentalErrors.versionRequired("If-Match with the current version is required");
        }
        long expected;
        try {
            expected = Long.parseLong(ifMatch.trim().replace("\"", ""));
        } catch (NumberFormatException e) {
            throw DentalErrors.versionRequired("If-Match must be the numeric version of the resource");
        }
        if (current == null || expected != current) {
            throw new ObjectOptimisticLockingFailureException(entityType, id);
        }
    }
}
