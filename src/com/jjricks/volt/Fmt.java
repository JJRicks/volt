package com.jjricks.volt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

/**
 * Display-tier number, unit and date formatting (plan section 4). Data (journals, CSV, JSON, logs,
 * Telemetry values) never comes here: it keeps Locale.ROOT.
 *
 * While the resolved language is English (also an unshipped phone language, or a pseudo-locale's
 * mangled l10n_tag), every call site keeps exactly what it did before localization: the locale it
 * passes as "legacy" (Locale.US, Locale.ROOT or Locale.getDefault()) and its legacy date pattern.
 * So en-GB, en-IN and a German phone before German ships format byte for byte as before, which
 * tests/l10n/FmtLegacyTest proves. Only a shipped non-English language gets that language's format
 * locale, with Latin digits.
 *
 * Numbers keep String.format, which rounds half up; java.text.NumberFormat would round half even.
 * Order and spacing around numbers and units belong to the templates in strings_fmt.xml, and the
 * structured results ({@link Formatted}) say where each number and unit symbol landed.
 *
 * Each call of {@link #locale}, {@link #datePattern} and {@link #legacy} reads the resolved language
 * (T.language()) again. A caller that needs both a locale and a date pattern, or formats several
 * values that must agree, should take the locale once and pass it on, rather than call each method in
 * turn: a language change between two calls (a configuration change on another thread) could
 * otherwise mix a legacy pattern with a display locale.
 */
public final class Fmt {
    private Fmt() {}

    /** Start-of-argument marks: private-use characters, one per template argument (see compose). */
    private static final char MARK = (char) 0xE000;
    /** End-of-argument mark. */
    private static final char MARK_END = (char) 0xE0FF;

    /** True while every call site keeps its legacy locale and pattern. */
    public static boolean legacy() {
        return AppLanguages.ENGLISH.equals(AppLanguages.resolve(T.language()));
    }

    /** The locale a display call site formats with: its own legacy locale while {@link #legacy()}. */
    public static Locale locale(Locale legacy) {
        return legacy() ? legacy : displayLocale();
    }

    /**
     * A shipped language's format locale: the language plus the configuration's region when the
     * languages match (de-AT, pt-BR), with Latin digits forced.
     */
    static Locale displayLocale() {
        return displayLocale(AppLanguages.resolve(T.language()), Locale.getDefault());
    }

    /**
     * {@link #displayLocale()} for a shipped language tag and the configuration's locale, kept apart so
     * tests can check it before any language ships.
     */
    static Locale displayLocale(String languageTag, Locale current) {
        Locale language = Locale.forLanguageTag(languageTag);
        Locale.Builder builder = new Locale.Builder().setLocale(language);
        if (language.getCountry().isEmpty() && language.getLanguage().equals(current.getLanguage())
                && !current.getCountry().isEmpty()) {
            builder.setRegion(current.getCountry());
        }
        return builder.setUnicodeLocaleKeyword("nu", "latn").build();
    }

    /**
     * A date or time pattern: the call site's legacy pattern while {@link #legacy()}, otherwise the
     * best pattern for the skeleton in the display locale. best is Android's
     * DateFormat::getBestDateTimePattern on the phone; this class stays free of Android so the pure
     * tests can run it.
     */
    public static String datePattern(String legacyPattern, String skeleton, BiFunction<Locale, String, String> best) {
        return legacy() ? legacyPattern : best.apply(displayLocale(), skeleton);
    }

    // Numbers

    /**
     * value with a fixed number of decimals (0 to 6), rounded half up, without grouping: exactly
     * String.format(legacy, "%.<decimals>f", value) while {@link #legacy()}. Moved here from
     * DisplayTemperature, which used it for every reading.
     */
    public static String decimal(Locale legacy, double value, int decimals) {
        int precision = Math.max(0, Math.min(6, decimals));
        return String.format(locale(legacy), "%." + precision + "f", value);
    }

    /** The grouped patterns, by decimals: "%,.Nf". */
    private static final String[] GROUPED = {"%,.0f", "%,.1f", "%,.2f", "%,.3f", "%,.4f", "%,.5f", "%,.6f"};

    /**
     * value with grouping separators and a fixed number of decimals (0 to 6), rounded half up:
     * exactly String.format(legacy, "%,.<decimals>f", value) while {@link #legacy()}.
     */
    public static String grouped(Locale legacy, double value, int decimals) {
        return String.format(locale(legacy), GROUPED[Math.max(0, Math.min(6, decimals))], value);
    }

    /**
     * A whole number without grouping, as Java's string concatenation writes it. With Latin digits
     * forced for every shipped language (waves 1-3), that is the same text in every locale; the RTL wave
     * decides whether Arabic and Urdu use their own digits here.
     */
    public static String integer(long value) {
        return Long.toString(value);
    }

    /**
     * A formatted number as structured text: its whole text is the number. It takes plain text, so a
     * {@link Formatted} passed here keeps its text but loses its number and unit ranges: pass a
     * Formatted on to {@link #quantity} and the other templates as it is instead.
     */
    public static Formatted number(CharSequence digits) {
        String text = digits.toString();
        return new Formatted(text, new int[]{0, text.length()}, new int[0]);
    }

    /** {@link #decimal} as structured text. */
    public static Formatted decimalNumber(Locale legacy, double value, int decimals) {
        return number(decimal(legacy, value, decimals));
    }

    /** {@link #integer} as structured text. */
    public static Formatted integerNumber(long value) {
        return number(integer(value));
    }

    /**
     * A percentage, "75%" in English (fmt_percent): the template owns the sign and its spacing. The
     * number keeps its ranges; the percent sign is template text, not a unit range. In English,
     * percent(decimalNumber(Locale.US, v, 0)) is String.format(Locale.US, "%.0f%%", v) and
     * percent(integerNumber(n)) is n + "%".
     */
    public static Formatted percent(Formatted number) {
        return compose(T.FMT_PERCENT, number);
    }

    // Units

    /** A unit's symbol in the current language, as structured text. */
    public static Formatted unit(UnitCode unit) {
        return unitText(unit.symbol());
    }

    /** A unit symbol that is already display text (a data symbol shown as it is, such as "°C"). */
    public static Formatted unitText(CharSequence symbol) {
        String text = symbol.toString();
        return new Formatted(text, new int[0], new int[]{0, text.length()});
    }

    /** A number and its unit, "12.5 km/h" in English (fmt_quantity). */
    public static Formatted quantity(Formatted number, Formatted unit) {
        return compose(T.FMT_QUANTITY, number, unit);
    }

    /** Two numbers sharing one unit, "10–25 km/h" in English (fmt_range). */
    public static Formatted range(Formatted low, Formatted high, Formatted unit) {
        return compose(T.FMT_RANGE, low, high, unit);
    }

    /** Two unit systems at once, "7.8 mph / 12.5 km/h" in English (fmt_dual). */
    public static Formatted dual(Formatted imperial, Formatted metric) {
        return compose(T.FMT_DUAL, imperial, metric);
    }

    /**
     * A template filled with structured parts, keeping every part's number and unit ranges. Each part
     * is passed to the template between private-use marks, so wherever the language's template puts
     * it (or repeats it), its position is read from the marks and never guessed from the text. The
     * marks are removed from the result. They are private-use characters (U+E000 to U+E0FF), which no
     * catalog text or formatted number contains.
     */
    static Formatted compose(int template, Formatted... parts) {
        if (parts.length >= MARK_END - MARK) throw new IllegalArgumentException("parts");
        Object[] marked = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) marked[i] = (char) (MARK + i) + parts[i].toString() + MARK_END;
        String filled = T.render(template, marked);
        StringBuilder text = new StringBuilder(filled.length());
        List<Integer> numbers = new ArrayList<>(), units = new ArrayList<>();
        for (int at = 0; at < filled.length(); at++) {
            char c = filled.charAt(at);
            int part = c - MARK;
            if (part < 0 || part >= parts.length) {
                text.append(c);
                continue;
            }
            int end = filled.indexOf(MARK_END, at + 1);
            if (end < 0) throw new IllegalStateException("template");
            int offset = text.length();
            text.append(filled, at + 1, end);
            shift(parts[part].numberRanges(), offset, numbers);
            shift(parts[part].unitRanges(), offset, units);
            at = end;
        }
        return new Formatted(text.toString(), ints(numbers), ints(units));
    }

    private static void shift(int[] ranges, int offset, List<Integer> into) {
        for (int range : ranges) into.add(range + offset);
    }

    private static int[] ints(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }
}
