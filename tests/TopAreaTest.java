package com.jjricks.volt;

/** TopArea: Clean Home's fixed top-area height (hub layout plan 1.6, PR 6). */
public final class TopAreaTest {
    private static int checks;

    public static void main(String[] args) {
        require(TopArea.heightDp(800, false, 1f) == 276, "360x800 phone: 34.5% of 800");
        require(TopArea.heightDp(882, false, 1f) == 304, "344x882 cover screen: the Layout Plan's proportion");
        require(TopArea.heightDp(915, false, 1f) == 316, "412x915 phone");
        require(TopArea.heightDp(882, false, 1.4f) == 304, "344x882 at 1.4x: the same area (the grown minimum, 291, is less)");
        require(TopArea.heightDp(915, false, 1.4f) >= 291, "Large text keeps at least 224 dp grown 1.3 times on a tall window");
        require(TopArea.heightDp(1100, false, 1.15f) == 380 && TopArea.heightDp(700, false, 1.15f) == 258, "Text growth below the cap scales the minimum");
        require(TopArea.heightDp(700, false, 0.85f) == 242, "Small text never shrinks the share");
        require(TopArea.heightDp(900, true, 1f) == 378, "Wide 840x900: 42%");
        require(TopArea.heightDp(360, true, 1f) == TopArea.FLOOR_DP, "Wide landscape 800x360: the floor");
        require(TopArea.heightDp(2000, false, 1f) == 380, "Tall phone window: phone maximum");
        require(TopArea.heightDp(2000, true, 1f) == 460, "Tall wide window: wide maximum");
        require(TopArea.heightDp(0, false, 1f) == TopArea.FLOOR_DP && TopArea.heightDp(-5, false, Float.NaN) == TopArea.FLOOR_DP, "Nonsense input still gives the floor");
        require(TopArea.heightPx(800, false, 1f, 2.75f) == 759, "Pixels at a density");
        // 725x604 dp at 1x is wide (a rail, no bottom bar): the share fits under the header with the Lock bar and the
        // quick actions below it.
        require(TopArea.heightDp(604, true, 1f) == 254, "725x604 at 1x: 42% of 604");
        require(604 - TopArea.heightDp(604, true, 1f) >= TopArea.reserveDp(true, 1f), "725x604 at 1x: the Lock bar and quick actions fit under it");
        // 725x604 dp at 1.4x keeps the phone layout (650 text-dp rule) with main's bottom bar: the grown minimum (291)
        // would push the Lock bar under the bar (emulator, part 8b), so the area takes what the window leaves.
        int short14 = TopArea.heightDp(604, false, 1.4f);
        require(short14 < 291 && short14 >= TopArea.FLOOR_DP, "725x604 at 1.4x: smaller than the grown minimum, " + short14);
        // With the header's two 48 dp targets (status line, name row), this window cannot also keep the quick actions
        // in view: the area sits at its floor, which the charging view's worst case needs (ChargingFitTest, a wrapped
        // pill at 1.4x), and the window keeps the header, the Lock bar and the bottom bar; the quick actions' names
        // fall under the bottom bar (PR #449, deferred).
        require(short14 == TopArea.FLOOR_DP, "725x604 at 1.4x: the floor, " + short14);
        int quickActions = 12 + 54 + 6 + 16 + 14;
        require(604 - short14 >= TopArea.reserveDp(false, 1.4f) - quickActions, "725x604 at 1.4x: the Lock bar fits on first view");
        require(TopArea.heightDp(500, false, 1.4f) == TopArea.FLOOR_DP, "Large text on a short window: the floor");
        // The parked card's spare height: spread over three gaps, none of them a band of 40 dp at the checked sizes.
        for (int extra : new int[]{0, 1, 7, 60, 76, 90, 95}) {
            int[] gaps = TopArea.parkedGaps(extra);
            require(gaps.length == 3 && gaps[0] + gaps[1] + gaps[2] == extra, "the gaps share exactly the spare height " + extra);
            require(gaps[0] >= 0 && gaps[1] >= 0 && gaps[2] >= 0, "no negative gap for " + extra);
            require(Math.max(gaps[0], Math.max(gaps[1], gaps[2])) < 44, "no band of 44 dp or more for " + extra);
        }
        // An even split: the gap over the readings is no larger than the others (the mock-up's 36/37/20 dp weights left
        // bands of 40 dp or more over the bar on the emulator's 344 and 412 dp phones).
        int[] even = TopArea.parkedGaps(93);
        require(even[2] <= even[0] && Math.abs(even[0] - even[1]) <= 1 && Math.abs(even[1] - even[2]) <= 1,
                "about even: " + even[0] + "/" + even[1] + "/" + even[2]);
        require(TopArea.parkedGaps(-3)[0] == 0 && TopArea.parkedGaps(-3)[1] == 0, "no spare height, no gaps");
        // The parked photo (the mock-up's ~144 dp picture right of the words): full size on a 344 dp phone at 1x (a
        // card's content is about 276 dp there), narrower but still shown at 1.4x, full size on the wide window.
        require(TopArea.parkedPhotoDp(276, 1f) == TopArea.PARKED_PHOTO_DP, "344 dp at 1x: the mock-up's photo");
        int large = TopArea.parkedPhotoDp(276, 1.4f);
        require(large >= TopArea.PARKED_PHOTO_MIN_DP && large < TopArea.PARKED_PHOTO_DP, "344 dp at 1.4x: the photo stays, narrower: " + large);
        require(276 - large - TopArea.PARKED_PHOTO_GAP_DP >= TopArea.PARKED_WORDS_DP * 1.4f, "344 dp at 1.4x: the words keep their room");
        require(TopArea.parkedPhotoDp(344, 1f) == TopArea.PARKED_PHOTO_DP && TopArea.parkedPhotoDp(600, 1.4f) == TopArea.PARKED_PHOTO_DP,
                "412 dp and wide windows: the mock-up's photo");
        require(TopArea.parkedPhotoDp(180, 1.4f) == 0 && TopArea.parkedPhotoDp(100, Float.NaN) == 0, "no room for the words and a photo: no photo");
        // Its height: the mock-up's size where the card has the height, else the words' row (it never pushes them out).
        require(TopArea.parkedPhotoHeight(100, 80, 144) == 144, "spare height: the photo takes its full side");
        require(TopArea.parkedPhotoHeight(100, 20, 144) == 120, "a little spare: the photo grows by it");
        require(TopArea.parkedPhotoHeight(100, 0, 144) == 100 && TopArea.parkedPhotoHeight(100, -5, 144) == 100, "no spare: the words' height");
        require(TopArea.parkedPhotoHeight(150, 50, 144) == 150, "words taller than the photo: the row's height");
        for (int h = 300; h <= 1400; h += 7)
            for (float scale : new float[]{0.8f, 1f, 1.3f, 2f})
                for (boolean wide : new boolean[]{false, true}) {
                    int v = TopArea.heightDp(h, wide, scale);
                    require(v >= TopArea.FLOOR_DP && v <= (wide ? TopArea.WIDE_MAX_DP : TopArea.PHONE_MAX_DP), "Always within the clamp");
                    require(TopArea.heightDp(h + 7, wide, scale) >= v, "Never shrinks on a taller window");
                }
        System.out.println("TopArea: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
