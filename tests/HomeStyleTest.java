package com.jjricks.volt;

/** HomeStyle: stored values, the one-time seed's decision and the What's new rule (hub layout plan 1.8, PR 6). */
public final class HomeStyleTest {
    private static int checks;

    public static void main(String[] args) {
        stored();
        seed();
        whatsNew();
        keys();
        System.out.println("HomeStyle: " + checks + " checks passed");
    }

    private static void stored() {
        require(HomeStyle.of("clean").equals(HomeStyle.CLEAN), "A stored clean is Clean");
        require(HomeStyle.of("widgets").equals(HomeStyle.WIDGETS), "A stored widgets is Widgets");
        require(HomeStyle.of(null).equals(HomeStyle.WIDGETS), "Absent (an older Volt, a pre-Hub backup) is Widgets");
        require(HomeStyle.of("Clean").equals(HomeStyle.WIDGETS) && HomeStyle.of("").equals(HomeStyle.WIDGETS)
                && HomeStyle.of(Boolean.TRUE).equals(HomeStyle.WIDGETS), "Anything else is Widgets, the safe choice for saved layouts");
        require(HomeStyle.clean("clean") && !HomeStyle.clean(null), "clean() follows of()");
        require(HomeStyle.valid("clean").equals(HomeStyle.CLEAN) && HomeStyle.valid("bogus").equals(HomeStyle.WIDGETS), "A picker writes only the two values");
    }

    private static void seed() {
        long t = 1_700_000_000_000L;
        require(HomeStyle.initial(false, false, false, false, t, t).equals(HomeStyle.CLEAN), "A fresh install starts on Clean");
        require(HomeStyle.origin(false, false, false, false, t, t).equals(HomeStyle.ORIGIN_INSTALL), "and records install");
        boolean[][] signals = {{true, false, false, false}, {false, true, false, false}, {false, false, true, false}, {false, false, false, true}};
        for (boolean[] s : signals) {
            require(HomeStyle.initial(s[0], s[1], s[2], s[3], t, t).equals(HomeStyle.WIDGETS), "Any earlier-owner signal keeps Widgets");
            require(HomeStyle.origin(s[0], s[1], s[2], s[3], t, t).equals(HomeStyle.ORIGIN_UPDATE), "and records update");
        }
        require(HomeStyle.initial(false, false, false, false, t, t + 1).equals(HomeStyle.WIDGETS)
                && HomeStyle.origin(false, false, false, false, t, t + 1).equals(HomeStyle.ORIGIN_UPDATE), "An updated installation keeps Widgets");
        require(HomeStyle.initial(false, false, false, false, 0, 1).equals(HomeStyle.WIDGETS), "Unknown package times (the seed's fallback) keep Widgets");
        // What the app-start seed writes while no style is stored.
        require(HomeStyle.reseed(null, false, false, false, false, t, t) == null, "A clean new install writes nothing: fresh app data stays empty");
        for (boolean[] s : signals) {
            String[] w = HomeStyle.reseed(null, s[0], s[1], s[2], s[3], t, t);
            require(w != null && w[0].equals(HomeStyle.WIDGETS) && w[1].equals(HomeStyle.ORIGIN_UPDATE), "An earlier owner records Widgets and update together");
        }
        String[] updated = HomeStyle.reseed(null, false, false, false, false, t, t + 1);
        require(updated != null && updated[0].equals(HomeStyle.WIDGETS) && updated[1].equals(HomeStyle.ORIGIN_UPDATE), "An updated installation records Widgets and update");
        String[] restored = HomeStyle.reseed(HomeStyle.ORIGIN_INSTALL, true, true, false, true, t, t);
        require(restored != null && restored[0].equals(HomeStyle.CLEAN) && restored[1].equals(HomeStyle.ORIGIN_INSTALL),
                "origin=install, style dropped by a pre-Hub restore, profile present: Clean, origin stays install");
        require(!HomeStyle.showWhatsNew(restored[1], false, false, false, false, true, true), "and that restore shows no What's new");
        String[] kept = HomeStyle.reseed(HomeStyle.ORIGIN_UPDATE, false, false, false, false, t, t);
        require(kept != null && kept[0].equals(HomeStyle.WIDGETS) && kept[1].equals(HomeStyle.ORIGIN_UPDATE), "A stored update origin is never re-decided");
        String[] odd = HomeStyle.reseed("bogus", false, false, false, false, t, t);
        require(odd == null, "An unknown stored origin counts as none");
        require(HomeStyle.pickedOrigin(null).equals(HomeStyle.ORIGIN_INSTALL), "A pick on an installation the seed left alone records install");
        require(HomeStyle.pickedOrigin(HomeStyle.ORIGIN_UPDATE).equals(HomeStyle.ORIGIN_UPDATE)
                && HomeStyle.pickedOrigin(HomeStyle.ORIGIN_INSTALL).equals(HomeStyle.ORIGIN_INSTALL), "A pick never rewrites a stored origin");
        require(HomeStyle.dashboardKey("dashboard_v1_abc") && HomeStyle.dashboardKey("dashboard_v1_unconfigured"), "A saved dashboard is a signal");
        require(!HomeStyle.dashboardKey("dashboard_v1_") && !HomeStyle.dashboardKey("dashboard_slot_v1_abc_0")
                && !HomeStyle.dashboardKey("dashboard_columns") && !HomeStyle.dashboardKey(null), "Other dashboard keys are not");
    }

    private static void whatsNew() {
        String u = HomeStyle.ORIGIN_UPDATE, i = HomeStyle.ORIGIN_INSTALL;
        require(HomeStyle.showWhatsNew(u, false, false, false, false, true, true), "An update that never chose sees it once");
        require(!HomeStyle.showWhatsNew(i, false, false, false, false, true, true), "A fresh install never sees it");
        require(!HomeStyle.showWhatsNew(null, false, false, false, false, true, true), "No origin (seed never ran) never sees it");
        require(!HomeStyle.showWhatsNew(u, true, false, false, false, true, true), "An owner who chose (or restored a choice) never sees it");
        require(!HomeStyle.showWhatsNew(u, false, true, false, false, true, true), "Shown once means never again");
        require(!HomeStyle.showWhatsNew(u, false, false, true, false, true, true), "Not during setup");
        require(!HomeStyle.showWhatsNew(u, false, false, false, true, true, true), "Not in riding mode");
        require(!HomeStyle.showWhatsNew(u, false, false, false, false, false, true), "Only on Home");
        require(!HomeStyle.showWhatsNew(u, false, false, false, false, true, false), "Only while resumed");
    }

    private static void keys() {
        String[] keys = {HomeStyle.KEY, HomeStyle.CHOSEN_KEY, HomeStyle.ORIGIN_KEY, HomeStyle.WHATS_NEW_SHOWN_KEY};
        for (String key : keys) require(BackupRegistry.rule("app_settings", key) != null, key + " is registered");
        require(BackupRegistry.PREFERENCES.equals(BackupRegistry.category("app_settings", HomeStyle.KEY))
                && BackupRegistry.PREFERENCES.equals(BackupRegistry.category("app_settings", HomeStyle.CHOSEN_KEY)), "The style and the explicit choice travel together");
        require(BackupRegistry.rule("app_settings", HomeStyle.ORIGIN_KEY).excluded()
                && BackupRegistry.rule("app_settings", HomeStyle.WHATS_NEW_SHOWN_KEY).excluded(), "This installation's history stays on this phone");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
