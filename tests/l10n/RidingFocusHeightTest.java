package com.jjricks.volt;

/**
 * Focus's speed row with chips beside its big number (the owner, Nov 14: the number drew smaller but the row kept its
 * height, leaving a gap). The row shrinks to the larger of the chips' column and the number at its everyday size.
 */
public final class RidingFocusHeightTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    // Roughly "88.8" over "mph" in Volt's bold figures, per px of size; 2.625 px a dp.
    private static final float WIDE = 1.62f, TALL = .72f + .72f * .30f * 1.9f, MAX = 525;
    private static final int SIDE = 5, BOTTOM = 11;

    public static void main(String[] args) {
        shrinks();
        chips();
        wide();
        steady();
        everyday();
        System.out.println("RidingFocusHeightTest: " + checks + " checks passed (a narrower number frees height, the chips "
                + "keep theirs, a wide number keeps the row, steadiness, everyday values)");
    }

    private static void shrinks() {
        // 412 dp upright with three chips: the number is 463 px wide in a 707 px row (640 px for the number itself).
        int row = 707, numberHeight = 640, width = 463;
        int need = RidingFocusHeight.rowHeight(WIDE, TALL, MAX, width, numberHeight, row, 357, SIDE, BOTTOM);
        float size = (width - SIDE) / WIDE;
        check(need < row, "a narrower number frees height: " + need);
        float largest = Math.min(MAX, (numberHeight - BOTTOM) / TALL);
        check(need == Math.max(357, row - (int) Math.floor((largest - size) * TALL)), "it frees only what the narrower width costs the number");
        int noRoom = RidingFocusHeight.rowHeight(WIDE, TALL, MAX, width, numberHeight, row, 0, SIDE, BOTTOM);
        check(noRoom <= need, "the chips only ever make it taller");
    }

    private static void chips() {
        int need = RidingFocusHeight.rowHeight(WIDE, TALL, MAX, 200, 640, 707, 600, SIDE, BOTTOM);
        check(need == 600, "a tall column of chips keeps its height");
        check(RidingFocusHeight.rowHeight(WIDE, TALL, MAX, 200, 640, 707, 900, SIDE, BOTTOM) == 707, "never taller than the usual row");
    }

    private static void wide() {
        // Where the everyday value fits at the number's largest size, nothing is freed.
        check(RidingFocusHeight.rowHeight(WIDE, TALL, MAX, 2000, 640, 707, 100, SIDE, BOTTOM) == 707, "a wide number keeps the row");
        check(RidingFocusHeight.rowHeight(0, TALL, MAX, 463, 640, 707, 100, SIDE, BOTTOM) == 707, "an unknown width keeps the row");
        check(RidingFocusHeight.rowHeight(WIDE, TALL, MAX, 0, 640, 707, 100, SIDE, BOTTOM) == 707, "so does no width");
    }

    private static void steady() {
        // rowHeight takes no value on show: the same layout gives the same row.
        int a = RidingFocusHeight.rowHeight(WIDE, TALL, MAX, 463, 640, 707, 357, SIDE, BOTTOM);
        int b = RidingFocusHeight.rowHeight(WIDE, TALL, MAX, 463, 640, 707, 357, SIDE, BOTTOM);
        check(a == b, "the same layout, the same row");
        // A narrower number (more chips' room) never needs a taller row.
        int prev = Integer.MAX_VALUE;
        for (int w = 700; w >= 200; w -= 50) {
            int h = RidingFocusHeight.rowHeight(WIDE, TALL, MAX, w, 640, 707, 0, SIDE, BOTTOM);
            check(h <= prev, "narrower, never taller: " + w);
            prev = h;
        }
    }

    private static void everyday() {
        check("88".equals(RidingFocusHeight.everyday("888")), "a three-figure speed's everyday value has two figures");
        check("10:58".equals(RidingFocusHeight.everyday("10:58")), "a clock stays");
        check("-888".equals(RidingFocusHeight.everyday("-888")), "a sign keeps the number as it is");
        check("88".equals(RidingFocusHeight.everyday("88")), "two figures stay");
    }
}
