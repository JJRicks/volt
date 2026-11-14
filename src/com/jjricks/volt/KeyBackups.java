package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which owner keys a saved backup holds, so no tap can lock a rider out of their own scooter (owner, 2026-11-13).
 *
 * The scooter keeps its owner key until it is released (OwnerRelease). Forgetting the scooter in Volt deletes only this
 * phone's copy, and a key Volt made (NewOwnerKey) has no other copy at all. So two things need a backup holding an
 * owner key first: Forget on an owner scooter (MoreScreen), and a Replace everything restore that would drop an owner
 * key the restored backup lacks (BackupArchive.plan).
 *
 * A backup that included Scooters & setup keys and reached its file (BackupActivity) records, per configured profile it
 * held, a fingerprint of the profile ID and key with the time saved, never the key itself. The record is this
 * phone's own: BackupRegistry excludes the prefix, so a restore or another phone never inherits a claim that a file
 * exists. A changed key no longer matches its fingerprint, so it needs a new backup.
 */
final class KeyBackups {
    static final String PREFIX = "key_backup_";
    private static final char SEPARATOR = '@';

    private KeyBackups() { }

    /** Owner keys only: forgetting a shared profile leaves the owner's own access untouched. */
    static boolean guarded(int role) { return role == 0; }

    /** The app_settings key holding one profile's record. */
    static String key(String profileId) { return PREFIX + profileId; }

    static String fingerprint(String profileId, String identity) {
        byte[] digest;
        try { digest = MessageDigest.getInstance("SHA-256").digest(
                (PREFIX + profileId + '\n' + identity.toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException missing) { throw new AssertionError(missing); }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i++) out.append(Character.forDigit((digest[i] >> 4) & 15, 16)).append(Character.forDigit(digest[i] & 15, 16));
        return out.toString();
    }

    /** What a backup holding these profiles records once its file is saved: key → fingerprint, configured profiles only. */
    static Map<String, String> held(List<BackupProfiles.Profile> profiles) {
        Map<String, String> result = new LinkedHashMap<>();
        for (BackupProfiles.Profile p : profiles) if (p.configured()) result.put(key(p.id), fingerprint(p.id, p.identity));
        return result;
    }

    /** The stored value for a fingerprint saved at savedAtMs. */
    static String value(String fingerprint, long savedAtMs) { return fingerprint + SEPARATOR + savedAtMs; }

    /** When a backup holding this profile's current key was saved on this phone, or 0 if none was. */
    static long savedAt(Map<String, ?> appSettings, String profileId, String identity) {
        Object stored = appSettings == null || profileId == null ? null : appSettings.get(key(profileId));
        if (!(stored instanceof String) || identity == null || !identity.matches("(?i)[0-9a-f]{12}")) return 0;
        String text = (String) stored; int at = text.indexOf(SEPARATOR);
        if (at <= 0 || !text.substring(0, at).equals(fingerprint(profileId, identity))) return 0;
        try { long time = Long.parseLong(text.substring(at + 1)); return Math.max(0, time); }
        catch (NumberFormatException damaged) { return 0; }
    }

    /**
     * Owner keys on this phone that replacing everything with a backup holding incoming would remove: an owner profile
     * whose key the backup holds for no owner profile. Unreadable saved scooters give none, since Replace everything is
     * also the way out of them and their keys can't be read anyway.
     */
    static List<BackupProfiles.Profile> dropped(Map<String, Map<String, Object>> live, List<BackupProfiles.Profile> incoming) {
        List<BackupProfiles.Profile> local;
        try {
            Map<String, Object> profiles = live == null ? null : live.get("profiles");
            local = BackupProfiles.parse(profiles == null ? null : profiles.get("items"));
        } catch (IOException unreadable) { return Collections.emptyList(); }
        List<BackupProfiles.Profile> result = new ArrayList<>();
        for (BackupProfiles.Profile p : local) {
            if (!guarded(p.role) || !p.configured()) continue;
            boolean kept = false;
            for (BackupProfiles.Profile q : incoming) if (guarded(q.role) && q.configured() && q.identity.equalsIgnoreCase(p.identity)) { kept = true; break; }
            if (!kept) result.add(p);
        }
        return result;
    }
}
