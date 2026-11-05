package com.jjricks.volt;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Folds a search box's text and the names it searches so they compare in the shown language (plan section 4.6):
 * lowercase by the display locale, then NFD with the combining marks stripped, so "cafe" finds "Café" and a Turkish
 * "I" finds "ı". Display only: a folded string is compared and dropped, never shown, stored or logged.
 *
 * While Volt is English this is exactly toLowerCase(Locale.ROOT), so what a search finds does not change.
 */
final class SearchFold {
    private SearchFold() {}

    static String fold(String text) {
        String lower = text.toLowerCase(Fmt.locale(Locale.ROOT));
        if (Fmt.legacy()) return lower;
        String split = Normalizer.normalize(lower, Normalizer.Form.NFD);
        StringBuilder folded = new StringBuilder(split.length());
        for (int i = 0; i < split.length(); ) {
            int c = split.codePointAt(i);
            i += Character.charCount(c);
            int type = Character.getType(c);
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK && type != Character.ENCLOSING_MARK)
                folded.appendCodePoint(c);
        }
        return folded.toString();
    }

    /** True when text, folded, holds the query, which {@link #fold} already folded. */
    static boolean holds(String text, String foldedQuery) {
        return fold(text).contains(foldedQuery);
    }

    /** A name and its group, with a space between them so a query can span both. */
    static boolean holds(String name, String group, String foldedQuery) {
        return holds(name + " " + group, foldedQuery);
    }

    /**
     * The same for names in the shown language, beside the English ones a search already looks in. Volt in English
     * has only those, so this finds nothing more and what a search finds there does not change.
     */
    static boolean holdsShown(String name, String group, String foldedQuery) {
        return !Fmt.legacy() && holds(name, group, foldedQuery);
    }
}
