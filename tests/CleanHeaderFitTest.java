package com.jjricks.volt;

/**
 * CleanHeaderFit: Clean Home's header (part 8b) at the sizes the maintainers check, 344x882 dp and 725x604 dp at 1x and
 * 1.4x text. The row is the window less Home's 18 dp side padding (MainActivity's scroller), at most Clean's 560 dp
 * column. Text widths come from the per-letter estimate of Roboto QuickActionLayoutTest uses (narrow letters and
 * spaces 0.3 em, m and w 0.85 em, capitals 0.65 em, the rest 0.55 em), 8% wider for the name's bold, at 23 sp times the
 * text scale; the robustness checks run again with text 15% wider. Line heights are Roboto's: 1.172 em a line plus
 * 0.155 em of font padding.
 */
public final class CleanHeaderFitTest {
    private static int checks;
    private static final float BOLD = 1.08f;

    private static float em(char c) {
        if ("iljtfr .:,'I".indexOf(c) >= 0) return .3f;
        if (c == 'm' || c == 'w') return .85f;
        if (Character.isUpperCase(c)) return .65f;
        return .55f;
    }

    private static CleanHeaderFit.Width width(float text, float wider) {
        return s -> {
            float total = 0;
            for (int i = 0; i < s.length(); i++) total += em(s.charAt(i));
            return total * CleanHeaderFit.NAME_SP * text * BOLD * wider;
        };
    }

    /** A text's height in dp: {@code lines} lines at {@code sp} sp. */
    private static float lineDp(float sp, int lines) { return sp * (1.172f * lines + .155f); }

    public static void main(String[] args) {
        for (int window : new int[]{344, 725})
            for (float text : new float[]{1f, 1.4f})
                for (float wider : new float[]{1f, 1.15f}) size(window, text, wider);
        order();
        targets();
        System.out.println("CleanHeaderFit: " + checks + " checks passed");
    }

    private static void size(int window, float text, float wider) {
        String at = window + " dp at " + text + "x" + (wider > 1 ? ", wider text" : "");
        float row = Math.min(window - 36, 560);
        float room = CleanHeaderFit.nameRoomDp(row);
        if (window == 344) require(room == 208, at + ": the name has 208 dp beside the two buttons (search, Settings), " + room);
        CleanHeaderFit.Width w = width(text, wider);
        float status = lineDp(12.5f * text, 1);
        // The status line's tap target: its own band above the name row, which no other view covers.
        require(CleanHeaderFit.statusTargetDp(status) >= CleanHeaderFit.TARGET_DP, at + ": the status line keeps a 48 dp target");
        require(CleanHeaderFit.statusTargetDp(status) >= CleanHeaderFit.TOP_DP + status, at + ": the status band holds its text");
        for (String name : new String[]{"Your scooter", "Midnight Runner", "Segway Ninebot Max G2", "Max", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"}) {
            CleanHeaderFit.Name fit = CleanHeaderFit.name(name, room, w);
            require(fit.lines >= 1 && fit.lines <= 2, at + ": " + name + " takes one or two lines: " + fit);
            require(fit.scale >= CleanHeaderFit.minScale() - .01f && fit.scale <= 1, at + ": " + name + " no smaller than 16 sp: " + fit);
            float nameDp = lineDp(CleanHeaderFit.NAME_SP * text * fit.scale, fit.lines);
            require(CleanHeaderFit.nameRowDp(nameDp) >= CleanHeaderFit.TARGET_DP, at + ": " + name + " keeps a 48 dp target");
            float header = CleanHeaderFit.heightDp(status, nameDp);
            // Two targets that do not overlap: the status band and the name row stack, nothing shared.
            require(header >= CleanHeaderFit.statusTargetDp(status) + CleanHeaderFit.nameRowDp(nameDp), at + ": the status band and the name row, stacked");
            require(header >= 2 * CleanHeaderFit.TARGET_DP, at + ": two 48 dp targets, the name row's and the status line's");
            if (fit.lines == 1) require(header <= 100, at + ": " + name + " on one line: two 48 dp bands, " + header);
            else require(header <= 136, at + ": " + name + " on two lines: the header is at most 136 dp, " + header);
            if (!fit.clipped) require(CleanHeaderFit.fits(name, room / fit.scale, fit.lines, w), at + ": " + name + " fits as fitted: " + fit);
        }
        // A usual name stays on one line beside the buttons: the default and a 15-letter name at 1x, the default at 1.4x.
        if (wider == 1) {
            require(CleanHeaderFit.name("Your scooter", room, w).lines == 1, at + ": Your scooter on one line");
            if (text == 1) require(CleanHeaderFit.name("Midnight Runner", room, w).lines == 1, at + ": Midnight Runner on one line");
        } else if (text == 1) require(CleanHeaderFit.name("Your scooter", room, w).lines == 1, at + ": Your scooter on one line");
        if (window == 344 && text == 1 && wider == 1) {
            require(CleanHeaderFit.name("Your scooter", room, w).scale == 1, at + ": Your scooter at its full size");
            // The mock-up's "Midnight Runner" at its full size beside search and Settings (no Edit button).
            CleanHeaderFit.Name midnight = CleanHeaderFit.name("Midnight Runner", room, w);
            require(midnight.lines == 1 && midnight.scale == 1, at + ": Midnight Runner on one line at its full size, as the mock-up: " + midnight);
            CleanHeaderFit.Name long_ = CleanHeaderFit.name("Segway Ninebot Max G2", room, w);
            require(long_.lines == 1 && long_.scale < 1 && !long_.clipped, at + ": a long name a little smaller rather than wrapped: " + long_);
            CleanHeaderFit.Name longer = CleanHeaderFit.name("Segway Ninebot Kickscooter Max G2", room, w);
            require(longer.lines == 2 && !longer.clipped, at + ": a longer name takes two whole lines: " + longer);
            float one = CleanHeaderFit.heightDp(lineDp(12.5f, 1), lineDp(23, 1));
            require(one == 100, at + ": one-line header is two 48 dp bands and 4 dp, " + one);
        }
        if (window == 725) {
            require(CleanHeaderFit.name("Segway Ninebot Max G2", room, w).lines == 1, at + ": a long name on one line on a wide window");
            if (text == 1) require(CleanHeaderFit.name("Segway Ninebot Max G2", room, w).scale == 1, at + ": at its full size");
        }
    }

    /** Smaller before two lines, two lines before cut; nothing is cut that fits two lines at the smallest size. */
    private static void order() {
        CleanHeaderFit.Width w = width(1, 1);
        float one = w.of("Midnight Runner");
        require(CleanHeaderFit.name("Midnight Runner", one, w).scale == 1, "fits exactly: full size");
        CleanHeaderFit.Name smaller = CleanHeaderFit.name("Midnight Runner", one * .9f, w);
        require(smaller.lines == 1 && smaller.scale < 1 && smaller.scale >= .9f - 1e-4f, "a little short: one line smaller, " + smaller);
        CleanHeaderFit.Name two = CleanHeaderFit.name("Midnight Runner", one * .6f, w);
        require(two.lines == 2 && two.scale == 1, "far too short: two lines at full size, " + two);
        CleanHeaderFit.Name cut = CleanHeaderFit.name("Midnight Runner", w.of("Midnight") * .5f, w);
        require(cut.clipped && cut.lines == 2, "nothing fits: two lines, cut, " + cut);
        require(CleanHeaderFit.name("", 10, w).lines == 1, "an empty name takes one line");
        require(CleanHeaderFit.nameRoomDp(100) == 0, "no room is no room");
    }

    /** The header's targets: a 48 dp name row and status target, 48 dp buttons around a 40 dp disc. */
    private static void targets() {
        require(CleanHeaderFit.TARGET_DP >= 48, "targets are at least 48 dp (docs/development-notes.md)");
        require(CleanHeaderFit.DISC_DP <= CleanHeaderFit.TARGET_DP && CleanHeaderFit.DISC_DP >= 36, "a disc inside its target");
        require(CleanHeaderFit.statusTargetDp(80) == 80 + CleanHeaderFit.TOP_DP, "a tall status line keeps the space above it");
        require(CleanHeaderFit.statusTargetDp(12.5f * 1.327f) >= 48, "the 12.5 sp status line has a 48 dp target");
        require(CleanHeaderFit.nameRowDp(10) == 48, "a short name keeps a full target");
        require(CleanHeaderFit.BUTTONS == 2, "two buttons beside the name, search and Settings, as the mock-up (no Edit)");
        require(CleanHeaderFit.nameRoomDp(308) == 308 - 2 * 48 - CleanHeaderFit.BUTTON_GAP_DP, "the name's room is the row less two buttons");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
