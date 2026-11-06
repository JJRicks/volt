package com.jjricks.volt;

import java.util.*;

/**
 * Maintenance intervals a model's manual states, keyed by the {@link NaveeModels} name (maintenance
 * plan, section 2). One entry per interval the manual states; no entry means "not listed", never a
 * guess. Every entry needs a {@link Source}: the manual's title, edition or date, and page.
 *
 * <p>The table ships empty: no manual has been reviewed yet. Adding a model later is one reviewed
 * line with its citation, plus a test.
 */
final class MaintenanceDefaults {
    /**
     * Suggested task ids (owner answer Q42: suggested names plus free text). Stable stored values;
     * the screen shows each through its catalog string. A reminder with any other name stores the
     * typed text and an empty task id.
     */
    static final String TIRE_PRESSURE = "tire_pressure", TIRE_TREAD = "tire_tread", BRAKES = "brakes", BOLTS_FOLDING = "bolts_folding",
            STEERING = "steering", LIGHTS = "lights", CLEAN_DRY = "clean_dry", BATTERY_STORAGE = "battery_storage";
    /**
     * The tasks the add sheet offers and an interval may name. TIRE_TREAD and STEERING have their names
     * already and join this list with the first manual entries that use them.
     */
    static final List<String> TASKS = Collections.unmodifiableList(Arrays.asList(TIRE_PRESSURE, BRAKES, BOLTS_FOLDING, LIGHTS, CLEAN_DRY, BATTERY_STORAGE));

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

    /** Empty until a manual is reviewed. Add entries as {@code new Interval(model, task, km, days, months, new Source(...))}. */
    private static final List<Interval> TABLE = Collections.emptyList();

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
