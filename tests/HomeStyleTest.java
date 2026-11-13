package com.jjricks.volt;

/** HomeStyle: stored values, the one-time seed's decision and the What's new rule (hub layout plan 1.8, PR 6). */
public final class HomeStyleTest {
    private static int checks;

    public static void main(String[] args) {
        stored();
        seed();
        whatsNew();
        setup();
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
        // A first setup under way made its own profile: no sign of an earlier owner, so nothing is written, even
        // across a restart, and setup's end decides (its last step preselects Clean; a skip records Clean and install).
        require(HomeStyle.reseed(null, false, true, true, false, false, t, t) == null, "A profile made by a first setup under way writes nothing");
        require(HomeStyle.setupChoice(null, false, null).equals(HomeStyle.CLEAN), "so setup's last step still preselects Clean after a restart");
        String[] skippedMidway = HomeStyle.afterSkippedSetup(null, false);
        require(HomeStyle.CLEAN.equals(skippedMidway[0]) && HomeStyle.ORIGIN_INSTALL.equals(skippedMidway[1]),
                "and skipping it then gives Clean and origin install");
        String[] setupKept = HomeStyle.reseed(HomeStyle.ORIGIN_UPDATE, false, true, true, false, false, t, t);
        require(setupKept != null && setupKept[0].equals(HomeStyle.WIDGETS), "A stored origin still gives its style during setup");
        String[] notSetup = HomeStyle.reseed(null, false, false, true, false, false, t, t);
        require(notSetup != null && notSetup[1].equals(HomeStyle.ORIGIN_UPDATE), "Outside a first setup a profile still marks an earlier owner");
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
        // Owner's answer on #403: the background-recording notice, only for updating owners who record automatically.
        require(HomeStyle.whatsNewBackgroundNotice(u, true), "An updating owner with Record rides automatically on sees the notice");
        require(!HomeStyle.whatsNewBackgroundNotice(u, false), "One who never used it does not");
        require(!HomeStyle.whatsNewBackgroundNotice(i, true), "A fresh install never does");
        require(!HomeStyle.whatsNewBackgroundNotice(null, true), "nor one the seed never saw");
        // PR 9's sheet: an updating owner who chose a style first still gets it once, without the offer.
        require(HomeStyle.showWhatsNew(u, false, false, false, true, true) && !HomeStyle.offerSwitch(true, HomeStyle.CLEAN),
                "An updating owner who already chose Clean sees the sheet, without the offer");
        require(HomeStyle.whatsNewHasContent(true, false, false), "The offer alone is worth a sheet");
        require(HomeStyle.whatsNewHasContent(false, true, false), "So is the background notice alone (a chosen owner, no notes)");
        require(HomeStyle.whatsNewHasContent(false, false, true), "So are this version's notes alone");
        require(!HomeStyle.whatsNewHasContent(false, false, false), "Nothing to show: no empty sheet");
    }

    /** Setup's last step (PR 9): what it preselects, and what a skipped setup leaves stored. */
    private static void setup() {
        require(HomeStyle.setupChoice(null, false, null).equals(HomeStyle.CLEAN), "A new install's setup preselects Clean");
        require(HomeStyle.setupChoice(null, true, "widgets").equals(HomeStyle.WIDGETS), "A rerun preselects the current style");
        require(HomeStyle.setupChoice(null, true, "clean").equals(HomeStyle.CLEAN), "including Clean");
        require(HomeStyle.setupChoice(null, true, null).equals(HomeStyle.WIDGETS), "A rerun with no stored style shows what Home shows: Widgets");
        require(HomeStyle.setupChoice(null, false, "widgets").equals(HomeStyle.WIDGETS), "An earlier owner's stored style is kept");
        require(HomeStyle.setupChoice("widgets", false, null).equals(HomeStyle.WIDGETS), "A pick on the step wins, through rotation");
        require(HomeStyle.setupChoice("clean", true, "widgets").equals(HomeStyle.CLEAN), "also on a rerun");
        require(HomeStyle.setupChoice("bogus", false, null).equals(HomeStyle.CLEAN), "An unknown pick is ignored");

        String[] fresh = HomeStyle.afterSkippedSetup(null, false);
        require(HomeStyle.CLEAN.equals(fresh[0]) && HomeStyle.ORIGIN_INSTALL.equals(fresh[1]), "A skipped new install: Clean and origin install");
        require(!HomeStyle.showWhatsNew(fresh[1], false, false, false, true, true), "so it never sees What's new");
        String[] update = HomeStyle.afterSkippedSetup(HomeStyle.ORIGIN_UPDATE, true);
        require(update[0] == null && update[1] == null, "An earlier owner skipping a rerun changes nothing");
        String[] kept = HomeStyle.afterSkippedSetup(HomeStyle.ORIGIN_INSTALL, true);
        require(kept[0] == null && kept[1] == null, "A stored style is never changed by skipping");
        String[] restored = HomeStyle.afterSkippedSetup(HomeStyle.ORIGIN_UPDATE, false);
        require(restored[0] == null && restored[1] == null, "An update whose style a restore dropped is left to the seed (Widgets)");
        require(HomeStyle.reseed(fresh[1], false, false, false, false, false, 1, 1)[0].equals(fresh[0]), "and the next start's seed agrees with it");

        // Part 6's second review, note 2: App preferences restored from the welcome screen during first-run setup bring
        // an earlier owner's style (seeded Widgets) with no origin; setup's end records update, not install.
        String[] skippedAfterRestore = HomeStyle.afterSkippedSetup(null, true);
        require(skippedAfterRestore[0] == null && HomeStyle.ORIGIN_UPDATE.equals(skippedAfterRestore[1]),
                "Skipping setup after a restore brought a style: the style stays and the origin is update");
        require(HomeStyle.showWhatsNew(skippedAfterRestore[1], false, false, false, true, true), "so that owner still sees What's new once");
        require(HomeStyle.pickedOrigin(null, true).equals(HomeStyle.ORIGIN_UPDATE), "Finishing setup over a restored style records update");
        require(HomeStyle.pickedOrigin(null, false).equals(HomeStyle.ORIGIN_INSTALL), "and over no style, install");
        require(HomeStyle.pickedOrigin(HomeStyle.ORIGIN_INSTALL, true).equals(HomeStyle.ORIGIN_INSTALL)
                && HomeStyle.pickedOrigin(HomeStyle.ORIGIN_UPDATE, false).equals(HomeStyle.ORIGIN_UPDATE), "A stored origin is never rewritten, style or not");
        require(HomeStyle.setupChoice(null, false, "widgets").equals(HomeStyle.WIDGETS), "and setup's last step preselects the restored Widgets");
        // Note 1: a pre-Hub restore lands on Widgets unless this phone's owner chose the style.
        require(HomeStyle.preHubRestoreGivesWidgets(false, true, HomeStyle.ORIGIN_INSTALL), "Clean stored unchosen (setup skipped): Widgets");
        require(HomeStyle.preHubRestoreGivesWidgets(false, false, HomeStyle.ORIGIN_INSTALL), "No style but an install origin, which the seed would turn Clean: Widgets");
        require(HomeStyle.preHubRestoreGivesWidgets(false, true, null), "A seeded or restored style with no origin: Widgets");
        require(!HomeStyle.preHubRestoreGivesWidgets(true, true, HomeStyle.ORIGIN_INSTALL), "A chosen style is kept");
        require(!HomeStyle.preHubRestoreGivesWidgets(false, false, null) && !HomeStyle.preHubRestoreGivesWidgets(false, false, HomeStyle.ORIGIN_UPDATE),
                "Nothing stored, or an update origin (the seed gives Widgets): left as it is");
        // Part 9's early council review, note 1: a pre-Hub backup's App preferences restored on a phone with neither a style
        // nor an origin (the welcome screen, setup under way) store an unchosen Widgets, so setup's end records update.
        require(HomeStyle.preHubRestoreStoresWidgets(false, null) && HomeStyle.preHubRestoreStoresWidgets(false, "bogus"),
                "A pre-Hub restore on a phone with no style and no origin stores Widgets");
        require(!HomeStyle.preHubRestoreStoresWidgets(true, null) && !HomeStyle.preHubRestoreStoresWidgets(false, HomeStyle.ORIGIN_INSTALL)
                && !HomeStyle.preHubRestoreStoresWidgets(false, HomeStyle.ORIGIN_UPDATE), "but never over a stored style or origin");
        require(HomeStyle.pickedOrigin(null, true).equals(HomeStyle.ORIGIN_UPDATE), "Finishing setup afterwards records update");
        String[] skippedAfterPreHub = HomeStyle.afterSkippedSetup(null, true);
        require(skippedAfterPreHub[0] == null && HomeStyle.ORIGIN_UPDATE.equals(skippedAfterPreHub[1]), "and so does skipping it, keeping Widgets");
        require(HomeStyle.setupChoice(null, false, HomeStyle.WIDGETS).equals(HomeStyle.WIDGETS), "Setup's last step preselects the earlier owner's Widgets");
        require(HomeStyle.showWhatsNew(HomeStyle.ORIGIN_UPDATE, false, false, false, true, true) && HomeStyle.whatsNewBackgroundNotice(HomeStyle.ORIGIN_UPDATE, true),
                "so that owner sees What's new once, with the background-recording notice when Record rides automatically is on");
        // Note 4: either offer button closes the sheet, so the background-recording notice comes before the offer.
        require(HomeStyle.whatsNewSections(true, true, true).equals(java.util.Arrays.asList(HomeStyle.SECTION_NOTICE, HomeStyle.SECTION_OFFER, HomeStyle.SECTION_NOTES)),
                "The notice, then the offer, then the notes");
        require(HomeStyle.whatsNewSections(true, false, true).equals(java.util.Arrays.asList(HomeStyle.SECTION_OFFER, HomeStyle.SECTION_NOTES)),
                "Without the notice, the offer leads");
        require(HomeStyle.whatsNewSections(false, true, false).equals(java.util.Collections.singletonList(HomeStyle.SECTION_NOTICE))
                && HomeStyle.whatsNewSections(false, false, false).isEmpty(), "Only what the sheet holds");
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
