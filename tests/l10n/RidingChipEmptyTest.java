package com.jjricks.volt;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A chip with no reading yet shows the dash with its reading's unit, where the language puts it
 * (RidingChipFit.emptyValue): "— Wh/mi", "—%" in English, "— %" in German, "%—" in Turkish. Never a sign
 * that isn't a unit ("≈", "±"), and nothing for a reading whose text has no number in it (a clock, whose
 * AM/PM is part of its text). Checked for every shipped language from its own catalog, with each chip
 * reading's first sample value built as RidingScreen.samples builds it.
 */
public final class RidingChipEmptyTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    private static final String DASH = "—";

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            plain();
            for (String language : languages()) shipped(language);
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("RidingChipEmptyTest: " + checks + " checks passed (the dash with its unit where the language "
                + "puts it, in every shipped language; no sign or clock text taken for a unit)");
    }

    private static void plain() {
        check(RidingChipFit.emptyValue(DASH, "", " Wh/mi").equals(DASH + " Wh/mi"), "a unit after a space");
        check(RidingChipFit.emptyValue(DASH, "", "%").equals(DASH + "%"), "English's percent sign, right after");
        check(RidingChipFit.emptyValue(DASH, "", " %").equals(DASH + " %"), "a percent sign after a no-break space");
        check(RidingChipFit.emptyValue(DASH, "%", "").equals("%" + DASH), "Turkish's percent sign, before");
        check(RidingChipFit.emptyValue(DASH, "≈ ", " mi").equals(DASH + " mi"), "an approximate range keeps its unit, not its sign");
        check(RidingChipFit.emptyValue(DASH, "±", " ft").equals(DASH + " ft"), "a GPS fix keeps its unit, not its sign");
        check(RidingChipFit.emptyValue(DASH, "≈ ", "").equals(DASH), "a sign alone is not a unit");
        check(RidingChipFit.emptyValue(DASH, "-", "").equals(DASH), "nor a minus");
        check(RidingChipFit.emptyValue(DASH, "", "").equals(DASH), "a word (a clock, a stopwatch) is the dash alone");
        check(RidingChipFit.emptyValue(DASH, null, null).equals(DASH), "nothing at all");
    }

    /** Each chip reading's first sample value with a number, as RidingScreen.samples builds it, in miles and feet. */
    private static Map<String, Formatted> samples() {
        Map<String, Formatted> out = new LinkedHashMap<>();
        Formatted mi = Fmt.unit(UnitCode.MI);
        out.put("battery", Fmt.percent(Fmt.integerNumber(88)));
        out.put("range_scooter", Fmt.quantity(Fmt.decimalNumber(Locale.US, 888, 0), mi));
        out.put("range_volt", Fmt.quantity(Fmt.compose(T.RIDING_APPROX, Fmt.decimalNumber(Locale.US, 888, 0)), mi));
        out.put("range_at_pace", Fmt.quantity(Fmt.compose(T.RIDING_APPROX, Fmt.decimalNumber(Locale.US, 888, 0)), mi));
        out.put("trip", Fmt.quantity(Fmt.decimalNumber(Locale.US, 888.8, 1), mi));
        out.put("average_speed", Fmt.quantity(Fmt.decimalNumber(Locale.US, 88.8, 1), Fmt.unit(UnitCode.MPH)));
        out.put("max_speed", Fmt.quantity(Fmt.decimalNumber(Locale.US, 88.8, 1), Fmt.unit(UnitCode.MPH)));
        out.put("efficiency", Fmt.quantity(Fmt.integerNumber(-888), Fmt.unitText(T.ridingUnitWhPer(UnitCode.MI.symbol()))));
        out.put("ride_energy", Fmt.quantity(Fmt.integerNumber(8888), Fmt.unit(UnitCode.WATT_HOUR)));
        out.put("voltage", Fmt.quantity(Fmt.decimalNumber(Locale.US, 88.8, 1), Fmt.unit(UnitCode.VOLT)));
        out.put("current", Fmt.quantity(Fmt.decimalNumber(Locale.US, -88.8, 1), Fmt.unit(UnitCode.AMP)));
        out.put("odometer", Fmt.quantity(Fmt.decimalNumber(Locale.US, 88888.8, 1), mi));
        out.put("power", Fmt.quantity(Fmt.integerNumber(-8888), Fmt.unit(UnitCode.WATT)));
        out.put("gps_accuracy", GpsAccuracyText.value(888, GpsAccuracy.unit(true)));
        return out;
    }

    /** RidingScreen.split's prefix and suffix: the text before and after the last number. */
    static String[] around(Formatted f) {
        String text = f.toString();
        if (f.numbers() == 0) return new String[]{"", ""};
        return new String[]{text.substring(0, f.numberStart(f.numbers() - 1)), text.substring(f.numberEnd(f.numbers() - 1))};
    }

    /** Every reading's empty value in a language (null: English), by content. */
    static Map<String, String> emptyValues(String language) {
        T.install(language == null ? null : source(language));
        Map<String, String> out = new LinkedHashMap<>();
        String dash = T.ridingNoValue();
        for (Map.Entry<String, Formatted> e : samples().entrySet()) {
            String[] parts = around(e.getValue());
            out.put(e.getKey(), RidingChipFit.emptyValue(dash, parts[0], parts[1]));
        }
        // The readings shown as words: the clock (its AM/PM is in its text), ride and recording time, a range's "Almost empty".
        out.put("clock", RidingChipFit.emptyValue(dash, "", ""));
        return out;
    }

    private static void shipped(String language) {
        Map<String, String> empty = emptyValues(language);
        String where = language == null ? "en" : language;
        String dash = T.ridingNoValue();
        for (Map.Entry<String, String> e : empty.entrySet()) {
            String v = e.getValue();
            check(v.contains(dash), where + " " + e.getKey() + ": the dash, in " + v);
            check(!v.contains("≈") && !v.contains("±") && !v.matches(".*[0-9].*"), where + " " + e.getKey() + ": no sign or figure, in " + v);
        }
        check(empty.get("clock").equals(dash), where + ": the clock is the dash alone");
        String battery = empty.get("battery");
        check(battery.contains("%") && !battery.equals(dash), where + ": the battery keeps its percent sign, " + battery);
        String percent = T.fmtPercent("\u0001");
        boolean before = percent.indexOf('%') < percent.indexOf('\u0001');
        check(before ? battery.endsWith(dash) : battery.startsWith(dash), where + ": the percent sign where the language puts it, " + battery);
        if ("tr".equals(language)) check(battery.equals("%" + dash), "Turkish: %" + dash + ", got " + battery);
        if (language == null) {
            check(battery.equals(dash + "%"), "English: " + dash + "%, got " + battery);
            check(empty.get("efficiency").equals(dash + " Wh/mi"), "English efficiency, got " + empty.get("efficiency"));
            check(empty.get("range_volt").equals(dash + " mi"), "English Volt's range, got " + empty.get("range_volt"));
            check(empty.get("gps_accuracy").equals(dash + " ft"), "English GPS accuracy, got " + empty.get("gps_accuracy"));
        }
        for (String content : new String[]{"range_scooter", "range_volt", "trip", "odometer"})
            check(empty.get(content).startsWith(dash) && empty.get(content).endsWith(UnitCode.MI.symbol()), where + " " + content + " ends in its unit, " + empty.get(content));
    }

    /** Every shipped language (res/values-*), English first as null. */
    static String[] languages() {
        File[] dirs = new File(L10nFiles.root(), "res").listFiles((d, n) -> n.startsWith("values-") && new File(d, n + "/strings_fmt.xml").isFile());
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(null);
        if (dirs != null) {
            java.util.Arrays.sort(dirs);
            for (File dir : dirs) out.add(dir.getName().substring("values-".length()).replace("-r", "-"));
        }
        check(out.contains("tr"), "Turkish ships");
        return out.toArray(new String[0]);
    }

    private static final Pattern STRING = Pattern.compile("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);

    /** A language's catalog text from its res/values-* files, as Android would read it (these strings use no markup). */
    static T.Source source(final String language) {
        final Map<String, String> text = new HashMap<>();
        File dir = new File(L10nFiles.root(), "res/values-" + language.replace("-", "-r"));
        File[] files = dir.listFiles((d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
        if (files != null)
            for (File f : files) {
                Matcher m = STRING.matcher(L10nFiles.read(f));
                while (m.find()) text.put(m.group(1), unescape(m.group(2)));
            }
        return new T.Source() {
            public String string(int id) { return text.get(T.key(id)); }
            public String plural(int id, long quantity) { return null; }
            public String language() { return language; }
        };
    }

    private static String unescape(String s) {
        if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
