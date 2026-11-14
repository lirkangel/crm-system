package com.crm.cccd;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses the CCCD QR payload {@code idNumber|oldIdNumber|fullName|ddMMyyyy|sex|address|ddMMyyyy}.
 * Anything that is not such a payload yields {@link Optional#empty()}.
 */
public final class CccdQrParser {

    private static final int FIELD_COUNT = 7;
    private static final Pattern ID_NUMBER = Pattern.compile("\\d{12}");
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("ddMMuuuu").withResolverStyle(ResolverStyle.STRICT);

    private CccdQrParser() {
    }

    public static Optional<CccdData> parse(String text, LocalDate today) {
        if (text == null) {
            return Optional.empty();
        }
        String[] fields = text.split("\\|", -1);
        if (fields.length != FIELD_COUNT) {
            return Optional.empty();
        }
        for (int i = 0; i < fields.length; i++) {
            fields[i] = fields[i].strip();
        }
        String idNumber = fields[0];
        String fullName = fields[2];
        if (!ID_NUMBER.matcher(idNumber).matches() || fullName.isEmpty()) {
            return Optional.empty();
        }
        LocalDate dob = parseDate(fields[3]);
        LocalDate issue = parseDate(fields[6]);
        if (dob == null || issue == null || dob.isAfter(today) || issue.isAfter(today) || issue.isBefore(dob)) {
            return Optional.empty();
        }
        String oldId = fields[1].isEmpty() ? null : fields[1];
        return Optional.of(new CccdData(idNumber, oldId, fullName, dob, fields[4], fields[5], issue));
    }

    private static LocalDate parseDate(String value) {
        if (value.length() != 8) {
            return null;
        }
        try {
            return LocalDate.parse(value, DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
