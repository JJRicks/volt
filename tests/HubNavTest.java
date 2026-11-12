package com.jjricks.volt;

import java.util.Arrays;

/** HubNav: MainActivity's page stack, its Back, tab resets and Bundle restore (hub layout plan, PR 1). */
public final class HubNavTest {
    private static int checks;

    public static void main(String[] args) {
        stack();
        back();
        tabs();
        limit();
        direction();
        restore();
        arguments();
        System.out.println("HubNav: " + checks + " checks passed");
    }

    private static void stack() {
        HubNav nav = new HubNav();
        require(nav.current().equals(HubPages.HOME) && nav.size() == 1, "Starts at Home");
        require(!nav.open(HubPages.HOME), "Opening the shown page changes nothing");
        require(nav.open(HubPages.BATTERY) && nav.open(HubPages.BATTERY_MODES), "Opens pages");
        require(stack(nav, HubPages.HOME, HubPages.BATTERY, HubPages.BATTERY_MODES), "Pushes in order");
        require(!nav.open(HubPages.BATTERY_MODES) && nav.size() == 3, "Re-opening the top is a no-op");
        require(nav.open(HubPages.BATTERY) && stack(nav, HubPages.HOME, HubPages.BATTERY), "A page lower in the stack closes those above");
        require(nav.open("stats") && nav.current().equals(HubPages.DATA), "Opening canonicalizes legacy ids");
        require(nav.open("bogus") && stack(nav, HubPages.HOME), "An unknown id opens Home");
        nav.open(HubPages.SETTINGS);
        try { nav.pages().add("x"); require(false, "pages() is read-only"); } catch (UnsupportedOperationException expected) { checks++; }
    }

    private static void back() {
        HubNav nav = new HubNav();
        require(nav.back() == null && nav.size() == 1, "Back at Home returns null and keeps Home");
        nav.open(HubPages.SERVICE); nav.open(HubPages.SERVICE_MAINTENANCE);
        require(HubPages.SERVICE.equals(nav.back()), "Back shows the page below");
        require(HubPages.HOME.equals(nav.back()), "Then Home");
        require(nav.back() == null, "Then nothing");
    }

    private static void tabs() {
        // While the tab bar remains, a tab tap resets the stack to that tab's root, so Back from any tab goes Home.
        HubNav nav = new HubNav();
        for (String legacy : new String[] {"stats", "controls", "rides", "more"}) {
            nav.reset(legacy);
            require(nav.size() == 2 && nav.current().equals(HubPages.canonical(legacy)), "Tab reset: " + legacy);
            require(HubPages.HOME.equals(nav.back()), "Back from a tab is Home: " + legacy);
        }
        nav.reset(HubPages.SETTINGS_UNITS);
        require(stack(nav, HubPages.HOME, HubPages.SETTINGS, HubPages.SETTINGS_UNITS), "Reset keeps the page's parents");
        nav.reset(HubPages.HOME);
        require(stack(nav, HubPages.HOME), "Reset to Home");
        nav.reset("lights");
        require(stack(nav, HubPages.HOME, HubPages.LIGHTS), "Reset to a subject");
    }

    private static void limit() {
        HubNav nav = new HubNav();
        // Sub-pages of different subjects never share a stack in practice, but the stack still stays bounded.
        String[] pages = {HubPages.RIDE, HubPages.RIDE_RIDES, HubPages.BATTERY, HubPages.BATTERY_MODES, HubPages.CONTROLS_ASSISTS,
                HubPages.LIGHTS, HubPages.SECURITY, HubPages.SERVICE, HubPages.DATA, HubPages.SETTINGS};
        for (String page : pages) nav.open(page);
        require(nav.size() == HubNav.MAX, "At most MAX pages");
        require(nav.pages().get(0).equals(HubPages.HOME), "Home stays at the bottom");
        require(nav.current().equals(HubPages.SETTINGS), "The newest page is on top");
        require(!nav.pages().contains(HubPages.RIDE), "The oldest page above Home is dropped");
    }

    private static void direction() {
        require(HubNav.direction(HubPages.HOME, HubPages.BATTERY) == 1, "Deeper");
        require(HubNav.direction(HubPages.BATTERY_MODES, HubPages.BATTERY) == -1, "Shallower");
        require(HubNav.direction(HubPages.BATTERY, HubPages.DATA) == 0, "Same depth");
        require(HubNav.direction("stats", HubPages.HOME) == -1, "Legacy ids count by their page");
    }

    private static void restore() {
        HubNav saved = new HubNav();
        saved.open(HubPages.SERVICE); saved.open(HubPages.SERVICE_COMPATIBILITY);
        HubNav back = HubNav.restore(saved.toArray(), saved.current());
        require(Arrays.equals(back.toArray(), saved.toArray()), "A saved stack restores as it was");
        // A Bundle from before the stack has only the page, by its old tab id.
        for (String legacy : new String[] {"home", "stats", "controls", "rides", "more"}) {
            HubNav nav = HubNav.restore(null, legacy);
            require(nav.current().equals(HubPages.canonical(legacy)) && nav.size() == (legacy.equals("home") ? 1 : 2), "Legacy page: " + legacy);
        }
        require(stack(HubNav.restore(null, null), HubPages.HOME), "No page restores Home");
        require(stack(HubNav.restore(null, "gone"), HubPages.HOME), "An unknown page restores Home");
        // Anything odd in a saved stack falls back to the saved page with its parents.
        String[][] broken = {
            {}, {HubPages.DATA}, {HubPages.HOME, "gone"}, {HubPages.HOME, "stats"}, {HubPages.HOME, HubPages.DATA, HubPages.DATA},
            {HubPages.HOME, null}, {HubPages.HOME, HubPages.CONTROLS},
            {"home", "ride", "ride.rides", "battery", "battery.modes", "lights", "security", "service", "data"},
        };
        for (String[] stack : broken) {
            HubNav nav = HubNav.restore(stack, HubPages.SETTINGS_ABOUT);
            require(stack(nav, HubPages.HOME, HubPages.SETTINGS, HubPages.SETTINGS_ABOUT), "Broken stack falls back: " + Arrays.toString(stack));
        }
        HubNav restored = HubNav.restore(saved.toArray(), HubPages.HOME);
        require(HubPages.SERVICE.equals(restored.back()), "A restored stack keeps its Back");
    }

    private static void arguments() {
        HubNav.Args inspect = HubNav.Args.inspect("cruise_control");
        require("cruise_control".equals(inspect.inspect) && !inspect.recovery && inspect.ride == null && inspect.anchor == null, "Inspect argument");
        require(HubNav.Args.recovery().recovery, "Recovery argument");
        require("r1".equals(HubNav.Args.ride("r1").ride) && "row".equals(HubNav.Args.anchor("row").anchor), "Ride and anchor arguments");
    }

    private static boolean stack(HubNav nav, String... pages) { return Arrays.equals(nav.toArray(), pages); }

    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
