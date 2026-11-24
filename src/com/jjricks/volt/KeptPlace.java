package com.jjricks.volt;

import java.util.List;

/**
 * Where the owner is inside a page, beyond the page itself and its scroll, carried across a rebuild (2.0.5; the owner on
 * a Pixel Fold: "There are some situations where folding and unfolding the phone will drop you back a menu layer or
 * two"). Volt handles a fold, an unfold and a rotation itself (MainActivity's configChanges), and every one rebuilds the
 * window: each page's screen is closed and built again, so whatever the old screen held over or inside the page (a
 * ride's details, its expanded map or chart, a charge recording, a sheet) closed with it, and the owner landed on the
 * page under it. The page stack and each page's scroll already came through (MainActivity.nav, scrollPositions).
 *
 * <p>Now the old screen hands its place over (AppScreen.keepPlace) just before it closes, and the screen built for the
 * same page takes it back (AppScreen.restorePlace) once it is on screen. Only the same page does: a rebuild that also
 * changes the page (a page opened, Back) drops the place, so nothing opens again over a page the owner left. Pure Java,
 * so the rules are unit tested (KeptPlaceTest).
 */
final class KeptPlace {
    /** The page the place was taken on (MainActivity's page key: a HubPages id, or RIDING for riding mode). */
    final String page;
    /** The screen's own place (a RidePlace, a ChargePlace, riding mode's tune menu...), opaque here; null for none. */
    final Object place;
    /**
     * One of the activity's own sheets that was open over the page (Charge done by, Charge alerts, Live charging, the
     * range source, a control's choices): opens it again. Null for none.
     */
    final Runnable sheet;
    /**
     * Whether a control's choice sheet opens again: it showed and nothing was sent yet. Not when the model's caution for
     * the chosen value was up over it: that caution closes with the rebuild (its Continue would send a choice from a sheet
     * that is gone), and the owner chooses again.
     */
    static boolean reopensChoice(boolean showing, boolean sent, boolean caution) { return showing && !sent && !caution; }

    /**
     * Whether riding mode's tune menu opens again: it was open, and the scooter is still now (it says stationary and its
     * newest fresh speed is 0), read fresh rather than from the speed peak the screen's refresh already took, so a
     * rebuild while the rider moves off never puts the menu back up.
     */
    static boolean reopensTuneMenu(boolean wasOpen, boolean still) { return wasOpen && still; }

    /**
     * The newest kept place's restore (MainActivity). Each rebuild posts one; two rebuilds in one turn (an unfold's size
     * change, then its posture) post two for the same place, and only the newest runs it: the older one leaves the place
     * held for it, whatever screen the older one was meant for.
     */
    static final class Turns {
        private int last;
        int next() { return ++last; }
        boolean newest(int turn) { return turn == last; }
    }

    /** Riding mode's key: its screen fills the window over whatever page is selected. */
    static final String RIDING = "riding-mode";

    private KeptPlace(String page, Object place, Runnable sheet) { this.page = page; this.place = place; this.sheet = sheet; }

    /** What a page kept, or null when it kept nothing (nothing beyond the page and its scroll). */
    static KeptPlace of(String page, Object place, Runnable sheet) {
        return page == null || place == null && sheet == null ? null : new KeptPlace(page, place, sheet);
    }

    /** What the page now built takes back: the kept place when it was taken on the same page, else null (dropped). */
    static KeptPlace forPage(KeptPlace kept, String page) { return kept != null && kept.page.equals(page) ? kept : null; }

    /**
     * A ride on All rides or the Ride page (RidesScreen). On one column a ride shows in its details page, a window over
     * the list; on a wide window All rides shows the picked ride in a pane beside the list (RidesScreen.wide), so the same
     * place shows one way or the other after a fold. Its expanded map and expanded readings chart open again over it,
     * with their view (the map's zoom and centre, the chart's span and cursor).
     */
    static final class RidePlace {
        /** How the ride shows after the rebuild: not at all, in its details page, or picked in the wide panes. */
        enum Show { NOTHING, DETAILS, PANE }
        final String ride;
        /**
         * What closing its details page does besides closing it: go back past All rides, to the page that opened the ride
         * (HubNav.Args.backAfterRide: a search result, Home's last ride). Null for none.
         */
        final Runnable onClose;
        /** The details page's or the detail pane's scroll (px; a fold changes the width, so it is near, not exact). */
        final int scrollY;
        final boolean mapExpanded, chartExpanded;
        /** The expanded map's view (RouteMapView.ViewState) and the charts' (RideChartView.State); null for the default view. */
        final Object mapView, chartView, inlineChartView;
        /** Compare two rides was open (under the ride's details, when one was opened from it): it opens again first. */
        final boolean comparison;

        RidePlace(String ride, Runnable onClose, int scrollY, boolean mapExpanded, Object mapView,
                  boolean chartExpanded, Object chartView, Object inlineChartView) {
            this(ride, onClose, scrollY, mapExpanded, mapView, chartExpanded, chartView, inlineChartView, false);
        }
        private RidePlace(String ride, Runnable onClose, int scrollY, boolean mapExpanded, Object mapView,
                          boolean chartExpanded, Object chartView, Object inlineChartView, boolean comparison) {
            this.ride = ride; this.onClose = ride == null ? null : onClose; this.scrollY = Math.max(0, scrollY);
            this.mapExpanded = ride != null && mapExpanded; this.mapView = this.mapExpanded ? mapView : null;
            this.chartExpanded = ride != null && chartExpanded; this.chartView = this.chartExpanded ? chartView : null;
            this.inlineChartView = ride == null ? null : inlineChartView; this.comparison = comparison;
        }

        /** Compare two rides open over the list, with no ride's details over it. */
        static RidePlace comparisonOnly() { return new RidePlace(null, null, 0, false, null, false, null, null, true); }

        /** This place with Compare two rides open under it (or not): the details were opened from the comparison. */
        RidePlace overComparison(boolean open) {
            return open == comparison ? this : new RidePlace(ride, onClose, scrollY, mapExpanded, mapView, chartExpanded, chartView, inlineChartView, open);
        }

        /** The ride's details page is open (one column, or a page that has no panes): that is where the owner is. */
        static RidePlace details(String ride, Runnable onClose, int scrollY, boolean mapExpanded, Object mapView,
                                 boolean chartExpanded, Object chartView, Object inlineChartView) {
            return ride == null ? null : new RidePlace(ride, onClose, scrollY, mapExpanded, mapView, chartExpanded, chartView, inlineChartView);
        }

        /**
         * The wide panes show a ride. The pane always shows one (the remembered ride, else the newest), so the pane's ride
         * is the owner's place only when they picked it on this visit (a tap on its row, a search result, Home's last ride,
         * or a fold that brought its details here) or opened its map or chart: otherwise folding to one column must show
         * All rides' list, never open a ride's details nobody asked for. Null when nothing beyond the list is kept. onClose
         * comes with a ride whose details page a fold turned into the pane, so folding back keeps its Done.
         */
        static RidePlace pane(String ride, boolean picked, Runnable onClose, int scrollY, boolean mapExpanded, Object mapView,
                              boolean chartExpanded, Object chartView, Object inlineChartView) {
            if (ride == null || !(picked || mapExpanded || chartExpanded)) return null;
            return new RidePlace(ride, onClose, scrollY, mapExpanded, mapView, chartExpanded, chartView, inlineChartView);
        }

        /** How the ride shows on the rebuilt page: in the panes when the page has them now, else its details page. */
        Show show(boolean panes) { return ride == null ? Show.NOTHING : panes ? Show.PANE : Show.DETAILS; }

        /** Whether the ride can still show: it is among the saved rides (it may have been deleted meanwhile). */
        boolean present(List<String> saved) { return ride != null && saved != null && saved.contains(ride); }
    }

    /**
     * Charge history & recordings (ChargeHistoryDialog, a window over the page): its list, or one recording with its
     * readings chart, expanded or not.
     */
    static final class ChargePlace {
        /** The open recording; null for the list. */
        final String recording;
        /** Rows the list shows (it grows as it scrolls), and the list's or the recording's scroll (px). */
        final int listShown, scrollY;
        final boolean expanded;
        /** The recording's chart view and the expanded chart's (RideChartView.State); null for the default view. */
        final Object chartView, expandedView;

        ChargePlace(String recording, int listShown, int scrollY, boolean expanded, Object chartView, Object expandedView) {
            this.recording = recording; this.listShown = Math.max(0, listShown); this.scrollY = Math.max(0, scrollY);
            this.expanded = recording != null && expanded; this.chartView = recording == null ? null : chartView;
            this.expandedView = this.expanded ? expandedView : null;
        }

        /**
         * Which row of the reloaded list opens again: the recording's index when it is still listed, else -1 (the list
         * shows; a recording deleted meanwhile, or another scooter's, never opens).
         */
        int index(List<String> listed) { return recording == null || listed == null ? -1 : listed.indexOf(recording); }

        /** The rows the reloaded list shows: as many as before (at least one page), never more than there are. */
        int shown(int page, int total) { return Math.max(0, Math.min(total, Math.max(page, listShown))); }
    }
}
