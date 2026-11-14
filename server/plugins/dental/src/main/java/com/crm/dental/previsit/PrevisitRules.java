package com.crm.dental.previsit;

import com.crm.dental.DentalErrors;
import com.crm.dental.Phones;
import com.crm.foundation.Plugin.PluginProblem;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The form's rules, pure so they are testable without a clock or a database. Every failure is
 * {@code DENTAL_INVALID_FORM} (400) with a fixed message naming the rule: a submitted value is never echoed.
 */
final class PrevisitRules {

    static final int CHILD_UNDER = 14;
    private static final Set<String> ID_TYPES = Set.of("CCCD", "PASSPORT", "OTHER");
    private static final Pattern NATIONALITY = Pattern.compile("[A-Z]{2}");

    private PrevisitRules() {
    }

    /** The request after trimming and normalising; what is stored. */
    record Checked(
            PrevisitRequest.Identity identity,
            String parentName,
            String parentPhone,
            String phone,
            AllergyAnswer allergies,
            MedicineAnswer medicines,
            ReasonAnswer reason) {

        @Override
        public String toString() {
            return "Checked[redacted]";
        }
    }

    /**
     * A patient is a child if under 14 on the visit's clinic-local date: the day before the 14th birthday is a
     * child, the birthday itself an adult. Someone born on 29 February turns 14 on 1 March of a common year (stated
     * here explicitly: {@code plusYears} would answer 28 February).
     */
    static boolean isChild(LocalDate dateOfBirth, LocalDate visitDate) {
        LocalDate fourteenth = dateOfBirth.plusYears(CHILD_UNDER);
        if (dateOfBirth.getMonth() == java.time.Month.FEBRUARY && dateOfBirth.getDayOfMonth() == 29
                && !fourteenth.isLeapYear()) {
            fourteenth = LocalDate.of(fourteenth.getYear(), 3, 1);
        }
        return fourteenth.isAfter(visitDate);
    }

    static Checked check(PrevisitRequest request, boolean child, LocalDate today) {
        if (!request.consent()) {
            throw invalid("Consent to the use of identity and health details is required");
        }
        String phone = phone(request.phone(), "Phone must be a Vietnamese mobile number");

        PrevisitRequest.Identity identity = null;
        String parentName = null;
        String parentPhone = null;
        if (child) {
            parentName = text(request.parentName(), 200, "A parent's name is required for a child under " + CHILD_UNDER);
            parentPhone = phone(request.parentPhone(), "A parent's Vietnamese mobile number is required for a child under " + CHILD_UNDER);
            if (request.identity() != null) {
                identity = identity(request.identity(), today);
            }
        } else {
            if (!isBlank(request.parentName()) || !isBlank(request.parentPhone())) {
                throw invalid("Parent details are only for a child under " + CHILD_UNDER);
            }
            if (request.identity() == null) {
                throw invalid("Identity details are required");
            }
            identity = identity(request.identity(), today);
        }
        return new Checked(identity, parentName, parentPhone, phone,
            allergies(request.allergies()), medicines(request.medicines()), reason(request.reason()));
    }

    private static PrevisitRequest.Identity identity(PrevisitRequest.Identity in, LocalDate today) {
        String idType = in.idType() == null ? null : in.idType().trim();
        if (idType == null || !ID_TYPES.contains(idType)) {
            throw invalid("The identity document type must be CCCD, PASSPORT or OTHER");
        }
        String idNumber = text(in.idNumber(), 32, "The identity document number is required (at most 32 characters)");
        String fullName = text(in.fullName(), 200, "The full name is required (at most 200 characters)");
        if (in.dateOfBirth() == null || !in.dateOfBirth().isBefore(today)) {
            throw invalid("The date of birth must be in the past");
        }
        if (in.nationality() == null || !NATIONALITY.matcher(in.nationality()).matches()) {
            throw invalid("The nationality must be a two-letter country code");
        }
        String address = isBlank(in.address()) ? null : text(in.address(), 500, "The address is at most 500 characters");
        if ("VN".equals(in.nationality()) && address == null) {
            throw invalid("An address is required for Vietnamese citizens");
        }
        return new PrevisitRequest.Identity(idType, idNumber, fullName, in.dateOfBirth(), in.nationality(), address);
    }

    private static AllergyAnswer allergies(AllergyAnswer in) {
        if (in == null) {
            throw invalid("The allergy question must be answered");
        }
        Set<AllergyCode> items = in.items() == null ? Set.of() : in.items();
        if (in.none() == !items.isEmpty()) {
            // none with items, or neither: a blank answer is never taken as "no allergies"
            throw invalid("Allergies need either 'none' or a selection, not both and not neither");
        }
        if (in.none()) {
            if (!isBlank(in.otherText())) {
                throw invalid("Allergy details cannot be given together with 'none'");
            }
            return new AllergyAnswer(true, EnumSet.noneOf(AllergyCode.class), null);
        }
        if (items.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("An allergy selection is invalid");
        }
        EnumSet<AllergyCode> chosen = EnumSet.copyOf(items);
        String other = null;
        if (chosen.contains(AllergyCode.OTHER)) {
            other = freeText(in.otherText(), 500, "Describe the other allergy (at most 500 characters)");
        } else if (!isBlank(in.otherText())) {
            throw invalid("Allergy text is only for the OTHER selection");
        }
        return new AllergyAnswer(false, chosen, other);
    }

    private static MedicineAnswer medicines(MedicineAnswer in) {
        if (in == null) {
            throw invalid("The medicines question must be answered");
        }
        if (in.none() == !isBlank(in.text())) {
            throw invalid("Medicines need either 'none' or a text, not both and not neither");
        }
        return in.none() ? new MedicineAnswer(true, null)
            : new MedicineAnswer(false, freeText(in.text(), 1000, "The medicines text is at most 1000 characters"));
    }

    private static ReasonAnswer reason(ReasonAnswer in) {
        if (in == null || in.code() == null) {
            throw invalid("The reason for the visit is required");
        }
        if (in.code() == ReasonCode.OTHER) {
            return new ReasonAnswer(ReasonCode.OTHER, freeText(in.text(), 500, "Describe the reason (at most 500 characters)"));
        }
        return new ReasonAnswer(in.code(), isBlank(in.text()) ? null : freeText(in.text(), 500, "The reason text is at most 500 characters"));
    }

    private static String phone(String raw, String message) {
        // Phones.normalize strips whitespace, which would let a newline or tab through: no control characters at all
        if (raw != null && raw.chars().anyMatch(Character::isISOControl)) {
            throw invalid(message);
        }
        try {
            return Phones.normalize(raw);
        } catch (PluginProblem e) {
            throw invalid(message);
        }
    }

    /** Identity and contact text: no control character of any kind. */
    private static String text(String value, int max, String message) {
        return clean(value, max, message, false);
    }

    /**
     * The free-text health answers (medicines, other allergy, reason): a patient types several lines, so newline,
     * carriage return and tab are allowed; every other control character is still refused.
     */
    private static String freeText(String value, int max, String message) {
        return clean(value, max, message, true);
    }

    private static String clean(String value, int max, String message, boolean allowLayout) {
        // The control-character check is on the raw value: String.trim() would silently strip a trailing NUL
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > max
                || value.chars().anyMatch(c -> Character.isISOControl(c) && !(allowLayout && isLayout(c)))) {
            throw invalid(message);
        }
        return trimmed;
    }

    private static boolean isLayout(int c) {
        return c == '\n' || c == '\r' || c == '\t';
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static PluginProblem invalid(String detail) {
        return DentalErrors.invalidForm(detail);
    }
}
