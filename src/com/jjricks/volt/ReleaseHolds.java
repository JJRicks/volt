package com.jjricks.volt;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The scooters whose release went out but wasn't confirmed (OwnerRelease.holds), kept on disk per profile id so that
 * no automatic reconnect reaches them after Volt's process or service ends: a reconnect would make Volt the owner of a
 * scooter that may have released. BleController loads it from two app_settings keys that BackupRegistry excludes as
 * this phone's state (like a hand stop's charge hold): a backup never exports them, and a restore keeps this phone's
 * values, so it neither creates a hold nor clears one. It asks the store before every connection. Pure: storage is the
 * Store the caller gives.
 *
 * A hold ends only when the owner chooses Reconnect, or when its profile is gone (forgotten, removed by a confirmed
 * release, or missing after a restore). A process or service restart, simulation, Bluetooth off and on, and switching
 * scooters all keep it. Each hold also owes the owner one question (the release sheet), until the owner closes that
 * sheet or picks an action, so a sheet lost to a restart or a recreated screen is asked again.
 */
final class ReleaseHolds {
    /** The preference namespace and its two string-set keys, excluded from backups (BackupRegistry). */
    static final String PREFERENCES = "app_settings", HELD = "release_held", OWED = "release_owed";

    /** Saves both sets, synchronously enough that a process ending right after keeps them. */
    interface Store { void save(Set<String> held, Set<String> owed); }

    private final Set<String> held = new LinkedHashSet<>(), owed = new LinkedHashSet<>();
    private final Store store;

    /** The holds as last saved: null sets read as empty, and a question is owed only for a held profile. */
    ReleaseHolds(Set<String> savedHeld, Set<String> savedOwed, Store store) {
        if (savedHeld != null) for (String id : savedHeld) if (id != null && !id.isEmpty()) held.add(id);
        if (savedOwed != null) for (String id : savedOwed) if (held.contains(id)) owed.add(id);
        this.store = store;
    }

    /** Whether Volt holds every automatic connection to this profile. */
    synchronized boolean held(String profileId) { return profileId != null && held.contains(profileId); }

    /**
     * A link ended, or the confirmation window passed, with releaseSent: a release frame for profileId went out and
     * nothing confirmed it. Holds the profile and owes its question. Nothing sent holds nothing.
     */
    synchronized void ended(String profileId, boolean releaseSent) {
        if (!releaseSent || profileId == null || profileId.isEmpty()) return;
        boolean changed = held.add(profileId) | owed.add(profileId);
        if (changed) save();
    }

    /** failed()'s retry and its "reconnecting" wording: never after a sent release, nor for a held profile. */
    synchronized boolean retries(String profileId, boolean releaseSent) { return !releaseSent && !held(profileId); }

    /** The first held profile still owed its question, or null; holds whose profile no longer exists are dropped first. */
    synchronized String owed(Collection<String> profileIds) {
        prune(profileIds);
        for (String id : owed) return id;
        return null;
    }

    /** The owner closed the sheet or picked an action: the question is answered, the hold stays. */
    synchronized void asked(String profileId) { if (profileId != null && owed.remove(profileId)) save(); }

    /** Reconnect chosen, or the profile forgotten or released: the hold and its question end. */
    synchronized void end(String profileId) {
        if (profileId == null) return;
        boolean changed = held.remove(profileId) | owed.remove(profileId);
        if (changed) save();
    }

    /** Drops holds whose profile no longer exists (a forget elsewhere, a restore): harmless, nothing to hold back. */
    synchronized void prune(Collection<String> profileIds) {
        Set<String> live = profileIds == null ? Collections.<String>emptySet() : new LinkedHashSet<>(profileIds);
        boolean changed = held.retainAll(live) | owed.retainAll(live);
        if (changed) save();
    }

    private void save() { if (store != null) store.save(new LinkedHashSet<>(held), new LinkedHashSet<>(owed)); }
}
