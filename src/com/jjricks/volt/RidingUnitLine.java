package com.jjricks.volt;

/**
 * Where Focus's chips sit on the line under its big number, right of the unit ("mph"). The owner, Nov 14: chips in a
 * column beside the number made it smaller and left a gap under it. Here the number keeps its size and the whole width,
 * and the chips take the room its unit leaves on its own line.
 *
 * Everything is worked out from the number's largest size in its spot and the widest unit its reading shows, never from
 * the value on show, so the chips never move or come and go while riding. It changes only with the layout (the spot's
 * size, the power bar, the window, text size, units or language).
 */
final class RidingUnitLine {
    private RidingUnitLine() { }

    /** The chip row's room across, px (0: no room), and its top, px down from the number view's top. */
    static final class Place {
        final int room, top;
        Place(int room, int top) { this.room = room; this.top = top; }
    }

    /**
     * The largest size, px, the number draws at in its view with its unit under it: its designed size, or less where the
     * view is too short for that (as the number's own fit works it out).
     */
    static float largestPx(float capPerPx, float unitCapPerPx, float maxPx, float height, float bottomPx) {
        float perPx = capPerPx + unitCapPerPx * 1.9f;
        if (!(perPx > 0)) return maxPx;
        return Math.max(0, Math.min(maxPx, (height - bottomPx) / perPx));
    }

    /**
     * Where the chip row goes.
     *
     * The row's middle lines up with the unit's middle at the size an everyday value draws (see {@link #everyday}): the
     * number at its largest when that fits across, else the size the everyday value fits the width at. It never comes up
     * over the number at its largest, and never goes past the view's bottom; where it can do neither, there is no room.
     * Across, it has the number's width less the widest unit at the number's largest size and a gap after it.
     *
     * @param capPerPx the number's figure height per px of size
     * @param unitCapPerPx the unit's figure height per px of number size
     * @param unitWidthPerPx the widest unit's width per px of number size
     * @param everydayWidthPerPx an everyday value's width per px of number size (with its unit under it)
     * @param maxPx the number's designed size
     * @param width the number view's width, px
     * @param height the number view's height, px
     * @param topPx the space the number keeps above its figures (and at its right edge)
     * @param bottomPx the space the number keeps under its unit
     * @param chipHeight the chip row's height
     * @param gap the space between the unit and the first chip
     * @param clear the least space between the number's figures and the chips
     */
    static Place place(float capPerPx, float unitCapPerPx, float unitWidthPerPx, float everydayWidthPerPx, float maxPx,
                       int width, int height, int topPx, int bottomPx, int chipHeight, int gap, int clear) {
        if (width <= 0 || height <= 0 || chipHeight <= 0) return new Place(0, 0);
        float largest = largestPx(capPerPx, unitCapPerPx, maxPx, height, bottomPx);
        float everydayPx = everydayWidthPerPx > 0 ? Math.min(largest, (width - topPx) / everydayWidthPerPx) : largest;
        float unitMiddle = topPx + everydayPx * (capPerPx + unitCapPerPx * 1.9f - unitCapPerPx / 2);
        int top = Math.round(unitMiddle - chipHeight / 2f);
        int lowest = height - chipHeight;
        int highest = (int) Math.ceil(topPx + largest * capPerPx) + clear;
        top = Math.max(highest, Math.min(top, lowest));
        if (top > lowest) return new Place(0, 0);
        int room = width - (int) Math.ceil(unitWidthPerPx * largest) - gap;
        return new Place(Math.max(0, room), top);
    }

    /**
     * An everyday value of a reading from its widest one: a number of three figures or more loses one ("888" mph to "88",
     * 888 % to 88), as no ride shows a speed over 99.9 for long; anything else stays as it is (a clock, a minus sign).
     */
    static String everyday(String main) {
        if (main == null || main.length() < 3) return main;
        for (int i = 0; i < main.length(); i++) if (!Character.isDigit(main.charAt(i))) return main;
        return main.substring(1);
    }
}
