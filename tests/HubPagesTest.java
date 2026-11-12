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

/** HubPages: the page tree, legacy ids, More's sections and the tab and riding rules (hub layout plan, PR 1). */
public final class HubPagesTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        tree();
        legacy();
        subjects();
        tabs();
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
            require(HubPages.known(page) && HubPages.canonical(page).equals(page) || page.equals(HubPages.CONTROLS),
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
        require(HubPages.canonical("controls").equals(HubPages.SCOOTER), "Until PR 3, controls is the Scooter tab");
        require(HubPages.canonical(null).equals(HubPages.HOME) && HubPages.canonical("bogus").equals(HubPages.HOME)
                && HubPages.canonical("").equals(HubPages.HOME), "Anything else opens Home");
        for (String legacy : new String[] {"home", "stats", "controls", "rides", "more"})
            require(HubPages.TABS.contains(HubPages.canonical(legacy)), "Each of today's tabs opens a tab root: " + legacy);
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
    }

    private static void tabs() {
        require(HubPages.TABS.size() == 5 && HubPages.TABS.get(0).equals(HubPages.HOME) && HubPages.TABS.get(2).equals(HubPages.SCOOTER),
                "Five tab roots, Home first and Scooter in the centre");
        for (String tab : HubPages.TABS) require(HubPages.tabOf(tab).equals(tab), "A tab root is its own tab: " + tab);
        for (String page : HubPages.ALL) require(HubPages.TABS.contains(HubPages.tabOf(page)), "Every page is on a tab: " + page);
        require(HubPages.tabOf(HubPages.DATA_STORAGE).equals(HubPages.DATA), "Data pages are on Live Stats");
        require(HubPages.tabOf(HubPages.RIDE_RIDING).equals(HubPages.RIDE), "Ride pages are on Rides");
        require(HubPages.tabOf(HubPages.SETTINGS_UNITS).equals(HubPages.SETTINGS), "Settings pages are on More");
        for (String page : new String[] {HubPages.CONTROLS_LIMITS, HubPages.BATTERY, HubPages.LIGHTS_STAR, HubPages.SECURITY,
                HubPages.SERVICE_COMPATIBILITY, HubPages.SCOOTER})
            require(HubPages.tabOf(page).equals(HubPages.SCOOTER), "Scooter pages are on Scooter: " + page);
        require(HubPages.tabOf("stats").equals(HubPages.DATA) && HubPages.tabOf("more").equals(HubPages.SETTINGS)
                && HubPages.tabOf("bogus").equals(HubPages.HOME), "Legacy and unknown ids find their tab");
    }

    private static void riding() {
        for (String page : new String[] {HubPages.HOME, HubPages.SCOOTER, HubPages.RIDE, HubPages.RIDE_RIDES, HubPages.DATA,
                HubPages.CONTROLS, HubPages.BATTERY, HubPages.LIGHTS})
            require(HubPages.clearForRiding(page), "Riding mode may open over " + page);
        for (String page : new String[] {HubPages.SETTINGS, HubPages.SETTINGS_SCOOTERS, HubPages.SECURITY, HubPages.SERVICE_EXPERIMENTAL,
                HubPages.SERVICE_COMPATIBILITY, HubPages.RIDE_RIDING, HubPages.RIDE_RECORDING, HubPages.DATA_STORAGE, HubPages.SEARCH})
            require(!HubPages.clearForRiding(page), "Riding mode waits on " + page);
        require(!HubPages.clearForRiding(null) && !HubPages.clearForRiding("more"), "Unknown ids are busy");
        // Today's rule with the tab roots: only More blocks by page.
        for (String tab : HubPages.TABS) require(HubPages.clearForRiding(tab) == !tab.equals(HubPages.SETTINGS), "Tab rule: " + tab);
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
    }

    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
