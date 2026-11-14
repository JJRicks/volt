package com.jjricks.volt;

/**
 * JustBackFit: Just back as the Layout Plan's mock-up draws it (the contributor's decisions on #383), at 344x882 and
 * 725x604 dp with 1x and 1.4x text: the signed battery change, the distance's size and how the fixed top area shares its
 * height between the distance and the route. The Avg speed tile's value is RideAverages.speedKmh, the rule Rides shares:
 * its checks are in RideAveragesTest, and tests/l10n/JustBackSpeedTest checks both pages' text.
 */
public final class JustBackFitTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        batteryChange();
        distance();
        values();
        split();
        names(args.length > 0 ? args[0] : null);
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
        // One size for the row (review of #390): at 1.4x, "20m 00s" needs shrinking while "18.6 mph" and "−18%"
        // fit; all three take the smallest fit size, so they share one size and one baseline.
        float time = JustBackFit.valueSize(29.4f, 16.8f, 112, 78), speed = JustBackFit.valueSize(29.4f, 16.8f, 76, 78),
                battery = JustBackFit.valueSize(29.4f, 16.8f, 60, 78);
        require(time < 29.4f && speed == 29.4f && battery == 29.4f, "only the ride time needs shrinking alone");
        float row = JustBackFit.rowValueSize(29.4f, time, speed, battery);
        require(row == time, "the row takes the smallest fit size: " + row);
        require(JustBackFit.rowValueSize(21, 21, 21, 21) == 21, "nothing shrinks: full size for all");
        require(JustBackFit.rowValueSize(21, 12, 21, 18) == 12, "a value at the floor (its short rung) sets the row");
        require(JustBackFit.rowValueSize(21) == 21 && JustBackFit.rowValueSize(21, -1, 0) == 21, "no fits: full size");
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

    /**
     * The tile names on one line (emulator walk on #390: "Avg speed" wrapped onto two lines at 344 dp with 1.4x text;
     * the mock-up has one line each): a little smaller together, never under JustBackFit.NAME_MIN_SCALE or the readable
     * floor, else they keep their size and wrap at spaces. Widths with Saira (res/font/saira_regular.ttf, the first
     * argument), the app's font, at the names' 12.5 sp times the text scale; the tiles a third of Home's row less 2 x
     * 8 dp, less their 2 x 10 dp padding and 1 px kept in hand. Rows: 344 dp less 2 x 18 dp; 725 dp at 1x is two
     * panes with Clean's column about 330 dp; 725 dp at 1.4x is the phone layout, at most 560 dp.
     */
    private static void names(String fontPath) throws Exception {
        java.awt.Font saira = fontPath == null ? new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12)
                : java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, new java.io.File(fontPath));
        java.awt.font.FontRenderContext context = new java.awt.font.FontRenderContext(null, true, true);
        String[] english = {"Ride time", "Avg speed", "Battery"};
        int[][] sizes = {{344, 308}, {725, 330}, {412, 376}};
        for (int[] size : sizes)
            for (float text : new float[]{1f, 1.4f})
                for (float wider : new float[]{1f, 1.1f}) {
                    int row = size[0] == 725 && text > 1 ? Math.min(725 - 36, 560) : size[1];
                    float room = (row - 2 * 8) / 3f - 20 - 1;
                    java.awt.Font font = saira.deriveFont(JustBackFit.NAME_SP * text);
                    float[] widths = new float[english.length];
                    for (int i = 0; i < english.length; i++) widths[i] = (float) font.getStringBounds(english[i], context).getWidth() * wider;
                    float scale = JustBackFit.nameScale(widths, room, JustBackFit.NAME_SP * text, 12);
                    String at = size[0] + " dp at " + text + "x" + (wider > 1 ? ", wider text" : "");
                    float widest = 0;
                    for (float w : widths) widest = Math.max(widest, w);
                    require(scale == 1 || scale >= JustBackFit.NAME_MIN_SCALE, at + ": never under the least share: " + scale);
                    require(scale == 1 || widest * scale <= room, at + ": a shrunk name fits on one line: " + scale);
                    if (wider == 1) require(widest * scale <= room, at + ": every English name on one line (scale " + scale + ", widest "
                            + widest + ", room " + room + ")");
                    if (size[0] == 344 && text == 1f && wider == 1) require(scale == 1, at + ": full size, as the mock-up");
                    if (size[0] == 344 && text > 1 && wider == 1) require(scale < 1, at + ": Avg speed a little smaller, on one line: " + scale);
                    System.out.println("  names " + at + ": scale " + scale + " (widest " + Math.round(widest) + " of " + Math.round(room) + ")");
                }
        // The rule alone.
        require(JustBackFit.nameScale(new float[]{50, 70}, 70, 12.5f, 12) == 1, "fits: full size");
        require(JustBackFit.nameScale(new float[]{50, 80}, 70, 17.5f, 12) == .87f, "a little wide: the largest share, in hundredths");
        require(JustBackFit.nameScale(new float[]{50, 100}, 70, 17.5f, 12) == 1, "too wide even at the least share: full size, wrapped at spaces");
        require(JustBackFit.nameScale(new float[]{50, 72}, 70, 12.5f, 12) == .97f, "at 1x, down to the readable floor (12 of 12.5 sp, 0.96): 0.97");
        require(JustBackFit.nameScale(new float[]{50, 76}, 70, 12.5f, 12) == 1, "at 1x, under the readable floor (0.92 < 0.96): full size");
        require(JustBackFit.nameScale(new float[]{80}, 0, 17.5f, 12) == 1, "no room: full size");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
