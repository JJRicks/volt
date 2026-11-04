package com.jjricks.volt;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * NAVEE's scooter line-up, for the owner to say which scooter a report is about. Scooters report
 * only a numeric Model ID, so the name the owner picks (or types) travels with the report.
 */
final class NaveeModels {
    static final class Series {
        /** about stays English (search, the tests); aboutLabel is the description in the current language. */
        final String name, about; final Msg aboutLabel; final List<String> models;
        Series(String name, Msg about, String... models) { this.name = name; this.aboutLabel = about; this.about = about.english(); this.models = Collections.unmodifiableList(Arrays.asList(models)); }
    }
    static final List<Series> SERIES = Collections.unmodifiableList(Arrays.asList(
            new Series("UT", M.controlsSeriesUt(), "UT3 Max", "UT5 Max", "UT5 Ultra X"),
            new Series("NT", M.controlsSeriesNt(), "NT5 Max", "NT5 Ultra X"),
            new Series("XT", M.controlsSeriesXt(), "XT5 Max", "XT5 Pro", "XT5 Ultra"),
            new Series("N", M.controlsSeriesN(), "N40", "N65", "N65i", "N65i II"),
            new Series("ST", M.controlsSeriesSt(), "ST3", "ST3 Pro", "ST5 Pro", "ST5 Max"),
            new Series("GT", M.controlsSeriesGt(), "GT3", "GT3 Pro", "GT3 Max", "GT5 Pro", "GT5 Max"),
            new Series("G", M.controlsSeriesG(), "G5", "G5 Pro", "G5 Max"),
            new Series("S", M.controlsSeriesS(), "S40", "S60", "S65", "S65C"),
            new Series("V", M.controlsSeriesV(), "V3 Pro", "V25", "V25 Pro", "V25i Pro", "V25i Pro II", "V40", "V40 Pro", "V40i", "V40i Pro",
                    "V40i Pro II", "V40N", "V45i", "V50", "V50i Pro", "V50i Pro II"),
            new Series("EasyRide", M.controlsSeriesEasyRide(), "E20 (EasyRide 20)", "E25 Pro (EasyRide 25 Pro)"),
            new Series("K", M.controlsSeriesK(), "K100", "K100 Pro", "K100 Max", "K100 Plus")));
    static final int MAX_TYPED = 40;
    private NaveeModels() {}

    static List<String> all() {
        List<String> out = new ArrayList<>();
        for (Series s : SERIES) out.addAll(s.models);
        return out;
    }

    /** Lower case without spaces, hyphens or brackets, so "ut5max", "UT5 MAX" and "ut-5 max" all match. */
    static String fold(String text) {
        return text == null ? "" : squeeze(text.toLowerCase(Locale.ROOT));
    }

    private static String squeeze(String lower) {
        return lower.replaceAll("[\\s\\-_()]+", "");
    }

    /**
     * fold for text in the shown language: lower case by the display locale's rules (Turkish İ is i), then
     * accents stripped (NFD without combining marks), so "ninos" finds "Niños". Display matching only.
     */
    static String displayFold(String text) {
        if (text == null) return "";
        String split = Normalizer.normalize(text.toLowerCase(Fmt.displayLocale()), Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(split.length());
        for (int i = 0; i < split.length(); i++) {
            char c = split.charAt(i);
            int type = Character.getType(c);
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK && type != Character.ENCLOSING_MARK) out.append(c);
        }
        return squeeze(out.toString());
    }

    /** Models whose name, or whose series' name or description, contains the query; all models for an empty one. */
    static List<String> search(String query) {
        String q = fold(query);
        List<String> out = new ArrayList<>();
        for (Series s : SERIES) for (String m : s.models)
            if (q.isEmpty() || fold(m).contains(q) || fold(s.about).contains(q) || fold(s.name + "series").startsWith(q)) out.add(m);
        return withShown(query, q, out);
    }

    /**
     * search's English matches plus the models of every series whose description, as the current language shows
     * it, contains the query, in list order. A shown description that differs from the English one is compared
     * with {@link #displayFold}; in English the shown description is the English one, so nothing is added.
     */
    private static List<String> withShown(String query, String q, List<String> english) {
        if (q.isEmpty()) return english;
        String shownQuery = displayFold(query);
        List<String> out = new ArrayList<>();
        for (Series s : SERIES) {
            String label = s.aboutLabel.toString();
            boolean shown = !label.equals(s.about) && !shownQuery.isEmpty() && displayFold(label).contains(shownQuery);
            for (String m : s.models) if (shown || english.contains(m)) out.add(m);
        }
        return out;
    }

    /** The listed model this text names exactly (ignoring case and spacing), or null. */
    static String exact(String text) {
        String q = fold(text);
        if (q.isEmpty()) return null;
        for (String m : all()) if (fold(m).equals(q) || fold(m.replaceAll("\\s*\\(.*\\)", "")).equals(q)) return m;
        return null;
    }

    static Series seriesOf(String model) {
        for (Series s : SERIES) if (s.models.contains(model)) return s;
        return null;
    }

    /** A write-in name, trimmed to printable text and a short length; "" when nothing usable is left. */
    static String typed(String text) {
        if (text == null) return "";
        String clean = text.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return clean.length() > MAX_TYPED ? clean.substring(0, MAX_TYPED).trim() : clean;
    }

    /** What the owner said this scooter is: a listed model, a name they typed, or not sure. */
    static final class Choice {
        enum Source { LIST, TYPED, UNSURE }
        final Source source; final String name;
        private Choice(Source source, String name) { this.source = source; this.name = name; }
        static Choice listed(String model) { if (seriesOf(model) == null) throw new IllegalArgumentException("Not a listed model"); return new Choice(Source.LIST, model); }
        /** A typed name that matches a listed model counts as picking it. */
        static Choice typedName(String text) {
            String match = exact(text); if (match != null) return new Choice(Source.LIST, match);
            String clean = typed(text); return clean.isEmpty() ? unsure() : new Choice(Source.TYPED, clean);
        }
        static Choice unsure() { return new Choice(Source.UNSURE, ""); }
        boolean known() { return source != Source.UNSURE; }
    }
}
