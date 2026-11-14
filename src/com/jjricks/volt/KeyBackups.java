package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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
 *
 * Stored as app_settings "key_backup" + profile ID → 16 hex digits of fingerprint followed by the time saved in
 * milliseconds. (The language gates allow only plain lower-case ids as new literals here, so the key has no trailing
 * separator and the value is fixed-width instead of split on a character.) The fingerprint only tells a changed key
 * from the saved one; the key itself sits in the same phone's saved scooters.
 */
final class KeyBackups {
    static final String PREFIX = "key_backup";
    /** Hex digits of fingerprint at the start of a stored value. */
    static final int FINGERPRINT_DIGITS = 16;

    private KeyBackups() { }

    /** Owner keys only: forgetting a shared profile leaves the owner's own access untouched. */
    static boolean guarded(int role) { return role == 0; }

    /** The app_settings key holding one profile's record. */
    static String key(String profileId) { return PREFIX + profileId; }

    /**
     * 16 lower-case hex digits for this profile's key, the same on every run. A configured key is always 12 characters,
     * so profile ID and key run together without ambiguity. The first digit is fixed at a so the length never varies.
     */
    static String fingerprint(String profileId, String identity) {
        long bits = UUID.nameUUIDFromBytes((key(profileId) + identity.toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8))
                .getMostSignificantBits();
        return Long.toHexString((bits >>> 4) | (0xaL << 60));
    }

    /** What a backup holding these profiles records once its file is saved: key → fingerprint, configured profiles only. */
    static Map<String, String> held(List<BackupProfiles.Profile> profiles) {
        Map<String, String> result = new LinkedHashMap<>();
        for (BackupProfiles.Profile p : profiles) if (p.configured()) result.put(key(p.id), fingerprint(p.id, p.identity));
        return result;
    }

    /** The stored value for a fingerprint saved at savedAtMs. */
    static String value(String fingerprint, long savedAtMs) { return fingerprint + savedAtMs; }

    /** When a backup holding this profile's current key was saved on this phone, or 0 if none was. */
    static long savedAt(Map<String, ?> appSettings, String profileId, String identity) {
        Object stored = appSettings == null || profileId == null ? null : appSettings.get(key(profileId));
        if (!(stored instanceof String) || identity == null || identity.length() != 12) return 0;
        String text = (String) stored;
        if (text.length() <= FINGERPRINT_DIGITS || !text.startsWith(fingerprint(profileId, identity))) return 0;
        try { long time = Long.parseLong(text.substring(FINGERPRINT_DIGITS)); return Math.max(0, time); }
        catch (NumberFormatException damaged) { return 0; }
    }

    /**
     * Owner keys on this phone that replacing everything with a backup holding incoming would remove: an owner profile
     * the backup doesn't hold, with this key, as an owner profile of the same scooter (same profile ID, or the same
     * scooter by BackupProfiles.Profile.sameScooter). The key alone isn't enough: a NAVEE account's scooters share one
     * key, and Volt can't use a key saved under another scooter's profile. Saved scooters are read entry by entry, so
     * one damaged entry doesn't hide the others' keys; a list that can't be read at all gives none, since Replace
     * everything is also the way out of it and its keys can't be read anyway.
     */
    static List<BackupProfiles.Profile> dropped(Map<String, Map<String, Object>> live, List<BackupProfiles.Profile> incoming) {
        Map<String, Object> profiles = live == null ? null : live.get("profiles");
        List<BackupProfiles.Profile> result = new ArrayList<>();
        for (BackupProfiles.Profile p : BackupProfiles.readable(profiles == null ? null : profiles.get("items"))) {
            if (!guarded(p.role) || !p.configured()) continue;
            boolean kept = false;
            for (BackupProfiles.Profile q : incoming)
                if (guarded(q.role) && q.configured() && q.identity.equalsIgnoreCase(p.identity) && (q.id.equals(p.id) || p.sameScooter(q))) { kept = true; break; }
            if (!kept) result.add(p);
        }
        return result;
    }
}
