package com.jjricks.volt;

/**
 * Focus's chips on the unit line (the owner, Nov 14: chips in a column beside the big number shrank it and left a gap
 * under it). The number keeps its size; the chips take the room right of its unit, worked out from the number's largest
 * size and the widest unit, never the value on show.
 */
public final class RidingUnitLineTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    // Roughly Volt's bold figures: cap height .72 of the size, the unit at .30 of it, "mph" 1.75 of the unit's size wide.
    private static final float CAP = .72f, UNIT_CAP = .72f * .30f, MPH = 1.75f * .30f, KMH = 2.1f * .30f, EVERYDAY = 1.62f;
    // At 2.625 px a dp: a 200 dp number, a 40 dp chip, the gaps.
    private static final float MAX = 525;
    private static final int TOP = 5, BOTTOM = 11, CHIP = 105, GAP = 42, CLEAR = 16;

    public static void main(String[] args) {
        largest();
        place();
        steady();
        tooShort();
        everyday();
        row();
        System.out.println("RidingUnitLineTest: " + checks + " checks passed (the number's largest size, the row's place and "
                + "room, steadiness, a short spot, everyday values, which chips show)");
    }

    private static void largest() {
        check(RidingUnitLine.largestPx(CAP, UNIT_CAP, MAX, 5000, BOTTOM) == MAX, "a tall spot keeps the designed size");
        float h = 600;
        float px = RidingUnitLine.largestPx(CAP, UNIT_CAP, MAX, h, BOTTOM);
        check(px < MAX && Math.abs(px * (CAP + UNIT_CAP * 1.9f) + BOTTOM - h) < .5f, "a short spot: the number and its unit fill its height");
        check(RidingUnitLine.largestPx(CAP, UNIT_CAP, MAX, 0, BOTTOM) == 0, "no height, no size");
    }

    private static void place() {
        int w = 818, h = 640;
        RidingUnitLine.Place p = RidingUnitLine.place(CAP, UNIT_CAP, MPH, EVERYDAY, MAX, w, h, TOP, BOTTOM, CHIP, GAP, CLEAR);
        float largest = RidingUnitLine.largestPx(CAP, UNIT_CAP, MAX, h, BOTTOM);
        check(p.room == w - (int) Math.ceil(MPH * largest) - GAP, "the room is the width less the widest unit at the largest size and a gap: " + p.room);
        check(p.top >= TOP + largest * CAP + CLEAR, "the row never comes up over the number at its largest");
        check(p.top + CHIP <= h, "the row stays inside the spot");
        RidingUnitLine.Place km = RidingUnitLine.place(CAP, UNIT_CAP, KMH, EVERYDAY, MAX, w, h, TOP, BOTTOM, CHIP, GAP, CLEAR);
        check(km.room < p.room && km.top == p.top, "a wider unit (km/h) leaves less room on the same line");
        RidingUnitLine.Place wide = RidingUnitLine.place(CAP, UNIT_CAP, MPH, EVERYDAY, MAX, w + 400, h, TOP, BOTTOM, CHIP, GAP, CLEAR);
        check(wide.room == p.room + 400, "a wider spot (power bar hidden, a Fold) gives the row all the extra width");
        // The row's middle meets the unit's middle where the everyday value draws.
        float every = Math.min(largest, (w - TOP) / EVERYDAY);
        float unitMiddle = TOP + every * (CAP + UNIT_CAP * 1.9f - UNIT_CAP / 2);
        check(Math.abs(p.top + CHIP / 2f - unitMiddle) <= 1 || p.top + CHIP == h, "the row's middle is on the unit's middle");
    }

    private static void steady() {
        // place() takes no value: only the spot's size, the reading's widest unit and the chips' height. The same inputs give
        // the same place, every time.
        RidingUnitLine.Place a = RidingUnitLine.place(CAP, UNIT_CAP, MPH, EVERYDAY, MAX, 818, 640, TOP, BOTTOM, CHIP, GAP, CLEAR);
        RidingUnitLine.Place b = RidingUnitLine.place(CAP, UNIT_CAP, MPH, EVERYDAY, MAX, 818, 640, TOP, BOTTOM, CHIP, GAP, CLEAR);
        check(a.room == b.room && a.top == b.top, "the same layout, the same place");
    }

    private static void tooShort() {
        RidingUnitLine.Place p = RidingUnitLine.place(CAP, UNIT_CAP, MPH, EVERYDAY, MAX, 818, CHIP / 2, TOP, BOTTOM, CHIP, GAP, CLEAR);
        check(p.room == 0, "a spot shorter than a chip has no room for the row");
        RidingUnitLine.Place none = RidingUnitLine.place(CAP, UNIT_CAP, MPH, EVERYDAY, MAX, 0, 640, TOP, BOTTOM, CHIP, GAP, CLEAR);
        check(none.room == 0, "nor has a spot with no width");
        RidingUnitLine.Place narrow = RidingUnitLine.place(CAP, UNIT_CAP, MPH, EVERYDAY, MAX, 200, 640, TOP, BOTTOM, CHIP, GAP, CLEAR);
        check(narrow.room >= 0, "the room is never negative");
    }

    private static void everyday() {
        check("88".equals(RidingUnitLine.everyday("888")), "a three-figure speed's everyday value has two figures");
        check("888".equals(RidingUnitLine.everyday("8888")), "a longer number loses one figure");
        check("88".equals(RidingUnitLine.everyday("88")), "two figures stay");
        check("10:58".equals(RidingUnitLine.everyday("10:58")), "a clock stays");
        check("-888".equals(RidingUnitLine.everyday("-888")), "a sign keeps the number as it is");
        check(RidingUnitLine.everyday(null) == null, "nothing stays nothing");
    }

    private static void row() {
        // The row of chips at today's size: as many as fit the room, the lowest priority hiding first, whatever the values.
        int[][] widths = new int[RidingChipFit.RUNGS][];
        for (int rung = 0; rung < RidingChipFit.RUNGS; rung++) widths[rung] = new int[]{246 - rung * 4, 161 - rung * 4, 329 - rung * 30};
        RidingChipFit two = RidingChipFit.choose(widths, 21, 480);
        check(two.count == 2 && two.shown[0] && two.shown[1] && !two.shown[2], "two fit: the third, always the same one, hides");
        RidingChipFit three = RidingChipFit.choose(widths, 21, 800);
        check(three.count == 3, "a wider line shows all three");
        RidingChipFit none = RidingChipFit.choose(widths, 21, 0);
        check(none.count == 0, "no room, no chips");
    }
}
