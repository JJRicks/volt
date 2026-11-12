package com.jjricks.volt;

/**
 * Riding mode's reading names in a group (Halo's strip, the readings under its ring): icons for every reading that has
 * one, or names for all, by one shared rule, so two readings side by side never mix a word and an icon because one
 * language's word happens to be longer, two different readings never show the same icon, and no reading with an icon
 * ever shows its name cut short.
 */
public final class RidingNameGroupTest {
    private static final int NONE = -1, U = RidingNameGroup.UNMEASURED, ICON = RidingNameGroup.ICON;
    private static final String TEMP = "temperature", BATT = "battery", ROUTE = "route", SPEED = "speed";
    // The icons RidingLayout gives Halo's readings (the same keys the screen draws).
    private static final String AVERAGE = RidingLayout.icon(RidingLayout.AVERAGE_SPEED), MAX = RidingLayout.icon(RidingLayout.MAX_SPEED),
            RANGE = RidingLayout.icon(RidingLayout.RANGE_AT_PACE), TRIP = RidingLayout.icon(RidingLayout.TRIP),
            BATTERY = RidingLayout.icon(RidingLayout.BATTERY);

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
        // A member offering no icon (inferred power at a rung carrying its cue; Odometer before it had its distance arrow)
        // keeps its name; the one that needs its icon still gets it.
        expect(new int[]{0, NONE}, new String[]{null, TEMP}, new int[]{2, 2}, new int[]{0, ICON}, "no icon keeps its name");
        // Only a member with no icon runs out of room: icons wouldn't help it, so the group keeps names and it shows its
        // shortest one (cut at its end, as a lone label always did). No riding reading lacks an icon now (below).
        expect(new int[]{NONE, 0}, new String[]{null, TEMP}, new int[]{2, 1}, new int[]{1, 0}, "no icon to give: names");
        // A member not laid out yet takes no part, and shows its full name until it is.
        expect(new int[]{U, NONE}, both, new int[]{2, 1}, new int[]{0, ICON}, "unmeasured member");
        expect(new int[]{U, 1}, both, new int[]{2, 2}, new int[]{0, 1}, "unmeasured member, names");
        // A lone label (a group of one) behaves as the ladder always did.
        expect(new int[]{NONE}, new String[]{TEMP}, new int[]{2}, new int[]{ICON}, "alone, icon");
        expect(new int[]{1}, new String[]{TEMP}, new int[]{2}, new int[]{1}, "alone, short name");
        expect(new int[]{NONE}, new String[]{null}, new int[]{2}, new int[]{1}, "alone, no icon");
        // Halo's defaults under the ring: Range at this pace, Trip and Battery. Trip has its own icon now (a flag at the
        // end of the way, where Range keeps the route), so when any name doesn't fit all three go to icons together.
        // (Review of 055bbbc1: with one route icon for both the group kept names and cut Trip's, or Battery's, mid-word.)
        String[] defaults = {RANGE, TRIP, BATTERY};
        expect(new int[]{0, NONE, 0}, defaults, new int[]{2, 2, 1}, new int[]{ICON, ICON, ICON}, "range and trip: icons");
        expect(new int[]{0, 0, NONE}, defaults, new int[]{2, 2, 1}, new int[]{ICON, ICON, ICON}, "battery tight beside range and trip: icons");
        expect(new int[]{0, 1, 0}, defaults, new int[]{2, 2, 1}, new int[]{0, 1, 0}, "range, trip and battery fit: names");
        // The tester's tile, Battery temperature, under the ring with Range and Trip: icons for all.
        expect(new int[]{0, 0, NONE}, new String[]{RANGE, TRIP, TEMP}, new int[]{2, 2, 2}, new int[]{ICON, ICON, ICON}, "temperature tile: icons");
        // Average and Max speed in the strip: a dot and a tick on the speedometer tell them apart, so they go to icons
        // together, whichever name runs out of room (the review's Portuguese "Velocidade média"/"Velocidade máx." at
        // 344 dp, where even "Méd." or "Vel. máx." didn't fit, and German's "Ø" fitting beside a "Spitze" that didn't).
        expect(new int[]{NONE, 0}, new String[]{AVERAGE, MAX}, new int[]{2, 2}, new int[]{ICON, ICON}, "average and max: icons");
        expect(new int[]{NONE, NONE}, new String[]{AVERAGE, MAX}, new int[]{2, 2}, new int[]{ICON, ICON}, "Portuguese average and max: icons");
        expect(new int[]{1, NONE}, new String[]{AVERAGE, MAX}, new int[]{2, 2}, new int[]{ICON, ICON}, "German average fits, max doesn't: icons");
        expect(new int[]{1, 1}, new String[]{AVERAGE, MAX}, new int[]{2, 2}, new int[]{1, 1}, "average and max short names fit: names");
        // Never a name cut short while there's an icon to show: two different readings that still share an icon (two of
        // the range readings) keep names while they fit, and one that runs out of room shows its icon, even beside a
        // name or a second route.
        expect(new int[]{0, NONE}, new String[]{ROUTE, ROUTE}, new int[]{2, 2}, new int[]{0, ICON}, "two ranges, one tight: its icon, not a cut name");
        expect(new int[]{NONE, NONE}, new String[]{ROUTE, ROUTE}, new int[]{2, 2}, new int[]{ICON, ICON}, "two ranges, both tight: two routes");
        expect(new int[]{0, 0, NONE}, new String[]{ROUTE, ROUTE, BATT}, new int[]{2, 2, 1}, new int[]{0, 0, ICON}, "battery beside two routes: its icon");
        // The same reading twice may share its icon.
        expect(new int[]{NONE, 0}, new String[]{TEMP, TEMP}, new String[]{"temp", "temp"}, new int[]{2, 2}, new int[]{ICON, ICON}, "same reading twice: icons");
        // A shared icon on a member not laid out yet doesn't count.
        expect(new int[]{NONE, U}, new String[]{ROUTE, ROUTE}, new int[]{2, 2}, new int[]{ICON, 0}, "unmeasured twin");
        // A member that takes no part in the icon choice (inferred power with its cue rung fitting) keeps its rung
        // beside a neighbour that needs its icon.
        expect(new int[]{0, NONE}, new String[]{null, TEMP}, new int[]{3, 2}, new int[]{0, ICON}, "cue keeps its rung");
        // Inferred power offers its icon only once neither rung carrying the cue fits: its full caption and short cue
        // stay out of the choice, its plain third name "Power" carries no cue and goes to its icon with its neighbours.
        int cue = RidingPowerCue.NONE;
        expectEqual(RidingNameGroup.offered("power", cue, RidingPowerCue.FULL), null, "full caption keeps its cue");
        expectEqual(RidingNameGroup.offered("power", cue, RidingPowerCue.CUE), null, "short cue keeps its cue");
        expectEqual(RidingNameGroup.offered("power", cue, RidingPowerCue.NONE), "power", "plain Power takes part");
        expectEqual(RidingNameGroup.offered("power", cue, NONE), "power", "no rung fits: its icon");
        expectEqual(RidingNameGroup.offered("power", 0, 0), "power", "an ordinary reading always offers its icon");
        expectEqual(RidingNameGroup.offered(null, cue, NONE), null, "no icon to offer");
        // The review's case: Power at its plain name beside Battery temperature that needs its thermometer: two icons.
        String[] power = {RidingNameGroup.offered("power", cue, RidingPowerCue.NONE), TEMP};
        expect(new int[]{RidingPowerCue.NONE, NONE}, power, new int[]{3, 2}, new int[]{ICON, ICON}, "plain power beside an icon: icons");
        // At its short cue it keeps the cue beside the thermometer.
        String[] cued = {RidingNameGroup.offered("power", cue, RidingPowerCue.CUE), TEMP};
        expect(new int[]{RidingPowerCue.CUE, NONE}, cued, new int[]{3, 2}, new int[]{RidingPowerCue.CUE, ICON}, "cue beside an icon: keeps it");
        // Riding readings that can share a group have icons of their own; only the three range readings share one.
        java.util.Map<String, String> owner = new java.util.HashMap<>();
        for (String content : RidingLayout.choices(RidingLayout.Kind.STRIP)) {
            String icon = RidingLayout.icon(content);
            if (icon == null) continue;
            String key = content.startsWith("range_") ? "range" : content;
            String before = owner.put(icon, key);
            if (before != null && !before.equals(key)) throw new AssertionError(before + " and " + key + " share the icon " + icon);
        }
        for (String pair : new String[]{"average_speed max_speed", "range_at_pace trip", "ride_time clock", "recording_time clock", "ride_time recording_time"}) {
            String[] ab = pair.split(" ");
            if (RidingLayout.icon(ab[0]) == null || RidingLayout.icon(ab[0]).equals(RidingLayout.icon(ab[1]))) throw new AssertionError(pair + " share an icon");
        }
        // Every reading a tile or the strip can show has an icon now (Speed, Efficiency, Voltage, Current, Drive mode and
        // Odometer gained one at the maintainer's request), and joins the group's rule: beside Battery temperature that
        // needs its thermometer, it shows its icon too, and with room for both names both show names.
        for (String content : RidingLayout.choices(RidingLayout.Kind.STRIP)) {
            String icon = RidingLayout.icon(content);
            if (icon == null) throw new AssertionError(content + " has no icon");
            if (content.equals(RidingLayout.BATTERY_TEMPERATURE)) continue;
            expect(new int[]{0, NONE}, new String[]{icon, TEMP}, new int[]{2, 2}, new int[]{ICON, ICON}, content + " beside a thermometer: icons");
            expect(new int[]{NONE, 1}, new String[]{icon, TEMP}, new int[]{2, 2}, new int[]{ICON, ICON}, content + " tight: icons");
            expect(new int[]{1, 1}, new String[]{icon, TEMP}, new int[]{2, 2}, new int[]{1, 1}, content + " with room: names");
        }
        for (String content : new String[]{RidingLayout.SPEED, RidingLayout.EFFICIENCY, RidingLayout.VOLTAGE, RidingLayout.CURRENT,
                RidingLayout.DRIVE_MODE, RidingLayout.ODOMETER})
            if (RidingLayout.icon(content) == null) throw new AssertionError(content + " lost its icon");
        // The review's Odometer beside Battery temperature at 344 dp: no more "Odo." beside a thermometer.
        expect(new int[]{1, NONE}, new String[]{RidingLayout.icon(RidingLayout.ODOMETER), TEMP}, new int[]{2, 2}, new int[]{ICON, ICON}, "odometer and temperature: icons");
        // Every member with an icon and no name that fits shows its icon, in any group: no name is ever cut short.
        String[] keys = {null, ROUTE, TEMP};
        int[] values = {U, NONE, 0, 1};
        for (int a : values) for (int b : values) for (int c : values)
            for (String ia : keys) for (String ib : keys) for (String ic : keys) {
                int[] fits = {a, b, c};
                String[] icons = {ia, ib, ic};
                boolean group = RidingNameGroup.icons(fits, icons, new String[]{"x", "y", "z"});
                for (int i = 0; i < 3; i++)
                    if (fits[i] == NONE && icons[i] != null && RidingNameGroup.shown(fits[i], 2, true, group) != ICON)
                        throw new AssertionError("cut name with an icon to show: " + java.util.Arrays.toString(fits) + " " + java.util.Arrays.toString(icons));
            }
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

    private static void expectEqual(String got, String want, String name) {
        if (got == null ? want != null : !got.equals(want)) throw new AssertionError(name + ": " + got + ", expected " + want);
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
