package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hub layout plan, PR 2: HubText's titles, short rungs, link rows and summaries, in English and under a
 * key-echo text source (each line is one whole catalog string, never glued from pieces).
 *
 * Later text PRs add pages' titles and methods; they add statements here, so nothing below assumes a
 * page stays untitled.
 */
public final class HubTextTest {
    private static int checks;

    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void equal(String expected, CharSequence actual, String message) {
        check(actual != null && expected.equals(actual.toString()), message + ": expected <" + expected + "> but was <" + actual + ">");
    }

    /** Pages whose titles arrive in a later text PR (settings.home in PR 7, search in PR 10). */
    private static final Set<String> LATER = new HashSet<>(Arrays.asList(HubPages.SETTINGS_HOME, HubPages.SEARCH));

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                T.install(null);
                titles();
                shortTitles();
                rows();
                summaries();
            }
            Locale.setDefault(Locale.US);
            T.install(ECHO);
            echo();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("HubTextTest: " + checks + " checks passed (page titles, short rungs, link rows, summaries "
                + "in English and under the key-echo source)");
    }

    private static void titles() {
        for (String page : HubPages.ALL) {
            if (LATER.contains(page)) continue;
            Msg title = HubText.title(page);
            check(title != null && !title.render().trim().isEmpty(), "a title for " + page);
            check(!title.isPlural(), "a title is not a count: " + page);
            check(HubText.rowTitle(page).equals(title), "a link row is named as its page: " + page);
        }
        check(HubText.title(null) == null && HubText.title("nowhere") == null, "no title for an unknown page");
        equal("Home", HubText.title(HubPages.HOME), "home");
        equal("Ride", HubText.title(HubPages.RIDE), "ride");
        equal("Battery & charging", HubText.title(HubPages.BATTERY), "battery");
        equal("Controls", HubText.title(HubPages.CONTROLS), "controls");
        equal("Lights & sound", HubText.title(HubPages.LIGHTS), "lights");
        equal("Security", HubText.title(HubPages.SECURITY), "security");
        equal("Service", HubText.title(HubPages.SERVICE), "service");
        equal("Data", HubText.title(HubPages.DATA), "data");
        equal("All rides & compare", HubText.title(HubPages.RIDE_RIDES), "ride.rides");
        equal("Recording settings", HubText.title(HubPages.RIDE_RECORDING), "ride.recording");
        equal("Riding mode", HubText.title(HubPages.RIDE_RIDING), "ride.riding");
        equal("Battery modes", HubText.title(HubPages.BATTERY_MODES), "battery.modes");
        equal("Estimates & charger", HubText.title(HubPages.BATTERY_ESTIMATES), "battery.estimates");
        equal("Charge history & recordings", HubText.title(HubPages.BATTERY_HISTORY), "battery.history");
        equal("Assists", HubText.title(HubPages.CONTROLS_ASSISTS), "controls.assists");
        equal("Speed limits", HubText.title(HubPages.CONTROLS_LIMITS), "controls.limits");
        equal("Line lights", HubText.title(HubPages.LIGHTS_LINE), "lights.line");
        equal("Star lights", HubText.title(HubPages.LIGHTS_STAR), "lights.star");
        equal("Maintenance", HubText.title(HubPages.SERVICE_MAINTENANCE), "service.maintenance");
        equal("Experimental controls", HubText.title(HubPages.SERVICE_EXPERIMENTAL), "service.experimental");
        equal("Compatibility test", HubText.title(HubPages.SERVICE_COMPATIBILITY), "service.compatibility");
        equal("Issue reports", HubText.title(HubPages.SERVICE_REPORTS), "service.reports");
        equal("Data & history", HubText.title(HubPages.DATA_STORAGE), "data.storage");
        equal("Settings", HubText.title(HubPages.SETTINGS), "settings");
        equal("Units", HubText.title(HubPages.SETTINGS_UNITS), "settings.units");
        equal("My scooters", HubText.title(HubPages.SETTINGS_SCOOTERS), "settings.scooters");
        equal("About & permissions", HubText.title(HubPages.SETTINGS_ABOUT), "settings.about");
        equal("Developer options", HubText.title(HubPages.SETTINGS_DEVELOPER), "settings.developer");
        // Every subject's page title is the full rung of its own key.
        for (String subject : HubPages.SUBJECTS) {
            Msg title = HubText.title(subject);
            check(T.key(title.id()).equals("hub_subject_" + subject), "the subject title key of " + subject);
        }
    }

    private static void shortTitles() {
        for (String subject : HubPages.SUBJECTS) {
            Msg full = HubText.title(subject), shortRung = HubText.shortTitle(subject);
            check(shortRung != null && !shortRung.render().trim().isEmpty(), "a short rung for " + subject);
            check(T.key(shortRung.id()).equals(T.key(full.id()) + "_short"), "the short rung sits beside its full key: " + subject);
            check(shortRung.render().length() <= full.render().length(), "the short rung is no longer than the full: " + subject);
        }
        for (String page : HubPages.ALL) {
            if (HubPages.SUBJECTS.contains(page)) continue;
            check(HubText.shortTitle(page) == null, "only subjects have a short rung: " + page);
        }
        check(HubText.shortTitle(null) == null, "no short rung for null");
        equal("Battery", HubText.shortTitle(HubPages.BATTERY), "battery short");
        equal("Lights", HubText.shortTitle(HubPages.LIGHTS), "lights short");
        equal("Ride", HubText.shortTitle(HubPages.RIDE), "ride short");
    }

    private static void rows() {
        equal("Diagnostics & connection activity", HubText.diagnosticsRow(), "diagnostics row");
        equal("Ride backups", HubText.rideBackupsRow(), "ride backups row");
        equal("Open riding mode", HubText.openRiding(), "open riding");
        equal("Recent rides", HubText.recentRides(), "recent rides");
        equal("Back to Battery & charging", HubText.backTo(HubText.title(HubPages.BATTERY)), "back to a subject");
        equal("Back to Home", HubText.backTo(HubText.title(HubPages.HOME)), "back to home");
        equal("Experimental", HubText.experimentalBadge(), "badge");
        equal("Usable ride energy, experimental. Opens its test in Experimental controls.",
                HubText.experimentalRowDescription("Usable ride energy"), "experimental row");
        equal("Service, maintenance due", HubText.dueDescription(HubText.title(HubPages.SERVICE)), "due description");
    }

    private static void summaries() {
        equal("Last ride 6.2 mi · today", HubText.rideLastToday("6.2 mi"), "ride today");
        equal("Last ride 6.2 mi · yesterday", HubText.rideLastYesterday("6.2 mi"), "ride yesterday");
        equal("Last ride 10 km · Mar 4", HubText.rideLastOn("10 km", "Mar 4"), "ride on a date");
        equal("Recording · 12:40", HubText.rideRecording("12:40"), "recording");
        equal("No rides yet", HubText.rideNone(), "no rides");
        equal("Limit 90% · not charging", HubText.batteryLimit("90%", false), "limit idle");
        equal("Charging · limit 90%", HubText.batteryLimit("90%", true), "limit charging");
        equal("72% · not charging", HubText.batteryLevel("72%", false), "level idle");
        equal("Charging · 72%", HubText.batteryLevel("72%", true), "level charging");
        equal("Sport 31 mph · cruise on", HubText.controls("Sport 31 mph", true), "cruise on");
        equal("Sport 31 mph · cruise off", HubText.controls("Sport 31 mph", false), "cruise off");
        equal("Automatic lights", HubText.automaticLights(), "automatic lights");
        equal("Tail light on", HubText.tailLight(true), "tail light on");
        equal("Tail light off", HubText.tailLight(false), "tail light off");
        equal("PIN on · proximity on", HubText.security(true, true), "pin on, proximity on");
        equal("PIN on · proximity off", HubText.security(true, false), "pin on, proximity off");
        equal("PIN off · proximity on", HubText.security(false, true), "pin off, proximity on");
        equal("PIN off · proximity off", HubText.security(false, false), "pin off, proximity off");
        equal("Tire pressure: 40 mi left", HubText.serviceDue("Tire pressure", "40 mi left"), "service due");
        equal("No checks due", HubText.serviceClear(), "service clear");
        equal("Live readings, charts", HubText.dataSummary(), "data");
        equal("72% · about 14 mi left", HubText.batteryLevelRange("72%", "14 mi"), "level and range");
        equal("Charging to 90%", HubText.batteryChargingTo("90%"), "charging to");
        equal("Not charging", HubText.batteryNotCharging(), "not charging");
        // A value that is itself a message renders in the language current when the line is read.
        equal("Charging · limit 80%", HubText.batteryLimit(Msg.verbatim("80%"), true), "a Msg argument");
    }

    /** Under the key-echo source every line is exactly one key: nothing is glued around a catalog string. */
    private static void echo() {
        List<Msg> all = new ArrayList<>();
        for (String page : HubPages.ALL) {
            Msg title = HubText.title(page);
            if (title != null) all.add(title);
            Msg shortRung = HubText.shortTitle(page);
            if (shortRung != null) all.add(shortRung);
        }
        all.addAll(Arrays.asList(HubText.diagnosticsRow(), HubText.rideBackupsRow(), HubText.openRiding(), HubText.recentRides(),
                HubText.backTo("x"), HubText.experimentalBadge(), HubText.experimentalRowDescription("x"), HubText.dueDescription("x"),
                HubText.rideLastToday("x"), HubText.rideLastYesterday("x"), HubText.rideLastOn("x", "y"), HubText.rideRecording("x"),
                HubText.rideNone(), HubText.batteryLimit("x", false), HubText.batteryLimit("x", true),
                HubText.batteryLevel("x", false), HubText.batteryLevel("x", true), HubText.controls("x", true),
                HubText.controls("x", false), HubText.automaticLights(), HubText.tailLight(true), HubText.tailLight(false),
                HubText.security(true, true), HubText.security(true, false), HubText.security(false, true),
                HubText.security(false, false), HubText.serviceDue("x", "y"), HubText.serviceClear(), HubText.dataSummary(),
                HubText.batteryLevelRange("x", "y"), HubText.batteryChargingTo("x"), HubText.batteryNotCharging()));
        Set<String> summaryKeys = new HashSet<>();
        for (Msg msg : all) {
            check(msg.id() >= 0, "a catalog message, not verbatim text");
            check(!msg.isPlural(), "no Hub line counts anything yet: " + T.key(msg.id()));
            equal("⟦" + T.key(msg.id()) + "⟧", msg.render(), "one whole key");
            if (T.key(msg.id()).startsWith("hub_summary_")) summaryKeys.add(T.key(msg.id()));
        }
        // Each state has its own sentence: no two summary states share a key.
        check(summaryKeys.size() == 21, "twenty-one summary sentences, one per state: " + summaryKeys.size());
        // The English of a message stays English whatever language is installed.
        equal("Charging · limit 90%", HubText.batteryLimit("90%", true).english(), "english() under another source");
    }
}
