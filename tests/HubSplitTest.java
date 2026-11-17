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
        // RTL (#528 council P3s): the first (scooter) pane is laid out at the right, and buildPanes pads only the start, so
        // its outer edge is the row's right padding, not the left. A 725 dp window, row at 0, 24 dp at the start: in LTR
        // the left padding is 24; in RTL the left padding is 0 and the room between paddings 701.
        int ltrEdge = HubSplit.firstEdge(false, 725, 0, 24, 701), rtlEdge = HubSplit.firstEdge(true, 725, 0, 0, 701);
        require(ltrEdge == 24 && rtlEdge == 24, "The first pane's edge is 24 dp from its side of the window both ways: " + ltrEdge + ", " + rtlEdge);
        HubSplit.Split ltr = HubSplit.split(701, 20, 320, 276, 0.5f, HubSplit.centreLeft(725, 20, ltrEdge), 0, 0, 8);
        HubSplit.Split rtl = HubSplit.split(701, 20, 320, 276, 0.5f, HubSplit.centreLeft(725, 20, rtlEdge), 0, 0, 8);
        require(ltr.left == rtl.left && ltr.right(701) == rtl.right(701), "RTL mirrors LTR's widths: " + ltr + " vs " + rtl);
        // The gutter's centre: LTR 24 + left + 10 from the left; RTL the same from the right (725 - 24 - left - 10 from the left).
        int ltrGutter = 24 + ltr.left + 10, rtlGutter = 725 - 24 - rtl.left - 10;
        require(Math.abs(ltrGutter - 362) <= 1 && Math.abs(rtlGutter - 362) <= 1, "Gutter on the centre line both ways: " + ltrGutter + ", " + rtlGutter);
        // The old reading (the left padding, 0 in RTL) put the RTL gutter 24 dp off centre: the check above catches it.
        HubSplit.Split old = HubSplit.split(701, 20, 320, 276, 0.5f, HubSplit.centreLeft(725, 20, 0), 0, 0, 8);
        require(Math.abs(725 - 24 - old.left - 10 - 362) >= 20, "The left padding in RTL was the wrong edge: " + old);
        // An RTL hinge: 30 dp at 350..380 from the window's left is 345..375 from its right; from the first pane's edge 321.
        require(HubSplit.hingeFromFirst(true, 725, 24, 350, 380) == 321 && HubSplit.hingeFromFirst(false, 725, 24, 350, 380) == 326,
                "The hinge measured from the first pane's edge, mirrored in RTL");
        // A row placed inside the window (rowLeft 10): RTL's edge counts from the right.
        require(HubSplit.firstEdge(true, 725, 10, 0, 691) == 24, "RTL edge with the row inset");

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
