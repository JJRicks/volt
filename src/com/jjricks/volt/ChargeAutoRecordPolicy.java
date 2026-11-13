package com.jjricks.volt;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides when Volt records a charge on its own ("Record charges automatically", off by default). Pure Java: the
 * service feeds it the facts it already has (the connection, the switch, the charger choice, fresh readings and a
 * monotonic clock) and acts on the answer. It never reads a clock, a sensor or a setting itself.
 *
 * Start: the switch is on, the connected scooter is the selected one, it reports charging, the ordinary manual
 * start would be allowed now (ScooterService.chargeCaptureBlocked() is null, which also rules out simulation, a
 * ride, a full battery and blocked notifications), the scooter is not moving above {@link #END_SPEED_KMH}, and no
 * hold or latch stands for that scooter. Connecting to a scooter that is already charging starts then: a partial
 * recording is fine. Never a prompt.
 *
 * Label: see {@link #label}. The recording keeps how its label was chosen.
 *
 * End, for automatic recordings only, on top of every rule that ends a recording today:
 * - at once when a live speed reading is above {@link #END_SPEED_KMH} ({@link #movedAway});
 * - {@link #CHARGE_OFF_END_MS} after charging stops, unless Volt's own charging schedule holds the current off
 *   ({@link #chargerOffWaitMs}). Manual recordings keep the 30-minute charger-pause wait.
 *
 * Kept: an automatic recording that ran under {@link #MIN_KEEP_MS} or gained under {@link #MIN_KEEP_POINTS}
 * percentage points is discarded ({@link #keep}). At the 20-recording limit an automatic recording may replace only
 * older automatic ones, and only once it is kept; it never deletes a manual one, and nothing saved goes for one that is
 * discarded ({@link #automaticToRemove}, ChargeSessionStore.trimAutomatic).
 *
 * Waits: an automatic start never arrives in the middle of other work a recording would block or cut short: a guided
 * setting test (running, or with a write-back outstanding) or a compatibility check ({@link #waits}). It starts by itself
 * once that is over, if the charge is still on.
 *
 * What a label stands for: only a chosen or detected label is the charger in effect while recording, and only those
 * teach a charger's curve ({@link #countsAsCharger}). A guess labels the file and nothing else.
 *
 * Hold: stopping a recording by hand (Stop on the page or in the notification, or starting a ride by hand) holds that
 * scooter until a fresh "not charging" reading is seen while Volt is not holding the current off itself (a
 * schedule or Charge done by pause is not an unplug; {@link #clearsHold}), across reconnects and restarts: an automatic recording always,
 * and one the owner started while the switch is on, so Volt never starts recording the charge the owner just stopped
 * recording (owner's answer 4: a manual stop holds until the next plug-in). The service
 * stores the held scooters ({@link #HOLD_KEY}). A recording that ended by itself sets no hold. A start that failed
 * (storage, or no room because every saved recording is manual) latches that scooter in memory by the same rule, so
 * it is not retried on every reading.
 */
public final class ChargeAutoRecordPolicy {
    /** The switch, off by default (absent means off). An app preference, backed up with App preferences. */
    public static final String KEY = "charge_auto_record";
    /** Scooters held after a hand stop, space-separated profile ids. Belongs to this phone; BackupRegistry leaves it out. */
    public static final String HOLD_KEY = "charge_auto_record_hold";
    /** About 3 mph. A live speed reading above this ends an automatic recording at once; jostling below it doesn't. */
    public static final double END_SPEED_KMH = 4.8;
    /** An automatic recording ends this long after charging stops (a schedule hold excepted). */
    public static final long CHARGE_OFF_END_MS = 2 * 60_000L;
    /** Shorter automatic recordings are not kept. */
    public static final long MIN_KEEP_MS = 10 * 60_000L;
    /** Automatic recordings that gained fewer battery percentage points are not kept. */
    public static final double MIN_KEEP_POINTS = 5;

    /** The store's keep and room rules for automatic recordings. Manual recordings never consult them. */
    public static final ChargeSessionStore.AutomaticRules RULES = new ChargeSessionStore.AutomaticRules() {
        public boolean keep(long ranMs, double gainedPoints) { return ChargeAutoRecordPolicy.keep(ranMs, gainedPoints); }
        public int toRemove(int saved, int automatic, int max) { return automaticToRemove(saved, automatic, max); }
    };

    public static final class Label {
        final ChargeSessionStore.Charger charger; final ChargeSessionStore.LabelSource source;
        Label(ChargeSessionStore.Charger charger, ChargeSessionStore.LabelSource source) { this.charger = charger; this.source = source; }
    }

    private ChargeAutoRecordPolicy() {}

    /**
     * Whether to start an automatic recording now.
     *
     * @param connectedSelected Volt is connected to the scooter that is selected
     * @param charging a fresh reading says the scooter is charging
     * @param blocked the manual start's refusal reason is not null (it covers simulation)
     */
    public static boolean shouldStart(boolean enabled, boolean connectedSelected, boolean charging, boolean simulating, boolean blocked,
            boolean recording, boolean held, boolean movingFast) {
        return enabled && connectedSelected && charging && !simulating && !blocked && !recording && !held && !movingFast;
    }

    /**
     * The charger an automatic recording is labelled with: the charger chosen in Estimates & charger; otherwise Volt's
     * detection for this charge; otherwise a best guess. The guess is the family with more qualified saved recordings
     * for this scooter (the charger the owner actually uses), and on a tie or none, the stock charger every scooter
     * ships with.
     */
    public static Label label(ChargeSessionStore.Charger chosen, ChargeSessionStore.Charger detected, int stockRecordings, int fastRecordings) {
        if (chosen != null) return new Label(chosen, ChargeSessionStore.LabelSource.CHOSEN);
        if (detected != null) return new Label(detected, ChargeSessionStore.LabelSource.DETECTED);
        return new Label(fastRecordings > stockRecordings ? ChargeSessionStore.Charger.HIGH_SPEED : ChargeSessionStore.Charger.STOCK, ChargeSessionStore.LabelSource.GUESS);
    }

    /**
     * The charger a running automatic recording is relabelled to, as DETECTED, or null to keep its label. The source
     * follows what is known: a chosen label never changes on detection; a detected one follows detection when it settles
     * on the other family; a guess becomes DETECTED as soon as detection settles, whether or not the family changes (the
     * store renames only, then). A recording stays a guess only when detection never settled.
     */
    public static ChargeSessionStore.Charger relabel(ChargeSessionStore.LabelSource source, ChargeSessionStore.Charger current, ChargeSessionStore.Charger detected) {
        if (source == null || source == ChargeSessionStore.LabelSource.CHOSEN || detected == null) return null;
        if (detected == current && source != ChargeSessionStore.LabelSource.GUESS) return null;
        return detected;
    }

    /** True for a live speed reading (received within AutoStartPolicy.SPEED_FRESH_MS) above END_SPEED_KMH. speedRaw is in tenths of km/h. */
    public static boolean movedAway(boolean connected, int speedRaw, long sampleMs, long nowMs) {
        return connected && sampleMs > 0 && nowMs >= sampleMs && nowMs - sampleMs <= AutoStartPolicy.SPEED_FRESH_MS
                && speedRaw / 10.0 > END_SPEED_KMH;
    }

    /** How long a recording waits after the charger stops below 99% with no schedule holding current off. */
    public static long chargerOffWaitMs(boolean automatic) {
        return automatic ? CHARGE_OFF_END_MS : ChargeSessionStore.CHARGER_PAUSE_MAX_MS;
    }

    /**
     * Whether charging off for offMs ends the recording. Volt's own charging schedule holding the current off never
     * does: the recording waits for the planned start, as a manual one does.
     */
    public static boolean chargeOffEnds(boolean automatic, boolean scheduleHolds, long offMs) {
        return !scheduleHolds && offMs >= chargerOffWaitMs(automatic);
    }

    /** Whether a finished automatic recording is kept. ranMs and gainedPoints are measured on its saved samples. */
    public static boolean keep(long ranMs, double gainedPoints) {
        return ranMs >= MIN_KEEP_MS && gainedPoints >= MIN_KEEP_POINTS;
    }

    /**
     * How many of the oldest automatic recordings an automatic start removes to stay within max saved recordings
     * (with the new one), or -1 when it cannot start because only manual recordings would have to go.
     */
    public static int automaticToRemove(int saved, int automatic, int max) {
        int need = saved - (max - 1);
        if (need <= 0) return 0;
        return automatic >= need ? need : -1;
    }

    /**
     * Whether a hand stop of this recording (Stop, or a ride started by hand) holds its scooter: an automatic one always;
     * one the owner started, while the switch is on (else nothing would start again anyway, and nothing is stored).
     */
    public static boolean holdsAfterHandStop(boolean enabled, boolean automatic) { return automatic || enabled; }

    /** Whether starting a ride by hand ends this charge recording instead of being refused. Manual ones still refuse. */
    public static boolean rideEndsRecording(boolean automatic) { return automatic; }

    /**
     * The charging reading as a start or a hold may use it: TRUE (charging) or FALSE (not charging) only while it is live
     * by a charge recording's own frame rule (ChargeSessionStore.fresh: at most MAX_FRAME_AGE_MS, 5 s, old on the elapsed
     * clock, never from the future), else null. Stricter than the reading's display rule (TelemetryHistory.freshnessMs
     * through BleController.readingFreshMs, 15 s or more), so a reading from before a sleep or a gap is never used, however
     * long the link itself is kept (#418). Any other raw value is null too.
     */
    public static Boolean liveCharging(ChargeSessionStore.Value state, long nowMono) {
        if (!ChargeSessionStore.fresh(state, nowMono)) return null;
        return state.value == 1 ? Boolean.TRUE : state.value == 0 ? Boolean.FALSE : null;
    }

    /**
     * Whether a reading clears holds and latches: only a fresh "not charging" reading does, and not while Volt itself holds
     * the current off (heldOff: a charging schedule, Charge done by or a schedule test pausing this charge,
     * ScooterService.scheduledChargePause). That pause reads exactly like an unplug, but the charger is still there.
     */
    public static boolean clearsHold(boolean fresh, double chargingState, boolean heldOff) { return fresh && chargingState == 0 && !heldOff; }

    /**
     * Whether an automatic start waits: a guided setting test is running, one has a write-back outstanding (its restore
     * would be refused while a recording runs), or a compatibility check is running (a recording starting cuts it short).
     * Waiting sets no latch: the start happens on its own once the work is over and the scooter still charges.
     */
    public static boolean waits(boolean guidedTest, boolean restoreOutstanding, boolean compatibilityCheck) {
        return guidedTest || restoreOutstanding || compatibilityCheck;
    }

    /**
     * Whether an unfinished guided test or try of the connected scooter (TestJournal, unresolved: its write may have been
     * sent) holds an automatic start, by its review now: every state but ALREADY_RESTORED, which has nothing left to write
     * back and resolves itself. WAITING and FIRMWARE_CHANGED can still lead to a restore, and a recording would refuse it;
     * dismissing the test releases the start.
     */
    public static boolean testHolds(GuidedTest.ReviewState review) { return review != GuidedTest.ReviewState.ALREADY_RESTORED; }

    /**
     * Whether a recording's label is a charger Volt may act on (estimates, Charge done by, learned curves): a manual
     * recording's (null source) and a chosen or detected one. A guess is not.
     */
    public static boolean countsAsCharger(ChargeSessionStore.LabelSource source) { return source != ChargeSessionStore.LabelSource.GUESS; }

    /** The held scooters stored in HOLD_KEY. */
    public static Set<String> holds(String stored) {
        Set<String> result = new LinkedHashSet<>();
        if (stored != null) for (String id : stored.trim().split(" +")) if (id.matches("[A-Za-z0-9_-]{1,128}")) result.add(id);
        return result;
    }

    public static String store(Set<String> holds) { return String.join(" ", holds); }
}
