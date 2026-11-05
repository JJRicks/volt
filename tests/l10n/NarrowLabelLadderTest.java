package com.jjricks.volt;

import java.io.File;

/**
 * D2: narrow labels fall to their next rung instead of shrinking (the owner's rule for cramped text: the full
 * label, then its abbreviation, then the icon alone; never a word cut in two, never below the readable floor).
 *   - TileRung.first, the choice every site makes: the first rung that fits, -1 (the icon) when none does.
 *   - DashboardWords.statusRungs: the Home status line's ladder starts with statusLine's own line, in every state,
 *     then drops the drive mode, then the words "last seen", then the time; under a key-echo source each rung is
 *     the key it names, in that order.
 *   - The new rungs' English: each short rung is its full word, except the side rail's Stats and the two
 *     connection lines' shorter forms.
 *   - The five sites in source: no auto-size below Ui.READABLE_FLOOR_SP, no 10 sp Quick access fallback, a
 *     rail caption of up to two whole-word lines, a one-line reading caption that ends at an icon, and a lock switch with no blank word.
 * Runs from the repository root (it reads sources).
 */
public final class NarrowLabelLadderTest {
    private static int checks;

    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "<" + T.key(id) + ">"; }
        @Override public String plural(int id, long quantity) { return "<" + T.key(id) + ">"; }
        @Override public String language() { return "es"; }
    };

    public static void main(String[] args) {
        try {
            T.install(null);
            first();
            english();
            T.install(ECHO);
            echo();
        } finally {
            T.install(null);
        }
        sources();
        System.out.println("NarrowLabelLadderTest: " + checks + " checks passed (rung choice, status ladders, English rungs, sites)");
    }

    private static void first() {
        check(TileRung.first(new float[]{80, 50, 20}, 100) == 0, "the full rung when it fits");
        check(TileRung.first(new float[]{80, 50, 20}, 80) == 0, "the full rung when it fits exactly");
        check(TileRung.first(new float[]{80, 50, 20}, 79.5f) == 1, "the short rung when the full one does not fit");
        check(TileRung.first(new float[]{80, 50, 20}, 20) == 2, "the last rung when only it fits");
        check(TileRung.first(new float[]{80, 50, 20}, 19) == -1, "the icon (-1) when no rung fits");
        check(TileRung.first(new float[]{80, 50, 20}, 0) == -1, "the icon (-1) when there is no room");
        check(TileRung.first(new float[0], 100) == -1, "no rungs: the icon");
        check(TileRung.first(new float[]{30, 60}, 40) == 0, "the first rung that fits, not the widest");
    }

    private static void english() {
        String seen = "14:05", mode = "Sport";
        same(new String[]{"Connected · Sport", "Connected"}, DashboardWords.statusRungs(true, mode, null), "connected with a mode");
        same(new String[]{"Connected"}, DashboardWords.statusRungs(true, null, null), "connected");
        same(new String[]{"Disconnected · last seen 14:05", "Disconnected · 14:05", "Disconnected", "Disconnected"},
                DashboardWords.statusRungs(false, null, seen), "disconnected, last seen");
        same(new String[]{"Disconnected", "Disconnected"}, DashboardWords.statusRungs(false, null, null), "disconnected");
        for (boolean ready : new boolean[]{true, false})
            for (String m : new String[]{null, mode})
                for (String s : new String[]{null, seen})
                    check(DashboardWords.statusRungs(ready, m, s)[0].equals(DashboardWords.statusLine(ready, m, s)),
                            "the ladder starts with the status line itself (" + ready + ", " + m + ", " + s + ")");
        String[][] pairs = {
                {T.dashboardStatusDisconnected(), T.dashboardStatusDisconnectedShort()},
                {T.bleStatusDisconnected(), T.bleStatusDisconnectedShort()},
                {T.mainNavHome(), T.mainNavHomeShort()},
                {T.mainNavScooter(), T.mainNavScooterShort()},
                {T.mainNavRides(), T.mainNavRidesShort()},
                {T.mainNavMore(), T.mainNavMoreShort()},
                {T.mainLockLocked(), T.mainLockLockedShort()},
                {T.mainLockUnlocked(), T.mainLockUnlockedShort()},
                {T.mainLockUnavailable(), T.mainLockUnavailableShort()},
                {T.mainLockActionLock(), T.mainLockActionLockShort()},
                {T.mainLockActionUnlock(), T.mainLockActionUnlockShort()}};
        for (String[] pair : pairs) check(pair[0].equals(pair[1]), pair[0] + ": its English short rung is the same word");
        check(T.mainNavStats().equals("Live Stats") && T.mainNavStatsShort().equals("Stats"), "Live Stats falls to Stats");
        check(T.mainStatusLastSeenShort("Disconnected", "5 min ago").equals("Disconnected · 5 min ago"),
                "the connection line's short rung keeps the state and the time");
        check(T.dashboardStatusDisconnectedSeenShort(seen).equals("Disconnected · 14:05"),
                "the Home status line's short rung keeps the time");
    }

    private static void echo() {
        same(new String[]{"<dashboard_status_disconnected_seen>", "<dashboard_status_disconnected_seen_short>",
                        "<dashboard_status_disconnected>", "<dashboard_status_disconnected_short>"},
                DashboardWords.statusRungs(false, null, "x"), "key echo: disconnected, last seen");
        same(new String[]{"<dashboard_status_connected_mode>", "<dashboard_status_connected>"},
                DashboardWords.statusRungs(true, "x", null), "key echo: connected with a mode");
        same(new String[]{"<dashboard_status_disconnected>", "<dashboard_status_disconnected_short>"},
                DashboardWords.statusRungs(false, null, null), "key echo: disconnected");
    }

    private static void sources() {
        String ui = read("Ui.java"), quick = read("QuickAccessGrid.java"), caption = read("DashboardCaption.java"),
                main = read("MainActivity.java"), dashboard = read("DashboardScreen.java");
        String fit = body(ui, "public void fitStatusLine(TextView view)");
        check(fit.contains("int floor = Math.round(sp(READABLE_FLOOR_SP));") && !fit.contains("sp(7)"),
                "fitStatusLine auto-sizes no lower than the readable floor");
        check(ui.contains("public static final float READABLE_FLOOR_SP = 12;"), "the readable floor is 12 sp");
        check(body(read("StatusRungs.java"), "private void fit()").contains("TileRung.first(widths, room)"),
                "status lines pick with TileRung.first");
        String nav = body(ui, "public View nav(String label, String shortLabel,");
        check(nav.contains("caption.setMaxLines(2);") && nav.contains("rungs.maxLines = 2; rungs.show(new CharSequence[]{label, shortLabel});"),
                "the rail caption is up to two lines of whole words: the name, its short rung, then the icon alone");
        check(body(read("StatusRungs.java"), "static boolean wholeLines(").contains("breaks.isBoundary(layout.getLineEnd(line))"),
                "a rung on more than one line breaks only between whole words");
        check(nav.contains("item.setContentDescription(label);"), "the rail tab is still read by its full name");
        String content = body(quick, "void measureContent(int width, boolean compact)");
        check(!content.contains("10 * ui.textScale"), "Quick access labels no longer shrink to 10 sp");
        check(content.contains("wholeLines(full, label, room, 2) ? full : wholeLines(brief, label, room, 2) ? brief : null"),
                "Quick access labels fall from the name to its short rung, then the icon alone");
        String measure = body(caption, "protected void onMeasure(int widthSpec, int heightSpec)");
        check(measure.contains("oneLine && fallbackIcon == null && !iconBeside ? shortLabel : null"),
                "a one-line caption falls to its icon instead of an ellipsis");
        check(dashboard.contains("((DashboardCaption) name).iconBeside(next <= INLINE);"),
                "a reading tile's caption knows when its own icon already shows");
        String draw = body(main, "private void drawLockWord(");
        check(draw.contains("drawLockIcon(canvas,icon,"), "a lock switch with no word that fits shows its icon");
        check(main.contains("if(arrow&&lockRung(rungs,lockRoom(true,w,geometry))<0)arrow=false;"),
                "the switch's decorative arrow goes before its word does");
        check(main.contains("else drawLockWord(canvas,rungs,"), "the switch never draws an empty word");
    }

    private static String read(String name) {
        return L10nFiles.read(new File(L10nFiles.root(), "src/com/jjricks/volt/" + name));
    }

    /** The text of the method or block starting at signature, through its matching closing brace. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        check(start >= 0, "found " + signature);
        int open = source.indexOf('{', start), depth = 0;
        for (int at = open; at < source.length(); at++) {
            char c = source.charAt(at);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, at + 1);
        }
        throw new AssertionError("unclosed " + signature);
    }

    private static void same(String[] expected, String[] actual, String what) {
        check(java.util.Arrays.equals(expected, actual), what + ": expected " + java.util.Arrays.toString(expected)
                + ", got " + java.util.Arrays.toString(actual));
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
