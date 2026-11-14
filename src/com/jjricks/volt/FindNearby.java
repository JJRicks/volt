package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Find it nearby: looking for a saved scooter at a new Bluetooth address (after a repair, say). Volt otherwise only ever
 * connects to the saved address, so a scooter whose address changed times out forever. The scan sheet's find mode lists
 * the scooters nearby; the owner picks one, confirms, and Volt sends the profile's owner key to it. Only the serial check
 * after the owner check (0x74, equal to the saved, non-empty serial) lets remember() save the new address; anything else
 * changes nothing. Pure rules (FindNearbyTest); BleController, MainActivity, DashboardScreen and MoreScreen apply them.
 */
final class FindNearby {
    private FindNearby() { }

    /** Consecutive timeouts at the saved address before Home offers Find it nearby. */
    static final int TIMEOUTS = 2;

    /**
     * Whether a profile can be found at a new address: it has a saved address to move from and a saved serial. The
     * serial is what proves the scooter at the new address is this one; with none saved, any scooter's serial would be
     * accepted, so such a profile never gets find mode.
     */
    static boolean eligible(String savedMac, String savedSerial) {
        return savedMac != null && !savedMac.isEmpty() && savedSerial != null && !savedSerial.isEmpty();
    }

    /**
     * Home's Find it nearby button (entry A): an eligible profile Volt wants connected, whose last {@link #TIMEOUTS}
     * attempts at its saved address all timed out, and nothing else going on: not connected, not simulating, not
     * recording, no change in flight.
     */
    static boolean offerHome(String savedMac, String savedSerial, boolean wanted, boolean stuck, boolean connected,
                             boolean simulating, boolean recording, boolean pending) {
        return eligible(savedMac, savedSerial) && wanted && stuck && !connected && !simulating && !recording && !pending;
    }

    /** My scooters' Find at a new address (entry B): the same profile condition, with no timeouts needed. */
    static boolean offerRow(String savedMac, String savedSerial) { return eligible(savedMac, savedSerial); }

    // ------------------------------------------------------------------ the timeout tally

    /**
     * Consecutive connect timeouts (or connectGatt failures) at one profile's saved address, in memory only. Any
     * successful connection, and any other profile, starts it again. Every other failure (Bluetooth off, a lost link,
     * an owner or serial refusal) and any attempt at another address leave it as it is; permission and simulation
     * never reach it (no attempt is made).
     */
    static final class Timeouts {
        private String profileId = "";
        private int count;

        /** A connection attempt for this profile: another profile than the tally's starts it again. */
        void attempt(String id) { if (!same(id)) { profileId = id == null ? "" : id; count = 0; } }

        /** The active profile changed (My scooters' Use, an import): the tally starts again for it. */
        void profile(String id) { profileId = id == null ? "" : id; count = 0; }

        /** A link failed. Counts only a timeout or connectGatt failure of an attempt at the profile's saved address. */
        void failed(String id, String attemptMac, String savedMac, ConnectionEventLog.Reason reason) {
            if (reason != ConnectionEventLog.Reason.CONNECT_TIMEOUT && reason != ConnectionEventLog.Reason.CONNECT_FAILED) return;
            if (savedMac == null || savedMac.isEmpty() || attemptMac == null || !savedMac.equalsIgnoreCase(attemptMac)) return;
            if (!same(id)) { profileId = id == null ? "" : id; count = 0; }
            count++;
        }

        /** The link reached ready (authenticated, serial checked, first status): the tally starts again. */
        void connected() { count = 0; }

        int count(String id) { return same(id) ? count : 0; }

        /** Whether this profile's last {@link #TIMEOUTS} attempts at its saved address all timed out. */
        boolean stuck(String id) { return count(id) >= TIMEOUTS; }

        private boolean same(String id) { return id != null && !id.isEmpty() && id.equals(profileId); }
    }

    // ------------------------------------------------------------------ the pick

    /** What a pick in the find sheet does. */
    enum Step {
        /** Send nothing: the profile can't be found (no saved address or serial), or the address is another profile's scooter. */
        REFUSE,
        /** The saved address itself: an ordinary connection. */
        CONNECT,
        /** Another address, not confirmed yet: ask first, send nothing. */
        ASK,
        /** Another address, confirmed: connect to it in find mode (its serial must match the saved one). */
        FIND
    }

    /**
     * The step for picking {@code picked} while finding the profile saved at {@code savedMac} with {@code savedSerial}.
     * {@code savedElsewhere}: the address is saved under another profile (BleController.mayOffer refuses it too, before
     * any owner check). {@code confirmed}: the owner tapped Connect in the confirmation.
     */
    static Step pick(String savedMac, String savedSerial, String picked, boolean savedElsewhere, boolean confirmed) {
        if (!eligible(savedMac, savedSerial) || picked == null || picked.isEmpty() || savedElsewhere) return Step.REFUSE;
        if (savedMac.equalsIgnoreCase(picked)) return Step.CONNECT;
        return confirmed ? Step.FIND : Step.ASK;
    }

    // ------------------------------------------------------------------ the serial check

    /** The 0x74 serial a find connection read, against the profile's saved serial. */
    enum Serial {
        /** The same scooter: remember() saves the new address. */
        MATCH,
        /** Another scooter: refused, nothing saved, no retry. */
        MISMATCH,
        /** No saved serial to compare with: refused like a mismatch (find mode never starts without one). */
        NO_SAVED_SERIAL
    }

    static Serial serial(String saved, String received) {
        if (saved == null || saved.isEmpty()) return Serial.NO_SAVED_SERIAL;
        return saved.equals(received) ? Serial.MATCH : Serial.MISMATCH;
    }

    // ------------------------------------------------------------------ the sheet

    /** One scooter the find scan heard. */
    static final class Heard {
        final String mac;
        final int rssi;
        /** Its advertised model ID, or null when its advertisement carries none. */
        final String pid;
        /** Saved under another profile: shown, but it can't be picked. */
        final boolean savedElsewhere;

        Heard(String mac, int rssi, String pid, boolean savedElsewhere) {
            this.mac = mac; this.rssi = rssi; this.pid = pid; this.savedElsewhere = savedElsewhere;
        }
    }

    /** What the find sheet shows. */
    static final class Sheet {
        /** Closest first; the saved address, if heard, is among them. */
        final List<Heard> shown;
        /** Scooters heard whose advertised model differs from the profile's known model. */
        final int hidden;
        /** The saved address, when it was heard (it starts selected), else null. */
        final String preselected;

        Sheet(List<Heard> shown, int hidden, String preselected) {
            this.shown = Collections.unmodifiableList(shown); this.hidden = hidden; this.preselected = preselected;
        }
    }

    /** A model ID's family: its first four digits ("" for none), as DeviceDescriptor.family() takes it. */
    static String family(String pid) { return pid == null ? "" : pid.substring(0, Math.min(4, pid.length())); }

    /**
     * Whether a heard scooter can be the profile's: the profile's model family ({@code family}, "" when unknown) and the
     * scooter's advertised family agree, or either is unknown (an advertisement without a model ID proves nothing).
     */
    static boolean sameModel(String family, String pid) {
        String theirs = family(pid);
        return family == null || family.isEmpty() || theirs.isEmpty() || theirs.equals(family);
    }

    /**
     * The find sheet: the scooters heard that can be the profile's model, closest (strongest signal) first, the saved
     * address always among them; the number hidden for another model; and the saved address to preselect if heard.
     */
    static Sheet sheet(List<Heard> heard, String savedMac, String family) {
        List<Heard> shown = new ArrayList<>();
        int hidden = 0;
        String preselected = null;
        for (Heard h : heard) {
            if (h == null || h.mac == null) continue;
            boolean saved = savedMac != null && !savedMac.isEmpty() && savedMac.equalsIgnoreCase(h.mac);
            if (!saved && !sameModel(family, h.pid)) { hidden++; continue; }
            if (saved) preselected = h.mac;
            shown.add(h);
        }
        // Stable: equal signals keep the order they were heard in.
        Collections.sort(shown, (a, b) -> Integer.compare(b.rssi, a.rssi));
        return new Sheet(shown, hidden, preselected);
    }
}
