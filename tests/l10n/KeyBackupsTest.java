package com.jjricks.volt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * KeyBackups (owner, 2026-11-13: no tap may lock a rider out of their own scooter): the fingerprint record a saved
 * backup leaves, how Forget reads it, and the Replace everything guard in BackupArchive.plan. Synthetic keys only.
 */
public final class KeyBackupsTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action, String message) throws Exception {
        checks++; try { action.run(); } catch (IOException expected) { return; } throw new AssertionError(message);
    }
    private static final String ID = "c0000000-0000-4000-8000-00000000000c", KEY = "0123456789AB";

    public static void main(String[] args) throws Exception {
        record();
        dropped();
        File root = Files.createTempDirectory("volt-key-backups-").toFile();
        try { replaceGuard(root); } finally { AppDataArchive.deleteTree(root); }
        System.out.println("KeyBackupsTest: " + checks + " checks passed (fingerprints, saved-at reads, dropped owner keys, Replace everything guard)");
    }

    private static Map<String, Object> saved(String id, String identity, long at) {
        Map<String, Object> app = new HashMap<>();
        app.put(KeyBackups.key(id), KeyBackups.value(KeyBackups.fingerprint(id, identity), at));
        return app;
    }

    private static void record() {
        String fp = KeyBackups.fingerprint(ID, KEY);
        check(fp.matches("[0-9a-f]{24}"), "a fingerprint is 24 lower-case hex digits: " + fp);
        check(fp.equals(KeyBackups.fingerprint(ID, KEY.toLowerCase(Locale.ROOT))), "the key's case doesn't matter");
        check(!fp.contains(KEY) && !fp.toUpperCase(Locale.ROOT).contains(KEY), "the record never holds the key itself");
        check(!fp.equals(KeyBackups.fingerprint(ID, "0123456789AC")), "another key, another fingerprint");
        check(!fp.equals(KeyBackups.fingerprint("d0000000-0000-4000-8000-00000000000d", KEY)), "another profile, another fingerprint");
        check(KeyBackups.key(ID).equals("key_backup_" + ID), "one app_settings key per profile");
        check(KeyBackups.guarded(0) && !KeyBackups.guarded(1), "owner profiles are guarded, shared ones aren't");

        check(KeyBackups.savedAt(saved(ID, KEY, 1763000000000L), ID, KEY) == 1763000000000L, "a backup of this key reads back its time");
        check(KeyBackups.savedAt(saved(ID, KEY, 1763000000000L), ID, "0123456789ab") == 1763000000000L, "case-insensitive key");
        check(KeyBackups.savedAt(saved(ID, KEY, 1763000000000L), ID, "0123456789AC") == 0, "a changed key needs a new backup");
        check(KeyBackups.savedAt(saved(ID, KEY, 5), "d0000000-0000-4000-8000-00000000000d", KEY) == 0, "another profile has no backup");
        check(KeyBackups.savedAt(new HashMap<>(), ID, KEY) == 0 && KeyBackups.savedAt(null, ID, KEY) == 0, "nothing saved, nothing read");
        check(KeyBackups.savedAt(saved(ID, KEY, 5), ID, "") == 0 && KeyBackups.savedAt(saved(ID, KEY, 5), ID, "not a key!!") == 0, "a profile without a key has no backup of it");
        Map<String, Object> damaged = new HashMap<>();
        for (Object bad : new Object[]{"", "@", KeyBackups.fingerprint(ID, KEY), KeyBackups.fingerprint(ID, KEY) + "@", KeyBackups.fingerprint(ID, KEY) + "@soon", 17L, true}) {
            damaged.put(KeyBackups.key(ID), bad);
            check(KeyBackups.savedAt(damaged, ID, KEY) == 0, "a damaged record reads as no backup: " + bad);
        }

        BackupProfiles.Profile owner = new BackupProfiles.Profile(ID, "Phone UT5", KEY, 0, "", "", "{}");
        BackupProfiles.Profile shared = new BackupProfiles.Profile("e0000000-0000-4000-8000-00000000000e", "Shared", "FEDCBA987654", 1, "", "", "{}");
        BackupProfiles.Profile blank = new BackupProfiles.Profile("f0000000-0000-4000-8000-00000000000f", "Spare", "", 0, "", "", "{}");
        Map<String, String> held = KeyBackups.held(Arrays.asList(owner, shared, blank));
        check(held.keySet().equals(new HashSet<>(Arrays.asList(KeyBackups.key(ID), KeyBackups.key(shared.id)))), "every configured profile in the backup is recorded, a blank one isn't: " + held.keySet());
        Map<String, Object> app = new HashMap<>();
        for (Map.Entry<String, String> e : held.entrySet()) app.put(e.getKey(), KeyBackups.value(e.getValue(), 42));
        check(KeyBackups.savedAt(app, ID, KEY) == 42 && KeyBackups.savedAt(app, shared.id, shared.identity) == 42, "what held records reads back");
    }

    private static Map<String, Map<String, Object>> live(String items) {
        Map<String, Map<String, Object>> live = new HashMap<>();
        Map<String, Object> profiles = new HashMap<>(); profiles.put("items", items); live.put("profiles", profiles);
        return live;
    }

    private static void dropped() throws IOException {
        String phone = "[" + BackupTestStore.profileItem(ID, "Phone UT5", KEY, "SERIAL-C") + ","
                + "{\"id\":\"e0000000-0000-4000-8000-00000000000e\",\"name\":\"Shared\",\"identity\":\"FEDCBA987654\",\"role\":1}]";
        List<BackupProfiles.Profile> none = Collections.emptyList();
        List<BackupProfiles.Profile> out = KeyBackups.dropped(live(phone), none);
        check(out.size() == 1 && out.get(0).id.equals(ID), "an owner key the backup lacks is dropped; a shared one isn't counted");
        List<BackupProfiles.Profile> same = BackupProfiles.parse("[{\"id\":\"x1\",\"name\":\"Other name\",\"identity\":\"0123456789ab\",\"role\":0}]");
        check(KeyBackups.dropped(live(phone), same).isEmpty(), "the backup holding the same owner key, under any profile, keeps it");
        List<BackupProfiles.Profile> asShared = BackupProfiles.parse("[{\"id\":\"x1\",\"identity\":\"0123456789AB\",\"role\":1}]");
        check(KeyBackups.dropped(live(phone), asShared).size() == 1, "the key held only as a shared profile still loses the owner's access");
        check(KeyBackups.dropped(live("[{broken"), none).isEmpty(), "unreadable saved scooters: Replace everything stays the way out");
        check(KeyBackups.dropped(new HashMap<>(), none).isEmpty() && KeyBackups.dropped(null, none).isEmpty(), "no saved scooters, nothing dropped");
    }

    /** BackupArchive.plan: Replace everything with a backup lacking this phone's owner key needs a backup of it saved here. */
    private static void replaceGuard(File root) throws Exception {
        BackupTestStore source = BackupTestStore.rich(new File(root, "source"));
        byte[] complete = source.export(BackupTestStore.all());
        BackupTestStore phone = BackupArchiveTest.phone(new File(root, "phone"));
        // BackupArchiveTest's phone saved a backup of its owner key; this one never did.
        Map<String, Map<String, Object>> unsaved = phone.preferences();
        check(unsaved.get("app_settings").remove(KeyBackups.key(BackupArchiveTest.C)) != null, "the fixture phone records its backup");
        phone.preferences(unsaved);
        Map<String, Map<String, Object>> before = phone.preferences();
        BackupArchive.Prepared prepared = phone.stage(complete);
        rejects(() -> BackupArchive.plan(phone, prepared, BackupTestStore.all(), true, Collections.emptyMap()),
                "Replace everything refuses to drop an owner key no backup on this phone holds");
        check(phone.preferences().equals(before), "the refused review changed nothing");
        try { BackupArchive.plan(phone, prepared, BackupTestStore.all(), true, Collections.emptyMap()); }
        catch (BackupException refused) { check(String.valueOf(refused.getMessage()).contains("owner key"), "the refusal says why: " + refused.getMessage()); }

        Map<String, Map<String, Object>> marked = phone.preferences();
        marked.get("app_settings").put(KeyBackups.key(BackupArchiveTest.C), KeyBackups.value(KeyBackups.fingerprint(BackupArchiveTest.C, "0123456789AB"), 1763000000000L));
        phone.preferences(marked);
        BackupArchive.Plan plan = BackupArchive.plan(phone, phone.stage(complete), BackupTestStore.all(), true, Collections.emptyMap());
        check(plan.replace, "with a backup of it saved here, Replace everything can be reviewed");
        BackupTransaction.apply(phone, plan);
        Map<String, Object> app = phone.preferences().get("app_settings");
        check(app.containsKey(KeyBackups.key(BackupArchiveTest.C)), "the record is this phone's own: Replace everything keeps it");

        // The same phone replaced with a backup that holds its own key needs nothing more.
        BackupTestStore self = BackupArchiveTest.phone(new File(root, "self"));
        byte[] own = self.export(BackupTestStore.all());
        check(BackupArchive.plan(self, self.stage(own), BackupTestStore.all(), true, Collections.emptyMap()).replace, "a backup holding this phone's owner key replaces freely");
    }
}
