package com.crm.dental;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Vietnamese mobile numbers in one stored form, {@code +84} followed by nine digits. */
public final class Phones {

    /** {@code 0} or {@code +84}, then a mobile prefix (3, 5, 7, 8, 9) and eight more digits. */
    private static final Pattern MOBILE = Pattern.compile("(?:\\+84|0)([35789]\\d{8})");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-]");

    private Phones() {
    }

    /**
     * Accepts {@code 0xxxxxxxxx} and {@code +84xxxxxxxxx}, tolerating spaces, dots and dashes, and returns
     * {@code +84xxxxxxxxx}.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_INVALID_PHONE} (400) for anything else; the
     *         message never carries the input
     */
    public static String normalize(String raw) {
        if (raw != null) {
            Matcher matcher = MOBILE.matcher(SEPARATORS.matcher(raw.trim()).replaceAll(""));
            if (matcher.matches()) {
                return "+84" + matcher.group(1);
            }
        }
        throw DentalErrors.invalidPhone("Phone must be a Vietnamese mobile number: 0 or +84 followed by a mobile prefix and the rest of the digits");
    }
}
