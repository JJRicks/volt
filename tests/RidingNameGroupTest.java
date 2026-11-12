package com.jjricks.volt;

/**
 * Riding mode's reading names in a group (Halo's strip, the readings under its ring): icons for every reading that has
 * one, or names for all, by one shared rule, so two readings side by side never mix a word and an icon because one
 * language's word happens to be longer.
 */
public final class RidingNameGroupTest {
    private static final int NONE = -1, U = RidingNameGroup.UNMEASURED, ICON = RidingNameGroup.ICON;

    public static void main(String[] args) {
        // The tester's strip, Battery temperature and Battery. English: "Batt. temp" doesn't fit, "Battery" does.
        boolean[] both = {true, true};
        int[] english = {NONE, 0};
        expect(english, both, new int[]{2, 1}, new int[]{ICON, ICON}, "English strip: both icons");
        // Portuguese: "Temp." fits, "Bateria" doesn't. Before, the icon moved to the other reading; now it's the same.
        int[] portuguese = {1, NONE};
        expect(portuguese, both, new int[]{2, 1}, new int[]{ICON, ICON}, "Portuguese strip: both icons");
        // Room for both names: both names, each on the rung it fits.
        expect(new int[]{1, 0}, both, new int[]{2, 1}, new int[]{1, 0}, "both fit: both names");
        expect(new int[]{0, 0}, both, new int[]{2, 2}, new int[]{0, 0}, "both full names");
        // Range under the ring and a temperature tile: the same rule across the group.
        expect(new int[]{0, NONE, 0}, new boolean[]{true, true, true}, new int[]{2, 2, 1}, new int[]{ICON, ICON, ICON}, "under the ring");
        // A reading with no icon (Odometer) keeps its name; the one that needs its icon still gets it.
        expect(new int[]{0, NONE}, new boolean[]{false, true}, new int[]{2, 2}, new int[]{0, ICON}, "no icon keeps its name");
        // Only a reading with no icon runs out of room: icons wouldn't help it, so the group keeps names and it shows its
        // shortest one (cut at its end, as a lone label always did).
        expect(new int[]{NONE, 0}, new boolean[]{false, true}, new int[]{2, 1}, new int[]{1, 0}, "no icon to give: names");
        // A member not laid out yet takes no part, and shows its full name until it is.
        expect(new int[]{U, NONE}, both, new int[]{2, 1}, new int[]{0, ICON}, "unmeasured member");
        expect(new int[]{U, 1}, both, new int[]{2, 2}, new int[]{0, 1}, "unmeasured member, names");
        // A lone label (a group of one) behaves as the ladder always did.
        expect(new int[]{NONE}, new boolean[]{true}, new int[]{2}, new int[]{ICON}, "alone, icon");
        expect(new int[]{1}, new boolean[]{true}, new int[]{2}, new int[]{1}, "alone, short name");
        expect(new int[]{NONE}, new boolean[]{false}, new int[]{2}, new int[]{1}, "alone, no icon");
        // Symmetric, whatever the order: every combination of two members agrees on icon versus name.
        for (int a = -2; a <= 1; a++)
            for (int b = -2; b <= 1; b++) {
                int[] fits = {a, b};
                boolean icons = RidingNameGroup.icons(fits, both);
                if (icons != RidingNameGroup.icons(new int[]{b, a}, both)) throw new AssertionError("order changed the choice: " + a + ", " + b);
                int sa = RidingNameGroup.shown(a, 2, true, icons), sb = RidingNameGroup.shown(b, 2, true, icons);
                if (a != U && b != U && (sa == ICON) != (sb == ICON)) throw new AssertionError("mixed icon and name: " + a + ", " + b);
            }
        System.out.println("RidingNameGroupTest passed");
    }

    private static void expect(int[] fits, boolean[] icons, int[] rungs, int[] shown, String name) {
        boolean group = RidingNameGroup.icons(fits, icons);
        for (int i = 0; i < fits.length; i++) {
            int got = RidingNameGroup.shown(fits[i], rungs[i], icons[i], group);
            if (got != shown[i]) throw new AssertionError(name + ": member " + i + " shows " + got + ", expected " + shown[i]);
        }
    }
}
