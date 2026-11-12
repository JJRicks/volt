package com.jjricks.volt;

/**
 * How a group of riding-mode readings shows their names: Halo's strip of small readings, and the
 * readings under its ring. Each name climbs its own narrow-label ladder (TileRung.first): its full
 * name while it fits, then its short one, then its icon alone. Left to themselves, two readings side
 * by side can land on different rungs of that ladder, one showing a word and the other an icon, and
 * which one gets the icon depends on how long each language's words are (a tester's report: Battery
 * temperature showed its thermometer and Battery its name in English, the other way round in
 * Portuguese). So the group decides together: when any reading has no name that fits and has an
 * icon to show instead, every reading in the group that has an icon shows its icon; otherwise every
 * reading shows a name. A reading with no icon (Speed, Voltage, Odometer and the like) always shows
 * its name, because it has nothing else to show. Pure, so the host tests run it.
 */
final class RidingNameGroup {
    private RidingNameGroup() {}

    /** A name shown as its icon alone. */
    static final int ICON = -1;
    /** A member that hasn't been laid out yet, or has no names: it takes no part in the choice. */
    static final int UNMEASURED = -2;

    /**
     * True when the group shows icons.
     *
     * @param fits each member's own ladder choice: the first rung that fits (TileRung.first), -1 when
     *             none does, or {@link #UNMEASURED}
     * @param icons whether each member has an icon
     */
    static boolean icons(int[] fits, boolean[] icons) {
        for (int i = 0; i < fits.length; i++) if (fits[i] == -1 && icons[i]) return true;
        return false;
    }

    /**
     * What one member shows: {@link #ICON}, or the index of the rung it shows.
     *
     * @param fit the member's own ladder choice, as in {@link #icons}
     * @param rungs how many names it has (at least one)
     * @param icon whether it has an icon
     * @param icons the group's choice ({@link #icons})
     */
    static int shown(int fit, int rungs, boolean icon, boolean icons) {
        if (fit == UNMEASURED) return 0;
        if (icons && icon) return ICON;
        // A name that doesn't fit and has no icon shows its shortest rung, cut at its end, as a lone label does.
        return fit >= 0 ? fit : rungs - 1;
    }
}
