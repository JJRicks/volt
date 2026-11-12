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

/**
 * Hub layout plan 1.12, PR 12: Hub search's pure core (HubSearch) and its table (HubSearchTables). The normalization
 * is the Scooter tab's; an entry matches by its English words, or by the name it shows in another language; results
 * come grouped (pages, settings, readings, rides), ranked inside a group (name starts with the query, a word does, it
 * holds it, only the English words match), at most HubSearch.MAX; every result opens a known page, never Home or the
 * search page; and search lists only what the model's gates offer.
 */
public final class HubSearchTest {
    private static int checks;

    /** Shows a fixed foreign word for every string, so shown-name matching (not English) is what matches. */
    private static final T.Source FOREIGN = new T.Source() {
        @Override public String string(int id) { return "Ladegerät für Schätzungen"; }
        @Override public String plural(int id, long quantity) { return "Ladegerät"; }
        @Override public String language() { return "es"; }
    };

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    private static final HubSearch.Gates ALL = new HubSearch.Gates() {
        @Override public boolean row(String id) { return true; }
        @Override public boolean lab(String key) { return false; }
        @Override public boolean page(String page) { return true; }
    };
    private static final HubSearch.Gates NONE = new HubSearch.Gates() {
        @Override public boolean row(String id) { return false; }
        @Override public boolean lab(String key) { return false; }
        @Override public boolean page(String page) { return false; }
    };
    private static final HubSearch.Gates LAB = new HubSearch.Gates() {
        @Override public boolean row(String id) { return false; }
        @Override public boolean lab(String key) { return true; }
        @Override public boolean page(String page) { return true; }
    };

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            normalize();
            matching();
            ranking();
            grouping();
            sources();
            gates();
            T.install(FOREIGN);
            shown();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("HubSearchTest: " + checks + " checks passed (normalization, matching, ranking, groups, sources, gates)");
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static HubSearch.Entry entry(HubSearch.Kind kind, String english, String shown) {
        return new HubSearch.Entry(kind, HubPages.BATTERY, english, shown, null, HubSearch.Target.PAGE, null);
    }

    /** The same normalization as the Scooter tab's search (MainActivity.normalize), character for character. */
    private static void normalize() {
        String main = read("src/com/jjricks/volt/MainActivity.java");
        String rule = "toLowerCase(Locale.ROOT).replaceAll(\"[\\\\s_-]+\",\"\")";
        check(main.contains("private static String normalize(String text){return text==null?\"\":text." + rule + ";}"),
                "MainActivity.normalize is still " + rule);
        String own = read("src/com/jjricks/volt/HubSearch.java");
        check(own.contains("text.toLowerCase(Locale.ROOT).replaceAll(\"[\\\\s_-]+\", \"\")"), "HubSearch.normalize uses the same rule");
        check(HubSearch.normalize("Top speed_max-SPEED").equals("topspeedmaxspeed"), "spaces, underscores and hyphens go");
        check(HubSearch.normalize(null).isEmpty(), "null is empty");
        check(HubSearch.normalize("  ").isEmpty(), "blank is empty");
        check(HubSearch.normalize("İ").equals("İ".toLowerCase(Locale.ROOT)), "root locale lower case");
    }

    private static void matching() {
        HubSearch.Entry charger = entry(HubSearch.Kind.SETTING, "Charger for estimates charger auto stock", "Charger for estimates");
        check(HubSearch.matches(charger, "charger"), "a word matches");
        check(HubSearch.matches(charger, "Auto Stock"), "spaces and case don't matter");
        check(HubSearch.matches(charger, "for-est"), "inside words, across a space");
        check(!HubSearch.matches(charger, "brake"), "another word doesn't");
        check(!HubSearch.matches(charger, "   "), "a blank query matches nothing");
        check(HubSearch.search(Arrays.asList(charger), "").isEmpty(), "an empty query lists nothing");
        check(HubSearch.search(null, "charger").isEmpty(), "no index, no results");
    }

    private static void ranking() {
        HubSearch.Entry english = entry(HubSearch.Kind.SETTING, "alarm battery", "Charge alert");
        HubSearch.Entry inside = entry(HubSearch.Kind.SETTING, "x", "Scheduledalarm");
        HubSearch.Entry word = entry(HubSearch.Kind.SETTING, "x", "Low alarm");
        HubSearch.Entry prefix = entry(HubSearch.Kind.SETTING, "x", "Alarm sound");
        HubSearch.Entry none = entry(HubSearch.Kind.SETTING, "nothing", "Headlight");
        check(HubSearch.rank(prefix, "alarm") == 0, "the name starts with it");
        check(HubSearch.rank(word, "alarm") == 1, "a word starts with it");
        check(HubSearch.rank(inside, "alarm") == 2, "the name holds it");
        check(HubSearch.rank(english, "alarm") == 3, "only the English words hold it");
        // English: the shown name doesn't match by itself, so add the words.
        List<HubSearch.Entry> index = Arrays.asList(none, english,
                entry(HubSearch.Kind.SETTING, "scheduledalarm", "Scheduledalarm"),
                entry(HubSearch.Kind.SETTING, "low alarm", "Low alarm"),
                entry(HubSearch.Kind.SETTING, "alarm sound", "Alarm sound"));
        List<HubSearch.Entry> found = HubSearch.search(index, "Alarm");
        check(found.size() == 4, "four match: " + found.size());
        check(found.get(0).shown.toString().equals("Alarm sound"), "prefix first");
        check(found.get(1).shown.toString().equals("Low alarm"), "word start second");
        check(found.get(2).shown.toString().equals("Scheduledalarm"), "inside third");
        check(found.get(3).shown.toString().equals("Charge alert"), "English-only last");
        // Equal ranks keep the index's order.
        List<HubSearch.Entry> same = HubSearch.search(Arrays.asList(
                entry(HubSearch.Kind.SETTING, "b one", "B one"), entry(HubSearch.Kind.SETTING, "a one", "A one")), "one");
        check(same.get(0).shown.toString().equals("B one") && same.get(1).shown.toString().equals("A one"), "ties keep the index's order");
    }

    private static void grouping() {
        List<HubSearch.Entry> index = new ArrayList<>();
        index.add(entry(HubSearch.Kind.RIDE, "battery ride", "Battery ride"));
        index.add(entry(HubSearch.Kind.READING, "battery level", "Battery level"));
        index.add(entry(HubSearch.Kind.SETTING, "battery alert", "Battery alert"));
        index.add(entry(HubSearch.Kind.PAGE, "battery page", "Battery page"));
        List<HubSearch.Entry> found = HubSearch.search(index, "battery");
        HubSearch.Kind[] order = HubSearch.Kind.values();
        check(found.size() == 4, "every group");
        for (int i = 0; i < order.length; i++) check(found.get(i).kind == order[i], "group " + i + " is " + order[i]);
        check(Arrays.asList(order).equals(Arrays.asList(HubSearch.Kind.PAGE, HubSearch.Kind.SETTING, HubSearch.Kind.READING, HubSearch.Kind.RIDE)),
                "pages, settings, readings, rides");
        List<HubSearch.Entry> many = new ArrayList<>();
        for (int i = 0; i < HubSearch.MAX + 20; i++) many.add(entry(HubSearch.Kind.RIDE, "ride " + i, "Ride " + i));
        many.add(entry(HubSearch.Kind.PAGE, "ride page", "Ride page"));
        List<HubSearch.Entry> capped = HubSearch.search(many, "ride");
        check(capped.size() == HubSearch.MAX, "at most MAX results");
        check(capped.get(0).kind == HubSearch.Kind.PAGE, "a page beats rides even when it was indexed last");
    }

    /** Every source's entry opens a known page that isn't Home or search, with a title; ids are the rows' own. */
    private static void sources() {
        List<HubSearch.Entry> all = new ArrayList<>();
        List<HubSearch.Entry> pages = HubSearch.pages(ALL);
        all.addAll(pages);
        all.addAll(HubSearch.controls(ALL, () -> "Experimental"));
        all.addAll(HubSearch.rows(ALL));
        all.addAll(HubSearch.settings());
        for (HubSearch.Entry e : all) {
            check(HubPages.known(e.page), "a known page: " + e.page);
            check(e.page.equals(HubPages.canonical(e.page)), "a canonical page: " + e.page);
            check(!e.page.equals(HubPages.HOME) && !e.page.equals(HubPages.SEARCH), "never Home or search: " + e.id());
            check(e.shown != null && e.shown.length() > 0, "a shown name: " + e.id());
            check(!e.english.isEmpty(), "English words: " + e.id());
        }
        Set<String> pageIds = new HashSet<>();
        for (HubSearch.Entry e : pages) { pageIds.add(e.page); check(e.kind == HubSearch.Kind.PAGE && e.anchor == null, "a page result opens the page"); }
        for (String page : HubPages.ALL)
            check(pageIds.contains(page) == !(page.equals(HubPages.HOME) || page.equals(HubPages.SEARCH)), "page listed: " + page);
        // Hand-built rows: each has a page, words and its title; the anchor is the row's id unless the page is the row.
        check(HubSearch.rows(ALL).size() == HubSearchTables.ROWS.length, "every hand-built row");
        for (String row : HubSearchTables.ROWS) {
            check(HubSearchTables.rowPage(row) != null, "a page for row " + row);
            check(HubSearchTables.rowWords(row) != null, "words for row " + row);
            check(HubSearchTables.rowAnchor(row) == null == ControlsPages.RETIRED.contains(row), "anchor for row " + row);
            String page = ControlsPages.pageOf(row);
            check(page == null || page.equals(HubSearchTables.rowPage(row)), "a row still on a scooter page opens that page: " + row);
        }
        check(HubSearchTables.rowPage(HubSearchTables.ROW_MAINTENANCE).equals(HubPages.SERVICE_MAINTENANCE), "maintenance");
        check(HubSearchTables.rowPage(HubSearchTables.ROW_TOP_SPEED).equals(HubPages.CONTROLS), "top speed on Controls");
        check(HubSearchTables.rowPage(HubSearchTables.ROW_CHARGE_DONE_BY).equals(ControlsPages.pageOf("timed_charging")), "Done by follows its parent");
        // Settings rows: every tag has its page and words; the riding switches are on Ride › Riding mode.
        check(HubSearch.settings().size() == HubSearchTables.SETTINGS.length, "every settings row");
        for (String tag : HubSearchTables.SETTINGS) {
            check(HubSearchTables.settingPage(tag) != null && HubSearchTables.settingWords(tag) != null, "page and words for " + tag);
            if (tag.startsWith("preference_riding_")) check(HubSearchTables.settingPage(tag).equals(HubPages.RIDE_RIDING), "riding switch " + tag);
        }
        check(HubSearchTables.settingPage(HubSearchTables.SETTING_REPORT_ISSUE).equals(HubPages.SERVICE_REPORTS), "issue report on Reports");
        check(HubSearchTables.settingPage("not-a-row") == null && HubSearchTables.rowWords("not-a-row") == null, "unknown ids");
        // Controls: every catalog control on the page its row is on.
        for (HubSearch.Entry e : HubSearch.controls(ALL, () -> "Experimental")) {
            check(e.kind == HubSearch.Kind.SETTING, "controls are settings");
            if (e.anchor == null) { check(e.page.equals(HubPages.SECURITY), "the lock, PIN and proximity open Security"); continue; }
            String page = ControlsPages.pageOf(e.anchor);
            check(page == null || page.equals(e.page), "a control opens its row's page: " + e.anchor);
        }
        // Readings and rides.
        HubSearch.Entry reading = HubSearch.reading("battery_percent", "Battery level", "Battery level", () -> "80 %");
        check(reading.page.equals(HubPages.DATA) && reading.target == HubSearch.Target.READING && reading.english.contains("batterypercent"), "a reading opens Data, by its key too");
        HubSearch.Entry ride = HubSearch.ride("r1", "Commute Mar 3", "Commute", () -> "Mar 3");
        check(ride.page.equals(HubPages.RIDE_RIDES) && ride.target == HubSearch.Target.RIDE && "r1".equals(ride.anchor), "a ride opens All rides with its id");
        // English words find what the Scooter tab's search found.
        List<HubSearch.Entry> index = new ArrayList<>(HubSearch.rows(ALL));
        check(firstPage(index, "deadline").equals(HubPages.BATTERY), "deadline finds Done by");
        check(firstPage(index, "km/h").equals(HubPages.CONTROLS), "km/h finds the top speed card");
        check(firstPage(HubSearch.settings(), "backup").equals(HubPages.SETTINGS), "backup finds Back up & restore");
        check(firstPage(HubSearch.pages(ALL), "live stats").equals(HubPages.DATA), "Live Stats finds Data");
    }

    private static String firstPage(List<HubSearch.Entry> index, String query) {
        List<HubSearch.Entry> found = HubSearch.search(index, query);
        check(!found.isEmpty(), "something matches " + query);
        return found.get(0).page;
    }

    /** Search lists only what the gates offer: rows, model-only pages and Lab tries (as Experimental). */
    private static void gates() {
        check(HubSearch.rows(NONE).isEmpty(), "no rows the model lacks");
        List<HubSearch.Entry> none = HubSearch.controls(NONE, () -> "Experimental");
        check(none.size() == 1 && none.get(0).page.equals(HubPages.SECURITY), "only the lock bar, always: " + none.size());
        Set<String> pages = new HashSet<>();
        for (HubSearch.Entry e : HubSearch.pages(NONE)) pages.add(e.page);
        check(!pages.contains(HubPages.LIGHTS_LINE) && !pages.contains(HubPages.LIGHTS_STAR), "no light family the model lacks");
        check(pages.contains(HubPages.LIGHTS), "other pages stay");
        for (HubSearch.Entry e : HubSearch.controls(LAB, () -> "Experimental")) {
            if (e.anchor == null) continue;
            check(e.value != null && "Experimental".contentEquals(e.value.get()), "a Lab try says Experimental: " + e.anchor);
            if (ControlsPages.pageOf(e.anchor) == null)
                check(e.target == HubSearch.Target.INSPECT && e.page.equals(HubPages.SERVICE_EXPERIMENTAL), "no page: its try opens: " + e.anchor);
            else check(e.target == HubSearch.Target.PAGE && e.page.equals(ControlsPages.pageOf(e.anchor)), "its entry on its page: " + e.anchor);
        }
        for (HubSearch.Entry e : HubSearch.controls(ALL, () -> "Experimental"))
            check(e.value == null, "an ordinary row has no badge: " + e.id());
    }

    /** In another language the shown name matches too, and its words rank it. */
    private static void shown() {
        check(!Fmt.legacy(), "another language is not legacy");
        HubSearch.Entry charger = entry(HubSearch.Kind.SETTING, "charger for estimates", "Ladegerät für Schätzungen");
        check(HubSearch.matches(charger, "ladegerät"), "the shown name matches");
        check(HubSearch.matches(charger, "Schatz"), "folded as Live Stats folds (accents)");
        check(HubSearch.matches(charger, "charger"), "the English words still match");
        check(HubSearch.rank(charger, "schätz") == 1, "a shown word start ranks");
        List<HubSearch.Entry> rows = HubSearch.rows(ALL);
        check(!HubSearch.search(rows, "ladegerät").isEmpty(), "rows match by the names they show");
    }
}
