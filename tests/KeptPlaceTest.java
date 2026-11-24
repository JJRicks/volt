package com.jjricks.volt;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * KeptPlace: what a page holds open comes back after a fold, an unfold or a rotation (2.0.5; owner on a Pixel Fold:
 * "folding and unfolding the phone will drop you back a menu layer or two"). Each case is a state change the owner can
 * cause, written as the rule the rebuilt page follows.
 */
public final class KeptPlaceTest {
    private static int checks;

    public static void main(String[] args) {
        pageRules();
        rideRules();
        chargeRules();
        roundOneRules();
        System.out.println("KeptPlace: " + checks + " checks passed");
    }

    /** Only the same page takes a place back; a rebuild that changes the page drops it. */
    private static void pageRules() {
        Object details = new Object();
        Runnable sheet = () -> { };
        require(KeptPlace.of(HubPages.RIDE_RIDES, null, null) == null, "A page with nothing open keeps nothing (its scroll is kept elsewhere)");
        require(KeptPlace.of(null, details, null) == null, "Setup (no page) keeps nothing");
        KeptPlace rides = KeptPlace.of(HubPages.RIDE_RIDES, details, null);
        require(KeptPlace.forPage(rides, HubPages.RIDE_RIDES) == rides && rides.place == details,
                "Unfolding on All rides with a ride open: All rides built again takes the ride back");
        require(KeptPlace.forPage(rides, HubPages.HOME) == null,
                "A rebuild onto another page (Back to Home, a page opened) drops the place: nothing reopens over a page the owner left");
        require(KeptPlace.forPage(rides, HubPages.RIDE) == null, "The Ride page is not All rides: a ride open on one never opens on the other");
        require(KeptPlace.forPage(null, HubPages.HOME) == null, "On Home with nothing open, unfolding opens nothing");
        KeptPlace battery = KeptPlace.of(HubPages.BATTERY, null, sheet);
        require(battery != null && battery.place == null && battery.sheet == sheet,
                "A sheet over Battery & charging (Charge done by, alerts, live charging) is kept with no screen place");
        require(KeptPlace.forPage(battery, HubPages.BATTERY) == battery, "Battery & charging built again opens its sheet again");
        KeptPlace riding = KeptPlace.of(KeptPlace.RIDING, Boolean.TRUE, null);
        require(KeptPlace.forPage(riding, KeptPlace.RIDING) == riding, "Riding mode's tune menu comes back on riding mode");
        require(KeptPlace.forPage(riding, HubPages.HOME) == null, "Riding mode closed by the rebuild: its tune menu never opens on Home");
        require(KeptPlace.forPage(rides, KeptPlace.RIDING) == null, "Riding mode opening over All rides doesn't carry the ride's details into it");
    }

    /** A ride's details page on one column, the picked ride in the wide panes, and their expanded map and chart. */
    private static void rideRules() {
        Object mapView = "map view", chartView = "chart view", inline = "inline view";
        Runnable back = () -> { };
        // The owner's first report: a ride's details with the map expanded, then unfold.
        KeptPlace.RidePlace phone = KeptPlace.RidePlace.details("ride-1", null, 640, true, mapView, false, null, inline);
        require(phone.show(false) == KeptPlace.RidePlace.Show.DETAILS, "Rotating a phone keeps the details page");
        require(phone.show(true) == KeptPlace.RidePlace.Show.PANE, "Unfolding onto wide All rides shows the ride in the detail pane");
        require(phone.mapExpanded && phone.mapView == mapView, "The expanded map opens again with its view");
        require(!phone.chartExpanded && phone.chartView == null, "No expanded chart was open: none opens");
        require(phone.scrollY == 640 && phone.inlineChartView == inline, "The details' scroll and its chart's view come along");
        require(KeptPlace.RidePlace.details(null, null, 0, true, mapView, true, chartView, null) == null, "No ride open: nothing kept");
        // A search result or Home's last ride: Done goes back past All rides, after a fold too.
        KeptPlace.RidePlace fromSearch = KeptPlace.RidePlace.details("ride-2", back, 0, false, null, true, chartView, null);
        require(fromSearch.onClose == back, "A ride opened from search keeps its Done (back past All rides)");
        require(fromSearch.chartExpanded && fromSearch.chartView == chartView, "The expanded readings chart opens again with its span");
        // Wide All rides always shows a ride in its pane: only a picked one is where the owner is.
        require(KeptPlace.RidePlace.pane("ride-3", false, null, 0, false, null, false, null, null) == null,
                "Wide All rides on its own (the newest ride shown by itself), then fold: the list, no details page nobody asked for");
        KeptPlace.RidePlace picked = KeptPlace.RidePlace.pane("ride-3", true, null, 120, false, null, false, null, inline);
        require(picked != null && picked.show(false) == KeptPlace.RidePlace.Show.DETAILS,
                "A ride picked in the panes, then fold: its details page");
        require(picked.show(true) == KeptPlace.RidePlace.Show.PANE, "Rotating a wide window keeps the ride in the pane");
        KeptPlace.RidePlace autoExpanded = KeptPlace.RidePlace.pane("ride-3", false, null, 0, true, mapView, false, null, null);
        require(autoExpanded != null && autoExpanded.mapExpanded, "The pane's ride with its map expanded is where the owner is, picked or not");
        require(KeptPlace.RidePlace.pane(null, true, null, 0, false, null, false, null, null) == null, "No rides: nothing kept");
        KeptPlace.RidePlace carried = KeptPlace.RidePlace.pane("ride-2", true, back, 0, false, null, false, null, null);
        require(carried.onClose == back, "Unfold then fold: a search result's Done survives its time in the pane");
        // The ride may be gone by the time the page is built again.
        List<String> saved = Arrays.asList("ride-1", "ride-2");
        require(phone.present(saved) && !picked.present(saved), "A ride deleted meanwhile doesn't open");
        require(!phone.present(null) && !phone.present(Collections.<String>emptyList()), "No saved rides: nothing opens");
        require(new KeptPlace.RidePlace("ride-1", null, -40, false, null, false, null, null).scrollY == 0, "A scroll is never negative");
    }

    /** Charge history & recordings: the list, or a recording and its expanded chart. */
    private static void chargeRules() {
        List<String> listed = Arrays.asList("c-3", "c-2", "c-1");
        // The owner's second report: a charge recording's graph, then unfold.
        KeptPlace.ChargePlace detail = new KeptPlace.ChargePlace("c-2", 50, 300, true, "chart", "expanded");
        require(detail.index(listed) == 1, "The open recording opens again from the reloaded list");
        require(detail.expanded && detail.expandedView.equals("expanded") && detail.chartView.equals("chart"),
                "Its expanded chart opens again with its span; its own chart keeps its view");
        require(new KeptPlace.ChargePlace("c-9", 50, 0, true, null, null).index(listed) == -1, "A recording deleted meanwhile: the list shows");
        KeptPlace.ChargePlace list = new KeptPlace.ChargePlace(null, 150, 2400, true, "chart", "expanded");
        require(list.index(listed) == -1 && !list.expanded && list.chartView == null && list.expandedView == null,
                "The list kept: no recording, no chart opens");
        require(list.shown(50, 400) == 150, "The list shows as many rows as before, so its scroll lands where it was");
        require(list.shown(50, 90) == 90, "Never more rows than there are");
        require(new KeptPlace.ChargePlace(null, 0, 0, false, null, null).shown(50, 400) == 50, "At least one page of rows");
        require(new KeptPlace.ChargePlace("c-1", 50, 0, false, "chart", "expanded").expandedView == null, "Not expanded: no expanded view");
    }

    /** Council round 1 on #625: the failure modes it found, as rules. */
    private static void roundOneRules() {
        // F03: an unfold's size change and its posture rebuild twice in one turn; the first restore must not take the
        // place away from the second (it was cleared before the first noticed its screen was gone).
        KeptPlace.Turns turns = new KeptPlace.Turns();
        int first = turns.next(), second = turns.next();
        require(!turns.newest(first) && turns.newest(second), "Two rebuilds in one turn: only the second restore runs the place");
        int only = turns.next();
        require(turns.newest(only), "One rebuild: its restore runs");
        // F05: a choice sheet with the model's caution up over it: the caution's Continue would send the old choice.
        require(KeptPlace.reopensChoice(true, false, false), "An open, unsent choice sheet opens again");
        require(!KeptPlace.reopensChoice(true, false, true), "With its caution up, nothing reopens: the caution closes, nothing is sent");
        require(!KeptPlace.reopensChoice(true, true, false), "A choice already sent isn't reopened: its answer is on the page");
        require(!KeptPlace.reopensChoice(false, false, false), "No sheet, nothing reopens");
        // F12: the tune menu only reopens when the scooter is still now, never from a consumed speed peak.
        require(KeptPlace.reopensTuneMenu(true, true), "Stopped: the tune menu opens again");
        require(!KeptPlace.reopensTuneMenu(true, false), "Moving off during the fold: the tune menu stays closed");
        require(!KeptPlace.reopensTuneMenu(false, true), "No menu was open: none opens");
        // F07: a ride opened from Compare two rides keeps the comparison under it.
        KeptPlace.RidePlace fromCompare = KeptPlace.RidePlace.details("ride-1", null, 0, false, null, false, null, null).overComparison(true);
        require(fromCompare.comparison && "ride-1".equals(fromCompare.ride), "Details over the comparison: both come back");
        KeptPlace.RidePlace compareOnly = KeptPlace.RidePlace.comparisonOnly();
        require(compareOnly.comparison && compareOnly.show(false) == KeptPlace.RidePlace.Show.NOTHING && compareOnly.show(true) == KeptPlace.RidePlace.Show.NOTHING,
                "The comparison alone: it comes back, no ride's details with it");
        require(!fromCompare.overComparison(false).comparison, "Closed comparison: not kept");
    }

    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
