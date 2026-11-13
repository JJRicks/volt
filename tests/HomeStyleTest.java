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
        // Arguments: setupFinished, setupPending, hasProfile, hasLastPage, hasDashboard, firstInstallTime, lastUpdateTime.
        require(HomeStyle.initial(false, true, false, false, false, t, t).equals(HomeStyle.CLEAN), "A fresh install starts on Clean");
        require(HomeStyle.origin(false, true, false, false, false, t, t).equals(HomeStyle.ORIGIN_INSTALL), "and records install");
        require(HomeStyle.initial(true, false, false, false, false, t, t).equals(HomeStyle.WIDGETS)
                && HomeStyle.origin(true, false, false, false, false, t, t).equals(HomeStyle.ORIGIN_UPDATE), "Setup finished (or re-run after finishing) keeps Widgets");
        boolean[][] signals = {{true, false, false}, {false, true, false}, {false, false, true}};
        for (boolean[] s : signals) {
            require(HomeStyle.initial(false, false, s[0], s[1], s[2], t, t).equals(HomeStyle.WIDGETS), "Once setup is not under way, any earlier-owner signal keeps Widgets");
            require(HomeStyle.origin(false, false, s[0], s[1], s[2], t, t).equals(HomeStyle.ORIGIN_UPDATE), "and records update");
            require(HomeStyle.initial(false, true, s[0], s[1], s[2], t, t).equals(HomeStyle.CLEAN)
                    && HomeStyle.origin(false, true, s[0], s[1], s[2], t, t).equals(HomeStyle.ORIGIN_INSTALL),
                    "A fresh install's own setup writes (profile, last page, dashboard) are not an earlier owner while setup is under way");
            require(HomeStyle.initial(false, true, s[0], s[1], s[2], t, t + 1).equals(HomeStyle.WIDGETS),
                    "An updated installation counts whatever its setup state");
        }
        require(HomeStyle.initial(false, false, false, false, false, t, t + 1).equals(HomeStyle.WIDGETS)
                && HomeStyle.origin(false, false, false, false, false, t, t + 1).equals(HomeStyle.ORIGIN_UPDATE), "An updated installation keeps Widgets");
        require(HomeStyle.initial(false, true, false, false, false, 0, 1).equals(HomeStyle.WIDGETS), "Unknown package times (the seed's fallback) keep Widgets, even mid-setup");
        // What the app-start seed writes while no style is stored.
        require(HomeStyle.reseed(null, false, true, false, false, false, t, t) == null, "A clean new install writes nothing: fresh app data stays empty");
        require(HomeStyle.reseed(null, false, true, true, true, true, t, t) == null,
                "Setup interrupted after storing the profile (and a page, a dashboard) writes nothing: the decision waits for setup to end");
        String[] finished = HomeStyle.reseed(null, true, false, true, false, false, t, t);
        require(finished != null && finished[0].equals(HomeStyle.WIDGETS) && finished[1].equals(HomeStyle.ORIGIN_UPDATE),
                "Setup finished or skipped before the Home style choice exists: an existing owner of the widgets Home (by design)");
        for (boolean[] s : signals) {
            String[] w = HomeStyle.reseed(null, false, false, s[0], s[1], s[2], t, t);
            require(w != null && w[0].equals(HomeStyle.WIDGETS) && w[1].equals(HomeStyle.ORIGIN_UPDATE), "An earlier owner records Widgets and update together");
            String[] u = HomeStyle.reseed(null, false, true, s[0], s[1], s[2], t, t + 1);
            require(u != null && u[0].equals(HomeStyle.WIDGETS) && u[1].equals(HomeStyle.ORIGIN_UPDATE), "An update whose setup is still open is an update");
        }
        String[] updated = HomeStyle.reseed(null, false, true, false, false, false, t, t + 1);
        require(updated != null && updated[0].equals(HomeStyle.WIDGETS) && updated[1].equals(HomeStyle.ORIGIN_UPDATE), "An updated installation records Widgets and update");
        String[] unknown = HomeStyle.reseed(null, false, true, false, false, false, 0, 1);
        require(unknown != null && unknown[1].equals(HomeStyle.ORIGIN_UPDATE), "Package times unreadable: update");
        String[] restored = HomeStyle.reseed(HomeStyle.ORIGIN_INSTALL, true, false, true, false, true, t, t);
        require(restored != null && restored[0].equals(HomeStyle.CLEAN) && restored[1].equals(HomeStyle.ORIGIN_INSTALL),
                "origin=install, style gone, profile present: Clean, origin stays install");
        require(!HomeStyle.showWhatsNew(restored[1], false, false, false, true, true), "and that installation shows no What's new");
        String[] kept = HomeStyle.reseed(HomeStyle.ORIGIN_UPDATE, false, true, false, false, false, t, t);
        require(kept != null && kept[0].equals(HomeStyle.WIDGETS) && kept[1].equals(HomeStyle.ORIGIN_UPDATE), "A stored update origin is never re-decided");
        String[] odd = HomeStyle.reseed("bogus", false, true, false, false, false, t, t);
        require(odd == null, "An unknown stored origin counts as none");
        // A style stored without an origin (a restore never carries the origin; an earlier build of this series).
        require(HomeStyle.ORIGIN_UPDATE.equals(HomeStyle.backfillOrigin(null, true, false, true, false, true, t, t)),
                "A restored style on a phone whose setup the restore finished gets origin update, so What's new can show");
        for (boolean[] s : signals)
            require(HomeStyle.ORIGIN_UPDATE.equals(HomeStyle.backfillOrigin(null, false, false, s[0], s[1], s[2], t, t)), "The same signals as the seed");
        require(HomeStyle.ORIGIN_UPDATE.equals(HomeStyle.backfillOrigin(null, false, true, false, false, false, t, t + 1)), "An updated installation: update");
        require(HomeStyle.ORIGIN_UPDATE.equals(HomeStyle.backfillOrigin(null, false, true, false, false, false, 0, 1)), "Package times unreadable: update");
        require(HomeStyle.backfillOrigin(null, false, true, true, true, true, t, t) == null,
                "A style restored mid-setup on a fresh install: nothing yet (never install from the seed)");
        require(HomeStyle.backfillOrigin(null, false, true, false, false, false, t, t) == null, "No signal: nothing");
        require(HomeStyle.backfillOrigin(HomeStyle.ORIGIN_INSTALL, true, false, true, true, true, 0, 1) == null
                && HomeStyle.backfillOrigin(HomeStyle.ORIGIN_UPDATE, false, true, false, false, false, t, t) == null, "A stored origin is never rewritten");
        require(HomeStyle.ORIGIN_UPDATE.equals(HomeStyle.backfillOrigin("bogus", true, false, false, false, false, t, t)), "An unknown stored origin counts as none here too");
        require(HomeStyle.pickedOrigin(null).equals(HomeStyle.ORIGIN_INSTALL), "A pick on an installation the seed left alone records install");
        require(HomeStyle.pickedOrigin(HomeStyle.ORIGIN_UPDATE).equals(HomeStyle.ORIGIN_UPDATE)
                && HomeStyle.pickedOrigin(HomeStyle.ORIGIN_INSTALL).equals(HomeStyle.ORIGIN_INSTALL), "A pick never rewrites a stored origin");
        require(HomeStyle.dashboardKey("dashboard_v1_abc") && HomeStyle.dashboardKey("dashboard_v1_unconfigured"), "A saved dashboard is a signal");
        require(!HomeStyle.dashboardKey("dashboard_v1_") && !HomeStyle.dashboardKey("dashboard_slot_v1_abc_0")
                && !HomeStyle.dashboardKey("dashboard_columns") && !HomeStyle.dashboardKey(null), "Other dashboard keys are not");
    }

    private static void whatsNew() {
        String u = HomeStyle.ORIGIN_UPDATE, i = HomeStyle.ORIGIN_INSTALL;
        require(HomeStyle.showWhatsNew(u, false, false, false, true, true), "An update sees it once");
        require(!HomeStyle.showWhatsNew(i, false, false, false, true, true), "A fresh install never sees it");
        require(!HomeStyle.showWhatsNew(null, false, false, false, true, true), "No origin (seed never ran) never sees it");
        require(!HomeStyle.showWhatsNew(u, true, false, false, true, true), "Shown once means never again");
        require(!HomeStyle.showWhatsNew(u, false, true, false, true, true), "Not during setup");
        require(!HomeStyle.showWhatsNew(u, false, false, true, true, true), "Not in riding mode");
        require(!HomeStyle.showWhatsNew(u, false, false, false, false, true), "Only on Home");
        require(!HomeStyle.showWhatsNew(u, false, false, false, true, false), "Only while resumed");
        // Choosing a style first (settings, or a restore that brought a choice) does not skip the sheet: it carries the
        // changelog and one-time notices. The choice only hides the offer to switch.
        require(HomeStyle.offerSwitch(false, null) && HomeStyle.offerSwitch(false, HomeStyle.WIDGETS) && HomeStyle.offerSwitch(false, "bogus"),
                "An owner who never chose, on Widgets, is offered Clean");
        require(!HomeStyle.offerSwitch(true, HomeStyle.WIDGETS) && !HomeStyle.offerSwitch(true, null), "An owner who chose is not offered a switch");
        require(!HomeStyle.offerSwitch(false, HomeStyle.CLEAN) && !HomeStyle.offerSwitch(true, HomeStyle.CLEAN), "Already on Clean: nothing to switch to");
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
