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
 * reading shows a name. Icons only help while they tell the readings apart: readings that can sit side
 * by side have icons of their own (RidingLayout.icon), and when two readings in the group would still
 * show the same icon (two of the range readings, which share the route), the group keeps names, as the
 * chips never give two readings one icon (RidingChipFit.nameStep). Whatever the group decides, a
 * reading with an icon never shows a name cut short: with no name that fits, it shows its icon, even
 * when that means a word beside it or the same icon twice (the project's rule for narrow labels: full
 * name, short name, icon, never a word cut mid-way). Every reading a tile or the strip can show has an
 * icon; a member offering none (inferred power while a rung carrying its cue fits, see {@link #offered})
 * keeps its name, because it has nothing else to show. Pure, so the host tests run it.
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
     * @param icons each member's icon key, or null when it has none (or takes no part in the icon choice)
     * @param contents each member's content id, so two members showing the same reading may share an icon
     */
    static boolean icons(int[] fits, String[] icons, String[] contents) {
        boolean wanted = false;
        for (int i = 0; i < fits.length; i++) if (fits[i] == -1 && icons[i] != null) wanted = true;
        if (!wanted) return false;
        // Two different readings showing one icon couldn't be told apart: the group keeps names instead.
        for (int i = 0; i < fits.length; i++) {
            if (fits[i] == UNMEASURED || icons[i] == null) continue;
            for (int j = i + 1; j < fits.length; j++) {
                if (fits[j] == UNMEASURED || !icons[i].equals(icons[j])) continue;
                if (contents[i] != null && contents[i].equals(contents[j])) continue;
                return false;
            }
        }
        return true;
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
        // A name that doesn't fit is never cut while there's an icon to show instead, whatever the group chose.
        if (icon && (icons || fit < 0)) return ICON;
        // A name that doesn't fit and has no icon shows its shortest rung, cut at its end, as a lone label does.
        return fit >= 0 ? fit : rungs - 1;
    }

    /**
     * The icon a member offers its group's choice: none while a rung that carries a cue beyond the name fits (inferred
     * power's "direction unknown" caption and short cue, which the number's minus sign depends on), so the group never
     * swaps the cue for an icon. Its plain name carries no cue, so at that rung, or with no rung that fits, it takes
     * part like any other reading.
     *
     * @param icon the member's icon key, or null
     * @param cueRungs how many of its first rungs carry the cue (0 for an ordinary reading)
     * @param fit its own ladder choice, as in {@link #icons}
     */
    static String offered(String icon, int cueRungs, int fit) {
        return fit >= 0 && fit < cueRungs ? null : icon;
    }
}
