package com.jjricks.volt;

/**
 * JustBackFit: Just back as the Layout Plan's mock-up draws it (the contributor's decisions on #383), at 344x882 and
 * 725x604 dp with 1x and 1.4x text: the signed battery change, the distance's size and how the fixed top area shares its
 * height between the distance and the route. The Avg speed tile's value is RideAverages.speedKmh, the rule Rides shares:
 * its checks are in RideAveragesTest, and tests/l10n/JustBackSpeedTest checks both pages' text.
 */
public final class JustBackFitTest {
    private static int checks;

    public static void main(String[] args) {
        batteryChange();
        distance();
        values();
        split();
        System.out.println("JustBackFit: " + checks + " checks passed");
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
        int full = 70, floor = 33, minRoute = JustBackFit.MIN_ROUTE_DP, gap = 6;
        require(minRoute == 56, "a route panel under 56 dp is too flat to read");
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
        // Short of the full distance plus 56 dp, the distance gets smaller so the route keeps 56 dp.
        int[] squeezed = JustBackFit.split(80 + gap + 55, 80, 46, minRoute, gap);
        require(squeezed[1] == 56 && squeezed[0] == 79, "the distance gives 1 dp so the route keeps 56");
        require(JustBackFit.split(80 + gap + 56, 80, 46, minRoute, gap)[1] == 56, "56 dp with the full distance");
        // 344x882 at 1.4x as measured on the emulator: about 44 dp would be left beside the full distance.
        int[] measured = JustBackFit.split(80 + gap + 44, 80, 46, minRoute, gap);
        require(measured[1] == 56 && measured[0] == 68, "344x882 1.4x: the distance gets smaller, the route stays");
        // Below the floor plus the route: the route goes and the distance's box takes the room (no blank band).
        int[] none = JustBackFit.split(46 + gap + 55, 80, 46, minRoute, gap);
        require(none[1] == 0 && none[0] == 107, "no room for both: the route goes, the box takes the room");
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
