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
        connectionLine();
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
        // The dock is pinned to the bottom (the contributor's decision, 14 Nov, #383): it neither lifts above Widgets' Lock bar
        // nor steps aside for it. Its place depends on the frame, its own height and its margin only: wherever the bar is
        // (the old cases, in dp at 3 px per dp: 344x882 at 1x and 1.4x, 412x915, 725x604 at 1x and 1.4x, scrolled or not),
        // the dock's top is the same. In px.
        int px = 3, margin = 10 * px, gap = 16 * px;
        int[][] frames = {{344, 847, 70}, {344, 847, 98}, {412, 880, 70}, {412, 880, 98}, {725, 569, 73}, {725, 530, 70}};
        int[][] bars = {{791, 843}, {820, 872}, {830, 882}, {860, 912}, {561, 615}, {431, 489}, {372, 422}, {-200, -10}, {150, 210}};
        for (int[] frame : frames) {
            int bottom = (frame[1] + 10) * px, height = frame[2] * px;
            int top = HubPages.dockTop(bottom, height, margin);
            require(top == bottom - margin - height, "The dock sits its margin above the frame's bottom: " + java.util.Arrays.toString(frame));
            for (int[] bar : bars) require(HubPages.dockTop(bottom, height, margin) == top, "The Lock bar at " + bar[0] + "-" + bar[1] + " dp does not move the dock");
            // Scrolled to its end with the clearance as its bottom padding, the page's last widget (the Lock bar when the
            // rider puts it last) ends gap above the dock: every widget can be scrolled fully clear of it.
            int padding = HubPages.dockClearance(height, margin, gap);
            require(HubPages.scrolledEndBottom(bottom, padding) == top - gap, "Scrolled to its end, the last widget clears the dock by the gap: " + java.util.Arrays.toString(frame));
            require(HubPages.scrolledEndBottom(bottom, padding - 1) > top - gap, "One pixel less and it would not");
        }
        require(HubPages.dockClearance(-5, 30, 48) == 78, "An unmeasured dock: its margin and the gap");
        // A taller dock (larger text, or captions on two lines) needs more room, never less.
        require(HubPages.dockClearance(98 * px, margin, gap) > HubPages.dockClearance(70 * px, margin, gap), "A taller dock: more room");
        require(!HubPages.dockSwipe(-30, 0, 8), "Downward: no swipe");
    }

    /**
     * The connection line in page headers (the contributor's decision, 14 Nov, #383): every page kind by every connection
     * state. Not connected covers disconnected, connecting, searching, Bluetooth off and a held release: only the plain
     * connected state (ready) counts as connected.
     */
    private static void connectionLine() {
        String[] subjects = {HubPages.RIDE, HubPages.BATTERY, HubPages.CONTROLS, HubPages.LIGHTS, HubPages.SECURITY, HubPages.SERVICE, HubPages.DATA, HubPages.SEARCH};
        String[] subPages = {HubPages.BATTERY_MODES, HubPages.BATTERY_HISTORY, HubPages.RIDE_RIDES, HubPages.RIDE_RECORDING, HubPages.SERVICE_COMPATIBILITY, HubPages.DATA_STORAGE};
        String[] appPages = {HubPages.SETTINGS, HubPages.SETTINGS_HOME, HubPages.SETTINGS_UNITS, HubPages.SETTINGS_SCOOTERS, HubPages.SETTINGS_DEVELOPER, HubPages.SETTINGS_ABOUT};
        String[] states = {"connected", "disconnected", "connecting", "searching", "Bluetooth off", "release held"};
        for (String state : states) {
            boolean connected = state.equals("connected");
            for (boolean held : new boolean[] {false, true}) {
                require(HubPages.headerConnectionLine(HubPages.HOME, connected, held) == HubPages.LINE_SHOWN, "Home keeps its own line: " + state);
                for (String[] kind : new String[][] {subjects, subPages})
                    for (String page : kind) {
                        int want = !connected ? HubPages.LINE_SHOWN : held ? HubPages.LINE_ROOM : HubPages.LINE_GONE;
                        require(HubPages.headerConnectionLine(page, connected, held) == want, page + " " + state + (held ? " (room held)" : "") + ": " + want);
                    }
                for (String page : appPages)
                    require(HubPages.headerConnectionLine(page, connected, held) == HubPages.LINE_GONE, page + " never shows the line: " + state);
            }
        }
        for (String page : HubPages.ALL) {
            require(HubPages.appPage(page) == HubPages.path(page).contains(HubPages.SETTINGS), "App pages are Settings and under it: " + page);
            if (HubPages.HOME.equals(page)) continue;
            // A connected page opened fresh has no gap where the line was; one not connected shows it.
            if (!HubPages.appPage(page)) {
                require(HubPages.headerConnectionLine(page, true, false) == HubPages.LINE_GONE, "Connected: no line and no gap: " + page);
                require(HubPages.headerConnectionLine(page, false, false) == HubPages.LINE_SHOWN, "Not connected: the line: " + page);
            }
        }
        // The old tab ids resolve as their pages do: More is Settings (never), Live Stats is Data (only when not connected).
        require(HubPages.headerConnectionLine("more", false, false) == HubPages.LINE_GONE, "More (Settings): never");
        require(HubPages.headerConnectionLine("stats", false, false) == HubPages.LINE_SHOWN, "Live Stats (Data): not connected");
        // While a page is open: shown (not connected), then connected keeps the room blank, then dropped shows it again; the
        // title's place never changes after the line first showed.
        String page = HubPages.BATTERY;
        boolean held = false;
        int[] seen = new int[4];
        boolean[] walk = {false, true, false, true};
        for (int i = 0; i < walk.length; i++) {
            seen[i] = HubPages.headerConnectionLine(page, walk[i], held);
            if (seen[i] == HubPages.LINE_SHOWN) held = true;
        }
        require(seen[0] == HubPages.LINE_SHOWN && seen[1] == HubPages.LINE_ROOM && seen[2] == HubPages.LINE_SHOWN && seen[3] == HubPages.LINE_ROOM,
                "Drop, connect, drop, connect on an open page: " + java.util.Arrays.toString(seen));
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
