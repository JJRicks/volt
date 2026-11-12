package com.jjricks.volt;

/** HubRoutes: every saved Quick access id still leads somewhere, and only to Hub pages (hub layout plan, PR 4). */
public final class HubRoutesTest {
    private static int checks;

    public static void main(String[] args) {
        catalog();
        sheets();
        features();
        moreSections();
        System.out.println("HubRoutes: " + checks + " checks passed");
    }

    /** Every catalog id opens a known page, keeps its quick-control sheet or keeps an action; nothing routes outside HubPages.ALL. */
    private static void catalog() {
        for (QuickAccessActions.Entry entry : QuickAccessActions.catalog()) {
            String page = HubRoutes.pageFor(entry.id), sheet = HubRoutes.sheetFor(entry.id);
            boolean action = HubRoutes.ACTIONS.contains(entry.id) || entry.id.startsWith(QuickAccessActions.FEATURE_PREFIX);
            int ways = (page != null ? 1 : 0) + (sheet != null ? 1 : 0) + (action ? 1 : 0);
            require(ways == 1, "Opens a page, a sheet or runs an action, exactly one: " + entry.id);
            if (page != null) require(HubPages.ALL.contains(page) && page.equals(HubPages.canonical(page)), "A Hub page: " + entry.id + " " + page);
        }
        for (String id : HubRoutes.pages().keySet()) require(QuickAccessActions.find(id) != null, "A route for a catalog id: " + id);
        for (String id : HubRoutes.SHEETS) require(QuickAccessActions.find(id) != null, "A sheet for a catalog id: " + id);
        for (String id : HubRoutes.ACTIONS) require(QuickAccessActions.find(id) != null, "An action for a catalog id: " + id);
        for (String id : QuickAccessActions.defaultIds())
            require(HubRoutes.pageFor(id) != null || HubRoutes.sheetFor(id) != null, "The default shortcuts open a page or a sheet: " + id);
        require(HubRoutes.pageFor(null) == null && HubRoutes.pageFor("bogus") == null && HubRoutes.pageFor("more:Security") == null,
                "Unknown ids open nothing");
        require(HubRoutes.sheetFor(null) == null && HubRoutes.sheetFor("bogus") == null && HubRoutes.sheetFor("section:Security") == null
                && HubRoutes.sheetFor("section:Profile") == null, "Unknown ids and other sections open no sheet");
    }

    /**
     * The owner's answer 3 (#383): shortcuts to whole settings pages open the page that holds the same rows; the
     * quick-control groups (maximum speed and energy recovery are in Ride settings) keep their sheet, on its section.
     */
    private static void sheets() {
        String[][] expected = {
                {"controls", HubPages.CONTROLS}, {"stats", HubPages.DATA}, {"rides", HubPages.RIDE},
                {"section:Security", HubPages.SECURITY},
                {"more:App", HubPages.SETTINGS}, {"more:Scooters", HubPages.SETTINGS_SCOOTERS}, {"more:Lab", HubPages.SERVICE_EXPERIMENTAL},
                {"more:Data", HubPages.DATA_STORAGE}, {"more:About", HubPages.SETTINGS_ABOUT}};
        for (String[] pair : expected) require(pair[1].equals(HubRoutes.pageFor(pair[0])), "Re-targeted: " + pair[0]);
        require(HubRoutes.pages().size() == expected.length, "No other id opens a page");
        String[][] kept = {
                {"lights", "Lights"}, {"section:Ride", "Ride"}, {"section:Battery", "Battery"},
                {"section:Line lights", "Line lights"}, {"section:Star lights", "Star lights"},
                {"section:Dashboard & sound", "Dashboard & sound"}};
        for (String[] pair : kept) {
            require(pair[1].equals(HubRoutes.sheetFor(pair[0])), "Keeps its quick-control sheet: " + pair[0]);
            require(HubRoutes.pageFor(pair[0]) == null, "A quick-control sheet is not a page: " + pair[0]);
        }
        require(HubRoutes.SHEETS.size() == kept.length, "No other id keeps a sheet");
        // A "more:" shortcut opens the page of the More section it named.
        for (String id : HubRoutes.pages().keySet())
            if (id.startsWith("more:")) require(HubPages.fromMoreSection(id.substring(5)).equals(HubRoutes.pageFor(id)), "Same page as its section: " + id);
    }

    private static void features() {
        // MainActivity.quickAction routes every control shortcut through forFeature: a Lab try opens Experimental
        // controls (with its inspect), sensitive controls and proximity unlock open Security, the rest stay direct.
        int security = 0;
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) {
            require(HubPages.SERVICE_EXPERIMENTAL.equals(HubRoutes.forFeature(f, true)), "A Lab try opens Experimental controls: " + f.key);
            boolean onSecurity = f.sensitive || f.key.equals("proximity_key");
            String page = HubRoutes.forFeature(f, false);
            if (onSecurity) {
                security++;
                require(HubPages.SECURITY.equals(page), "A sensitive or proximity control opens Security: " + f.key);
                require(QuickAccessActions.find(QuickAccessActions.FEATURE_PREFIX + f.key) != null, "Security control has a shortcut id: " + f.key);
            } else {
                require(page == null, "Other controls keep their toggle or editor: " + f.key);
            }
        }
        require(security > 0 && HubPages.SECURITY.equals(HubRoutes.forFeature(ScooterCommands.find("proximity_key"), false)),
                "Proximity unlock and the PIN controls route to Security");
        require(HubRoutes.forFeature(null, false) == null, "No feature, no page");
    }

    /** Until PR 5 the Settings and Data pages open in More's sections; every other page is built by MainActivity. */
    private static void moreSections() {
        for (String page : HubPages.ALL) {
            String section = HubRoutes.moreSectionOf(page);
            if (section == null) continue;
            require(HubPages.subjectOf(page).equals(HubPages.SETTINGS) || page.equals(HubPages.DATA_STORAGE), "Only Settings and Data storage: " + page);
            String back = HubPages.fromMoreSection(section);
            require(back.equals(page), "The section's page is the page: " + page + " " + section);
        }
        for (String page : HubRoutes.pages().values())
            require(HubRoutes.moreSectionOf(page) != null || !HubPages.subjectOf(page).equals(HubPages.SETTINGS),
                    "A Settings route has a More section until PR 5: " + page);
        require(HubRoutes.moreSectionOf(HubPages.SECURITY) == null && HubRoutes.moreSectionOf(HubPages.SERVICE_EXPERIMENTAL) == null
                && HubRoutes.moreSectionOf(HubPages.BATTERY_HISTORY) == null, "Subject pages are built as pages");
        require(HubRoutes.moreSectionOf(null) == null && HubRoutes.moreSectionOf(HubPages.SETTINGS_HOME) == null, "No section for the rest");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
