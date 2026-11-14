package com.jjricks.volt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;

/**
 * Hub layout plan, PR 10: the text the ordinary parts before it could not add, for the screens the Layout Plan draws
 * (the contributor's decisions on #383). Clean Home's one-line round quick-action names ("Charge by" for Charge done
 * by), Just back's Battery tile name, short ride time and one spoken sentence, Home style's list separator, the
 * Quick access "Search settings" tile and its short rung, Battery &amp; charging's section labels and "Last charge"
 * line, Controls' "More controls" heading and its two sub-lines, the Ride page's "Today" / "Yesterday" ride titles
 * and the Record tile's short rung while simulating. In English and under a key-echo text source (each line is one
 * whole catalog string, never glued from pieces).
 *
 * <p>The round names also fit their one line under the circles at the maintainers' sizes, 344x882 dp and 725x604 dp
 * at 1x and 1.4x text (and 412 dp), by QuickActionLayout's rule with the same per-letter Saira estimate as
 * tests/QuickActionLayoutTest: whole at full size at 1x, whole (a little smaller at most) at 1.4x. The captures on
 * the emulator are the final word; this keeps a later rewording from cutting a name without notice.
 */
public final class HubPlanTextTest {
    private static int checks;

    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    private static final String F = QuickAccessActions.FEATURE_PREFIX;

    /**
     * Part 10's short round names (HubText.quickActionShortName): action id, English, key. Ride has none: it keeps the
     * Ride controls group's existing, translated name (#449, HubText.quickActionRoundName: "Fahren", "Condução"), so
     * part 10's hub_qa_ride_round is not shown.
     */
    private static final String[][] ROUND = {
            {HomeQuickActions.RECORD, "Record", "hub_qa_record_round"},
            {HomeQuickActions.CHARGE_ALERTS, "Alerts", "hub_qa_alerts_round"},
            {"charge_done_by", "Charge by", "hub_qa_charge_done_by_round"},
            {F + "cruise_control", "Cruise", "hub_qa_cruise_round"},
            {F + "traction_control", "Traction", "hub_qa_traction_round"},
            {F + "energy_recovery", "Recovery", "hub_qa_recovery_round"},
            {F + "overspeed_control", "Overspeed", "hub_qa_overspeed_round"},
            {F + "hill_descent_control", "Descent", "hub_qa_descent_round"},
    };

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void equal(String expected, CharSequence actual, String message) {
        check(actual != null && expected.equals(actual.toString()), message + ": expected <" + expected + "> but was <" + actual + ">");
    }
    private static void key(String expected, Msg msg, String message) {
        check(msg != null && expected.equals(T.key(msg.id())), message + ": key " + expected + " but was " + (msg == null ? "null" : T.key(msg.id())));
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                T.install(null);
                roundNames();
                justBack();
                pages();
            }
            T.install(null);
            for (int window : new int[]{344, 412, 725})
                for (float text : new float[]{1f, 1.4f}) roundFit(window, text);
            echo();
            catalog();
            wiring();
        } finally {
            Locale.setDefault(saved);
            T.install(null);
        }
        System.out.println("HubPlanTextTest: " + checks + " checks passed (round names and their fit, Just back, Home style, "
                + "Quick access search, Battery & charging, Controls, Ride, key echo, wiring)");
    }

    /** Clean's one short name per round button, from part 10's own keys; any other action has none (its short rung shows). */
    private static void roundNames() {
        for (String[] r : ROUND) {
            Msg name = HubText.quickActionShortName(r[0]);
            equal(r[1], name == null ? null : name.render(), "round name of " + r[0]);
            key(r[2], name, "round name of " + r[0]);
        }
        check(HubText.quickActionShortName(null) == null, "no id, no round name");
        check(HubText.quickActionShortName(HomeQuickActions.RIDING) == null, "Ride has no part 10 short name");
        equal("Ride", HubText.quickActionRoundName(HomeQuickActions.RIDING).render(), "Ride is named as riding");
        key("controls_group_ride", HubText.quickActionRoundName(HomeQuickActions.RIDING), "Ride's name is the Ride controls group's");
        for (String other : new String[]{"lights", "controls", "rides", F + "headlight", F + "hill_start_assist", "cruise_control",
                F + "charge_done_by", "feature", F})
            check(HubText.quickActionShortName(other) == null, "no round name for " + other);
        // Clean's four default names under the circles, joined as a list (Settings › Home style's row joins the names
        // before part 10's short ones, "Charge done by" whole: QuickAccessGrid.listName).
        String line = String.join(HubText.listSeparator().render(), Arrays.asList(
                HubText.quickActionRoundName(HomeQuickActions.RIDING).render(), HubText.quickActionShortName(HomeQuickActions.RECORD).render(),
                HubText.quickActionShortName("charge_done_by").render(), HubText.quickActionShortName(F + "cruise_control").render()));
        equal("Ride, Record, Charge by, Cruise", line, "Clean's four round names as a list");
        equal("Search settings", HubText.quickAccessSearch().render(), "Quick access search tile");
        equal("Search", HubText.quickAccessSearchShort().render(), "Quick access search tile, short rung");
    }

    /** Just back: "Battery", the ride time as the mock-up writes it, and one spoken sentence. */
    private static void justBack() {
        equal("Battery", HubText.justBackBatteryName().render(), "Just back's battery tile");
        key("hub_just_back_battery", HubText.justBackBatteryName(), "Just back's battery tile has its own key");
        long s = 1000, m = 60 * s, h = 60 * m;
        Object[][] times = {
                {-5 * s, "0s"}, {0L, "0s"}, {45 * s, "45s"}, {59 * s + 999, "59s"}, {m, "1m"}, {24 * m + 59 * s, "24m"},
                {20 * m, "20m"}, {59 * m + 59 * s, "59m"}, {h, "1h 00m"}, {h + 2 * m + 30 * s, "1h 02m"}, {h + 10 * m, "1h 10m"},
                {10 * h + 59 * m, "10h 59m"}, {25 * h, "25h 00m"},
        };
        for (Object[] t : times) equal((String) t[1], HubText.justBackRideTimeValue((Long) t[0]).render(), "ride time of " + t[0] + " ms");
        // Spoken in words with the same whole units, so "20m" is never read as a distance.
        Object[][] spokenTimes = {
                {-5 * s, "0 seconds"}, {s, "1 second"}, {45 * s, "45 seconds"}, {m, "1 minute"}, {20 * m + 30 * s, "20 minutes"},
                {59 * m + 59 * s, "59 minutes"}, {h, "1 hour"}, {h + m, "1 hour 1 minute"}, {h + 2 * m + 30 * s, "1 hour 2 minutes"},
                {2 * h, "2 hours"}, {10 * h + 59 * m, "10 hours 59 minutes"},
        };
        for (Object[] t : spokenTimes) equal((String) t[1], HubText.justBackRideTimeSpoken((Long) t[0]).render(), "spoken ride time of " + t[0] + " ms");
        equal("Ride ended just now, 6.2 mi. Opens the ride.", HubText.justBackSpoken(0, "6.2 mi").render(), "spoken, just now");
        equal("Ride ended 1 minute ago, 6.2 mi. Opens the ride.", HubText.justBackSpoken(1, "6.2 mi").render(), "spoken, one minute");
        equal("Ride ended 12 minutes ago, 10.0 km. Opens the ride.", HubText.justBackSpoken(12, "10.0 km").render(), "spoken, minutes");
        // The spoken sentence starts with the line the card shows.
        for (long minutes : new long[]{0, 1, 12, 59})
            check(HubText.justBackSpoken(minutes, "6.2 mi").render().startsWith(HubText.justBackEnded(minutes).render()),
                    "the spoken sentence starts with the ended line at " + minutes + " min");
    }

    /** Battery & charging, Controls and Ride: the Layout Plan's labels and lines. */
    private static void pages() {
        equal("Charging", HubText.batterySectionCharging().render(), "Battery & charging: CHARGING label");
        equal("History", HubText.batterySectionHistory().render(), "Battery & charging: HISTORY label");
        equal("Last charge 42% → 90% · 2 h 10 min", HubText.batteryLastCharge("42% → 90%", "2 h 10 min").render(), "HISTORY line");
        equal("Last charge 42% → 90%", HubText.batteryLastCharge("42% → 90%", null).render(), "HISTORY line without a duration");
        equal("More controls", HubText.controlsMore().render(), "Controls: More controls");
        equal("Walk, power, hill descent, kick-start", HubText.controlsAssistsSub().render(), "Controls: Assists line");
        equal("Custom, per mode, overspeed", HubText.controlsLimitsSub().render(), "Controls: Speed limits line");
        LocalDate today = LocalDate.of(2026, 11, 14);
        equal("Today, 9:41 am", HubText.recentRideTitle(today, today, "9:41 am").render(), "a ride today");
        equal("Yesterday, 5:42 pm", HubText.recentRideTitle(today.minusDays(1), today, "5:42 pm").render(), "a ride yesterday");
        // Across a month and a year.
        equal("Yesterday, 11:59 pm", HubText.recentRideTitle(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1), "11:59 pm").render(), "yesterday across a year");
        check(HubText.recentRideTitle(today.minusDays(2), today, "x") == null, "an older ride keeps its day and date");
        check(HubText.recentRideTitle(today.plusDays(1), today, "x") == null, "a ride dated after today keeps its date");
        check(HubText.recentRideTitle(null, today, "x") == null && HubText.recentRideTitle(today, null, "x") == null, "no date, no title");
        equal("Simulating", HubText.rideSimulatingShort().render(), "Record tile's short rung while simulating");
    }

    private static float em(char c) {
        if ("iljtfr .:,'I".indexOf(c) >= 0) return .3f;
        if (c == 'm' || c == 'w') return .85f;
        if (Character.isUpperCase(c)) return .65f;
        return .55f;
    }

    /** One of the maintainers' sizes: every round name on its one line, whole; at full size at 1x. */
    private static void roundFit(int window, float text) {
        String at = window + " dp at " + text + "x";
        float row = Math.min(window - 36, 560);
        int columns = QuickActionLayout.columns(row, 4);
        check(columns == 4, at + ": four in a row");
        float room = QuickActionLayout.room(QuickActionLayout.column(row, columns));
        float sp = QuickActionLayout.NAME_SP * text;
        QuickActionLayout.Width width = name -> {
            float total = 0;
            for (int i = 0; i < name.length(); i++) total += em(name.charAt(i));
            return total * sp;
        };
        for (String[] r : ROUND) {
            String name = HubText.quickActionShortName(r[0]).render();
            QuickActionLayout.Name fit = QuickActionLayout.name(room, width, name);
            check(!fit.iconOnly() && name.equals(fit.text), at + ": " + name + " whole on one line: " + fit);
            check(width.of(name) * fit.scale <= room, at + ": " + name + " within its column: " + fit);
            if (text == 1f) check(fit.scale == 1f, at + ": " + name + " at its full size: " + fit);
            else check(fit.scale >= QuickActionLayout.MIN_SCALE, at + ": " + name + " no smaller than the least size: " + fit);
        }
    }

    /** Under the key-echo source every line is exactly one key: nothing is glued around a catalog string. */
    private static void echo() {
        T.install(ECHO);
        try {
            for (String[] r : ROUND) equal("⟦" + r[2] + "⟧", HubText.quickActionShortName(r[0]).render(), "echo: round name of " + r[0]);
            equal("⟦hub_list_separator⟧", HubText.listSeparator().render(), "echo: list separator");
            equal("⟦hub_qa_search_settings⟧", HubText.quickAccessSearch().render(), "echo: search tile");
            equal("⟦hub_qa_search_settings_short⟧", HubText.quickAccessSearchShort().render(), "echo: search tile short");
            equal("⟦hub_just_back_battery⟧", HubText.justBackBatteryName().render(), "echo: battery tile");
            equal("⟦hub_just_back_time_seconds⟧", HubText.justBackRideTimeValue(30_000).render(), "echo: seconds");
            equal("⟦hub_just_back_time_minutes⟧", HubText.justBackRideTimeValue(24 * 60_000).render(), "echo: minutes");
            equal("⟦hub_just_back_time_hours⟧", HubText.justBackRideTimeValue(62 * 60_000).render(), "echo: hours");
            equal("⟦hub_just_back_time_spoken_seconds⟧", HubText.justBackRideTimeSpoken(30_000).render(), "echo: spoken seconds");
            equal("⟦hub_just_back_time_spoken_minutes⟧", HubText.justBackRideTimeSpoken(24 * 60_000).render(), "echo: spoken minutes");
            equal("⟦hub_just_back_time_spoken_hours⟧", HubText.justBackRideTimeSpoken(60 * 60_000).render(), "echo: spoken hours");
            equal("⟦hub_just_back_time_spoken_hours_minutes⟧", HubText.justBackRideTimeSpoken(62 * 60_000).render(), "echo: spoken hours and minutes");
            equal("⟦hub_just_back_spoken_now⟧", HubText.justBackSpoken(0, "6.2 mi").render(), "echo: spoken now");
            equal("⟦hub_just_back_spoken_minutes⟧", HubText.justBackSpoken(12, "6.2 mi").render(), "echo: spoken minutes");
            equal("⟦hub_battery_section_charging⟧", HubText.batterySectionCharging().render(), "echo: CHARGING");
            equal("⟦hub_battery_section_history⟧", HubText.batterySectionHistory().render(), "echo: HISTORY");
            equal("⟦hub_battery_last_charge⟧", HubText.batteryLastCharge("a", "b").render(), "echo: last charge");
            equal("⟦hub_battery_last_charge_span⟧", HubText.batteryLastCharge("a", null).render(), "echo: last charge, span");
            equal("⟦hub_controls_more⟧", HubText.controlsMore().render(), "echo: More controls");
            equal("⟦hub_controls_assists_sub⟧", HubText.controlsAssistsSub().render(), "echo: Assists line");
            equal("⟦hub_controls_limits_sub⟧", HubText.controlsLimitsSub().render(), "echo: Speed limits line");
            LocalDate today = LocalDate.of(2026, 11, 14);
            equal("⟦hub_ride_today⟧", HubText.recentRideTitle(today, today, "t").render(), "echo: today");
            equal("⟦hub_ride_yesterday⟧", HubText.recentRideTitle(today.minusDays(1), today, "t").render(), "echo: yesterday");
            equal("⟦hub_ride_simulating⟧", HubText.rideSimulatingShort().render(), "echo: simulating short");
        } finally {
            T.install(null);
        }
    }

    /** The keys sit where the catalog's rules put them, with their notes. */
    private static void catalog() {
        String hub = read("res/values/strings_hub.xml"), rides = read("res/values/strings_rides.xml");
        for (String[] r : ROUND)
            check(hub.contains("<string name=\"" + r[2] + "\" volt:screen=\"Clean Home quick actions, Home style\"")
                    && hub.contains("name=\"" + r[2] + "\"") && hub.matches("(?s).*name=\"" + r[2] + "\"[^>]*volt:max=\"72dp@13.5sp\".*"),
                    r[2] + " is in strings_hub.xml with the round button's budget");
        check(hub.matches("(?s).*name=\"hub_qa_charge_done_by_round\"[^>]*volt:safety=\"charge\".*"), "Charge by is charging text");
        check(hub.matches("(?s).*name=\"hub_qa_search_settings\"[^>]*volt:ladder=\"full\".*")
                && hub.matches("(?s).*name=\"hub_qa_search_settings_short\"[^>]*volt:ladder=\"short\".*"), "the search tile's ladder");
        check(rides.contains(">Simulating · Exit</string>") && hub.matches("(?s).*name=\"hub_ride_simulating\"[^>]*>Simulating<.*"),
                "Simulating · Exit keeps its English; the shorter name is a key of its own");
    }

    /** The screens on this branch read the new text through HubText. */
    private static void wiring() {
        String justBack = read("src/com/jjricks/volt/JustBackView.java");
        check(justBack.contains("new Cell(host, HubText.justBackBatteryName())"), "Just back's battery tile is named by its own key");
        check(justBack.contains("HubText.justBackRideTimeValue(s.shownElapsedMs)"), "Just back's ride time is the short form");
        check(justBack.contains("time.set(duration, HubText.justBackRideTimeSpoken(s.shownElapsedMs).render());"), "Just back's ride time is spoken in words");
        check(justBack.contains("spoken = HubText.justBackSpoken(minutes, spokenDistance).render();"), "Just back is spoken as one sentence");
        String editor = read("src/com/jjricks/volt/HomeQuickEditor.java");
        check(editor.contains("String.join(HubText.listSeparator().render(), names)"), "Home style's line joins with the list separator");
        check(editor.contains("QuickAccessGrid.listName(entry)"), "Home style's row keeps the names before part 10's short ones");
        String grid = read("src/com/jjricks/volt/QuickAccessGrid.java");
        // Under QuickActionLayout's ladder part 10's short name is tried first, before the names that were there before it.
        check(grid.contains("Msg shortName = HubText.quickActionShortName(entry.id);")
                && grid.contains("return shortName == null ? before : withFirst(shortName.render(), before);"),
                "part 10's short name is the ladder's first name, the names before it follow");
        String sheet = read("src/com/jjricks/volt/WhatsNewSheet.java");
        int cut = sheet.indexOf("if (notes.cut)"), line = sheet.indexOf("HubText.whatsNewCut()"), page = sheet.indexOf("HubText.whatsNewReleasePage()");
        check(cut >= 0 && line > cut && page > line, "What's new says a long changelog was cut, above the release page link");
    }
}
