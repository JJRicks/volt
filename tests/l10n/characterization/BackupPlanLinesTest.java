package com.jjricks.volt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * G5 characterization for B10 (the backup engine), committed before the batch changes anything.
 *
 * Pins, line by line and in order, the restore review that BackupArchive.plan writes for synthetic
 * backups (the fixtures of BackupTestStore and BackupArchiveTest): Replace everything, a merge of
 * every category, a repeated merge, every scooter skipped, one of two scooters skipped, two backup
 * scooters onto one, a fresh phone that can only skip, an App-preferences-only file, the charge
 * limit, a phone file without its ride and a History-only restore with nothing to add. Counts of 1
 * and of more than 1 both appear, so each plural-style phrase is pinned in both forms. It also pins
 * the English messages a refused file, a refused choice and a refused restore carry (getMessage()).
 * Every case runs with the phone set to en-US, de-DE, tr-TR and ar-EG.
 */
public final class BackupPlanLinesTest {
    private static int checks;
    private static final String A = BackupTestStore.A, B = BackupTestStore.B, C = BackupArchiveTest.C;

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static Set<String> set(String... values) { return new LinkedHashSet<>(Arrays.asList(values)); }
    private interface Action { void run() throws Exception; }

    private static String message(Action action) throws Exception {
        try { action.run(); } catch (IOException refused) { return refused.getMessage(); }
        throw new AssertionError("expected a refusal");
    }

    private static void lines(String name, BackupArchive.Plan plan, String... expected) {
        check(plan.lines.equals(Arrays.asList(expected)), name + " review lines: " + plan.lines);
    }

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR"), new Locale("ar", "EG")}) {
                Locale.setDefault(phone);
                run();
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("BackupPlanLinesTest: " + checks + " checks passed (restore review lines and refusal messages, four phone locales)");
    }

    private static void run() throws Exception {
        File root = Files.createTempDirectory("volt-plan-lines-").toFile();
        try {
            BackupTestStore source = BackupTestStore.rich(new File(root, "source"));
            byte[] complete = source.export(BackupTestStore.all());
            Map<String, String> chosen = new LinkedHashMap<>();

            BackupTestStore phone = BackupArchiveTest.phone(new File(root, "replace"));
            lines("Replace everything", BackupArchive.plan(phone, phone.stage(complete), BackupTestStore.all(), true, Collections.<String, String>emptyMap()),
                    "Replaces everything on this phone", "Scooters & setup keys: 2 scooters", "Dashboards: 2 scooters", "App preferences: 13 settings",
                    "Rides: 3 rides", "Charges: 2 charges", "Readings history: 2 scooters", "Scooter preferences: 2 scooters", "Online map: on");

            BackupTestStore merge = BackupArchiveTest.phone(new File(root, "merge"));
            chosen.put(A, C); chosen.put(B, B);
            BackupArchive.Plan merged = BackupArchive.plan(merge, merge.stage(complete), BackupTestStore.all(), false, chosen);
            lines("Merge", merged,
                    "“Fold UT5” → Phone UT5", "Scooters: adds 1 · 1 already here", "Dashboards: replaces layouts for Phone UT5, Spare",
                    "App preferences: replaces 13 settings", "Rides: 3 new · 0 already here · 0 kept here", "Charges: 2 new · 0 already here · 0 kept here",
                    "Readings history: adds to 2 scooters", "Scooter preferences: replaces settings for Phone UT5, Spare", "Online map: on");
            BackupTransaction.apply(merge, merged);
            lines("Repeated merge", merge.plan(merge.stage(complete), BackupTestStore.all(), false, null),
                    "“Fold UT5” → Phone UT5", "Scooters: adds 0 · 2 already here", "Dashboards: replaces layouts for Phone UT5, Spare", "App preferences: replaces 13 settings",
                    "Rides: 0 new · 3 already here · 0 kept here", "Charges: 0 new · 2 already here · 0 kept here", "Readings history: adds to 2 scooters",
                    "Scooter preferences: replaces settings for Phone UT5, Spare", "Online map: on");

            BackupTestStore skipAll = BackupArchiveTest.phone(new File(root, "skip-all"));
            chosen.put(A, BackupArchive.SKIP); chosen.put(B, BackupArchive.SKIP);
            lines("Every scooter skipped", BackupArchive.plan(skipAll, skipAll.stage(complete), BackupTestStore.all(), false, chosen),
                    "Scooters: adds 0 · 0 already here", "2 scooters skipped · not restored", "Dashboards: nothing to replace",
                    "Dashboards for 2 scooters skipped · not restored", "App preferences: replaces 13 settings", "Rides: 0 new · 0 already here · 0 kept here",
                    "3 rides skipped · scooter not restored", "Charges: 0 new · 0 already here · 0 kept here", "2 charges skipped · scooter not restored",
                    "Readings history: nothing to add", "Readings for 2 scooters skipped · not restored", "Scooter preferences: nothing to replace",
                    "Scooter preferences for 2 scooters skipped · not restored", "Online map: on");

            BackupTestStore skipOne = BackupArchiveTest.phone(new File(root, "skip-one"));
            chosen.put(A, C); chosen.put(B, BackupArchive.SKIP);
            lines("One scooter skipped", BackupArchive.plan(skipOne, skipOne.stage(complete), BackupTestStore.all(), false, chosen),
                    "“Fold UT5” → Phone UT5", "Scooters: adds 0 · 1 already here", "1 scooter skipped · not restored", "Dashboards: replaces layouts for Phone UT5",
                    "Dashboards for 1 scooter skipped · not restored", "App preferences: replaces 13 settings", "Rides: 2 new · 0 already here · 0 kept here",
                    "1 ride skipped · scooter not restored", "Charges: 1 new · 0 already here · 0 kept here", "1 charge skipped · scooter not restored",
                    "Readings history: adds to 1 scooter", "Readings for 1 scooter skipped · not restored", "Scooter preferences: replaces settings for Phone UT5",
                    "Scooter preferences for 1 scooter skipped · not restored", "Online map: on");

            BackupTestStore shared = BackupArchiveTest.phone(new File(root, "shared"));
            chosen.put(A, C); chosen.put(B, C);
            lines("Two scooters onto one", BackupArchive.plan(shared, shared.stage(complete),
                    set(BackupRegistry.DASHBOARDS, BackupRegistry.SCOOTER_PREFERENCES, BackupRegistry.RIDES, BackupRegistry.HISTORY), false, chosen),
                    "“Fold UT5” → Phone UT5", "“Spare” → Phone UT5", "Dashboards: nothing to replace", "Dashboards kept for Phone UT5 · two scooters chose it",
                    "Rides: 3 new · 0 already here · 0 kept here", "Readings history: adds to 1 scooter", "Scooter preferences: nothing to replace",
                    "Scooter preferences kept for Phone UT5 · two scooters chose it");

            BackupTestStore fresh = new BackupTestStore(new File(root, "fresh"));
            Set<String> chosenCategories = set(BackupRegistry.RIDES, BackupRegistry.DASHBOARDS);
            lines("Fresh phone", fresh.plan(fresh.stage(source.export(chosenCategories)), chosenCategories, false, BackupArchive.SKIP),
                    "Dashboards: nothing to replace", "Dashboards for 2 scooters skipped · not restored", "Rides: 0 new · 0 already here · 0 kept here",
                    "3 rides skipped · scooter not restored");
            lines("App preferences only", fresh.plan(fresh.stage(source.export(set(BackupRegistry.PREFERENCES))), set(BackupRegistry.PREFERENCES), false, null),
                    "App preferences: replaces 13 settings", "Online map: on");

            BackupTestStore oneSetting = new BackupTestStore(new File(root, "one-setting-source"));
            oneSetting.put("app_settings", "theme", "light");
            lines("One app preference", fresh.plan(fresh.stage(oneSetting.export(set(BackupRegistry.PREFERENCES))), set(BackupRegistry.PREFERENCES), false, null),
                    "App preferences: replaces 1 setting");

            BackupTestStore full = BackupArchiveTest.phone(new File(root, "charge-limit"));
            for (int i = 0; i < 14; i++) full.charge(C, 1761000000000L + i * 1000000L, false);
            BackupTestStore charges = new BackupTestStore(new File(root, "charge-limit-source"));
            for (int i = 0; i < 15; i++) charges.charge(A, 1760000000000L + i * 1000000L, false);
            lines("Charge limit", full.plan(full.stage(charges.export(set(BackupRegistry.CHARGES))), set(BackupRegistry.CHARGES), false, C),
                    "“Saved scooter” → Phone UT5", "Charges: 5 new · 0 already here · 0 kept here", "10 charges skipped · 20-charge limit");
            BackupTestStore oneOver = new BackupTestStore(new File(root, "charge-one-over-source"));
            for (int i = 0; i < 6; i++) oneOver.charge(A, 1760000000000L + i * 1000000L, false);
            lines("One charge over the limit", full.plan(full.stage(oneOver.export(set(BackupRegistry.CHARGES))), set(BackupRegistry.CHARGES), false, C),
                    "“Saved scooter” → Phone UT5", "Charges: 5 new · 0 already here · 0 kept here", "1 charge skipped · 20-charge limit");

            BackupTestStore orphanSource = new BackupTestStore(new File(root, "orphan-source"));
            orphanSource.ride(A, 1760000000000L, false, "With an orphan");
            for (int i = 0; i < 2; i++)
                BackupTestStore.file(new File(orphanSource.files(), "phone-rides/" + UUID.randomUUID() + "-phone.csv"), "orphan\n");
            BackupTestStore orphanPhone = BackupArchiveTest.phone(new File(root, "orphan"));
            lines("Phone files without a ride", orphanPhone.plan(orphanPhone.stage(orphanSource.export(set(BackupRegistry.RIDES))), set(BackupRegistry.RIDES), false, C),
                    "“Saved scooter” → Phone UT5", "Rides: 1 new · 0 already here · 0 kept here", "2 phone files skipped · no ride");
            BackupTestStore oneOrphan = new BackupTestStore(new File(root, "one-orphan-source"));
            oneOrphan.ride(A, 1760000000000L, false, "With an orphan");
            BackupTestStore.file(new File(oneOrphan.files(), "phone-rides/" + UUID.randomUUID() + "-motion.csv"), "orphan\n");
            lines("One phone file without a ride", orphanPhone.plan(orphanPhone.stage(oneOrphan.export(set(BackupRegistry.RIDES))), set(BackupRegistry.RIDES), false, C),
                    "“Saved scooter” → Phone UT5", "Rides: 1 new · 0 already here · 0 kept here", "1 phone file skipped · no ride");

            BackupTestStore history = BackupArchiveTest.phone(new File(root, "history"));
            chosen.clear(); chosen.put(A, BackupArchive.SKIP); chosen.put(B, BackupArchive.SKIP);
            lines("History, nothing to add", BackupArchive.plan(history, history.stage(complete), set(BackupRegistry.HISTORY), false, chosen),
                    "Readings history: nothing to add", "Readings for 2 scooters skipped · not restored");

            refusals(root, complete);
        } finally {
            AppDataArchive.deleteTree(root);
        }
    }

    /** The English a refusal carries, which logs keep and the Backup screen shows today. */
    private static void refusals(File root, byte[] complete) throws Exception {
        BackupTestStore phone = BackupArchiveTest.phone(new File(root, "refusals"));
        check("Choose a Volt backup file.".equals(message(() -> BackupArchive.stage(phone, new java.io.ByteArrayInputStream("not a backup".getBytes(StandardCharsets.UTF_8)), null))), "an unknown file");
        check("Invalid JSON object.".equals(message(() -> BackupJson.parse("{\"a\" 1}", 8))), "a JSON object without a colon");
        check("Duplicate JSON key.".equals(message(() -> BackupJson.parse("{\"a\":1,\"a\":2}", 8))), "a repeated JSON key");
        check("Incomplete JSON.".equals(message(() -> BackupJson.parse("[1,", 8))), "cut JSON");
        check("The backup has invalid scooter profiles.".equals(message(() -> BackupProfiles.parse(5L))), "profiles that are not text");
        check("The backup has unreadable scooter profiles.".equals(message(() -> BackupProfiles.parse("[{"))), "profiles that are not JSON");
        check("The owner setup file contains unsupported fields.".equals(message(() -> BackupArchive.stage(phone, new java.io.ByteArrayInputStream("{\"format\":\"navee-offline-owner-v1\",\"extra\":1}".getBytes(StandardCharsets.UTF_8)), null))), "a pairing file with an unknown field");
        check("The owner setup file has an invalid owner key.".equals(message(() -> BackupArchive.stage(phone, new java.io.ByteArrayInputStream("{\"format\":\"navee-offline-owner-v1\",\"owner_identity\":\"nothex\"}".getBytes(StandardCharsets.UTF_8)), null))), "a pairing file with a bad identity");
        BackupArchive.Prepared prepared = phone.stage(complete);
        check("Choose what to restore.".equals(message(() -> BackupArchive.plan(phone, prepared, Collections.<String>emptySet(), false, null))), "no category");
        check("Choose where each scooter's data goes.".equals(message(() -> BackupArchive.plan(phone, prepared, set(BackupRegistry.RIDES), false, Collections.singletonMap(A, C)))), "an unresolved scooter");
        check("Replace everything needs a complete backup with every category.".equals(message(() -> BackupArchive.plan(phone, prepared, set(BackupRegistry.RIDES), true, null))), "a partial replace");
        BackupArchive.Plan plan = phone.plan(prepared, set(BackupRegistry.PREFERENCES), false, null);
        phone.usable = 0;
        check("Not enough free storage".equals(message(() -> BackupTransaction.apply(phone, plan))), "no free space");
        phone.usable = Long.MAX_VALUE;
        phone.put("app_settings", "dashboard_columns", 3);
        check("Saved data changed. Review again.".equals(message(() -> BackupTransaction.apply(phone, plan))), "data changed after the review");
        BackupArchive.discard(phone, prepared);
        check("Choose the backup again.".equals(message(() -> BackupArchive.destinations(phone, prepared, set(BackupRegistry.RIDES)))), "a discarded stage");
    }
}
