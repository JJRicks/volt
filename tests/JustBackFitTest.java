package com.jjricks.volt;

/**
 * JustBackFit: Just back as the Layout Plan's mock-up draws it (the contributor's decisions on #383), at 344x882 and
 * 725x604 dp with 1x and 1.4x text: the average speed from distance and time, the signed battery change, the distance's
 * size and how the fixed top area shares its height between the distance and the route.
 */
public final class JustBackFitTest {
    private static int checks;

    public static void main(String[] args) {
        averageSpeed();
        batteryChange();
        distance();
        values();
        split();
        System.out.println("JustBackFit: " + checks + " checks passed");
    }

    private static void averageSpeed() {
        // The rig's seeded ride: 6.21 mi (9.994 km) in 20:00 with no speed readings stored: about 18.6 mph, not a dash.
        Double seeded = JustBackFit.averageSpeedKmh(null, null, 9.994, 0, 20 * 60_000L);
        require(seeded != null && Math.abs(seeded - 29.982) < .01, "distance over the shown time: " + seeded);
        require(Math.abs(seeded / 1.609344 - 18.63) < .01, "about 18.6 mph");
        require(JustBackFit.averageSpeedKmh(24.0, 26.0, 9.994, 0, 20 * 60_000L) == 24.0, "a stored average comes first");
        require(JustBackFit.averageSpeedKmh(null, 26.0, 9.994, 0, 20 * 60_000L) == 26.0, "then the stored moving average");
        Double moving = JustBackFit.averageSpeedKmh(null, null, 10.0, 15 * 60_000L, 20 * 60_000L);
        require(moving != null && Math.abs(moving - 40.0) < 1e-9, "derived over the moving time when there is one");
        require(JustBackFit.averageSpeedKmh(Double.NaN, Double.POSITIVE_INFINITY, 10.0, 0, 30 * 60_000L) == 20.0, "unusable stored values are skipped");
        require(JustBackFit.averageSpeedKmh(-1.0, null, 10.0, 0, 30 * 60_000L) == 20.0, "a negative stored average is unusable");
        require(JustBackFit.averageSpeedKmh(null, null, null, 0, 60_000L) == null, "no distance: none (a dash)");
        require(JustBackFit.averageSpeedKmh(null, null, 0.0, 0, 60_000L) == null, "zero distance: none");
        require(JustBackFit.averageSpeedKmh(null, null, 5.0, 0, 0) == null, "no time: none");
        require(JustBackFit.averageSpeedKmh(null, null, Double.NaN, 0, 60_000L) == null, "NaN distance: none");
        require(JustBackFit.averageSpeedKmh(0.0, null, 5.0, 0, 60_000L) == 0.0, "a stored zero is a real average");
    }

    private static void batteryChange() {
        require("\u221218".equals(JustBackFit.signedChange(18.0)), "a ride that used 18% reads \u221218 with a true minus");
        require("+2".equals(JustBackFit.signedChange(-2.0)), "a gain reads +2");
        require("0".equals(JustBackFit.signedChange(0.0)) && "0".equals(JustBackFit.signedChange(-0.0)), "no change reads 0, unsigned");
        require("0".equals(JustBackFit.signedChange(0.4)) && "0".equals(JustBackFit.signedChange(-0.4)), "under half a percent rounds to 0");
        require("\u22121".equals(JustBackFit.signedChange(0.5)), "half a percent rounds away");
        require(JustBackFit.signedChange(null) == null && JustBackFit.signedChange(Double.NaN) == null, "unknown: none (a dash)");
    }

    private static void distance() {
        require(JustBackFit.distanceSp(1f) == 60f, "about 60 sp at 1x, as the mock-up renders it");
        require(Math.abs(JustBackFit.distanceSp(1.4f) * 1.4f - 69f) < .01, "at 1.4x it grows only to 69 sp shown");
        require(Math.abs(JustBackFit.distanceSp(1.1f) * 1.1f - 66f) < .01, "a little large text grows it with the text");
        require(JustBackFit.distanceSp(Float.NaN) == 60f && JustBackFit.distanceSp(0) == 60f, "an unreadable scale is 1x");
    }

    private static void values() {
        // Tiles at 344 dp: (311 - 2 x 8) / 3 = 98 dp wide, 78 dp inside their padding. "15.5 mph" at 21 sp bold with
        // "mph" at 0.62x measures about 4 x 0.58 x 21 + 4 x 0.55 x 13 = 77 dp at 1x and 108 dp at 1.4x.
        require(JustBackFit.valueSize(21, 12, 77, 78) == 21, "1x at 344 dp: full size");
        float large = JustBackFit.valueSize(29.4f, 16.8f, 108, 78);
        require(large >= 16.8f && large < 29.4f && 108 * large / 29.4f <= 78.01f, "1.4x at 344 dp: smaller, the unit still whole: " + large);
        // 725 dp wide at 1x (two panes, the left about 330 dp): tiles about 99 dp, the same as the phone.
        require(JustBackFit.valueSize(21, 12, 77, 79) == 21, "725x604 at 1x: full size");
        // 725 dp at 1.4x is the phone layout: about 230 dp of content per tile row... tiles 215 wide.
        require(JustBackFit.valueSize(29.4f, 16.8f, 108, 215) == 29.4f, "725x604 at 1.4x (phone layout, wide tiles): full size");
        require(JustBackFit.valueSize(21, 12, 300, 78) == -1, "too wide even at the floor: the short rung");
        require(JustBackFit.valueSize(21, 12, 0, 78) == 21, "an empty value fits");
        require(JustBackFit.valueSize(21, 12, 10, 0) == -1, "no room: the short rung");
    }

    private static void split() {
        int full = 70, floor = 33, minRoute = 40, gap = 6;
        // 344x882 at 1x: area 304 dp, ended line 48, tiles 8 + 66: 182 left; the route gets 106.
        int[] phone = JustBackFit.split(304 - 48 - 74, full, floor, minRoute, gap);
        require(phone[0] == full && phone[1] == 106 && phone[2] == gap, "344x882 1x: full distance, a 106 dp route");
        // 344x882 at 1.4x: area 304, ended line 48, tiles 8 + 101 (names on two lines): 147 left; distance 80, route 61.
        int[] phoneLarge = JustBackFit.split(304 - 48 - 109, 80, 46, minRoute, gap);
        require(phoneLarge[0] == 80 && phoneLarge[1] == 61, "344x882 1.4x: the route still shows");
        // 725x604 at 1x (two panes): area 254, 48 + 74: 132 left; distance 70, route 56.
        int[] wide = JustBackFit.split(254 - 48 - 74, full, floor, minRoute, gap);
        require(wide[0] == full && wide[1] == 56, "725x604 1x: the route shows");
        // 725x604 at 1.4x (phone layout): area 208, 48 + 101 + 8 = 157: 51 left. The route goes, the distance takes 51.
        int[] short_ = JustBackFit.split(208 - 157, 80, 46, minRoute, gap);
        require(short_[1] == 0 && short_[2] == 0 && short_[0] == 51, "725x604 1.4x: the route goes first, the distance shrinks");
        int[] tiny = JustBackFit.split(10, 80, 46, minRoute, gap);
        require(tiny[0] == 46 && tiny[1] == 0, "never below the distance's floor");
        require(JustBackFit.split(-5, 80, 46, minRoute, gap)[0] == 46, "a negative room is none");
        require(JustBackFit.split(80 + gap + 39, 80, 46, minRoute, gap)[1] == 0, "a route under 40 dp is not drawn");
        require(JustBackFit.split(80 + gap + 40, 80, 46, minRoute, gap)[1] == 40, "40 dp is");
        for (int room = 0; room < 400; room++) {
            int[] r = JustBackFit.split(room, 80, 46, minRoute, gap);
            require(r[0] + r[1] + r[2] <= Math.max(room, 46), "never more than the room (or the floor): " + room);
        }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
