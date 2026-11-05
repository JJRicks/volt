package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * F4's own suite (the B18, B17 and B11 follow-ups): what the screens show now, against the base English
 * FollowupsEnglishTest pinned before the change (G5), in English and under a key-echo text source.
 *
 * MoreScreen, OnboardingScreen and BackupActivity need Android, so this reads their source for the
 * calls (as MoreTextTest and BackupRelabelTest do) and runs the pure parts:
 *  - the submenu titles: SECTION_TITLE_IDS render SECTION_TITLES, in order, and follow the source;
 *  - the PIN, pairing and proximity toasts show BleController.displayText(): for every message those
 *    paths leave it is message()'s English in English, the shown language otherwise, and null or empty
 *    exactly when message() is (so the toast shows in the same cases; MainActivity.toast skips both);
 *  - the default scooter name goes through Profile.displayName() on the My scooters card, the menu
 *    index and the setup sentences, while the rename field keeps the stored name;
 *  - BackupSelectAll: Select all mirrors the enabled boxes exactly as base did, and keeps what it shows
 *    while createBackup has frozen the list (B11 review item 1);
 *  - the Restore page's layout destination is spoken with backup_file_layout, not the engine's English.
 */
public final class FollowupsTest {
    private static int checks;

    /** Answers every id with its key between marks, and says the text is Spanish. */
    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("en", "IN"), new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                T.install(null);
                titles(false);
                toasts(false);
                names(false);
                layout(false);
            }
            Locale.setDefault(Locale.US);
            T.install(ECHO);
            titles(true);
            toasts(true);
            names(true);
            layout(true);
            T.install(null);
            selectAll();
            sources();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("FollowupsTest: " + checks + " checks passed (submenu titles, PIN and pairing toasts, default "
                + "scooter names, Select all while frozen, the layout destination's spoken name, and the key-echo source)");
    }

    // ---- Submenu titles ---------------------------------------------------------------------

    private static void titles(boolean echo) throws Exception {
        String more = FollowupsEnglishTest.read("src/com/jjricks/volt/MoreScreen.java");
        List<String> english = FollowupsEnglishTest.items(more, "String[] SECTION_TITLES = {", "\"([^\"]*)\"");
        List<String> ids = FollowupsEnglishTest.items(more, "int[] SECTION_TITLE_IDS = {", "T\\.([A-Z0-9_]+)");
        check(english.equals(Arrays.asList(FollowupsEnglishTest.Base.SECTION_TITLES)), "SECTION_TITLES " + english);
        check(ids.size() == english.size(), "SECTION_TITLE_IDS " + ids + " beside SECTION_TITLES " + english);
        for (int i = 0; i < ids.size(); i++) {
            int id = T.class.getField(ids.get(i)).getInt(null);
            equal(echo ? "⟦" + T.key(id) + "⟧" : english.get(i), T.render(id), "submenu title " + i);
        }
        check(ids.get(2).equals("MORE_SECTION_SECURITY_TITLE"), "PIN & proximity has its own title key");
        equal("PIN & proximity", EnglishText.INSTANCE.string(T.MORE_SECTION_SECURITY_TITLE), "the short title's English");
    }

    // ---- PIN, pairing and proximity toasts ----------------------------------------------------

    /** displayText() as BleController has it: message==null?null:message.toString(). */
    private static String displayText(CharSequence message) { return message == null ? null : message.toString(); }

    private static void toasts(boolean echo) {
        // Every notice enablePin, bond, set (proximity) and setPin can leave, and the refusals refused() gives them.
        List<CharSequence> messages = new ArrayList<>(Arrays.<CharSequence>asList(
                M.blePairReadPin(), M.blePairNeedsPin(), M.blePairAlready(), M.blePairApprove(), M.blePairNotStarted(),
                M.blePairUnavailable(), M.blePinProximityFirst(), M.blePinReadFirst(), M.bleProximityReadPin(),
                M.bleConnectSimulatedFirst(), M.bleAlreadySet("Proximity unlock"), M.bleApplying("handlebar pin"),
                M.bleStatusNotConnected(), M.bleWaitCurrentChange(), M.bleWaitFreshConnection(), M.bleResolveWarning(5),
                M.bleStopScooterFirst(), M.blePinOwnerOnly(), M.bleNeedsBond(), M.bleNeedsPin(), M.bleNotAvailableNow(),
                M.bleOutsideDomain(), M.blePinConnectOwner()));
        // simulationUnavailable's English sentence, an exception's own message and quiet()'s "" stay plain text.
        messages.add("Handlebar PIN changes is unavailable in simulation. No scooter command was sent.");
        messages.add("PIN must be 4 digits");
        messages.add("");
        messages.add(null);
        for (CharSequence message : messages) {
            CharSequence shown = ConnectionText.notice(null, message);
            String english = ConnectionText.english(shown), toast = displayText(shown);
            check((english == null) == (toast == null), "null exactly when message() is: " + english);
            if (english == null) continue;
            check(english.isEmpty() == toast.isEmpty(), "empty exactly when message() is: " + english);
            if (!echo || !(shown instanceof Msg)) equal(english, toast, "the toast shows message()'s English");
            else {
                check(toast.startsWith("⟦ble_") && !toast.equals(english), "the toast follows the shown language: " + toast);
                check(english.equals(((Msg) shown).english()), "message() keeps the English: " + english);
            }
        }
    }

    // ---- Default scooter names ----------------------------------------------------------------

    private static void names(boolean echo) {
        String name = T.ownerProfileDefaultName();
        if (echo) check(name.equals("⟦owner_profile_default_name⟧"), "the default name follows the source: " + name);
        else equal(FollowupsEnglishTest.Base.DEFAULT_NAME, name, "the default name in English");
        if (!echo) {
            equal("Connecting to Scooter", T.onboardingConnectConnecting(name), "connecting sentence");
            equal("Scooter is ready.", T.onboardingDoneReady(name), "done sentence");
        }
    }

    // ---- The layout destination's spoken name -------------------------------------------------

    private static void layout(boolean echo) {
        Msg spoken = M.backupDestinationForChoice(M.backupFileLayout(), M.backupDontRestore());
        if (echo) check(!spoken.toString().contains("Layout file"), "no English layout name: " + spoken);
        else equal("Destination for Layout file: Don't restore", spoken.toString(), "the spoken choice in English");
        equal("Destination for Layout file: Don't restore", spoken.english(), "the spoken choice's English");
    }

    // ---- Select all ---------------------------------------------------------------------------

    /** Base's sync: every enabled box checked (BackupActivity.selectAll before F4). */
    private static boolean base(boolean[] enabled, boolean[] checked) {
        boolean every = true;
        for (int i = 0; i < enabled.length; i++) if (enabled[i] && !checked[i]) every = false;
        return every;
    }

    private static void selectAll() {
        // Not frozen: exactly base's answer, for every state of up to seven boxes (Back up has seven categories).
        for (int n = 0; n <= 7; n++) {
            for (int bits = 0; bits < 1 << (2 * n); bits++) {
                boolean[] enabled = new boolean[n], checked = new boolean[n];
                for (int i = 0; i < n; i++) { enabled[i] = (bits >> i & 1) != 0; checked[i] = (bits >> (n + i) & 1) != 0; }
                for (boolean shown : new boolean[]{true, false}) {
                    check(BackupSelectAll.checked(shown, false, enabled, checked) == base(enabled, checked),
                            "not frozen, as base: " + Arrays.toString(enabled) + Arrays.toString(checked));
                    check(BackupSelectAll.checked(shown, true, enabled, checked) == shown,
                            "frozen keeps what it shows: " + Arrays.toString(enabled) + Arrays.toString(checked));
                }
            }
        }
        // The review's case: a partial selection, then createBackup disables every box; a relabel syncs again.
        boolean[] frozen = new boolean[7], partial = {true, true, false, true, false, false, true};
        check(base(frozen, partial), "base checks Select all over a partial selection once every box is disabled");
        check(!BackupSelectAll.checked(false, true, frozen, partial), "F4 keeps it cleared, busy or after success");
        // A full selection with an empty category (disabled, never checked) stays checked.
        boolean[] full = {true, true, true, true, true, true, false};
        check(BackupSelectAll.checked(true, true, frozen, full), "F4 keeps a full selection checked");
    }

    // ---- The screens' calls -------------------------------------------------------------------

    private static void sources() throws Exception {
        String more = FollowupsEnglishTest.read("src/com/jjricks/volt/MoreScreen.java");
        check(more.contains("sectionTitle.setText(T.render(SECTION_TITLE_IDS[i]));") && !more.contains("setText(SECTION_TITLES"),
                "showSection shows the catalog title");
        check(!more.contains("host.toast(service().ble.message())"), "no PIN, pairing or proximity toast shows message()");
        check(count(more, "host.toast(service().ble.displayText())") == 4, "the four toasts show displayText()");
        check(more.contains("service().ble.enablePin(enabled); host.toast(service().ble.displayText());")
                && more.contains("service().ble.bond(); host.toast(service().ble.displayText());")
                && more.contains("service().ble.set(ProximityFollowUp.KEY, enabled ? 1 : 0); host.toast(service().ble.displayText());")
                && more.contains("if (\"pin\".equals(service().ble.pending())) {\n                host.toast(service().ble.displayText()); dialog.dismiss();"),
                "each toast follows its call, as before");
        check(ProximityFollowUp.KEY.equals("proximity_key"), "ProximityFollowUp.KEY is the proximity feature key");
        check(more.contains("String name = p == null ? T.moreIndexNoScooter() : p.displayName();"), "the menu index shows displayName()");
        check(more.contains("card.name.setText(item.displayName());"), "the My scooters card shows displayName()");
        check(more.contains("name.setText(p.name); name.selectAll();"), "the rename field keeps the stored name");

        String setup = FollowupsEnglishTest.read("src/com/jjricks/volt/OnboardingScreen.java");
        check(setup.contains("T.onboardingConnectConnecting(active.displayName())") && !setup.contains("T.onboardingConnectConnecting(active.name)"),
                "the connecting sentence names displayName()");
        check(setup.contains("T.onboardingDoneReady(p.displayName())") && !setup.contains("T.onboardingDoneReady(p.name)"),
                "the done sentence names displayName()");

        String backup = FollowupsEnglishTest.read("src/com/jjricks/volt/BackupActivity.java");
        check(backup.contains("all.setChecked(BackupSelectAll.checked(all.isChecked(),!all.isEnabled(),enabled,checked));"),
                "Select all syncs through BackupSelectAll, frozen while it is disabled");
        check(count(backup, "all.setEnabled(false)") == 1 && backup.contains("if(all!=null)all.setEnabled(false);\n        setBusy(M.backupEncrypting());"),
                "only createBackup disables Select all");
        check(backup.contains("private static CharSequence destinationName(AppDataBackup.Destination d){return d.source.equals(BackupArchive.LAYOUT_SOURCE)?M.backupFileLayout():d.name;}"),
                "the layout destination's name is backup_file_layout");
        check(backup.contains("final CharSequence source=destinationName(d);") && count(backup, "M.backupDestinationForChoice(source,") == 2,
                "both spoken choices use the display name");
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) n++;
        return n;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void equal(String expected, String actual, String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        checks++;
    }
}
