package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Maintenance reminders through a real encrypted backup: Replace everything keeps them exactly,
 * a merge moves them with their scooter and drops the raw odometer baseline only when the profile
 * changes, and this phone's notified bookkeeping never travels.
 */
public final class MaintenanceBackupTest {
    private static int checks;
    private static final String A = BackupTestStore.A, B = BackupTestStore.B, C = BackupArchiveTest.C;
    private static final long WALL = 1_800_000_000_000L;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    private static String document(double firstKm, double lastKm, String reminderId) {
        MaintenanceStore.Settings s = new MaintenanceStore.Settings();
        s.on = true;
        s.distance.observe(firstKm, 32, WALL - 1000); s.distance.observe(lastKm, 32, WALL);
        MaintenancePolicy.add(s.reminders, MaintenancePolicy.create(reminderId, MaintenanceDefaults.BRAKES, "", 300.0, 0, 0, true, WALL, s.distance.accumulatedKm, WALL));
        return MaintenanceStore.encode(s);
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("volt-maintenance-backup-").toFile();
        try {
            BackupTestStore source = BackupTestStore.rich(new File(root, "source"));
            String forA = document(1000, 1250.5, "ra"), forB = document(40, 41, "rb");
            source.put("app_settings", MaintenanceStore.key(A), forA);
            source.put("app_settings", MaintenanceStore.key(B), forB);
            source.put("app_settings", MaintenanceStore.seenKey(A), "ra");
            byte[] complete = source.export(BackupTestStore.all());

            BackupArchive.Prepared prepared = new BackupTestStore(new File(root, "peek")).stage(complete);
            check(prepared.categories.get(BackupRegistry.SCOOTER_PREFERENCES) == 2, "both scooters still count once in Scooter preferences");

            BackupTestStore fresh = new BackupTestStore(new File(root, "fresh"));
            fresh.restore(complete, BackupTestStore.all(), true, null);
            Map<String, Object> app = fresh.preferences().get("app_settings");
            check(forA.equals(app.get(MaintenanceStore.key(A))) && forB.equals(app.get(MaintenanceStore.key(B))), "Replace everything restores the reminders exactly, baseline included");
            check(!app.containsKey(MaintenanceStore.seenKey(A)), "the notified bookkeeping is never restored");
            check(MaintenanceStore.decode((String) app.get(MaintenanceStore.key(A))).distance.lastRawKm == 1250.5, "the same profile keeps its raw baseline");

            BackupTestStore phone = BackupArchiveTest.phone(new File(root, "merge"));
            phone.put("app_settings", MaintenanceStore.key(C), document(5, 6, "local"));
            phone.put("app_settings", MaintenanceStore.seenKey(C), "local");
            BackupArchive.Prepared staged = phone.stage(complete);
            Map<String, String> chosen = new LinkedHashMap<>(); chosen.put(A, C); chosen.put(B, B);
            BackupTransaction.apply(phone, BackupArchive.plan(phone, staged, BackupTestStore.all(), false, chosen));
            app = phone.preferences().get("app_settings");
            MaintenanceStore.Settings moved = MaintenanceStore.decode((String) app.get(MaintenanceStore.key(C))), was = MaintenanceStore.decode(forA);
            check(moved.find("ra") != null && moved.find("local") == null && moved.on, "a merge replaces the mapped scooter's reminders with the backup's");
            check(!moved.distance.hasBaseline() && moved.distance.accumulatedKm == was.distance.accumulatedKm && moved.distance.everRead(), "onto another profile: the raw baseline goes, the count stays");
            check(app.get(MaintenanceStore.key(C)).equals(forA.replace("\"lastRawKm\":1250.5,", "")), "nothing else in the document changes");
            check(!app.containsKey(MaintenanceStore.key(A)), "the backup's profile id is not written");
            check(forB.equals(app.get(MaintenanceStore.key(B))), "a scooter added under its own id keeps its document exactly");
            check("local".equals(app.get(MaintenanceStore.seenKey(C))) && !app.containsKey(MaintenanceStore.seenKey(A)), "this phone's notified bookkeeping stays; the backup's never arrives");
            moved.distance.observe(77.7, 32, WALL + 1);
            check(moved.distance.accumulatedKm == was.distance.accumulatedKm, "the mapped scooter's first reading adds nothing");
        } finally {
            BackupTestStore.delete(root);
        }
        System.out.println("MaintenanceBackupTest: " + checks + " checks passed (exact restore, merge moves reminders and clears the baseline, device key stays)");
    }
}
