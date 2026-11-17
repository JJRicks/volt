package com.jjricks.volt;

/** HubSplit: where the wide Home's two panes (and More's old ones) divide a row (hub layout plan 1.11, PR 13). */
public final class HubSplitTest {
    private static int checks;

    public static void main(String[] args) {
        // Fitting: the wide Clean Home asks before building two panes (320 dp scooter pane, 260 dp page pane, 20 dp gutter).
        require(HubSplit.fits(602, 20, 320, 260), "A 650 dp window less 24 dp padding a side holds both panes");
        require(HubSplit.fits(600, 20, 320, 260), "Exactly the minimums fit");
        require(!HubSplit.fits(599, 20, 320, 260), "One short does not");
        require(!HubSplit.fits(0, 20, 320, 260) && !HubSplit.fits(-10, 20, 0, 0), "No width never fits");

        // The window's centre line: an 840 dp window, row from 24 dp, 792 wide.
        int centre = HubSplit.centreLeft(840, 20, 24);
        require(centre == 386, "Centre of 840 less half the gutter less the padding");
        HubSplit.Split even = HubSplit.split(792, 20, 320, 260, 0.42f, centre, 0, 0, 8);
        require(even.left == 386 && even.gap == 20 && even.right(792) == 386, "The gutter sits on the centre line: " + even);

        // Minimums hold the centre back.
        HubSplit.Split narrow = HubSplit.split(602, 20, 320, 260, 0.42f, HubSplit.centreLeft(650, 20, 24), 0, 0, 8);
        require(narrow.left == 320 && narrow.right(602) == 262, "A 650 dp window: the scooter pane keeps 320 dp: " + narrow);
        HubSplit.Split wideRight = HubSplit.split(1000, 20, 320, 260, 0.42f, 900, 0, 0, 8);
        require(wideRight.left == 720 && wideRight.right(1000) == 260, "The page pane keeps its 260 dp: " + wideRight);

        // Before the row knows its place: the share.
        HubSplit.Split shared = HubSplit.split(792, 20, 220, 260, 0.42f, -1, 0, 0, 8);
        require(shared.left == Math.round(772 * 0.42f), "Unplaced: 42% of the row less the gutter: " + shared);

        // Too narrow for both minimums: halves (More's old rule); the Hub asks fits() first and takes one pane.
        HubSplit.Split half = HubSplit.split(500, 20, 320, 260, 0.42f, 240, 0, 0, 8);
        require(half.left == 240 && half.gap == 20, "Halves when the minimums cannot both hold: " + half);
        require(HubSplit.split(0, 20, 320, 260, 0.42f, -1, 0, 0, 8).left == 0, "An empty row never goes negative");

        // A vertical hinge (book posture): the gutter covers it, with room on each side.
        HubSplit.Split hinge = HubSplit.split(792, 20, 320, 260, 0.42f, 386, 380, 30, 8);
        require(hinge.left == 372 && hinge.gap == 46 && hinge.right(792) == 374, "The gutter covers the hinge: " + hinge);
        HubSplit.Split thinHinge = HubSplit.split(792, 20, 320, 260, 0.42f, 386, 400, 0, 8);
        require(thinHinge.left == 386 && thinHinge.gap == 20, "A hinge with no width is no hinge: " + thinHinge);
        HubSplit.Split farHinge = HubSplit.split(792, 20, 320, 260, 0.42f, 386, 200, 30, 8);
        require(farHinge.left == 386 && farHinge.gap == 20, "A hinge that would starve the left pane is not followed: " + farHinge);
        HubSplit.Split nearEnd = HubSplit.split(792, 20, 320, 260, 0.42f, 386, 600, 30, 8);
        require(nearEnd.left == 386, "Nor one that would starve the right pane: " + nearEnd);

        // Always within the row and the minimums whenever they fit.
        for (int size = 560; size <= 1600; size += 13)
            for (int want = -1; want <= size; want += 37) {
                HubSplit.Split s = HubSplit.split(size, 20, 320, 260, 0.42f, want, 0, 0, 8);
                require(s.left >= 0 && s.left + s.gap <= size, "Inside the row");
                if (HubSplit.fits(size, 20, 320, 260)) require(s.left >= 320 && s.right(size) >= 260, "Minimums hold at " + size + "/" + want);
            }
        System.out.println("HubSplit: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
