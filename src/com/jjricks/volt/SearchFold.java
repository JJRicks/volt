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
    /** What the shortcut search puts between a shortcut's name and its group, so a query can span both. */
    static final String SPACE = " ";

    private SearchFold() {}

    static String fold(String text) {
        String lower = text.toLowerCase(Fmt.locale(Locale.ROOT));
        if (Fmt.legacy()) return lower;
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    /** True when text, folded, holds the query, which {@link #fold} already folded. */
    static boolean holds(String text, String foldedQuery) {
        return fold(text).contains(foldedQuery);
    }
}
