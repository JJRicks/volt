package com.jjricks.volt;

import java.util.*;

/**
 * Maintenance intervals a model's manual states, keyed by the {@link NaveeModels} name (maintenance
 * plan, section 2). One entry per interval the manual states; no entry means "not listed", never a
 * guess. Every entry needs a {@link Source}: the manual's title, edition or date, and page.
 *
 * <p>The table holds the intervals NAVEE's public user manuals state (owner answer Q41 C, researched
 * once on 2026-11-06 from NAVEE's Product Resource page, service.naveetech.com/instruction-manual/;
 * Volt itself never goes online for this). Those manuals share one "Scooter Maintenance Schedule
 * (Recommended)" table, with columns Every month / Every 3 months / Every 500 km/6 months /
 * Every 1000 km/1 year / Every 10000 km/3 years. A row's ticks run from one column to the right, and its
 * interval is that first column. Kept: tire pressure, tire tread, the handlebar screws (bolts), brake
 * adjustment, steering, and battery storage where the manual gives one number. Left out: where a manual
 * gives two numbers for the same thing (battery storage "every 30 days" in its text and "every 60 days"
 * in the table), the second screw rows, the charging row, and any model whose manual has no schedule.
 * The UT5 Max is the one model read from a printed copy: NAVEE's online UT5 Max PDF is a Quick User Guide
 * with no schedule (checked 2026-11-08), so its line cites the owner's paper manual, see {@link #PRINTED}.
 * Adding a model later is one reviewed line with its citation, plus a test.
 */
final class MaintenanceDefaults {
    /**
     * Suggested task ids (owner answer Q42: suggested names plus free text). Stable stored values;
     * the screen shows each through its catalog string. A reminder with any other name stores the
     * typed text and an empty task id.
     */
    static final String TIRE_PRESSURE = "tire_pressure", BRAKES = "brakes", BOLTS_FOLDING = "bolts_folding", LIGHTS = "lights",
            CLEAN_DRY = "clean_dry", BATTERY_STORAGE = "battery_storage";
    static final String TIRE_TREAD = "tire_tread", STEERING = "steering";
    static final List<String> TASKS = Collections.unmodifiableList(Arrays.asList(TIRE_PRESSURE, TIRE_TREAD, BRAKES, BOLTS_FOLDING, STEERING, LIGHTS,
            CLEAN_DRY, BATTERY_STORAGE));

    static boolean task(String id) { return TASKS.contains(id); }

    /** Where an interval comes from. All three parts are required. */
    static final class Source {
        final String title, edition, page;
        Source(String title, String edition, String page) {
            this.title = required(title, "title"); this.edition = required(edition, "edition"); this.page = required(page, "page");
        }
    }

    /** One manual interval: distance in km and/or time in days or months. */
    static final class Interval {
        final String model, task; final Double km; final int days, months; final Source source;
        Interval(String model, String task, Double km, int days, int months, Source source) {
            if (source == null) throw new IllegalArgumentException("source");
            if (!task(task)) throw new IllegalArgumentException("task");
            if (km != null && !(km > 0 && km <= MaintenancePolicy.MAX_KM)) throw new IllegalArgumentException("km");
            if (days < 0 || months < 0 || days > 0 && months > 0 || days > MaintenancePolicy.MAX_DAYS || months > MaintenancePolicy.MAX_MONTHS)
                throw new IllegalArgumentException("days/months");
            if (km == null && days == 0 && months == 0) throw new IllegalArgumentException("interval");
            this.model = required(model, "model"); this.task = task; this.km = km; this.days = days; this.months = months; this.source = source;
        }
    }

    enum Kind { LISTED, NOT_LISTED, UNKNOWN_MODEL }

    /** What the "From the {model} manual" row shows: the listed intervals, "No intervals listed yet", or "Unknown model". */
    static final class Result {
        final Kind kind; final List<Interval> intervals;
        private Result(Kind kind, List<Interval> intervals) { this.kind = kind; this.intervals = Collections.unmodifiableList(intervals); }
    }

    /** Each manual's schedule, one line per interval: see the class comment for how the table reads. */
    private static final List<Interval> TABLE = Collections.unmodifiableList(build());

    /**
     * NAVEE prints no edition or revision in these manuals, so the date is the month of the online PDF
     * (its upload folder, /2026/03/). A number, so the caption needs no translation.
     */
    private static final String ONLINE = "2026-03";

    /**
     * The edition of a manual read from a printed copy that states no edition or date (the owner's UT5 Max
     * manual). A dash rather than the month we read it, which would look like the manual's own date: the
     * caption shows this value untranslated, so it can't be a word.
     */
    private static final String PRINTED = "\u2014";

    private static List<Interval> build() {
        List<Interval> t = new ArrayList<>();
        // service.naveetech.com/wp-content/uploads/2026/03/NAVEE-Electric-Scooter-GT3-User-Manual.pdf, -GT3-Pro-, -GT3-Max- (one file each).
        for (String m : new String[]{"GT3", "GT3 Pro", "GT3 Max"})
            schedule(t, m, "NAVEE Electric Scooter " + m + " User Manual", "09", "10", false, true);
        // .../2026/03/NAVEE-Electric-Scooter-ST3-User-Manual.pdf, -ST3-Pro-.
        for (String m : new String[]{"ST3", "ST3 Pro"})
            schedule(t, m, "NAVEE Electric Scooter " + m + " User Manual", "09", "10", false, true);
        // .../2026/03/NAVEE-Electric-Scooter-V25i-Pro-IIV40i-Pro-IIV50i-Pro-II-User-Manual.pdf (NAVEE links it for all three).
        for (String m : new String[]{"V25i Pro II", "V40i Pro II", "V50i Pro II"})
            schedule(t, m, "NAVEE Electric Scooter V25i Pro II/V40i Pro II/V50i Pro II User Manual", "09", "10", false, true);
        // .../2026/03/NAVEE-Kids-Electric-Scooter-K100-Series-User-Manual.pdf (K100, K100 Pro and K100 Max EU).
        for (String m : new String[]{"K100", "K100 Pro", "K100 Max"})
            schedule(t, m, "NAVEE Kids Electric Scooter K100 Series User Manual", "08", "09", false, true);
        // .../2026/03/NAVEE-Elecitric-Scooter-S40-User-Manual-2.pdf, -S60-User-Manual.pdf. Battery storage: 30 or 60 days, left out.
        for (String m : new String[]{"S40", "S60"})
            schedule(t, m, "NAVEE Electric Scooter " + m + " User Manual", "07", "08", true, false);
        // .../2026/03/NAVEE-Electric-Scooter-V40i-ProV50i-Pro-User-Manual-1.pdf. Battery storage left out, as above.
        for (String m : new String[]{"V40i Pro", "V50i Pro"})
            schedule(t, m, "NAVEE Electric Scooter V40i Pro/V50i Pro User Manual", "07", "08", true, false);
        // .../2026/03/NAVEE-Electric-Scooter-V40-i-User-Manual.pdf, -V40-Pro-User-Manual.pdf, -V25-User-Manual.pdf, -V25I-Pro-User-Manual.pdf.
        schedule(t, "V40i", "NAVEE Electric Scooter V40i User Manual", "07", "08", true, false);
        schedule(t, "V40 Pro", "NAVEE Electric Scooter V40 Pro User Manual", "05", "06", true, false);
        schedule(t, "V25", "NAVEE Electric Scooter V25 User Manual", "06", "07", true, false);
        schedule(t, "V25i Pro", "NAVEE Electric Scooter V25i Pro User Manual", "06", "07", true, false);
        // .../2026/03/NAVEE-Elecitric-Scooter-S65-User-Manual.pdf: NAVEE's S65 link, a manual that names the S65C only.
        schedule(t, "S65C", "NAVEE Electric Scooter User Manual, SPU: S65C", "05", "06", true, false);
        // The owner's printed manual, pp. 14-15: no tire pressure row. Battery storage left out until p. 13's storage text is read.
        schedule(t, "UT5 Max", "NAVEE UT5 Max printed manual", PRINTED, "14", "15", false, false);
        return t;
    }

    /**
     * One manual's schedule: the first page holds the tire and screw rows, the second the brake, steering
     * and battery rows. Tire pressure ("Inflate the tire until its pressure reaches 45-50 psi") is in some
     * manuals only; battery storage ("turn it on to charge every 60 days") is kept only where the manual
     * gives that one number.
     */
    private static void schedule(List<Interval> t, String model, String title, String first, String second, boolean pressure, boolean battery) {
        schedule(t, model, title, ONLINE, first, second, pressure, battery);
    }

    private static void schedule(List<Interval> t, String model, String title, String edition, String first, String second, boolean pressure, boolean battery) {
        Source tires = new Source(title, edition, first), rest = new Source(title, edition, second);
        if (pressure) t.add(new Interval(model, TIRE_PRESSURE, null, 0, 1, tires));
        t.add(new Interval(model, TIRE_TREAD, 500.0, 0, 6, tires));
        t.add(new Interval(model, BOLTS_FOLDING, null, 0, 3, tires));
        t.add(new Interval(model, BRAKES, 500.0, 0, 6, rest));
        t.add(new Interval(model, STEERING, 1000.0, 0, 12, rest));
        if (battery) t.add(new Interval(model, BATTERY_STORAGE, null, 60, 0, rest));
    }

    private MaintenanceDefaults() { }

    static List<Interval> table() { return TABLE; }

    /** A null or blank name is an unknown model. */
    static Result forModel(String model) { return forModel(model, TABLE); }

    static Result forModel(String model, List<Interval> table) {
        if (model == null || model.trim().isEmpty()) return new Result(Kind.UNKNOWN_MODEL, Collections.<Interval>emptyList());
        List<Interval> found = new ArrayList<>();
        // Case-blind: a scooter's own name reads "UT5 MAX", the model list says "UT5 Max".
        for (Interval interval : table) if (interval.model.equalsIgnoreCase(model.trim())) found.add(interval);
        return found.isEmpty() ? new Result(Kind.NOT_LISTED, found) : new Result(Kind.LISTED, found);
    }

    private static String required(String value, String what) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(what);
        return value;
    }
}
