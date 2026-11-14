package com.jjricks.volt;

/**
 * Where Focus's chips go (the owner, Nov 14: "D on phones, and the one you recommended for folding"): rows under the big
 * number on a phone; on a wide screen, the unit line when every chip picked fits there with the number at its full size,
 * else the same rows. Chosen once per layout, from widest widths only, so it never flips while riding.
 */
public final class RidingChipPlaceTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    private static final int GAP = 21;
    // Roughly Volt's bold figures at 2.625 px a dp (as RidingUnitLineTest): a 200 dp number, "mph" under it, a 40 dp chip.
    private static final float CAP = .72f, UNIT_CAP = .72f * .30f, MPH = 1.75f * .30f, EVERYDAY = 1.62f, MAX = 525;
    private static final int TOP = 5, BOTTOM = 11, CHIP = 105, UNIT_GAP = 42, CLEAR = 16;

    public static void main(String[] args) {
        widths();
        choice();
        everyChipPicked();
        latch();
        refit();
        fold();
        System.out.println("RidingChipPlaceTest: " + checks + " checks passed (which screens are wide, when the unit line "
                + "is chosen, every chip picked counts, one choice per layout, a rotation or fold chooses again, the Fold "
                + "and a phone side by side)");
    }

    private static RidingChipFit.Widths fixed(int... widths) { return (chip, rung, step) -> widths[chip]; }
    private static RidingChipRows oneRow(int room, int... widths) {
        int n = widths.length;
        return RidingChipRows.split(new String[n][], new String[n], fixed(widths), fixed(widths), new boolean[n], GAP, room, 1);
    }

    private static void widths() {
        check(RidingChipPlace.wide(673), "the Fold's inner screen is wide");
        check(RidingChipPlace.wide(600) && RidingChipPlace.wide(800), "600 dp and a tablet are wide");
        check(!RidingChipPlace.wide(599), "just under 600 dp is not");
        for (int phone : new int[]{320, 344, 360, 393, 412, 430, 480})
            check(!RidingChipPlace.wide(phone), "a " + phone + " dp phone is not wide, upright or sideways (its smallest side)");
    }

    private static void choice() {
        RidingChipRows fits = oneRow(1200, 246, 161, 329);
        check(RidingChipPlace.onUnitLine(true, true, true, 1200, fits), "wide, unit below, every chip fits: the unit line");
        check(!RidingChipPlace.onUnitLine(false, true, true, 1200, fits), "a phone: rows, however much room");
        check(!RidingChipPlace.onUnitLine(true, false, true, 1200, fits), "the unit beside the number: rows");
        check(!RidingChipPlace.onUnitLine(true, true, false, 1200, fits), "a reading without a unit: rows");
        check(!RidingChipPlace.onUnitLine(true, true, true, 0, oneRow(0, 246, 161, 329)), "no room right of the unit: rows");
        RidingChipRows short1 = oneRow(500, 246, 161, 329);
        check(short1.row[2] < 0, "the last chip in slot order is the one that doesn't fit");
        check(!RidingChipPlace.onUnitLine(true, true, true, 500, short1), "one chip short of the room: rows, not a chip hidden");
        check(!RidingChipPlace.onUnitLine(true, true, true, 1200, null), "no split: rows");
    }

    /** A drive mode not in yet still counts, so the choice doesn't wait on it. */
    private static void everyChipPicked() {
        // Mode (shown later), average speed, voltage; 520 fits the last two, not all three.
        RidingChipRows all = oneRow(520, 150, 246, 250);
        RidingChipRows shownNow = oneRow(520, 246, 250);
        check(!RidingChipPlace.onUnitLine(true, true, true, 520, all), "every chip picked: rows");
        check(RidingChipPlace.onUnitLine(true, true, true, 520, shownNow), "(only the chips on show would have said the line)");
    }

    private static void latch() {
        RidingChipPlace.Latch latch = new RidingChipPlace.Latch();
        int[] asked = {0};
        boolean first = latch.choose(1766, 900, "mph", () -> { asked[0]++; return true; });
        check(first && asked[0] == 1, "the first measure chooses");
        // Readings change, a drive mode comes in, a chip widens once for a value past its samples: the same layout.
        for (int reading = 0; reading < 50; reading++) {
            boolean again = latch.choose(1766, 900, "mph", () -> { asked[0]++; return false; });
            check(again, "the same layout keeps the unit line whatever the readings");
        }
        check(asked[0] == 1, "and never asks again");
        RidingChipPlace.Latch rows = new RidingChipPlace.Latch();
        rows.choose(1766, 900, "mph", () -> false);
        check(!rows.choose(1766, 900, "mph", () -> true), "rows stay rows for the same layout");
    }

    private static void refit() {
        RidingChipPlace.Latch latch = new RidingChipPlace.Latch();
        latch.choose(1766, 900, "mph", () -> true);
        check(!latch.choose(2200, 640, "mph", () -> false), "turned sideways (another size): chosen again");
        check(latch.choose(1766, 900, "mph", () -> true), "turned back: chosen again");
        check(!latch.choose(1766, 900, "km/h", () -> false), "other units: chosen again");
    }

    /** The unit line's room at the Fold's inner screen against a phone, from RidingUnitLine with today's sizes. */
    private static void fold() {
        int fold = Math.round(641 * 2.625f), phone = Math.round(380 * 2.625f), h = 640;
        RidingUnitLine.Place f = RidingUnitLine.place(CAP, UNIT_CAP, MPH, EVERYDAY, MAX, fold, h, TOP, BOTTOM, CHIP, UNIT_GAP, CLEAR);
        RidingUnitLine.Place p = RidingUnitLine.place(CAP, UNIT_CAP, MPH, EVERYDAY, MAX, phone, h, TOP, BOTTOM, CHIP, UNIT_GAP, CLEAR);
        // Clock + battery temperature + average speed, about 246, 161 and 329 px.
        check(RidingChipPlace.onUnitLine(RidingChipPlace.wide(673), true, true, f.room, oneRow(f.room, 246, 161, 329)),
                "the Fold: three chips on the unit line");
        check(!RidingChipPlace.onUnitLine(RidingChipPlace.wide(412), true, true, p.room, oneRow(p.room, 246, 161, 329)),
                "a phone: rows");
    }
}
