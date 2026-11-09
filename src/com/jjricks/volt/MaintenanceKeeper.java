package com.jjricks.volt;

import java.time.ZoneId;
import java.util.*;
import java.util.function.Consumer;

/**
 * Reads and writes each scooter's maintenance document ({@link MaintenanceStore}) for the service and the
 * Maintenance page, and feeds it odometer readings (maintenance plan, section 3).
 *
 * <ul>
 * <li>A scope is a scooter profile id, kept in app_settings, or a {@link #simulationScope} that lives in
 *     memory only: the simulator's sample odometer never reaches a real scooter's reminders.</li>
 * <li>Readings are counted only while reminders are on. Each one updates the count in memory; it is
 *     written when {@link MaintenanceDistance#worthSaving} says so (0.1 km more, a new baseline or reset, or
 *     6 h newer), and by {@link #flush} when the connection ends.</li>
 * <li>Changes from the page go through {@link #update}, which applies them to the latest document, so a
 *     reading that arrived while a sheet was open is never lost.</li>
 * </ul>
 * Pure Java: the service hands it the preferences as a {@link Storage}.
 */
final class MaintenanceKeeper {
    /** The preferences the documents live in. */
    interface Storage { String read(String key); void write(String key, String value); void remove(String key); }

    /** Profile ids are UUIDs, so none starts with this. */
    private static final String SIMULATION = "simulation";
    private final Storage storage;
    /** Simulation documents, by scope. */
    private final Map<String, String> memory = new HashMap<>();
    /** Counted distance not yet written, by scope, and the stored text it was counted on. */
    private final Map<String, MaintenanceDistance> pending = new HashMap<>();
    private final Map<String, String> pendingOn = new HashMap<>();

    /** Told after the page changed a scope's document, or a scooter was forgotten (MaintenanceNotify cancels or re-plans). */
    interface Listener { void changed(String scope, boolean forgotten); }
    private Listener listener;
    /** The simulator's device-only notification bookkeeping, by scope. */
    private final Map<String, String> memorySeen = new HashMap<>();

    MaintenanceKeeper(Storage storage) { this.storage = storage; }

    void listen(Listener listener) { this.listener = listener; }

    /** The profile id a scope belongs to (a simulation scope belongs to the profile it was made for). */
    static String profileOf(String scope) { return simulation(scope) ? scope.substring(SIMULATION.length()) : scope; }

    /** The scope's "already notified" bookkeeping (maintenance_seen_, never backed up), or null. */
    String seen(String scope) { return simulation(scope) ? memorySeen.get(scope) : storage.read(MaintenanceStore.seenKey(scope)); }

    void seen(String scope, String text) {
        if (simulation(scope)) memorySeen.put(scope, text); else storage.write(MaintenanceStore.seenKey(scope), text);
    }

    /** The memory-only scope the simulator uses for a profile (or none). */
    static String simulationScope(String profileId) { return SIMULATION + (profileId == null ? "" : profileId); }
    static boolean simulation(String scope) { return scope.startsWith(SIMULATION); }

    private String stored(String scope) {
        return simulation(scope) ? memory.get(scope) : storage.read(MaintenanceStore.key(scope));
    }

    private void store(String scope, String text) {
        if (simulation(scope)) memory.put(scope, text); else storage.write(MaintenanceStore.key(scope), text);
    }

    /** The latest document, with any distance counted since the last write. A fresh copy: changing it changes nothing. */
    MaintenanceStore.Settings load(String scope) {
        String text = stored(scope);
        MaintenanceStore.Settings s = MaintenanceStore.decode(text);
        MaintenanceDistance counted = pending.get(scope);
        // A document replaced meanwhile (a restore) drops what was counted on the old one.
        if (counted != null && Objects.equals(text, pendingOn.get(scope))) s.distance = counted.copy();
        else forgetPending(scope);
        return s;
    }

    /** Applies a change to the latest document and writes it at once. */
    void update(String scope, Consumer<MaintenanceStore.Settings> change) {
        MaintenanceStore.Settings s = load(scope);
        change.accept(s);
        store(scope, MaintenanceStore.encode(s));
        forgetPending(scope);
        if (listener != null) listener.changed(scope, false);
    }

    /**
     * One odometer reading for a scope's scooter. Ignored while reminders are off. Returns what the
     * reading did, or null when it was ignored.
     */
    MaintenanceDistance.Outcome observe(String scope, double rawKm, int bits, long wall) {
        String text = stored(scope);
        MaintenanceStore.Settings s = load(scope);
        if (!s.on) return null;
        MaintenanceDistance saved = MaintenanceStore.decode(text).distance;
        MaintenanceDistance.Outcome outcome = s.distance.observe(rawKm, bits, wall);
        // Reminders saved before any reading take this one as their distance base (written at once).
        boolean settled = s.distance.everRead() && MaintenancePolicy.settle(s.reminders, s.distance.accumulatedKm);
        if (settled || s.distance.worthSaving(saved)) { store(scope, MaintenanceStore.encode(s)); forgetPending(scope); }
        else { pending.put(scope, s.distance.copy()); pendingOn.put(scope, text); }
        return outcome;
    }

    /** Writes distance counted since the last write (the connection ended). */
    void flush(String scope) {
        if (!pending.containsKey(scope)) return;
        MaintenanceStore.Settings s = load(scope);
        if (pending.containsKey(scope)) store(scope, MaintenanceStore.encode(s));
        forgetPending(scope);
    }

    /** Whether anything counted is still unwritten. */
    boolean unwritten(String scope) { return pending.containsKey(scope); }

    /** A forgotten scooter: its document and its device-only bookkeeping go. */
    void forget(String profileId) {
        if (profileId == null || profileId.isEmpty()) return;
        forgetPending(profileId);
        storage.remove(MaintenanceStore.key(profileId));
        storage.remove(MaintenanceStore.seenKey(profileId));
        memorySeen.remove(simulationScope(profileId));
        if (listener != null) listener.changed(profileId, true);
    }

    /** The row's and the tab dot's counts for a scope. */
    MaintenanceView.Summary summary(String scope, long nowWall, ZoneId zone) {
        return MaintenanceView.summary(load(scope), nowWall, zone);
    }

    private void forgetPending(String scope) { pending.remove(scope); pendingOn.remove(scope); }
}
