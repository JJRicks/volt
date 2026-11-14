package com.jjricks.volt;

/**
 * How wide a one-column page may grow on a wide window (hub layout plan 1.11, PR 13). Settings and the other list pages
 * are one column at every width since part 11; across a whole 850 dp window a row's value sits far from its title. So
 * where a page has the window to itself (the Widgets style on a wide window, or the wide Clean Home where two panes do
 * not fit), its column keeps to {@link #MAX_DP} dp, grown with large text, centred with its header. In the wide Home's
 * page pane a page already has the pane's width, so nothing changes there; nor on a phone, where the window is narrower.
 * Home keeps its own layouts (Widgets as on main, Clean's 560 dp column), and the pages that lay themselves out in panes
 * on a wide window (Data, All rides, Maintenance reminders) keep the window. Pure Java, so the JVM suite checks it
 * (tests/PageColumnTest).
 */
final class PageColumn {
    /** The column's widest at 1x text (dp), its own side padding included. */
    static final int MAX_DP = 640;

    private PageColumn() { }

    /** Whether page keeps to the column: every page but Home and the pages with a wide layout of their own. */
    static boolean capped(String page) {
        if (page == null) return false;
        switch (page) {
            case HubPages.HOME: case HubPages.DATA: case HubPages.RIDE_RIDES: case HubPages.SERVICE_MAINTENANCE: return false;
            default: return true;
        }
    }

    /** The column's widest (dp) at a text scale (Volt's text size times the system font scale): larger text, wider. */
    static int maxDp(float textScale) {
        float scale = Float.isFinite(textScale) ? Math.max(1f, Math.min(2f, textScale)) : 1f;
        return Math.round(MAX_DP * scale);
    }

    /** The space (dp) on each side of the column in a window widthDp wide; 0 when the window is no wider than it. */
    static int sideDp(int widthDp, float textScale) {
        int max = maxDp(textScale);
        return widthDp > max ? (widthDp - max) / 2 : 0;
    }
}
