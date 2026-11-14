package com.jjricks.volt;

/**
 * How tall Focus's speed row needs to be with chips beside its big number. The owner, Nov 14: the chips' column takes
 * part of the row's width, so the number draws smaller, but the row kept its full height and left a gap under the unit.
 * Here the row shrinks to what it needs, and the rows under it share the rest.
 *
 * The number's size in the row is the size at which an everyday value of its reading fits the number's width (see
 * {@link #everyday}), never larger than in the row's usual height. Worked out from widest values and the layout, never
 * from the value on show, so the row never changes height while riding; a value wider than the everyday one (a speed
 * over 99.9) draws smaller in the same row, as any value too wide for its spot always has.
 */
final class RidingFocusHeight {
    private RidingFocusHeight() { }

    /**
     * The speed row's height, px: its usual height less what the number's narrower width costs it, never shorter than its
     * chips' column. Where an everyday value fits the width at the number's largest size, the row keeps its height.
     *
     * @param widthPerPx an everyday value's width per px of number size, its unit included as it draws
     * @param heightPerPx its height per px of number size (figures, and a unit under them)
     * @param maxPx the number's designed size
     * @param numberWidth the number's width beside the chips, px
     * @param numberHeight the number's height in the row's usual height, px
     * @param row the row's usual height, px
     * @param chips the height the chips' column takes
     * @param sidePx the space the number keeps at its side
     * @param bottomPx the space the number keeps under it
     */
    static int rowHeight(float widthPerPx, float heightPerPx, float maxPx, int numberWidth, int numberHeight, int row, int chips,
                         int sidePx, int bottomPx) {
        if (!(widthPerPx > 0) || !(heightPerPx > 0) || numberWidth <= 0 || numberHeight <= 0 || row <= 0) return row;
        float largest = Math.min(maxPx, (numberHeight - bottomPx) / heightPerPx);
        float size = Math.min(largest, (numberWidth - sidePx) / widthPerPx);
        // Only the height the narrower width costs the number is freed: the name over it, the line under it and any room
        // the number had spare at its largest stay as they were.
        int freed = size < largest ? (int) Math.floor((largest - size) * heightPerPx) : 0;
        return Math.min(row, Math.max(row - freed, chips));
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
