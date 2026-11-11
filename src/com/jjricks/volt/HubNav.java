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

    /** Shows a page with only its parents below it (a tab tap, a notification or a return to Home). */
    void reset(String id) {
        stack.clear();
        stack.addAll(HubPages.path(HubPages.canonical(id)));
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
