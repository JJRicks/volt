package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HubPages: the page tree, legacy ids, More's sections and the riding rule (hub layout plan, PRs 1 and 11). */
public final class HubPagesTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        tree();
        legacy();
        subjects();
        hub();
        dock();
        riding();
        moreSections(args.length > 0 ? args[0] : "src/com/jjricks/volt/MoreScreen.java");
        iconsDrawn(args.length > 1 ? args[1] : "src/com/jjricks/volt/Ui.java");
        System.out.println("HubPages: " + checks + " checks passed");
    }

    private static void tree() {
        require(HubPages.ALL.get(0).equals(HubPages.HOME), "Home is the first page");
        require(new HashSet<>(HubPages.ALL).size() == HubPages.ALL.size(), "Page ids are unique");
        require(HubPages.parent(HubPages.HOME) == null && HubPages.depth(HubPages.HOME) == 0, "Home is the root");
        Pattern id = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*");
        for (String page : HubPages.ALL) {
            require(id.matcher(page).matches(), "Lower-case id: " + page);
            require(HubPages.known(page) && HubPages.canonical(page).equals(page),
                    "A page is its own canonical id: " + page);
            // Acyclic and rooted: walking parents reaches Home within the page count.
            String at = page; int steps = 0;
            while (at != null && !at.equals(HubPages.HOME) && steps <= HubPages.ALL.size()) { at = HubPages.parent(at); steps++; }
            require(HubPages.HOME.equals(at), "Reaches Home: " + page);
            List<String> path = HubPages.path(page);
            require(path.get(0).equals(HubPages.HOME) && path.get(path.size() - 1).equals(page), "Path runs Home to the page: " + page);
            require(HubPages.depth(page) == path.size() - 1 && HubPages.depth(page) <= 2, "Depth matches the path: " + page);
            for (int i = 1; i < path.size(); i++) require(HubPages.parent(path.get(i)).equals(path.get(i - 1)), "Path follows parents: " + page);
            if (!page.equals(HubPages.HOME)) require(HubPages.known(HubPages.parent(page)), "Parent is a page: " + page);
            // Dotted ids sit under the page their prefix names.
            int dot = page.indexOf('.');
            if (dot > 0) require(HubPages.parent(page).equals(page.substring(0, dot)), "Sub-page under its prefix: " + page);
            require(HubPages.ALL.contains(HubPages.subjectOf(page)), "Subject is a page: " + page);
        }
        require(HubPages.subjectOf(HubPages.BATTERY_MODES).equals(HubPages.BATTERY), "A sub-page's subject");
        require(HubPages.subjectOf(HubPages.HOME).equals(HubPages.HOME), "Home's subject is Home");
        require(!HubPages.known(null) && !HubPages.known("") && !HubPages.known("Stats"), "Unknown ids");
        require(HubPages.path("nowhere").equals(java.util.Collections.singletonList(HubPages.HOME)) && HubPages.depth(null) == 0,
                "An unknown id's path is Home");
        require(HubPages.parent("nowhere") == null, "An unknown id has no parent");
    }

    private static void legacy() {
        // Ids saved before the Hub: Bundle "page", last_page and Home's navigate(page) calls.
        require(HubPages.canonical("home").equals(HubPages.HOME), "home stays Home");
        require(HubPages.canonical("stats").equals(HubPages.DATA), "Live Stats is Data");
        require(HubPages.canonical("rides").equals(HubPages.RIDE), "Rides is Ride");
        require(HubPages.canonical("more").equals(HubPages.SETTINGS), "More is Settings");
        // The Scooter tab's old id is the Controls page's (PR 3): an old Bundle or last_page opens Controls on that tab.
        require(HubPages.canonical("controls").equals(HubPages.CONTROLS), "controls is the Controls page");
        require(HubPages.canonical(null).equals(HubPages.HOME) && HubPages.canonical("bogus").equals(HubPages.HOME)
                && HubPages.canonical("").equals(HubPages.HOME), "Anything else opens Home");
        // The Scooter tab's index (parts 3-10) is gone (PR 11): its id opens Home, whose subjects it listed.
        require(HubPages.canonical("scooter").equals(HubPages.HOME) && !HubPages.known(HubPages.SCOOTER), "scooter opens Home");
        for (String legacy : new String[] {"home", "stats", "controls", "rides", "more", "scooter"})
            require(HubPages.depth(HubPages.canonical(legacy)) <= 1, "Each old tab opens Home or a page directly under it: " + legacy);
    }

    private static void subjects() {
        require(HubPages.SUBJECTS.size() == 7, "Seven subjects");
        Set<String> icons = new HashSet<>();
        for (String subject : HubPages.SUBJECTS) {
            require(HubPages.parent(subject).equals(HubPages.HOME), "A subject sits under Home: " + subject);
            String icon = HubPages.icon(subject);
            require(icon != null && !icon.isEmpty() && !icon.equals("home"), "Subject icon: " + subject);
            icons.add(icon);
        }
        require(icons.size() == HubPages.SUBJECTS.size(), "Subject icons differ");
        require(HubPages.icon(HubPages.SETTINGS).equals("gear") && HubPages.icon(HubPages.SEARCH).equals("search"), "Header icons");
        require(HubPages.icon(HubPages.SERVICE_MAINTENANCE).equals(HubPages.icon(HubPages.SERVICE)), "A sub-page has its subject's icon");
        // The Layout Plan's glyphs: Battery & charging a bolt, Controls rising bars, Lights & sound a bulb, Service a wrench (part 8b).
        require(HubPages.icon(HubPages.BATTERY).equals("bolt") && HubPages.icon(HubPages.CONTROLS).equals("bars")
                && HubPages.icon(HubPages.LIGHTS).equals("bulb") && HubPages.icon(HubPages.SERVICE).equals("wrench"), "The plan's subject icons");
    }

    private static void hub() {
        // The Hub has no tabs (PR 11): Home is the root of every stack, and every other page is under a page under Home.
        for (String page : HubPages.ALL)
            require(page.equals(HubPages.HOME) || HubPages.parent(HubPages.subjectOf(page)).equals(HubPages.HOME), "Under Home: " + page);
        for (String page : new String[] {HubPages.SETTINGS, HubPages.SEARCH})
            require(HubPages.parent(page).equals(HubPages.HOME) && !HubPages.SUBJECTS.contains(page), "Header page, not a subject: " + page);
    }

    /** The Widgets dock (owner's answer 8 on #383): never cramped, every subject one tap away through "All". */
    private static void dock() {
        require(new HashSet<>(HubPages.DOCK_ORDER).equals(new HashSet<>(HubPages.SUBJECTS)) && HubPages.DOCK_ORDER.size() == 7,
                "The dock's order lists each subject once");
        // A 344 dp phone less the dock's 14 dp margins and 4 dp padding (308 dp): four subjects and All, each at least
        // 60 dp (well over 48).
        require(HubPages.dockRoom(344) == 308, "A 344 dp phone's dock room: " + HubPages.dockRoom(344));
        List<String> phone = HubPages.dockSubjects(HubPages.dockRoom(344), 1f);
        require(phone.equals(java.util.Arrays.asList(HubPages.RIDE, HubPages.BATTERY, HubPages.CONTROLS, HubPages.DATA)), "344 dp: " + phone);
        require(HubPages.dockRoom(344) / (phone.size() + 1) >= 48, "Each item gets 48 dp at 344 dp");
        for (float scale : new float[] {1f, 1.15f, 1.3f, 1.6f, 2f})
            for (int width = 280; width <= 900; width += 4) {
                List<String> shown = HubPages.dockSubjects(width, scale);
                require(!shown.isEmpty(), "At least one subject: " + width + "/" + scale);
                require(width / (shown.size() + 1f) >= HubPages.DOCK_ITEM_DP * scale || shown.size() == 1,
                        "Comfortable items: " + width + "/" + scale);
                int last = -1;
                for (String subject : shown) {
                    int at = HubPages.SUBJECTS.indexOf(subject);
                    require(at > last, "Home's order: " + shown);
                    last = at;
                }
                require(HubPages.dockSubjects(width + 4, scale).containsAll(shown), "More room never hides a subject: " + width + "/" + scale);
            }
        // At 1.3x text a 344 dp phone shows Ride and Battery & charging beside All, each item about 100 dp.
        require(HubPages.dockSubjects(HubPages.dockRoom(344), 1.3f).equals(java.util.Arrays.asList(HubPages.RIDE, HubPages.BATTERY)),
                "Large text: fewer, still comfortable: " + HubPages.dockSubjects(HubPages.dockRoom(344), 1.3f));
        require(HubPages.dockSubjects(HubPages.dockRoom(600), 1f).size() == 7, "A wide phone shows all seven");
        // A wide dock (#392 early review): full names only when every one fits on one line, else every short name.
        // 725 dp less 24 dp each side gives 677 dp, eight items of about 83.6 dp, captions of about 79.6 dp.
        float[] fits = {40, 70, 60, 79};
        require(HubPages.wideDockNames(fits, 677, 8, 96, 4), "Every name fits: full names");
        float[] oneWraps = {40, 90, 60, 50};
        require(!HubPages.wideDockNames(oneWraps, 677, 8, 96, 4), "One name would wrap (Battery & charging at 725 dp): short names");
        // With room to spare an item is capped at 96 dp: 92 dp captions.
        require(HubPages.wideDockNames(new float[] {92}, 2000, 8, 96, 4), "The cap's caption: 92 dp fits");
        require(!HubPages.wideDockNames(new float[] {92.5f}, 2000, 8, 96, 4), "Past the cap's caption: short names");
        require(!HubPages.wideDockNames(new float[] {10}, 677, 0, 96, 4), "No items: short names");
        require(!HubPages.wideDockNames(new float[] {Float.NaN}, 677, 8, 96, 4), "An unmeasured name: short names");
        // The dock's upward swipe: past twice the slop upward and more up than across; a quick flick counts on its first move.
        require(HubPages.dockSwipe(17, 0, 8), "17 dp up past a 8 dp slop: a swipe");
        require(!HubPages.dockSwipe(16, 0, 8), "Not past twice the slop: no swipe");
        require(!HubPages.dockSwipe(30, 31, 8), "More across than up: no swipe");
        require(!HubPages.dockSwipe(-30, 0, 8), "Downward: no swipe");
    }

    private static void riding() {
        for (String page : new String[] {HubPages.HOME, HubPages.RIDE, HubPages.RIDE_RIDES, HubPages.DATA,
                HubPages.CONTROLS, HubPages.BATTERY, HubPages.LIGHTS})
            require(HubPages.clearForRiding(page), "Riding mode may open over " + page);
        for (String page : new String[] {HubPages.SETTINGS, HubPages.SETTINGS_SCOOTERS, HubPages.SECURITY, HubPages.SERVICE_EXPERIMENTAL,
                HubPages.SERVICE_COMPATIBILITY, HubPages.RIDE_RIDING, HubPages.RIDE_RECORDING, HubPages.DATA_STORAGE, HubPages.SEARCH})
            require(!HubPages.clearForRiding(page), "Riding mode waits on " + page);
        require(!HubPages.clearForRiding(null) && !HubPages.clearForRiding("more"), "Unknown ids are busy");
        // The owner's answer 21 (#383): of the scooter subjects only Controls, Battery & charging and Lights & sound let it
        // open; every other subject and every sub-page blocks it. Checked over every page so a new page can't slip in.
        Set<String> clear = new HashSet<>(java.util.Arrays.asList(HubPages.HOME, HubPages.RIDE, HubPages.RIDE_RIDES,
                HubPages.DATA, HubPages.CONTROLS, HubPages.BATTERY, HubPages.LIGHTS));
        for (String page : HubPages.ALL)
            require(HubPages.clearForRiding(page) == clear.contains(page), "Only the agreed pages let riding mode open: " + page);
        for (String page : HubPages.ALL)
            if (HubPages.depth(page) > HubPages.depth(HubPages.subjectOf(page)))
                require(!HubPages.clearForRiding(page) || page.equals(HubPages.RIDE_RIDES), "A sub-page blocks it: " + page);
        // The old tab roots keep their rule: only More (Settings) blocks by page; the Scooter tab's id is not a page now.
        for (String tab : new String[] {"home", "stats", "rides", "more"})
            require(HubPages.clearForRiding(HubPages.canonical(tab)) == !tab.equals("more"), "Tab rule: " + tab);
        require(!HubPages.clearForRiding(HubPages.SCOOTER), "scooter is not a page");
    }

    private static void moreSections(String moreScreen) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(moreScreen)), StandardCharsets.UTF_8);
        Matcher sections = Pattern.compile("String\\[\\]\\s+SECTIONS\\s*=\\s*\\{([^}]*)\\}").matcher(source);
        require(sections.find(), "MoreScreen.SECTIONS found");
        List<String> ids = new ArrayList<>();
        Matcher quoted = Pattern.compile("\"([^\"]+)\"").matcher(sections.group(1));
        while (quoted.find()) ids.add(quoted.group(1));
        require(ids.size() >= 7, "MoreScreen.SECTIONS read");
        Matcher recovery = Pattern.compile("COMPATIBILITY_RECOVERY\\s*=\\s*\"([^\"]+)\"").matcher(source);
        require(recovery.find(), "MoreScreen.COMPATIBILITY_RECOVERY found");
        ids.add("Units"); ids.add("Compatibility"); ids.add(recovery.group(1));
        for (String section : ids) {
            String page = HubPages.fromMoreSection(section);
            require(HubPages.known(page), "Section has a page: " + section);
            require(!page.equals(HubPages.SETTINGS) || section.equals("App"), "Only App is Settings itself: " + section);
        }
        require(HubPages.fromMoreSection("Compatibility").equals(HubPages.SERVICE_COMPATIBILITY)
                && HubPages.fromMoreSection(recovery.group(1)).equals(HubPages.SERVICE_COMPATIBILITY), "Both Compatibility ids");
        require(HubPages.fromMoreSection("Data").equals(HubPages.DATA_STORAGE) && HubPages.fromMoreSection("Lab").equals(HubPages.SERVICE_EXPERIMENTAL)
                && HubPages.fromMoreSection("Security").equals(HubPages.SECURITY), "Sections move to their subjects");
        require(HubPages.fromMoreSection("Charging").equals(HubPages.BATTERY_HISTORY)
                && HubPages.fromMoreSection("Reports").equals(HubPages.SERVICE_REPORTS), "Charging and Reports are subject pages");
        for (String section : new String[] {"Charging", "Reports"})
            require(source.contains("\"" + section + "\""), "MoreScreen builds the " + section + " section");
        require(HubPages.fromMoreSection(null).equals(HubPages.SETTINGS) && HubPages.fromMoreSection("").equals(HubPages.SETTINGS),
                "More's index is Settings");
    }

    /** Every icon key HubPages hands out is one Ui draws: a typo would show as a missing glyph. */
    private static void iconsDrawn(String ui) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(ui)), StandardCharsets.UTF_8);
        Set<String> pages = new HashSet<>(HubPages.ALL);
        pages.add(HubPages.SCOOTER); pages.add(HubPages.HOME);
        for (String page : pages) {
            String icon = HubPages.icon(page);
            require(source.contains("key.equals(\"" + icon + "\")"), "Ui draws the icon " + icon + " for " + page);
        }
        require(source.contains("key.equals(\"" + HubPages.DOCK_ALL_ICON + "\")"), "Ui draws the dock's All icon");
    }

    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
