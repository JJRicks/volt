package com.jjricks.volt;

/** PageColumn: how wide a one-column page grows on a wide window (hub layout plan 1.11, PR 13). */
public final class PageColumnTest {
    private static int checks;

    public static void main(String[] args) {
        // The sizes the maintainers check: 344x882 dp (phone) and 725x604 dp, at 1x and 1.4x text.
        require(PageColumn.sideDp(344, 1f) == 0, "344 dp, 1x: the phone's page is untouched");
        require(PageColumn.sideDp(344, 1.4f) == 0, "344 dp, 1.4x: untouched");
        require(PageColumn.sideDp(725, 1f) == 42, "725 dp, 1x: a 640 dp column, 42 dp a side: " + PageColumn.sideDp(725, 1f));
        require(PageColumn.sideDp(725, 1.4f) == 0, "725 dp, 1.4x (the phone layout): the column grows with the text, so the page keeps the window");
        // The review's case: Settings across an 850 dp window.
        require(PageColumn.sideDp(850, 1f) == 105, "850 dp, 1x: 105 dp a side");
        require(PageColumn.sideDp(1280, 1f) == 320, "1280 dp: 320 dp a side");
        require(PageColumn.sideDp(910, 1.4f) == 7, "910 dp at 1.4x: an 896 dp column");
        require(PageColumn.sideDp(640, 1f) == 0 && PageColumn.sideDp(641, 1f) == 0 && PageColumn.sideDp(642, 1f) == 1, "the edge");
        require(PageColumn.maxDp(1f) == 640 && PageColumn.maxDp(1.4f) == 896, "1.4x text: a 1.4 times wider column");
        require(PageColumn.maxDp(.85f) == 640, "smaller text never narrows it");
        require(PageColumn.maxDp(Float.NaN) == 640 && PageColumn.maxDp(9f) == 1280, "odd scales stay sane");
        require(PageColumn.sideDp(0, 1f) == 0 && PageColumn.sideDp(-5, 1f) == 0, "no window, no space");

        // Which pages keep to it: Settings and the list pages do; Home and the pages with panes of their own don't.
        for (String page : new String[] {HubPages.SETTINGS, HubPages.SETTINGS_HOME, HubPages.BATTERY, HubPages.CONTROLS,
                HubPages.LIGHTS, HubPages.SECURITY, HubPages.SERVICE, HubPages.RIDE, HubPages.RIDE_RECORDING, HubPages.SEARCH}) {
            require(PageColumn.capped(page), page + " keeps to the column");
        }
        for (String page : new String[] {HubPages.HOME, HubPages.DATA, HubPages.RIDE_RIDES, HubPages.SERVICE_MAINTENANCE, null}) {
            require(!PageColumn.capped(page), page + " keeps the window");
        }
        System.out.println("PageColumnTest: " + checks + " checks passed");
    }

    private static void require(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
