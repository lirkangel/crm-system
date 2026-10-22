package com.crm.hotel.guest;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Folds guest names and phones into the accent-insensitive form stored in {@code guest.search_key}. */
public final class GuestSearchKey {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private GuestSearchKey() {
    }

    /**
     * NFD, combining marks removed, {@code đ/Đ} mapped to {@code d} (they have no
     * decomposition), lower-cased, whitespace collapsed and trimmed; null yields "".
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String folded = COMBINING_MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        folded = folded.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(folded).replaceAll(" ").trim();
    }

    /** The key stored for a guest: the normalized name and phone. */
    public static String of(String fullName, String phone) {
        return normalize(fullName + " " + phone);
    }
}
