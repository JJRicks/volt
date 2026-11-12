package com.jjricks.volt;

/**
 * Riding mode's reading names in a group (Halo's strip, the readings under its ring): icons for every reading that has
 * one, or names for all, by one shared rule, so two readings side by side never mix a word and an icon because one
 * language's word happens to be longer, and two different readings never show the same icon.
 */
public final class RidingNameGroupTest {
    private static final int NONE = -1, U = RidingNameGroup.UNMEASURED, ICON = RidingNameGroup.ICON;
    private static final String TEMP = "temperature", BATT = "battery", ROUTE = "route", SPEED = "speed";

    public static void main(String[] args) {
        // The tester's strip, Battery temperature and Battery. English: "Batt. temp" doesn't fit, "Battery" does.
        String[] both = {TEMP, BATT};
        int[] english = {NONE, 0};
        expect(english, both, new int[]{2, 1}, new int[]{ICON, ICON}, "English strip: both icons");
        // Portuguese: "Temp." fits, "Bateria" doesn't. Before, the icon moved to the other reading; now it's the same.
        int[] portuguese = {1, NONE};
        expect(portuguese, both, new int[]{2, 1}, new int[]{ICON, ICON}, "Portuguese strip: both icons");
        // Room for both names: both names, each on the rung it fits.
        expect(new int[]{1, 0}, both, new int[]{2, 1}, new int[]{1, 0}, "both fit: both names");
        expect(new int[]{0, 0}, both, new int[]{2, 2}, new int[]{0, 0}, "both full names");
        // Range under the ring and a temperature tile: the same rule across the group.
        expect(new int[]{0, NONE, 0}, new String[]{ROUTE, TEMP, BATT}, new int[]{2, 2, 1}, new int[]{ICON, ICON, ICON}, "under the ring");
        // A reading with no icon (Odometer) keeps its name; the one that needs its icon still gets it.
        expect(new int[]{0, NONE}, new String[]{null, TEMP}, new int[]{2, 2}, new int[]{0, ICON}, "no icon keeps its name");
        // Only a reading with no icon runs out of room: icons wouldn't help it, so the group keeps names and it shows its
        // shortest one (cut at its end, as a lone label always did).
        expect(new int[]{NONE, 0}, new String[]{null, TEMP}, new int[]{2, 1}, new int[]{1, 0}, "no icon to give: names");
        // A member not laid out yet takes no part, and shows its full name until it is.
        expect(new int[]{U, NONE}, both, new int[]{2, 1}, new int[]{0, ICON}, "unmeasured member");
        expect(new int[]{U, 1}, both, new int[]{2, 2}, new int[]{0, 1}, "unmeasured member, names");
        // A lone label (a group of one) behaves as the ladder always did.
        expect(new int[]{NONE}, new String[]{TEMP}, new int[]{2}, new int[]{ICON}, "alone, icon");
        expect(new int[]{1}, new String[]{TEMP}, new int[]{2}, new int[]{1}, "alone, short name");
        expect(new int[]{NONE}, new String[]{null}, new int[]{2}, new int[]{1}, "alone, no icon");
        // Halo's defaults under the ring: Range at this pace and Trip share the route icon, so when Trip's name doesn't
        // fit the group keeps names (Trip's shortest, cut) rather than show two routes over two distances.
        String[] defaults = {ROUTE, ROUTE, BATT};
        expect(new int[]{0, NONE, 0}, defaults, new int[]{2, 2, 1}, new int[]{0, 1, 0}, "range and trip: names");
        expect(new int[]{0, 0, NONE}, defaults, new int[]{2, 2, 1}, new int[]{0, 0, 0}, "battery tight beside range and trip: names");
        // The tester's tile, Battery temperature, under the ring with Range and Trip: names for all.
        expect(new int[]{0, 0, NONE}, new String[]{ROUTE, ROUTE, TEMP}, new int[]{2, 2, 2}, new int[]{0, 0, 1}, "temperature tile: names");
        // Average and Max speed in the strip both show the speedometer: names.
        expect(new int[]{NONE, 0}, new String[]{SPEED, SPEED}, new int[]{2, 2}, new int[]{1, 0}, "average and max: names");
        // The same reading twice may share its icon.
        expect(new int[]{NONE, 0}, new String[]{TEMP, TEMP}, new String[]{"temp", "temp"}, new int[]{2, 2}, new int[]{ICON, ICON}, "same reading twice: icons");
        // A shared icon on a member not laid out yet doesn't count.
        expect(new int[]{NONE, U}, new String[]{ROUTE, ROUTE}, new int[]{2, 2}, new int[]{ICON, 0}, "unmeasured twin");
        // A member that takes no part in the icon choice (inferred power with its cue rung fitting) keeps its rung
        // beside a neighbour that needs its icon.
        expect(new int[]{0, NONE}, new String[]{null, TEMP}, new int[]{3, 2}, new int[]{0, ICON}, "cue keeps its rung");
        // Symmetric, whatever the order: every combination of two members agrees on icon versus name.
        for (int a = -2; a <= 1; a++)
            for (int b = -2; b <= 1; b++) {
                int[] fits = {a, b};
                String[] contents = {"a", "b"};
                boolean icons = RidingNameGroup.icons(fits, both, contents);
                if (icons != RidingNameGroup.icons(new int[]{b, a}, new String[]{BATT, TEMP}, new String[]{"b", "a"})) throw new AssertionError("order changed the choice: " + a + ", " + b);
                int sa = RidingNameGroup.shown(a, 2, true, icons), sb = RidingNameGroup.shown(b, 2, true, icons);
                if (a != U && b != U && (sa == ICON) != (sb == ICON)) throw new AssertionError("mixed icon and name: " + a + ", " + b);
            }
        System.out.println("RidingNameGroupTest passed");
    }

    private static void expect(int[] fits, String[] icons, int[] rungs, int[] shown, String name) {
        String[] contents = new String[fits.length];
        for (int i = 0; i < contents.length; i++) contents[i] = "reading" + i;
        expect(fits, icons, contents, rungs, shown, name);
    }

    private static void expect(int[] fits, String[] icons, String[] contents, int[] rungs, int[] shown, String name) {
        boolean group = RidingNameGroup.icons(fits, icons, contents);
        for (int i = 0; i < fits.length; i++) {
            int got = RidingNameGroup.shown(fits[i], rungs[i], icons[i] != null, group);
            if (got != shown[i]) throw new AssertionError(name + ": member " + i + " shows " + got + ", expected " + shown[i]);
        }
    }
}
