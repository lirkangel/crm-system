package com.crm.dental;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Folds names into the accent-insensitive form stored in {@code patient.name_search}. */
public final class Names {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Names() {
    }

    /**
     * Lower-cased, NFD with combining marks removed, {@code đ/Đ} mapped to {@code d} (no decomposition),
     * whitespace collapsed and trimmed.
     *
     * @throws NullPointerException for null
     */
    public static String fold(String text) {
        java.util.Objects.requireNonNull(text, "text");
        String folded = COMBINING_MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        folded = folded.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(folded).replaceAll(" ").trim();
    }
}
