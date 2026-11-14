package com.jjricks.volt;

import java.util.Arrays;

/** HubNav: MainActivity's page stack, its Back, resets, Bundle restore and retention (hub layout plan, PRs 1 and 11). */
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
        retention();
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
        // A reset (a notification, an old tab id from a validation or a Bundle) keeps only the page's parents, so Back goes Home.
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
        // The Scooter tab's old index is Home now (PR 11).
        nav.reset("scooter");
        require(stack(nav, HubPages.HOME), "The Scooter tab's id resets to Home");
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
            {HubPages.HOME, null}, {HubPages.HOME, "rides"},
            {"home", "ride", "ride.rides", "battery", "battery.modes", "lights", "security", "service", "data"},
        };
        for (String[] stack : broken) {
            HubNav nav = HubNav.restore(stack, HubPages.SETTINGS_ABOUT);
            require(stack(nav, HubPages.HOME, HubPages.SETTINGS, HubPages.SETTINGS_ABOUT), "Broken stack falls back: " + Arrays.toString(stack));
        }
        // A stack saved by parts 3-10 under the Scooter tab's index opens the saved page under Home.
        require(stack(HubNav.restore(new String[] {"home", "scooter", "service", "service.maintenance"}, HubPages.SERVICE_MAINTENANCE),
                HubPages.HOME, HubPages.SERVICE, HubPages.SERVICE_MAINTENANCE), "A Scooter-tab stack falls back to the page's parents");
        HubNav restored = HubNav.restore(saved.toArray(), HubPages.HOME);
        require(HubPages.SERVICE.equals(restored.back()), "A restored stack keeps its Back");
    }

    private static void arguments() {
        HubNav.Args inspect = HubNav.Args.inspect("cruise_control");
        require("cruise_control".equals(inspect.inspect) && !inspect.recovery && inspect.ride == null && inspect.anchor == null, "Inspect argument");
        require(HubNav.Args.recovery().recovery, "Recovery argument");
        require("r1".equals(HubNav.Args.ride("r1").ride) && "row".equals(HubNav.Args.anchor("row").anchor), "Ride and anchor arguments");
        HubNav.Args reading = HubNav.Args.reading("speed_kmh");
        require("speed_kmh".equals(reading.reading) && reading.anchor == null && reading.ride == null && reading.inspect == null && !reading.recovery, "A reading argument carries only its key (#393 review, finding 4)");
        require(HubNav.Args.anchor("row").reading == null && HubNav.Args.ride("r1").reading == null, "Other arguments carry no reading");
        HubNav.Args back = HubNav.Args.rideAndBack("r2");
        require("r2".equals(back.ride) && back.backAfterRide && back.anchor == null && back.reading == null, "A search result's ride closes back to the search");
        require(!HubNav.Args.ride("r1").backAfterRide, "Home's last ride keeps All rides under its details");
    }

    private static void retention() {
        // A long time away closes sub-pages back to their subject; Home and a subject's own page stay.
        HubNav nav = new HubNav();
        nav.open(HubPages.SETTINGS); nav.open(HubPages.SETTINGS_SCOOTERS);
        require(nav.closeSubPages() && stack(nav, HubPages.HOME, HubPages.SETTINGS), "Sub-page closes to its subject");
        require(!nav.closeSubPages() && stack(nav, HubPages.HOME, HubPages.SETTINGS), "A subject stays");
        nav.reset(HubPages.HOME);
        require(!nav.closeSubPages() && stack(nav, HubPages.HOME), "Home is never forced");
        nav.open(HubPages.BATTERY); nav.open(HubPages.DATA); nav.open(HubPages.DATA_STORAGE);
        require(nav.closeSubPages() && stack(nav, HubPages.HOME, HubPages.DATA), "Pages opened through other subjects close too");
        // The clock rules: a minute, a clock that went backwards, a reboot; never when Volt didn't leave.
        long minute = HubNav.RETENTION_MS;
        require(minute == 60_000, "One minute, as More's submenu kept");
        require(!HubNav.awayExpired(-1, 3, 999_999, 3), "Never away");
        require(!HubNav.awayExpired(1_000, 3, 1_000 + minute - 1, 3), "A short return keeps the stack");
        require(HubNav.awayExpired(1_000, 3, 1_000 + minute, 3), "A minute closes sub-pages");
        require(HubNav.awayExpired(1_000, 3, 500, 3), "A clock that went backwards closes them");
        require(HubNav.awayExpired(1_000, 3, 1_001, 4), "A reboot closes them");
        // When leaving starts that clock (#392 early review): not over Volt's own dialog (a ride's notes being typed),
        // not when a page itself opened another app just before (an email, a file picker).
        require(HubNav.awayClockStarts(false, -1, 50_000), "Plain leave: the clock starts");
        require(!HubNav.awayClockStarts(true, -1, 50_000), "Leaving with Volt's own dialog showing: the stack is kept");
        require(HubNav.awayClockStarts(false, -1, 50_000),
                "Focus lost to a window that is not Volt's (the notification shade, split screen), then leave: the clock starts");
        require(!HubNav.awayClockStarts(true, 40_000, 50_000), "A dialog showing and an old launch: kept");
        require(!HubNav.awayClockStarts(false, 49_500, 50_000), "A page opened another app 0.5 s before: kept");
        require(!HubNav.awayClockStarts(false, 50_000 - HubNav.LAUNCH_GRACE_MS, 50_000), "Opened another app at the grace's end: kept");
        require(HubNav.awayClockStarts(false, 40_000, 50_000), "An app opened long before leaving: the clock starts");
        require(HubNav.awayClockStarts(false, 60_000, 50_000), "A launch on a clock that went backwards: the clock starts");
        // The page header's link goes where it says: on Search it skips the query-clearing step; elsewhere the page's own Back runs.
        require(HubNav.linkSkipsPageBack(HubPages.SEARCH), "Search's link goes to the page it names");
        require(!HubNav.linkSkipsPageBack(HubPages.SETTINGS), "Settings' link keeps the page's own Back step");
        require(!HubNav.linkSkipsPageBack(HubPages.RIDE), "Ride's link keeps the page's own Back step");
        require(!HubNav.linkSkipsPageBack(null), "No page: the page's own Back step");
    }

    private static boolean stack(HubNav nav, String... pages) { return Arrays.equals(nav.toArray(), pages); }

    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
