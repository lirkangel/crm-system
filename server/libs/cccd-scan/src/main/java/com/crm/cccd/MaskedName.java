package com.crm.cccd;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** A name for display back to the guest: every word keeps its first letter, the rest becomes {@code *}. */
public final class MaskedName {

    private MaskedName() {
    }

    /**
     * {@code "Nguyễn Văn An"} gives {@code "N***** V** A*"}. Works per Unicode code point (the name is composed
     * first, so a letter with its accent counts once); runs of whitespace collapse to one space.
     */
    public static String of(String name) {
        if (name == null) {
            return "";
        }
        List<String> words = new ArrayList<>();
        for (String word : Normalizer.normalize(name, Normalizer.Form.NFC).trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            int first = word.codePointAt(0);
            int rest = word.codePointCount(Character.charCount(first), word.length());
            words.add(new String(Character.toChars(first)) + "*".repeat(rest));
        }
        return String.join(" ", words);
    }
}
