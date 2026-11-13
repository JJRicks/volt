package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MainActivity's page stack (hub layout plan, PR 1): Home at the bottom, the shown page on top. Pure, so the JVM suite
 * tests it. It only holds canonical, known HubPages ids; the activity saves it as a String[] in its Bundle.
 */
final class HubNav {
    /** The deepest stack kept; opening past it drops the oldest page above Home. */
    static final int MAX = 8;

    /**
     * One-shot arguments for the next page built: a feature to inspect, the compatibility check's recovery view, a ride
     * id, or the view tag to bring on screen. The page factory takes them once; they are never saved.
     */
    static final class Args {
        final String inspect, ride, anchor;
        final boolean recovery;
        Args(String inspect, boolean recovery, String ride, String anchor) {
            this.inspect = inspect; this.recovery = recovery; this.ride = ride; this.anchor = anchor;
        }
        static Args inspect(String feature) { return new Args(feature, false, null, null); }
        static Args recovery() { return new Args(null, true, null, null); }
        static Args ride(String id) { return new Args(null, false, id, null); }
        static Args anchor(String tag) { return new Args(null, false, null, tag); }
    }

    private final ArrayList<String> stack = new ArrayList<>();

    HubNav() { stack.add(HubPages.HOME); }

    /** The shown page. */
    String current() { return stack.get(stack.size() - 1); }

    /** Pages in the stack, Home included (1 at Home). */
    int size() { return stack.size(); }

    List<String> pages() { return Collections.unmodifiableList(new ArrayList<>(stack)); }

    /**
     * Opens a page above the current one. The same page as the top changes nothing (false: the caller scrolls to the top);
     * a page already in the stack closes the pages above it; Home resets the stack.
     */
    boolean open(String id) {
        String page = HubPages.canonical(id);
        if (page.equals(current())) return false;
        int at = stack.indexOf(page);
        if (at >= 0) { while (stack.size() > at + 1) stack.remove(stack.size() - 1); return true; }
        if (stack.size() >= MAX) stack.remove(1);
        stack.add(page);
        return true;
    }

    /** Closes the top page and returns the one now shown, or null at Home (nothing changes then). */
    String back() {
        if (stack.size() <= 1) return null;
        stack.remove(stack.size() - 1);
        return current();
    }

    /** Shows a page with only its parents below it (a notification, leaving riding mode or setup, a return to Home). */
    void reset(String id) {
        stack.clear();
        stack.addAll(HubPages.path(HubPages.canonical(id)));
    }

    /**
     * After a long time away (RETENTION_MS or a reboot, see {@link #awayExpired}): sub-pages close back to their subject,
     * which stays open; Home and a subject's own page are kept. True when the stack changed.
     */
    boolean closeSubPages() {
        List<String> kept = HubPages.path(HubPages.subjectOf(current()));
        if (kept.equals(stack)) return false;
        stack.clear();
        stack.addAll(kept);
        return true;
    }

    /** How long Volt may stay in the background before its open sub-pages close (generalizes More's old minute). */
    static final long RETENTION_MS = 60_000;

    /**
     * Whether a stay away from the app has expired its sub-pages: awayAt is the elapsed-realtime clock when Volt left
     * (negative when it never did), awayBoot and boot the device's boot count then and now. A clock that went backwards
     * or another boot always expires; a short return (a permission dialog, a rotation, a quick app switch) keeps them.
     */
    static boolean awayExpired(long awayAt, int awayBoot, long now, int boot) {
        if (awayAt < 0) return false;
        return now < awayAt || now - awayAt >= RETENTION_MS || awayBoot != boot;
    }

    /**
     * How long before leaving a dialog must have been on top to hold the pages (#392 early review): a dialog shown at
     * least this long before Volt left was the owner's, not the system's leaving animation taking focus.
     */
    static final long DIALOG_HOLD_MS = 750;
    /** How soon after Volt itself opened another app (an email, a file picker) leaving still counts as that app's. */
    static final long LAUNCH_GRACE_MS = 2_000;

    /**
     * Whether leaving starts the away clock that closes sub-pages after RETENTION_MS (#392 early review). Not while one of
     * Volt's own dialogs or sheets was on top (an editor such as a ride's notes may hold unsaved text, and the rebuild on
     * return would dismiss it): focusLostAt is when Volt's window last lost focus (negative while it has it). Not when a
     * page itself sent the owner to another app just before (an email to send, a file to save or pick): launchedAt is
     * when Volt last started an activity (negative for none), and the page they were using is kept for their return.
     * A clock that went backwards counts neither.
     */
    static boolean awayClockStarts(long focusLostAt, long launchedAt, long now) {
        boolean dialog = focusLostAt >= 0 && now >= focusLostAt && now - focusLostAt >= DIALOG_HOLD_MS;
        boolean launched = launchedAt >= 0 && now >= launchedAt && now - launchedAt <= LAUNCH_GRACE_MS;
        return !dialog && !launched;
    }

    /**
     * The page header's link (it names the page below and is read "Back to <page>") goes there. It skips a page's own
     * first Back step only where that step keeps the page: Search's, which clears a typed query (#392 early review); the
     * system Back keeps both steps.
     */
    static boolean linkSkipsPageBack(String page) {
        return HubPages.SEARCH.equals(HubPages.canonical(page));
    }

    /** The transition's direction from one page to another: +1 deeper, -1 shallower, 0 at the same depth. */
    static int direction(String from, String to) {
        return Integer.signum(HubPages.depth(HubPages.canonical(to)) - HubPages.depth(HubPages.canonical(from)));
    }

    String[] toArray() { return stack.toArray(new String[0]); }

    /**
     * The stack saved in a Bundle. Every entry must be a known canonical id, starting at Home, with no repeats and no
     * more than MAX pages; otherwise the saved top page (legacy tab ids included) opens with only its parents below it.
     */
    static HubNav restore(String[] saved, String page) {
        HubNav nav = new HubNav();
        if (valid(saved)) { nav.stack.clear(); Collections.addAll(nav.stack, saved); }
        else nav.reset(page);
        return nav;
    }

    private static boolean valid(String[] saved) {
        if (saved == null || saved.length == 0 || saved.length > MAX || !HubPages.HOME.equals(saved[0])) return false;
        List<String> seen = new ArrayList<>();
        for (String id : saved) {
            if (!HubPages.known(id) || !id.equals(HubPages.canonical(id)) || seen.contains(id)) return false;
            seen.add(id);
        }
        return true;
    }
}
