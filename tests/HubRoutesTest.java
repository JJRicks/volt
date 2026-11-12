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

    /** Every catalog id opens a known page or keeps an action; nothing routes outside HubPages.ALL. */
    private static void catalog() {
        for (QuickAccessActions.Entry entry : QuickAccessActions.catalog()) {
            String page = HubRoutes.pageFor(entry.id);
            boolean action = HubRoutes.ACTIONS.contains(entry.id) || entry.id.startsWith(QuickAccessActions.FEATURE_PREFIX);
            require(page != null ^ action, "Opens a page or runs an action, never both or neither: " + entry.id);
            if (page != null) require(HubPages.ALL.contains(page) && page.equals(HubPages.canonical(page)), "A Hub page: " + entry.id + " " + page);
        }
        for (String id : HubRoutes.pages().keySet()) require(QuickAccessActions.find(id) != null, "A route for a catalog id: " + id);
        for (String id : HubRoutes.ACTIONS) require(QuickAccessActions.find(id) != null, "An action for a catalog id: " + id);
        for (String id : QuickAccessActions.defaultIds()) require(HubRoutes.pageFor(id) != null, "The default shortcuts open pages: " + id);
        require(HubRoutes.pageFor(null) == null && HubRoutes.pageFor("bogus") == null && HubRoutes.pageFor("more:Security") == null,
                "Unknown ids open nothing");
    }

    /** The sheets each id opened before the Hub, and the page that holds the same rows now. */
    private static void sheets() {
        String[][] expected = {
                {"lights", HubPages.LIGHTS}, {"controls", HubPages.CONTROLS}, {"stats", HubPages.DATA}, {"rides", HubPages.RIDE},
                {"section:Ride", HubPages.CONTROLS}, {"section:Battery", HubPages.BATTERY},
                {"section:Line lights", HubPages.LIGHTS_LINE}, {"section:Star lights", HubPages.LIGHTS_STAR},
                {"section:Dashboard & sound", HubPages.LIGHTS}, {"section:Security", HubPages.SECURITY},
                {"more:App", HubPages.SETTINGS}, {"more:Scooters", HubPages.SETTINGS_SCOOTERS}, {"more:Lab", HubPages.SERVICE_EXPERIMENTAL},
                {"more:Data", HubPages.DATA_STORAGE}, {"more:About", HubPages.SETTINGS_ABOUT}};
        for (String[] pair : expected) require(pair[1].equals(HubRoutes.pageFor(pair[0])), "Re-targeted: " + pair[0]);
        // A "more:" shortcut opens the page of the More section it named.
        for (String id : HubRoutes.pages().keySet())
            if (id.startsWith("more:")) require(HubPages.fromMoreSection(id.substring(5)).equals(HubRoutes.pageFor(id)), "Same page as its section: " + id);
    }

    private static void features() {
        require(HubPages.SERVICE_EXPERIMENTAL.equals(HubRoutes.forFeature(true, false))
                && HubPages.SERVICE_EXPERIMENTAL.equals(HubRoutes.forFeature(true, true)), "A Lab try opens Experimental controls");
        require(HubPages.SECURITY.equals(HubRoutes.forFeature(false, true)), "PIN and proximity open Security");
        require(HubRoutes.forFeature(false, false) == null, "Other controls keep their toggle or editor");
        // The controls that route to Security: sensitive ones and proximity unlock (MainActivity.quickAction's rule).
        for (ScooterCommands.Feature f : ScooterCommands.catalog())
            if (f.sensitive || f.key.equals("proximity_key"))
                require(QuickAccessActions.find(QuickAccessActions.FEATURE_PREFIX + f.key) != null, "Security control has a shortcut id: " + f.key);
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
