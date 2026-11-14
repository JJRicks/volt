package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ride and Data as subjects (hub layout plan, PR 5): the switches that moved out of App preferences and Data & history
 * keep their keys and defaults, each is built once, and on the page the plan gives it. MoreScreen and RidesScreen can't
 * run on the JVM, so this reads their source. Arguments: MoreScreen.java, then RidesScreen.java.
 */
public final class HubRideDataTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        String more = read(args[0]), rides = read(args[1]);

        // Ride › Recording settings: recording switches, battery use, the map, ride auto-save.
        String recording = body(more, "private void recording()");
        check(recording.contains("\"keep_screen_awake\", true)"), "Keep screen awake: same key, on by default");
        check(recording.contains("\"ride_auto_stop\", true)"), "End rides automatically: same key, on by default");
        check(recording.contains("AutoStartPolicy.AUTO_RECORD_KEY, false, this::offerRideLocation)"),
                "Record rides automatically: same key, off by default, the location offer before it turns on");
        check(recording.contains("HubText.recordInBackground().render(), HubText.recordInBackgroundNote().render()")
                && recording.contains("getBoolean(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY, false)")
                && recording.contains("putBoolean(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY, enabled)"),
                "Start recording in the background: its own switch, off by default, with the no-GPS-route note under it (part 5b)");
        check(recording.indexOf("AutoStartPolicy.AUTO_RECORD_KEY, false, this::offerRideLocation)") < recording.indexOf("HubText.recordInBackground()"),
                "the background switch follows Record rides automatically");
        check(recording.contains("ACTION_APPLICATION_DETAILS_SETTINGS"), "Battery use opens Android's page for Volt");
        check(recording.contains("this::mapSettings"), "Map settings");
        check(recording.contains("autoSaveGroup();"), "Ride auto-save");

        // Settings › My scooters: how Volt keeps in touch with the scooter.
        String profiles = body(more, "private void profiles()");
        check(profiles.contains("\"auto_connect\", true)"), "Connect automatically: same key, on by default");
        check(profiles.contains("\"background_connection\", false)"), "Stay connected in background: same key, off by default");
        check(profiles.contains("this::telemetryRate") && profiles.contains("\"poll_interval_ms\", 2000"), "Telemetry refresh, 2 s by default");

        // Data › Data & history: the connected history switch and Live Stats' detail density.
        String data = body(more, "private void dataHistory()");
        check(data.contains("\"stats_density\"") && data.contains("\"comfortable\");"), "Live Stats detail: same key, comfortable by default");
        check(data.contains("\"history_enabled\", true)"), "Connected history: same key, on by default");
        check(!data.contains("autoSaveGroup();"), "Ride auto-save left Data & history");

        // App preferences keeps only the app's own look and feel.
        String prefs = body(more, "private void preferences()");
        for (String moved : new String[] {"stats_density", "keep_screen_awake", "ride_auto_stop", "AUTO_RECORD_KEY", "auto_connect",
                "background_connection", "poll_interval_ms", "mapSettings", "AUTO_RIDE_MODE_KEY"})
            check(!prefs.contains(moved), "App preferences no longer holds " + moved);

        // Each moved switch is built once in More, so no page shows two copies of it.
        for (String key : new String[] {"\"keep_screen_awake\"", "\"ride_auto_stop\"", "\"auto_connect\"", "\"background_connection\"",
                "\"history_enabled\""})
            check(lines(more, "preferenceToggle(", key) == 1, "one switch for " + key);
        check(count(more, "AutoStartPolicy.AUTO_RECORD_KEY, false, this::offerRideLocation") == 1, "one More copy of Record rides automatically");
        check(!more.contains("private void ridingPreferences()"), "Open automatically lives in Ride › Riding mode (RidingSettingsMenu) only");

        // Ride › Recording settings › Phone sensors: same keys, on by default, built once.
        String sensors = body(rides, "private View phoneSensors(LinearLayout content, boolean sheet)");
        check(sensors.contains("\"phone_gps\", true)") && sensors.contains("\"phone_motion\", true)"), "Phone sensors: same keys, on by default");
        check(lines(rides, "switchRow(", "\"phone_gps\"") == 1 && lines(rides, "switchRow(", "\"phone_motion\"") == 1, "one switch for each phone sensor");
        // The card on Recording settings and the Phone sensors sheet (kept for validations) share those switches.
        check(body(rides, "private LinearLayout phoneSensorsCard()").contains("phoneSensors(content, false)"), "the Recording settings card shows the phone sensors");
        check(body(rides, "private void phoneOptions(Runnable dismissed)").contains("phoneSensors(content, true)"), "the Phone sensors sheet shows the same switches");
        // The Next ride tag beside the Phone sensors heading drops to its own line when the two don't fit side by side, never
        // cut mid-word (council review of #389, finding 3: "Próxi" in pt-PT at 1.4x text).
        check(sensors.contains("new HeadingTag(ui.sectionLabel(title), chip)"), "the card's heading and tag share a HeadingTag");
        String headingTag = body(rides, "private final class HeadingTag extends ViewGroup");
        check(headingTag.contains("below = heading.getMeasuredWidth() + gap() + tag.getMeasuredWidth() > available")
                && headingTag.contains("top + heading.getMeasuredHeight()"), "HeadingTag puts the tag under the heading when they don't fit");

        // Live Stats' Data & history link shows the stored history's size as its value, as More's Data row did, kept
        // current by the history's storage refresh (council review of #389, finding 4). StatsScreen sits beside MoreScreen.
        String stats = read(Paths.get(args[0]).resolveSibling("StatsScreen.java").toString());
        check(stats.contains("HubText.title(HubPages.DATA_STORAGE), () -> storedSize,"), "the Data & history link has the stored size as its value");
        String storedSize = body(stats, "private void refreshStoredSize()");
        check(storedSize.contains("history.storage(") && storedSize.contains("storedSize = MoreScreen.mib(storage.bytes); storageLink.refresh();"),
                "the value comes from the storage refresh, in Data & history's MiB form");
        check(body(stats, "@Override public void refresh()").contains("refreshStoredSize();"), "Live Stats' refresh keeps it current");
        check(more.contains("static String mib(long bytes) { return bytes <= 0 ? \"\" :"), "nothing stored: no value, as More's row had");

        // One home per setting in the Hub (the owner's "one home" answer): Record rides automatically on Ride › Recording
        // settings, not again on Ride › Riding mode (riding mode's own tune menu keeps it); Ride backups on the Ride page,
        // not again on All rides & compare. Council review of #389, the duplicates question.
        String hubRiding = read(Paths.get(args[0]).resolveSibling("HubRidingSettings.java").toString());
        check(hubRiding.contains("() -> true).withoutAutoRecord();"), "Ride › Riding mode leaves Record rides automatically out");
        String riding = read(Paths.get(args[0]).resolveSibling("RidingScreen.java").toString());
        check(riding.contains("new MoreScreen.RidingSettingsMenu(") && !riding.contains("withoutAutoRecord"), "riding mode's tune menu keeps it");
        check(body(more, "void fill(LinearLayout body)").contains("if (autoRecord) content.addView(toggle(autoRecordTitle()"),
                "the shared menu shows it unless told not to");
        check(count(rides, "M.ridesBackups()") == 1 && rides.contains("if (mode == SUMMARY) {\n            Row backups = navRow(null, M.ridesBackups()"),
                "Ride backups only on the Ride page");

        System.out.println("HubRideData: " + checks + " checks passed");
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** The body of the method whose declaration is given, by brace matching (no braces in its strings). */
    private static String body(String source, String declaration) {
        int at = source.indexOf(declaration);
        check(at >= 0, "found " + declaration);
        check(source.indexOf(declaration, at + 1) < 0, "one " + declaration);
        int open = source.indexOf('{', at), depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open + 1, i);
        }
        throw new AssertionError("unbalanced " + declaration);
    }

    /** Lines holding both a call and a key. */
    private static int lines(String text, String call, String key) {
        int n = 0;
        for (String line : text.split("\n")) if (line.contains(call) && line.contains(key)) n++;
        return n;
    }

    private static int count(String text, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
