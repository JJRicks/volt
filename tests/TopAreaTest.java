package com.jjricks.volt;

/** TopArea: Clean Home's fixed top-area height (hub layout plan 1.6, PR 6). */
public final class TopAreaTest {
    private static int checks;

    public static void main(String[] args) {
        // A phone's area follows the window's width (304 of 344 dp), so the parked card keeps the Layout Plan's
        // proportions on every phone; the same value holds the parked card, the charging view and Just back.
        require(TopArea.heightDp(344, 882, false, 1f) == 304, "344x882 cover screen: the Layout Plan's proportion");
        require(TopArea.heightDp(360, 800, false, 1f) == 318, "360x800 phone: 304/344 of 360");
        require(TopArea.heightDp(393, 852, false, 1f) == 347, "393x852 phone");
        require(TopArea.heightDp(412, 901, false, 1f) == 364, "412x901 phone (the owner's)");
        require(TopArea.heightDp(412, 915, false, 1f) == 364, "412x915 phone: the same as 412x901, the height does not count");
        require(TopArea.heightDp(430, 932, false, 1f) == 380, "430x932 phone: 380, the phone maximum");
        for (int[] size : new int[][]{{344, 882}, {360, 800}, {393, 852}, {412, 901}, {412, 915}, {430, 932}})
            for (float text : new float[]{1f, 1.4f}) {
                int area = TopArea.heightDp(size[0], size[1], false, text);
                // The mock-up's card is 304 dp tall on a 344 dp phone (the card 312 dp wide); a wider phone keeps that
                // proportion, never shorter than at 344 dp, until the phone maximum.
                require(area >= 304 && area <= TopArea.PHONE_MAX_DP, size[0] + "x" + size[1] + " at " + text + "x: " + area);
                require(area == Math.min(TopArea.PHONE_MAX_DP, Math.round(size[0] * 304f / 344f)),
                        size[0] + "x" + size[1] + " at " + text + "x: the width's share, " + area);
                require(size[1] - area >= TopArea.reserveDp(false, text), size[0] + "x" + size[1] + " at " + text
                        + "x: the header, the Lock bar, the quick actions and the bottom bar fit under it");
            }
        require(TopArea.heightDp(344, 882, false, 1.4f) == 304, "344x882 at 1.4x: the same area (the grown minimum, 291, is less)");
        require(TopArea.heightDp(300, 915, false, 1.4f) == 291, "Large text keeps at least 224 dp grown 1.3 times on a narrow window");
        require(TopArea.heightDp(260, 1100, false, 1.15f) == 258 && TopArea.heightDp(250, 700, false, 1f) == 224, "Text growth below the cap scales the minimum");
        require(TopArea.heightDp(300, 700, false, 0.85f) == 265, "Small text never shrinks the share");
        require(TopArea.heightDp(840, 900, true, 1f) == 378, "Wide 840x900: 42% of the height, as before");
        require(TopArea.heightDp(800, 360, true, 1f) == TopArea.FLOOR_DP, "Wide landscape 800x360: the floor");
        require(TopArea.heightDp(600, 2000, false, 1f) == 380, "Wide phone window: phone maximum");
        require(TopArea.heightDp(1200, 2000, true, 1f) == 460, "Tall wide window: wide maximum");
        require(TopArea.heightDp(0, 0, false, 1f) == TopArea.FLOOR_DP && TopArea.heightDp(-5, -5, false, Float.NaN) == TopArea.FLOOR_DP, "Nonsense input still gives the floor");
        require(TopArea.heightPx(360, 800, false, 1f, 2.75f) == 875, "Pixels at a density");
        // 725x604 dp at 1x is wide (a rail, no bottom bar): the share fits under the header with the Lock bar and the
        // quick actions below it. Unchanged by the phones' rule.
        require(TopArea.heightDp(725, 604, true, 1f) == 254, "725x604 at 1x: 42% of 604");
        require(604 - TopArea.heightDp(725, 604, true, 1f) >= TopArea.reserveDp(true, 1f), "725x604 at 1x: the Lock bar and quick actions fit under it");
        // 725x604 dp at 1.4x keeps the phone layout (650 text-dp rule) with main's bottom bar: the grown minimum (291)
        // would push the Lock bar under the bar (emulator, part 8b), so the area takes what the window leaves.
        int short14 = TopArea.heightDp(725, 604, false, 1.4f);
        require(short14 < 291 && short14 >= TopArea.FLOOR_DP, "725x604 at 1.4x: smaller than the grown minimum, " + short14);
        // With the header's two 48 dp targets (status line, name row), this window cannot also keep the quick actions
        // in view: the area sits at its floor, which the charging view's worst case needs (ChargingFitTest, a wrapped
        // pill at 1.4x), and the window keeps the header, the Lock bar and the bottom bar; the quick actions' names
        // fall under the bottom bar (PR #449, deferred).
        require(short14 == TopArea.FLOOR_DP, "725x604 at 1.4x: the floor, " + short14);
        int quickActions = 12 + 54 + 6 + 16 + 14;
        require(604 - short14 >= TopArea.reserveDp(false, 1.4f) - quickActions, "725x604 at 1.4x: the Lock bar fits on first view");
        require(TopArea.heightDp(344, 500, false, 1.4f) == TopArea.FLOOR_DP, "Large text on a short window: the floor");
        require(TopArea.heightDp(412, 640, false, 1f) == 260, "A short wide phone: the window less what must show under the area");
        // The parked card's spare height: spread over three gaps, none of them a band of 40 dp at the checked sizes.
        for (int extra : new int[]{0, 1, 7, 60, 76, 90, 95}) {
            int[] gaps = TopArea.parkedGaps(extra);
            require(gaps.length == 3 && gaps[0] + gaps[1] + gaps[2] == extra, "the gaps share exactly the spare height " + extra);
            require(gaps[0] >= 0 && gaps[1] >= 0 && gaps[2] >= 0, "no negative gap for " + extra);
            require(Math.max(gaps[0], Math.max(gaps[1], gaps[2])) < 44, "no band of 44 dp or more for " + extra);
        }
        // Weighted as the mock-up spreads it (about 36, 37 and 20 dp of 93): the readings sit close under the bar.
        int[] mock = TopArea.parkedGaps(93);
        require(Math.abs(mock[0] - 36) <= 1 && Math.abs(mock[1] - 37) <= 1 && Math.abs(mock[2] - 20) <= 1,
                "the mock-up's split, about 36/37/20 of 93: " + mock[0] + "/" + mock[1] + "/" + mock[2]);
        require(mock[2] < mock[0] && mock[2] < mock[1], "the gap over the readings is the smallest");
        require(TopArea.parkedGaps(Math.round(84 * 3f))[2] <= 56, "pixels: the same weights at 3 px per dp");
        require(TopArea.parkedGaps(-3)[0] == 0 && TopArea.parkedGaps(-3)[1] == 0, "no spare height, no gaps");
        // The parked photo (the mock-up's ~144 dp picture right of the words): full size on a 344 dp phone at 1x (the
        // card's content is 280 by 280 dp there), narrower but still shown at 1.4x.
        require(TopArea.parkedPhotoDp(280, 280, 1f, 1f, 0) == TopArea.PARKED_PHOTO_DP, "344 dp at 1x: the mock-up's photo");
        int large = TopArea.parkedPhotoDp(280, 280, 1.4f, 1f, 0);
        require(large >= TopArea.PARKED_PHOTO_MIN_DP && large < TopArea.PARKED_PHOTO_DP, "344 dp at 1.4x: the photo stays, narrower: " + large);
        require(large == 102, "344 dp at 1.4x: as before, 102 dp: " + large);
        require(280 - large - TopArea.PARKED_PHOTO_GAP_DP >= TopArea.PARKED_WORDS_DP * 1.4f, "344 dp at 1.4x: the words keep their room");
        require(TopArea.parkedPhotoDp(180, 280, 1.4f, 1f, 0) == 0 && TopArea.parkedPhotoDp(100, 280, Float.NaN, 1f, 0) == 0, "no room for the words and a photo: no photo");
        // On a larger phone the card's content grows as the card does (the owner's first build on a 412 dp phone, #383:
        // "way too much padding"): the percentage and the photo, and half as much the label, range line and readings,
        // from 1 on the 344 dp phone to at most 1.3 times. A card's content is the window less 64 dp (Home's 18 dp and
        // the card's 14 dp each side), its height the area less 24 dp.
        int[][] phones = {{344, 882}, {360, 800}, {393, 852}, {412, 901}, {412, 915}, {430, 932}};
        int[] percent10 = {58, 61, 67, 70, 70, 74}, photo10 = {144, 151, 166, 174, 174, 183}, photo14 = {102, 118, 151, 170, 170, 183};
        for (int i = 0; i < phones.length; i++) {
            int content = phones[i][0] - 64, card = TopArea.heightDp(phones[i][0], phones[i][1], false, 1f) - 24;
            require(card == TopArea.heightDp(phones[i][0], phones[i][1], false, 1.4f) - 24, "the same card at 1.4x");
            String at = phones[i][0] + "x" + phones[i][1];
            float growth = TopArea.parkedGrowth(content, card), text = TopArea.parkedTextGrowth(content, card, 1f);
            require(growth == text && growth >= 1 && growth <= TopArea.PARKED_MAX_GROWTH, at + ": at 1x the text grows as the card does, " + growth);
            require(Math.abs(growth - Math.min(content / 280f, card / 280f)) < 1e-4, at + ": as much as the card grew both ways, " + growth);
            require(TopArea.parkedTextGrowth(content, card, 1.4f) == 1f, at + " at 1.4x: large text keeps its size");
            require(Math.round(58 * text) == percent10[i], at + ": the percentage " + Math.round(58 * text) + " sp");
            int side = TopArea.parkedPhotoDp(content, card, 1f, text, 0), side14 = TopArea.parkedPhotoDp(content, card, 1.4f, 1f, 0);
            require(side == photo10[i], at + " at 1x: the photo " + side);
            require(side14 == photo14[i], at + " at 1.4x: the photo " + side14);
            require(side >= TopArea.PARKED_PHOTO_DP && side14 >= large, at + ": never smaller than on the 344 dp phone");
            // The photo grows in proportion with the card: the mock-up's share of it, as on the 344 dp phone.
            require(Math.abs(side - TopArea.PARKED_PHOTO_DP * growth) <= 1, at + ": the photo keeps its share of the card");
            // The words keep their room beside it, grown as the percentage is.
            require(content - side - TopArea.PARKED_PHOTO_GAP_DP >= TopArea.PARKED_WORDS_DP * text, at + " at 1x: the words' room");
            require(content - side14 - TopArea.PARKED_PHOTO_GAP_DP >= TopArea.PARKED_WORDS_DP * 1.4f, at + " at 1.4x: the words' room");
            // The parked card's lines at their grown sizes, in dp as the 344 dp phone measures them (label 20.3, the
            // percentage 91, the range line 23.7, the bar 8 with 12 above and below, grown as the percentage, the
            // readings 51, at 1x), leave the spare height the 344 dp card has (53 dp of 280), grown no more than the
            // card: no gap becomes a band.
            float small = TopArea.parkedSmallGrowth(text);
            float row = Math.max(side, 20.3f * small + 91 * text + 23.7f * small), lines = row + 8 + 2 * Math.round(12 * text) + 51 * small;
            int spare = Math.round(card - lines);
            int[] gaps = TopArea.parkedGaps(spare);
            require(spare >= 0, at + ": the lines fit the card: " + spare);
            require(Math.max(gaps[0], Math.max(gaps[1], gaps[2])) <= Math.round(TopArea.parkedGaps(53)[0] * growth) + 4,
                    at + ": no gap larger than the 344 dp card's grown with the card (within 4 dp: the bar and the readings grow less): " + gaps[0] + "/" + gaps[1] + "/" + gaps[2]);
        }
        require(TopArea.parkedSmallGrowth(1f) == 1f && TopArea.parkedSmallGrowth(1.3f) == 1.15f && TopArea.parkedSmallGrowth(.5f) == 1f,
                "the label, range line and readings grow half as much");
        require(TopArea.parkedGrowth(600, 600) == TopArea.PARKED_MAX_GROWTH && TopArea.parkedGrowth(200, 400) == 1f
                && TopArea.parkedGrowth(Float.NaN, 300) == 1f, "growth within 1 and 1.3");
        require(TopArea.parkedTextGrowth(400, 400, 1.15f) > 1f && TopArea.parkedTextGrowth(400, 400, 1.15f) <= 1.3f / 1.15f + 1e-6,
                "a little large text grows only to what 1x text reaches");
        // A wide window's pane (725x604 dp: a 532 dp wide card 230 dp tall) and the phone layout on a short window
        // (725x604 at 1.4x: the area's floor) do not grow: the card is no taller than on the 344 dp phone.
        require(TopArea.parkedGrowth(532, TopArea.heightDp(725, 604, true, 1f) - 24) == 1f, "725x604 at 1x: no growth");
        require(TopArea.parkedGrowth(532, TopArea.heightDp(725, 604, false, 1.4f) - 24) == 1f, "725x604 at 1.4x: no growth");
        require(TopArea.parkedPhotoDp(532, 230, 1f, 1f, 0) == TopArea.PARKED_PHOTO_DP, "725x604 at 1x: the photo as before");
        // A long range line stays whole: where it is wider than the words' room, the photo gives way (a translation can
        // be). "about 124 km left" is 120.3 dp at 15 sp and 168.4 dp at 21 sp (emulator, 344x882).
        require(TopArea.parkedPhotoDp(280, 280, 1f, 1f, 120.3f) == 144, "344 dp, \"about 124 km left\" at 1x: the photo as before");
        require(TopArea.parkedPhotoDp(280, 280, 1f, 1f, 131) == 280 - 10 - 131, "344 dp, a wider line at 1x: the photo narrower, the line whole");
        require(TopArea.parkedPhotoDp(280, 280, 1.4f, 1f, 168.4f) == 101, "344 dp at 1.4x, \"about 124 km left\": the photo 1 dp narrower, the line whole");
        require(TopArea.parkedPhotoDp(280, 280, 1.4f, 1f, 210) == 0, "no room for a photo beside the line: no photo, the line whole");
        require(TopArea.parkedPhotoDp(348, 340, 1f, TopArea.parkedTextGrowth(348, 340, 1f),
                120.3f * TopArea.parkedSmallGrowth(TopArea.parkedTextGrowth(348, 340, 1f))) == 174,
                "412 dp, \"about 124 km left\" at the grown size: the photo keeps its size");
        require(TopArea.parkedPhotoDp(348, 340, 1.4f, 1f, 168.4f) == 169, "412 dp at 1.4x, \"about 124 km left\": the line whole");
        // Its height: the mock-up's size where the card has the height, else the words' row (it never pushes them out).
        require(TopArea.parkedPhotoHeight(100, 80, 144) == 144, "spare height: the photo takes its full side");
        require(TopArea.parkedPhotoHeight(100, 20, 144) == 120, "a little spare: the photo grows by it");
        require(TopArea.parkedPhotoHeight(100, 0, 144) == 100 && TopArea.parkedPhotoHeight(100, -5, 144) == 100, "no spare: the words' height");
        require(TopArea.parkedPhotoHeight(150, 50, 144) == 150, "words taller than the photo: the row's height");
        for (int h = 300; h <= 1400; h += 7)
            for (int w = 240; w <= 1000; w += 37)
                for (float scale : new float[]{0.8f, 1f, 1.3f, 2f})
                    for (boolean wide : new boolean[]{false, true}) {
                        int v = TopArea.heightDp(w, h, wide, scale);
                        require(v >= TopArea.FLOOR_DP && v <= (wide ? TopArea.WIDE_MAX_DP : TopArea.PHONE_MAX_DP), "Always within the clamp");
                        require(TopArea.heightDp(w, h + 7, wide, scale) >= v, "Never shrinks on a taller window");
                        require(TopArea.heightDp(w + 37, h, wide, scale) >= v, "Never shrinks on a wider window");
                    }
        System.out.println("TopArea: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
