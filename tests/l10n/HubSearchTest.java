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

    /**
     * Spanish as the catalog ships it for the zones row's words (main_search_zones) and the light families' group names
     * (controls_feature_*_lights); every other string a stand-in.
     */
    private static final T.Source ZONES = new T.Source() {
        @Override public String string(int id) {
            String key = T.key(id);
            if ("main_search_zones".equals(key)) return "luces de línea luces de estrella zonas ambiente patrón color Boost";
            if ("controls_feature_line_lights".equals(key)) return "Luces de línea";
            if ("controls_feature_star_lights".equals(key)) return "Luces de estrella";
            return "Otra página";
        }
        @Override public String plural(int id, long quantity) { return "Otra"; }
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
            reviewNotes();
            earlyReview();
            settingsRowsFindable();
            layoutRules();
            T.install(ZONES);
            zones();
            T.install(FOREIGN);
            shown();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("HubSearchTest: " + checks + " checks passed (normalization, matching, ranking, groups, sources, gates, rows findable, layout rules)");
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
        // Settings rows: every tag has its page and words; the riding switches are on Ride › Riding mode, except Record
        // rides automatically, whose one home is Ride › Recording settings (#393 review, finding 3).
        check(HubSearch.settings().size() == HubSearchTables.SETTINGS.length, "every settings row");
        for (String tag : HubSearchTables.SETTINGS) {
            check(HubSearchTables.settingPage(tag) != null && HubSearchTables.settingWords(tag) != null, "page and words for " + tag);
            if (tag.startsWith("preference_riding_") && !tag.equals(HubSearchTables.SETTING_RIDING_AUTO_RECORD))
                check(HubSearchTables.settingPage(tag).equals(HubPages.RIDE_RIDING), "riding switch " + tag);
        }
        rowsOnTheirPages();
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
        HubSearch.Entry reading = HubSearch.reading("battery_percent", "Battery level", "Battery level", () -> "80 %", () -> false);
        check(reading.page.equals(HubPages.DATA) && reading.target == HubSearch.Target.READING && reading.english.contains("batterypercent"), "a reading opens Data, by its key too");
        check(reading.live != null && !reading.live.get(), "a reading says whether its value is live (a saved one is muted)");
        check(HubSearch.rows(ALL).get(0).live == null, "a row has no liveness");
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

    /** The search notes the reviews of parts 3, 4 and the #404 follow-up left to this part. */
    private static void reviewNotes() {
        List<HubSearch.Entry> pages = HubSearch.pages(ALL);
        // Each light family's own words, its title among them (#404 review, notes 2 and 3).
        Set<String> star = new HashSet<>();
        for (HubSearch.Entry e : HubSearch.search(pages, "star")) star.add(e.page);
        check(star.contains(HubPages.LIGHTS_STAR) && !star.contains(HubPages.LIGHTS_LINE), "\"star\" lists Star lights, not Line lights");
        check(firstPage(pages, "line lights").equals(HubPages.LIGHTS_LINE), "\"line lights\" finds Line lights");
        check(firstPage(pages, "star lights").equals(HubPages.LIGHTS_STAR), "\"star lights\" finds Star lights");
        Set<String> zones = new HashSet<>();
        for (HubSearch.Entry e : HubSearch.search(pages, "zones")) zones.add(e.page);
        check(zones.contains(HubPages.LIGHTS_LINE) && zones.contains(HubPages.LIGHTS_STAR), "\"zones\" finds both light pages");
        for (HubSearch.Entry e : pages)
            if (HubSearch.sharedPageWords(e.page)) check(e.words.length == 0, "in English a light page has no shared words: " + e.page);
        // Charge history & recordings' rows are found through its page by part 10's words (#387 review, note 8).
        for (String word : new String[] {"export", "recordings", "email"}) {
            Set<String> found = new HashSet<>();
            for (HubSearch.Entry e : HubSearch.search(pages, word)) found.add(e.page);
            check(found.contains(HubPages.BATTERY_HISTORY), "\"" + word + "\" finds Charge history & recordings");
        }
        HubSearch.Entry history = null;
        for (HubSearch.Entry e : pages) if (e.page.equals(HubPages.BATTERY_HISTORY)) history = e;
        check(history != null && history.words.length == 1 && history.words[0] != null, "its words in the current language match too");
        // A sub-page with nothing on this model (its link hides) is not a result; subjects always are.
        HubSearch.Gates noAssists = new HubSearch.Gates() {
            @Override public boolean row(String id) { return true; }
            @Override public boolean lab(String key) { return false; }
            @Override public boolean page(String page) { return !page.equals(HubPages.CONTROLS_ASSISTS) && !page.equals(HubPages.CONTROLS); }
        };
        Set<String> listed = new HashSet<>();
        for (HubSearch.Entry e : HubSearch.pages(noAssists)) listed.add(e.page);
        check(!listed.contains(HubPages.CONTROLS_ASSISTS), "a sub-page with nothing to show is left out");
        check(listed.contains(HubPages.CONTROLS), "a subject is never left out");
        // Controls shown as Experimental entries are found, on the page that shows the entry (#385 review, note 5):
        // a GT3 Max's Power Assist (Controls › Assists), a UT5 MAX's Acceleration profile (Controls).
        List<HubSearch.Entry> lab = HubSearch.controls(LAB, () -> "Experimental");
        HubSearch.Entry power = first(HubSearch.search(lab, "power assist"), "parking_assist");
        check(power != null && power.page.equals(HubPages.CONTROLS_ASSISTS) && power.target == HubSearch.Target.PAGE && power.value != null,
                "Power Assist's Experimental entry opens Controls › Assists");
        HubSearch.Entry acceleration = first(HubSearch.search(lab, "acceleration"), "acceleration");
        check(acceleration != null && acceleration.page.equals(HubPages.CONTROLS) && acceleration.target == HubSearch.Target.PAGE,
                "Acceleration's Experimental entry opens Controls");
    }
    /**
     * Each settings row search names is on the page search opens for it (#393 review, finding 3): Record rides
     * automatically lives on Ride › Recording settings, which tags its switch; Riding mode leaves it out.
     */
    private static void rowsOnTheirPages() {
        String more = read("src/com/jjricks/volt/MoreScreen.java");
        check(HubSearchTables.settingPage(HubSearchTables.SETTING_RIDING_AUTO_RECORD).equals(HubPages.RIDE_RECORDING),
                "Record rides automatically opens Ride › Recording settings");
        check(more.contains("autoRecordRow.view.setTag(HubSearchTables.SETTING_RIDING_AUTO_RECORD);"),
                "Recording settings tags its Record rides automatically switch");
        check(read("src/com/jjricks/volt/HubRidingSettings.java").contains(".withoutAutoRecord()"), "Riding mode leaves the switch out");
        for (String tag : HubSearchTables.SETTINGS) {
            String page = HubSearchTables.settingPage(tag);
            // Every other settings row is built by MoreScreen (Settings, its pages, Reports and riding mode's menu).
            if (!tag.equals(HubSearchTables.SETTING_RIDING_AUTO_RECORD))
                check(more.contains("\"" + tag + "\"") || more.contains("HubSearchTables." + constant(tag)), "MoreScreen carries the tag " + tag + " (" + page + ")");
        }
        // Brightness and Night vision bring their selectors on screen, not the cards below them (#393 review, note 6).
        check("preference-selector-riding_brightness".equals(HubSearchTables.settingAnchor(HubSearchTables.SETTING_RIDING_BRIGHTNESS)), "brightness selector");
        check("preference-selector-riding_night_vision".equals(HubSearchTables.settingAnchor(HubSearchTables.SETTING_RIDING_NIGHT)), "night vision selector");
        check(more.contains("selector.setTag(\"preference-selector-\" + key);"), "the riding selectors carry those tags");
        check(read("src/com/jjricks/volt/RidingBrightness.java").contains("\"riding_brightness\"")
                && read("src/com/jjricks/volt/NightSchedule.java").contains("\"riding_night_vision\""), "by their preference keys");
        check(HubSearchTables.settingAnchor(HubSearchTables.SETTING_UNITS).equals(HubSearchTables.SETTING_UNITS), "other rows by their own tag");
    }

    private static String constant(String tag) {
        for (java.lang.reflect.Field field : HubSearchTables.class.getDeclaredFields()) {
            try {
                if (field.getType() == String.class && tag.equals(field.get(null))) return field.getName();
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        return "?";
    }

    /** The council's early review of #393 and the maintainers' emulator check: matching, one result per thing, captions. */
    private static void earlyReview() {
        List<HubSearch.Entry> index = everything();
        // A query's words match one by one: "max speed" finds Maximum speed (the emulator check).
        HubSearch.Entry max = entry(HubSearch.Kind.SETTING, "maximum speed sport", "Maximum speed · Sport");
        check(HubSearch.matches(max, "max speed") && HubSearch.matches(max, "speed max"), "each word of the query matches");
        check(!HubSearch.matches(max, "max brake"), "every word must match");
        // Accents fold: "café" and "batería" find English words without them.
        HubSearch.Entry cafe = entry(HubSearch.Kind.PAGE, "cafe racer", "Cafe racer");
        check(HubSearch.matches(cafe, "café"), "a query's accents fold for the English words");
        check(HubSearch.unaccented("batería").equals("bateria") && HubSearch.unaccented(null).isEmpty(), "unaccented");
        // One thing, one result: PIN & proximity unlock once; the Units page and not the Units row beside it.
        check(count(HubSearch.search(index, "pin"), "PIN & proximity unlock") <= 1, "PIN & proximity unlock is listed once");
        check(count(HubSearch.search(index, "proximity"), "Proximity unlock") <= 1, "Proximity unlock is listed once");
        List<HubSearch.Entry> units = HubSearch.search(index, "units");
        check(count(units, "Units") == 1, "Units is listed once: " + names(units));
        HubSearch.Entry unitsPage = null;
        for (HubSearch.Entry e : units) if ("Units".equals(e.shown.toString())) unitsPage = e;
        check(unitsPage != null && unitsPage.kind == HubSearch.Kind.PAGE && unitsPage.page.equals(HubPages.SETTINGS_UNITS), "as its page");
        // Captions name the path (the Layout Plan's mock-up): subject › sub-page; a subject page has none.
        check(HubSearch.captionPages(entry(HubSearch.Kind.SETTING, "x", "Low battery mode")).equals(Arrays.asList(HubPages.BATTERY)), "a row on a subject");
        HubSearch.Entry modes = new HubSearch.Entry(HubSearch.Kind.SETTING, HubPages.BATTERY_MODES, "x", "Low battery mode", null, HubSearch.Target.PAGE, null);
        check(HubSearch.captionPages(modes).equals(Arrays.asList(HubPages.BATTERY, HubPages.BATTERY_MODES)), "a row on a sub-page: subject › sub-page");
        HubSearch.Entry modesPage = new HubSearch.Entry(HubSearch.Kind.PAGE, HubPages.BATTERY_MODES, "x", "Battery modes", null, HubSearch.Target.PAGE, null);
        check(HubSearch.captionPages(modesPage).equals(Arrays.asList(HubPages.BATTERY)), "a sub-page: its subject");
        HubSearch.Entry battery = new HubSearch.Entry(HubSearch.Kind.PAGE, HubPages.BATTERY, "x", "Battery & charging", null, HubSearch.Target.PAGE, null);
        check(HubSearch.captionPages(battery).isEmpty(), "a subject page: none");
        check(HubSearch.captionPages(HubSearch.reading("speed_kmh", "Speed", "Speed", () -> "0", () -> true)).equals(Arrays.asList(HubPages.DATA)), "a reading: Data");
        check(HubSearch.captionPages(HubSearch.ride("r1", "Commute", "Commute", () -> "Mar 3")).equals(Arrays.asList(HubPages.RIDE, HubPages.RIDE_RIDES)), "a ride: Ride › All rides");
        // A saved reading says so (finding 2): its age line, read when shown; a live one has none.
        HubSearch.Entry saved = HubSearch.reading("battery_percent", "Battery level", "Battery level", null, () -> "80 %", () -> false, () -> "Last seen 2 h ago");
        check(saved.note != null && "Last seen 2 h ago".contentEquals(saved.note.get()) && !saved.live.get(), "a saved reading carries its age");
        // The council's finding 4: a reading opens Data as a one-shot argument (HubNavTest), never through stats_query.
        String main = read("src/com/jjricks/volt/MainActivity.java");
        check(main.contains("HubNav.Args.reading(entry.anchor)") && !main.contains("putString(\"stats_query\",entry.anchor)"), "a reading result leaves the saved Readings search alone");
    }

    private static List<HubSearch.Entry> everything() {
        List<HubSearch.Entry> all = new ArrayList<>(HubSearch.pages(ALL));
        all.addAll(HubSearch.controls(ALL, () -> "Experimental"));
        all.addAll(HubSearch.rows(ALL));
        all.addAll(HubSearch.settings());
        return all;
    }

    private static int count(List<HubSearch.Entry> found, String shown) {
        int n = 0;
        for (HubSearch.Entry e : found) if (shown.equals(e.shown.toString())) n++;
        return n;
    }

    private static List<String> names(List<HubSearch.Entry> found) {
        List<String> out = new ArrayList<>();
        for (HubSearch.Entry e : found) out.add(e.kind + ":" + e.shown + "@" + e.page);
        return out;
    }

    /**
     * Every settings row is found by the title written on it (#393 emulator check: 45 of 114 were not). Two lists:
     * the rows the maintainers typed in turn and found nothing for; and every English row, switch or menu row whose
     * catalog entry says it is on a Hub settings page (volt:screen), so a row added there later fails here until search
     * finds it (an entry in HubSearchTables or its page's words).
     */
    private static void settingsRowsFindable() {
        List<HubSearch.Entry> index = everything();
        String[] walked = {
                "Open riding mode", "Ride backups", "Compare two rides", "Keep screen awake", "End rides automatically",
                "Start recording in the background", "Background recording", "Map settings", "Auto-save rides", "Folder",
                "GPS route", "Barometer elevation", "Motion sensors", "Record this charge", "Export charge recordings",
                "Clear this scooter's charge recordings", "Email to Volt", "Live Update status", "PIN unlock",
                "Set or change PIN", "Pair this phone with scooter", "Unlock without phone", "Diagnostics & connection activity",
                "Controls to try", "Command evidence", "Clear try results", "Favorites", "Save readings history",
                "Clear this scooter's history", "Clear all readings history", "Haptic feedback", "Show charging automatically",
                "Show the last ride after riding", "Stay connected in background", "Nearby devices", "Location", "Notifications",
                "Online map", "Connection activity", "Licenses & attribution", "Connect selected scooter", "Compatibility checker",
                "Simulate scooter as connected", "Simulated model", "Appearance", "Screen rotation",
        };
        List<String> missing = new ArrayList<>();
        for (String title : walked) if (!findsSetting(index, title)) missing.add(title);
        check(missing.isEmpty(), "rows not found by their titles: " + missing);
        Set<String> screens = new HashSet<>(Arrays.asList("App preferences", "About & permissions", "Charge history & recordings",
                "Data & history", "Developer options", "Experimental controls", "Home style settings", "Licenses & attribution",
                "More", "PIN & proximity", "Recording settings", "Ride page", "Service page", "Battery & charging page",
                "Controls page", "Lights & sound page", "Data page", "Battery alerts", "Charge schedule"));
        Set<String> purposes = new HashSet<>(Arrays.asList("row", "switch", "menu row", "row title", "row name", "row and page title"));
        java.util.regex.Pattern string = java.util.regex.Pattern.compile("<string name=\"([^\"]+)\"([^>]*)>(.*?)</string>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Pattern attribute = java.util.regex.Pattern.compile("volt:(screen|purpose)=\"([^\"]*)\"");
        int rows = 0;
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(Paths.get("res/values"))) {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.sorted()::iterator) {
                if (!file.getFileName().toString().startsWith("strings_")) continue;
                java.util.regex.Matcher m = string.matcher(read(file.toString()));
                while (m.find()) {
                    String screen = null, purpose = null;
                    java.util.regex.Matcher a = attribute.matcher(m.group(2));
                    while (a.find()) { if (a.group(1).equals("screen")) screen = unescape(a.group(2)); else purpose = a.group(2); }
                    if (screen == null || purpose == null || !screens.contains(screen) || !purposes.contains(purpose)) continue;
                    String title = unescape(m.group(3));
                    // A value to fill in, or a choice inside a sheet rather than a row of a page (Battery alerts' Notify me).
                    if (title.contains("%") || m.group(1).equals("main_alert_notify")) continue;
                    rows++;
                    if (!findsSetting(index, title)) missing.add(title + " (" + m.group(1) + ", " + screen + ")");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        check(rows > 60, "the catalog's settings rows were read: " + rows);
        check(missing.isEmpty(), "settings rows search does not find by their titles: " + missing);
    }

    /** Whether typing a row's title lists a page or a setting (a reading or a ride doesn't take the owner to the row). */
    private static boolean findsSetting(List<HubSearch.Entry> index, String title) {
        for (HubSearch.Entry e : HubSearch.search(index, title))
            if (e.kind == HubSearch.Kind.PAGE || e.kind == HubSearch.Kind.SETTING) return true;
        return false;
    }

    private static String unescape(String xml) {
        return xml.replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    /**
     * The search page's layout rules at the sizes the series checks (344 x 882 dp and 725 x 604 dp, text 1.0x and
     * 1.4x), in dp at density 1: a value beside the name never takes more than 40% of the row (finding 1); the header
     * steps aside while searching; the long placeholder only where it fits whole (344 dp at 1.4x, part 13's 262 dp pane).
     */
    private static void layoutRules() {
        // A result row's content on a phone page: the window less the page's 18 dp gutters, the card's 14 dp padding and
        // the value's 10 dp gap; a row with a chevron also less 23 dp. On the wide page (725 dp) the gutters are 24 dp.
        int phone = 344 - 2 * 18 - 2 * 14 - 10, phoneChevron = phone - 23, wide = 725 - 2 * 24 - 2 * 14 - 10;
        int phone412 = 412 - 2 * 18 - 2 * 14 - 10 - 23;
        for (float scale : new float[] {1f, 1.4f}) {
            // About these wide at 14 sp and 1.0x (the review's 266 dp for the serial answer), growing with the text.
            float serial = 266 * scale, voltage = 52 * scale, rideValue = 118 * scale;
            check(HubSearch.valueBelow(serial, phone) && HubSearch.valueBelow(serial, wide), "a raw serial answer goes under the name at " + scale + "x");
            check(!HubSearch.valueBelow(voltage, phone) && !HubSearch.valueBelow(voltage, wide), "a voltage stays beside the name at " + scale + "x");
            check(HubSearch.valueBelow(rideValue, phoneChevron), "a ride's date and distance goes under the name on a 344 dp phone at " + scale + "x");
            check(HubSearch.valueBelow(rideValue, phone412) == (scale > 1f), "beside at 1.0x, under at 1.4x on a 412 dp phone");
            check(!HubSearch.valueBelow(rideValue, wide - 23), "beside on the 725 dp page at " + scale + "x");
            // Whatever stays beside leaves the name at least 60% of the row.
            for (int content : new int[] {phone, phoneChevron, phone412, wide})
                for (float value : new float[] {voltage, rideValue, serial})
                    if (!HubSearch.valueBelow(value, content)) check(content - value >= content * 0.6f, "the name keeps 60% at " + content + " dp");
        }
        check(!HubSearch.valueBelow(500, 0), "a row not measured yet keeps its value beside");
        // Captions wrap only after a "›" (seen at 344 dp and 1.4x: "Battery & charging › Battery" over "modes").
        String nb = " ";
        check(("Battery" + nb + "&" + nb + "charging" + nb + "› Battery" + nb + "modes").equals(HubSearch.captionText(Arrays.asList("Battery & charging", "Battery modes"), t -> true)), "a path breaks only after its separator");
        check("Battery & charging".equals(HubSearch.captionText(Arrays.asList("Battery & charging"), t -> true)), "a single title wraps as usual");
        check(HubSearch.captionText(Arrays.asList(), t -> true) == null && HubSearch.captionText(Arrays.asList("", null), t -> true) == null, "no titles, no caption");
        String twoLines = HubSearch.captionText(Arrays.asList("Ride", "All rides & compare"), t -> true);
        check(twoLines.indexOf(' ') == twoLines.indexOf("›") + 1 && twoLines.indexOf(' ') == twoLines.lastIndexOf(' '), "the one break opportunity is after the separator");
        // A title wider than one caption line keeps its spaces, so it wraps between words, never mid-word (Italian
        // "Cronologia ricariche e registrazioni" at 344 dp and 1.4x); a title that fits is still kept whole.
        java.util.List<String> asked = new java.util.ArrayList<>();
        String long2 = HubSearch.captionText(Arrays.asList("Batteria e ricarica", "Cronologia ricariche e registrazioni"),
                t -> { asked.add(t); return !t.startsWith("Cronologia"); });
        check(("Batteria" + nb + "e" + nb + "ricarica" + nb + "› Cronologia ricariche e registrazioni").equals(long2), "a title too wide for one line keeps its spaces");
        check(asked.contains("Batteria e ricarica ›") && asked.contains("Cronologia ricariche e registrazioni ›"), "each title is measured with its separator");
        check("Cronologia ricariche e registrazioni".equals(HubSearch.captionText(Arrays.asList("Cronologia ricariche e registrazioni"), t -> false)), "a single title is left as is");
        // Value styles: a reading's bold; a ride's date and distance quiet (no mock-up value of its own).
        check(HubSearch.valueStrong(HubSearch.Kind.READING) && !HubSearch.valueStrong(HubSearch.Kind.RIDE), "readings bold, rides quiet");
        // The count line: only "No matches" shows (the mock-up has none; one more row above the keyboard at 725 x 604).
        check(HubSearch.countShown(0) && !HubSearch.countShown(1) && !HubSearch.countShown(29), "only No matches shows");
        // The header: hidden while the field has the cursor or holds a query, at any size; back when both go.
        check(HubSearch.headerHidden(false, true, "") && HubSearch.headerHidden(false, false, "batt") && HubSearch.headerHidden(false, true, "batt"), "hidden while searching");
        check(!HubSearch.headerHidden(false, false, "") && !HubSearch.headerHidden(false, false, "   "), "without Cancel, shown when the field is empty and left");
        // With Cancel beside the field (the search page), always hidden: one way out, the field at the very top.
        check(HubSearch.headerHidden(true, false, "") && HubSearch.headerHidden(true, false, "   ") && HubSearch.headerHidden(true, true, "batt"), "with Cancel, always hidden");
        // The placeholder: "Search settings, readings and rides" is about 232 dp at 14 sp and 1.0x; the field's room for
        // text is its width less 16 dp padding at each end and the 20 dp magnifier with its 10 dp gap.
        float hint = 232;
        int phoneField = 344 - 2 * 18 - 70 - 46, wideField = 725 - 2 * 24 - 70 - 46, pane = 262 - 2 * 18 - 70 - 46;
        check(HubSearch.longHintFits(hint, wideField) && HubSearch.longHintFits(hint * 1.4f, wideField), "the long placeholder on the wide page");
        check(!HubSearch.longHintFits(hint, phoneField) || hint <= phoneField, "the long one only where it fits whole");
        check(!HubSearch.longHintFits(hint * 1.4f, phoneField), "344 dp at 1.4x: the short one");
        check(!HubSearch.longHintFits(hint, pane), "part 13's 262 dp pane: the short one");
        check(HubSearch.longHintFits(hint, 0), "a field not measured yet keeps the long one");
    }

    private static HubSearch.Entry first(List<HubSearch.Entry> found, String anchor) {
        for (HubSearch.Entry e : found) if (anchor.equals(e.anchor)) return e;
        return null;
    }

    /**
     * The light pages carry the zones row's translated words (main_search_zones), as the controls' search did: "zonas"
     * finds both in Spanish (#404 review, P3 3). In English they match only by each family's own words (reviewNotes).
     */
    private static void zones() {
        List<HubSearch.Entry> pages = HubSearch.pages(ALL);
        Set<String> found = new HashSet<>();
        for (HubSearch.Entry e : HubSearch.search(pages, "zonas")) found.add(e.page);
        check(found.contains(HubPages.LIGHTS_LINE) && found.contains(HubPages.LIGHTS_STAR), "\"zonas\" finds both light pages");
        check(found.size() == 2, "and no other page: " + found);
        Set<String> zones = new HashSet<>();
        for (HubSearch.Entry e : HubSearch.search(pages, "zones")) zones.add(e.page);
        check(zones.contains(HubPages.LIGHTS_LINE) && zones.contains(HubPages.LIGHTS_STAR), "the English words still match");
        // A family's name in the shared words reaches only its own page (#404 review): "estrella" doesn't list Line lights.
        Set<String> star = new HashSet<>();
        for (HubSearch.Entry e : HubSearch.search(pages, "estrella")) star.add(e.page);
        check(star.contains(HubPages.LIGHTS_STAR) && !star.contains(HubPages.LIGHTS_LINE), "\"estrella\" lists Star lights only: " + star);
        Set<String> line = new HashSet<>();
        for (HubSearch.Entry e : HubSearch.search(pages, "línea")) line.add(e.page);
        check(line.contains(HubPages.LIGHTS_LINE) && !line.contains(HubPages.LIGHTS_STAR), "\"línea\" lists Line lights only: " + line);
        Set<String> both = new HashSet<>();
        for (HubSearch.Entry e : HubSearch.search(pages, "luces")) both.add(e.page);
        check(both.contains(HubPages.LIGHTS_LINE) && both.contains(HubPages.LIGHTS_STAR), "\"luces\", in both names, finds both");
        check(HubSearch.otherFamilyOnly(HubPages.LIGHTS_LINE, "Estrella") && !HubSearch.otherFamilyOnly(HubPages.LIGHTS_STAR, "Estrella"),
                "the old index's light links skip the shared words the same way");
    }

    /** In another language the shown name matches too, and its words rank it. */
    private static void shown() {
        check(!Fmt.legacy(), "another language is not legacy");
        HubSearch.Entry charger = entry(HubSearch.Kind.SETTING, "charger for estimates", "Ladegerät für Schätzungen");
        check(HubSearch.matches(charger, "ladegerät"), "the shown name matches");
        check(HubSearch.matches(charger, "Schatz"), "folded as Live Stats folds (accents)");
        check(HubSearch.matches(charger, "charger"), "the English words still match");
        check(HubSearch.rank(charger, "schätz") == 1, "a shown word start ranks");
        // Typed without its accents, a word shown with them matches, the query's words one by one (#393 emulator check).
        check(HubSearch.matches(entry(HubSearch.Kind.PAGE, "battery", "Batería y carga"), "bateria carga"), "\"bateria carga\" finds Batería y carga");
        // A reading's translated full name and group are words folded as the shown name (finding 8).
        HubSearch.Entry translated = HubSearch.reading("speed_kmh", "Speed Ride", "Velocidade", new CharSequence[] {"Velocidade", "Condução"}, () -> "0", () -> true, null);
        check(HubSearch.matches(translated, "conducao"), "\"conducao\" finds a reading in the Condução group");
        List<HubSearch.Entry> rows = HubSearch.rows(ALL);
        check(!HubSearch.search(rows, "ladegerät").isEmpty(), "rows match by the names they show");
        // The translated main_search_* words and a control's short label (Gates.words) and description match too,
        // as on the Scooter tab's search; ranking still reads the shown name.
        HubSearch.Gates words = new HubSearch.Gates() {
            @Override public boolean row(String id) { return true; }
            @Override public boolean lab(String key) { return false; }
            @Override public boolean page(String page) { return true; }
            @Override public CharSequence[] words(String id) { return new CharSequence[] {"Schnellladegerät " + id}; }
        };
        List<HubSearch.Entry> found = HubSearch.search(HubSearch.rows(words), "schnellladegerät " + HubSearchTables.ROW_CHARGER);
        check(!found.isEmpty() && found.get(0).page.equals(HubSearchTables.rowPage(HubSearchTables.ROW_CHARGER)), "a row's shown search words match: " + found.size());
        check(HubSearch.search(HubSearch.rows(ALL), "schnellladegerät").isEmpty(), "not without them");
        check(!HubSearch.search(HubSearch.controls(words, () -> "Experimental"), "schnellladegerät").isEmpty(), "a control's shown words match");
        HubSearch.Entry described = new HubSearch.Entry(HubSearch.Kind.SETTING, HubPages.BATTERY, "charger", "Ladegerät",
                new CharSequence[] {"Zeit bis voll"}, null, HubSearch.Target.PAGE, null, null);
        check(HubSearch.matches(described, "bis voll"), "a description in the current language matches");
        check(HubSearch.rank(described, "bis voll") == 3, "ranked as an extra word, not by the name");
    }
}
