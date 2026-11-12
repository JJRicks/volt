package com.jjricks.volt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hub layout plan, PR 10: the text of search, the Widgets dock and the wide Home's right pane through HubText,
 * and the names search gives the scooter's hand-built rows and the settings rows (HubSearchTables), each the
 * name the row already shows. In English and under a key-echo text source (each line is one whole catalog
 * string, never glued from pieces).
 */
public final class HubSearchTextTest {
    private static int checks;

    /** The keys PR 10 adds to res/values/strings_hub.xml. */
    private static final Set<String> PR10_KEYS = new HashSet<>(Arrays.asList("hub_page_search", "hub_search_hint",
            "hub_search_none", "hub_search_count", "hub_search_group_pages", "hub_search_group_settings",
            "hub_search_group_readings", "hub_search_group_rides", "hub_search_in", "hub_dock_all", "hub_all",
            "hub_service_card_title", "hub_service_card_none"));

    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void equal(String expected, CharSequence actual, String message) {
        check(actual != null && expected.equals(actual.toString()), message + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every name of a string or plurals entry in res/values/strings_hub.xml (the suite runs from the repository root). */
    private static Set<String> hubCatalogKeys() {
        Set<String> keys = new HashSet<>();
        Matcher m = Pattern.compile("<(?:string|plurals)\\s+name=\"([^\"]+)\"").matcher(read("res/values/strings_hub.xml"));
        while (m.find()) keys.add(m.group(1));
        return keys;
    }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                T.install(null);
                search();
                dockAndPane();
                rowNames();
                settingNames();
            }
            Locale.setDefault(Locale.US);
            T.install(ECHO);
            echo();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        ids();
        System.out.println("HubSearchTextTest: " + checks + " checks passed (search, dock, wide pane, row and setting "
                + "names, in English and under the key-echo source)");
    }

    private static void search() {
        equal("Search", HubText.title(HubPages.SEARCH), "search title");
        check(HubText.rowTitle(HubPages.SEARCH).equals(HubText.title(HubPages.SEARCH)), "search's row is named as its page");
        check(!HubText.title(HubPages.SEARCH).isPlural(), "a title is not a count");
        // With search titled, every page has a title now.
        for (String page : HubPages.ALL) check(HubText.title(page) != null, "a title for " + page);
        equal("Search settings, readings and rides", HubText.searchHint(), "hint");
        equal("No matches", HubText.searchNone(), "none");
        equal("0 results", HubText.searchCount(0), "no results");
        equal("1 result", HubText.searchCount(1), "one result");
        equal("12 results", HubText.searchCount(12), "results");
        check(HubText.searchCount(3).isPlural(), "the count is a plural");
        equal("Pages", HubText.searchGroupPages(), "pages group");
        equal("Settings", HubText.searchGroupSettings(), "settings group");
        equal("Live readings", HubText.searchGroupReadings(), "readings group");
        equal("Rides", HubText.searchGroupRides(), "rides group");
        equal("In Battery & charging", HubText.searchIn(HubText.title(HubPages.BATTERY)), "in a subject (a Msg argument)");
        equal("In Speed limits", HubText.searchIn("Speed limits"), "in a sub-page");
    }

    private static void dockAndPane() {
        equal("All subjects", HubText.dockAll(), "dock");
        equal("All", HubText.all(), "right pane back link");
        equal("Next checks", HubText.serviceCardTitle(), "service card");
        equal("No checks set", HubText.serviceCardNone(), "service card, nothing set");
    }

    /** Each hand-built row keeps the name it shows today in the scooter's controls. */
    private static void rowNames() {
        equal("Headlight", HubText.controlRowTitle(HubSearchTables.ROW_HEADLIGHT_INFO), "headlight");
        equal("Line & star lights", HubText.controlRowTitle(HubSearchTables.ROW_LIGHT_ZONES), "light zones");
        equal("Scooter clock", HubText.controlRowTitle(HubSearchTables.ROW_CLOCK_SYNC), "clock");
        equal("Test scheduled charging", HubText.controlRowTitle(HubSearchTables.ROW_SCHEDULE_TEST), "schedule test");
        equal("Charge done by", HubText.controlRowTitle(HubSearchTables.ROW_CHARGE_DONE_BY), "done by");
        equal("Charger for estimates", HubText.controlRowTitle(HubSearchTables.ROW_CHARGER), "charger");
        equal("Charge alert", HubText.controlRowTitle(HubSearchTables.ROW_CHARGE_ALERT), "charge alert");
        equal("Low battery alert", HubText.controlRowTitle(HubSearchTables.ROW_LOW_ALERT), "low alert");
        equal("Live charging", HubText.controlRowTitle(HubSearchTables.ROW_CHARGE_LIVE), "live charging");
        equal("PIN & proximity unlock", HubText.controlRowTitle(HubSearchTables.ROW_SECURITY_LINK), "security");
        equal("Scooter name", HubText.controlRowTitle(HubSearchTables.ROW_SCOOTER_NAME), "name");
        equal("Reminders", HubText.controlRowTitle(HubSearchTables.ROW_MAINTENANCE), "maintenance");
        equal("Maximum speed · Sport", HubText.controlRowTitle(HubSearchTables.ROW_TOP_SPEED), "top speed");
        Set<String> keys = new HashSet<>();
        for (String row : HubSearchTables.ROWS) {
            Msg name = HubText.controlRowTitle(row);
            check(name != null && !name.render().trim().isEmpty(), "a name for row " + row);
            check(!name.isPlural(), "a row name is not a count: " + row);
            check(keys.add(T.key(name.id())), "each row has a name of its own: " + row);
        }
        check(HubText.controlRowTitle(null) == null && HubText.controlRowTitle("nowhere") == null, "no name for an unknown row");
        // Feature rows are named by their feature, not here.
        check(HubText.controlRowTitle("cruise_control") == null, "no name for a feature row");
    }

    /** Each settings row keeps the name it shows today. */
    private static void settingNames() {
        equal("Home style", HubText.settingTitle(HubSearchTables.SETTING_HOME_STYLE), "home style");
        equal("Units", HubText.settingTitle(HubSearchTables.SETTING_UNITS), "units");
        equal("Text size", HubText.settingTitle(HubSearchTables.SETTING_DISPLAY), "display card");
        equal("Language", HubText.settingTitle(HubSearchTables.SETTING_LANGUAGE), "language");
        equal("Open automatically", HubText.settingTitle(HubSearchTables.SETTING_RIDING_AUTO_OPEN), "auto open");
        equal("Record rides automatically", HubText.settingTitle(HubSearchTables.SETTING_RIDING_AUTO_RECORD), "auto record");
        equal("Hide status bar", HubText.settingTitle(HubSearchTables.SETTING_RIDING_HIDE_STATUS_BAR), "status bar");
        equal("Show seconds on clock", HubText.settingTitle(HubSearchTables.SETTING_RIDING_CLOCK_SECONDS), "seconds");
        equal("Screen brightness", HubText.settingTitle(HubSearchTables.SETTING_RIDING_BRIGHTNESS), "brightness");
        equal("Night vision", HubText.settingTitle(HubSearchTables.SETTING_RIDING_NIGHT), "night vision");
        equal("Automatically check for updates", HubText.settingTitle(HubSearchTables.SETTING_UPDATE_AUTO), "auto updates");
        equal("Check for updates", HubText.settingTitle(HubSearchTables.SETTING_CHECK_UPDATES), "check updates");
        equal("Back up & restore", HubText.settingTitle(HubSearchTables.SETTING_BACKUP), "backup");
        equal("Report an issue", HubText.settingTitle(HubSearchTables.SETTING_REPORT_ISSUE), "report");
        equal("Developer options", HubText.settingTitle(HubSearchTables.SETTING_DEVELOPER), "developer");
        // The two pages that are also settings rows share their page's title.
        check(HubText.settingTitle(HubSearchTables.SETTING_HOME_STYLE).equals(HubText.title(HubPages.SETTINGS_HOME)), "home style as its page");
        check(HubText.settingTitle(HubSearchTables.SETTING_DEVELOPER).equals(HubText.title(HubPages.SETTINGS_DEVELOPER)), "developer as its page");
        Set<String> keys = new HashSet<>();
        for (String tag : HubSearchTables.SETTINGS) {
            Msg name = HubText.settingTitle(tag);
            check(name != null && !name.render().trim().isEmpty(), "a name for setting " + tag);
            check(!name.isPlural(), "a setting name is not a count: " + tag);
            check(keys.add(T.key(name.id())), "each setting has a name of its own: " + tag);
        }
        check(HubText.settingTitle(null) == null && HubText.settingTitle("nowhere") == null, "no name for an unknown tag");
    }

    /** Under the key-echo source every line is exactly one key: nothing is glued around a catalog string. */
    private static void echo() {
        List<Msg> all = new ArrayList<>(Arrays.asList(HubText.title(HubPages.SEARCH), HubText.searchHint(),
                HubText.searchNone(), HubText.searchCount(1), HubText.searchCount(5), HubText.searchGroupPages(),
                HubText.searchGroupSettings(), HubText.searchGroupReadings(), HubText.searchGroupRides(),
                HubText.searchIn("x"), HubText.dockAll(), HubText.all(), HubText.serviceCardTitle(),
                HubText.serviceCardNone()));
        for (String row : HubSearchTables.ROWS) all.add(HubText.controlRowTitle(row));
        for (String tag : HubSearchTables.SETTINGS) all.add(HubText.settingTitle(tag));
        Set<String> reached = new HashSet<>();
        for (Msg msg : all) {
            check(msg != null && msg.id() >= 0, "a catalog message, not verbatim text");
            equal("⟦" + T.key(msg.id()) + "⟧", msg.render(), "one whole key");
            if (PR10_KEYS.contains(T.key(msg.id()))) reached.add(T.key(msg.id()));
        }
        // Every key this part adds is in strings_hub.xml and reached through HubText.
        check(hubCatalogKeys().containsAll(PR10_KEYS), "strings_hub.xml holds every search, dock and pane key");
        check(reached.equals(PR10_KEYS), "every search, dock and pane key reached: " + PR10_KEYS + " but reached " + reached);
        // The English of a message stays English whatever language is installed.
        equal("In Data", HubText.searchIn("Data").english(), "english() under another source");
        equal("3 results", HubText.searchCount(3).english(), "english() of the count");
    }

    /**
     * HubSearchTables spells out Maintenance's row id because MaintenanceScreen needs Android, which this suite
     * does not compile; the two must stay the same id (saved menu layouts and validations find the row by it).
     */
    private static void ids() {
        Matcher m = Pattern.compile("\\bROW\\s*=\\s*\"([^\"]+)\"").matcher(read("src/com/jjricks/volt/MaintenanceScreen.java"));
        check(m.find(), "MaintenanceScreen.ROW is a string constant");
        equal(m.group(1), HubSearchTables.ROW_MAINTENANCE, "HubSearchTables.ROW_MAINTENANCE is MaintenanceScreen.ROW");
        Set<String> rows = new HashSet<>(Arrays.asList(HubSearchTables.ROWS));
        check(rows.size() == HubSearchTables.ROWS.length, "no row listed twice");
        Set<String> settings = new HashSet<>(Arrays.asList(HubSearchTables.SETTINGS));
        check(settings.size() == HubSearchTables.SETTINGS.length, "no setting listed twice");
    }
}
