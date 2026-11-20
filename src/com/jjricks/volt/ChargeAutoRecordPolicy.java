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
 * Label: see {@link #label}, with {@link #detectedLabel} as its detected family. The recording keeps how its label was
 * chosen.
 *
 * End, for automatic recordings only, on top of every rule that ends a recording today:
 * - at once when a live speed reading is above {@link #END_SPEED_KMH} ({@link #movedAway});
 * - {@link #CHARGE_OFF_END_MS} after charging stops, unless Volt's own charging schedule holds the current off
 *   ({@link #chargerOffWaitMs}). Manual recordings keep the 30-minute charger-pause wait.
 *
 * Kept: an automatic recording that ran under {@link #MIN_KEEP_MS} or gained under {@link #MIN_KEEP_POINTS}
 * percentage points is discarded ({@link #keep}). Room: automatic and manual recordings are alike (owner, Nov 19 2026:
 * "auto and manual recordings could be classed the same in that way. rotate away."). Once a recording is saved, the
 * oldest go to keep the folder within ChargeSessionStore's count and budget, whichever kind they are; nothing saved
 * goes for an automatic recording that is discarded, since room is made only after it is kept. So an automatic start
 * never finds the folder full.
 *
 * Waits: an automatic start never arrives in the middle of other work a recording would block or cut short: a guided
 * setting test (running, or with a write-back outstanding) or a compatibility check ({@link #waits}). It starts by itself
 * once that is over, if the charge is still on.
 *
 * What a label stands for: a chosen, detected or setting label is the charger in effect while recording, and only those
 * teach a charger's curve ({@link #countsAsCharger}); the curve is the label the recording ends with. A guess labels the
 * file and nothing else.
 *
 * Hold: stopping a recording by hand (Stop on the page or in the notification, or starting a ride by hand) holds that
 * scooter until a fresh "not charging" reading is seen while Volt is not holding the current off itself (a
 * schedule or Charge done by pause is not an unplug; {@link #clearsHold}), across reconnects and restarts: an automatic recording always,
 * and one the owner started while the switch is on, so Volt never starts recording the charge the owner just stopped
 * recording (owner's answer 4: a manual stop holds until the next plug-in). The service
 * stores the held scooters ({@link #HOLD_KEY}). A recording that ended by itself sets no hold. A start that failed
 * (storage) latches that scooter in memory by the same rule, so it is not retried on every reading.
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

    /** The store's keep rule for automatic recordings. Manual recordings never consult it. */
    public static final ChargeSessionStore.AutomaticRules RULES = ChargeAutoRecordPolicy::keep;

    public static final class Label {
        final ChargeSessionStore.Charger charger; final ChargeSessionStore.LabelSource source;
        /** The charger setting at the start (null: Automatic), kept in the recording's id for audit. */
        final ChargeSessionStore.Charger setting;
        Label(ChargeSessionStore.Charger charger, ChargeSessionStore.LabelSource source, ChargeSessionStore.Charger setting) {
            this.charger = charger; this.source = source; this.setting = setting;
        }
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
     * The charger an automatic recording starts labelled with: Volt's detection for this charge when it has settled
     * (DETECTED), since it reads the charger actually plugged in; otherwise the charger chosen in Estimates & charger
     * (SETTING), provisional until detection settles ({@link #relabel}); otherwise a best guess (GUESS). The guess is the
     * family with more qualified saved recordings for this scooter (the charger the owner actually uses), and on a tie or
     * none, the stock charger every scooter ships with. setting is the charger setting (null: Automatic), detected
     * {@link #detectedLabel}'s family (null: neither detection nor, under Automatic, the learned curves have settled).
     */
    public static Label label(ChargeSessionStore.Charger setting, ChargeSessionStore.Charger detected, int stockRecordings, int fastRecordings) {
        return label(setting, detected, stockRecordings, fastRecordings, false);
    }

    /**
     * As above, with the first charge as a baseline (owner, Nov 19 2026: "a user records a charge, that's the baseline…
     * next time with the high speed, that records as a separate curve"). baseline: the scooter's model has no charger
     * detection (ChargerDetection.supports false) and its saved recordings are known (a snapshot has loaded). Then, under
     * Automatic with nothing detected or matched and no recording of either charger that teaches a curve, the recording is
     * labelled the stock charger as DETECTED: Volt's own choice, so it teaches that curve, and the next charges match it
     * by power (LearnedChargerMatch); a charger far off it gets the hint, never a label. The audit's detected charger
     * stays null, as for a learned match. On a model with detection (the UT5 MAX) the recording keeps today's guess, so
     * its physical rules name the charger. Accepted: a first charge on a high-speed charger is filed as stock on these
     * models; the curve still matches that charger's power there, since no bundled reference stands behind the name.
     */
    public static Label label(ChargeSessionStore.Charger setting, ChargeSessionStore.Charger detected, int stockRecordings, int fastRecordings,
            boolean baseline) {
        if (detected != null) return new Label(detected, ChargeSessionStore.LabelSource.DETECTED, setting);
        if (setting == null && baseline && stockRecordings == 0 && fastRecordings == 0)
            return new Label(ChargeSessionStore.Charger.STOCK, ChargeSessionStore.LabelSource.DETECTED, null);
        if (setting != null) return new Label(setting, ChargeSessionStore.LabelSource.SETTING, setting);
        return new Label(fastRecordings > stockRecordings ? ChargeSessionStore.Charger.HIGH_SPEED : ChargeSessionStore.Charger.STOCK,
                ChargeSessionStore.LabelSource.GUESS, null);
    }

    /**
     * The family automatic recordings take as DETECTED ({@link #label} and {@link #relabel}'s detected), or null.
     * ChargerDetection's settled family first (detection: it rests on what each charger can physically deliver, and
     * outranks the setting, as since #555). Then, only under Automatic (setting null), the family LearnedChargerMatch
     * settled on: this charge's power matches that family's own learned curve. Nothing else: a charge far off the only
     * recorded family (RecordChargerHint) suggests recording it, but labels nothing, since a throttled charge can look
     * like the other charger. A likeness never replaces the charger setting, and a label the owner chose is never
     * replaced (relabel). With neither, the recording keeps the setting's label or its guess.
     */
    public static ChargeSessionStore.Charger detectedLabel(ChargeSessionStore.Charger setting, ChargeSessionStore.Charger detection,
            ChargeSessionStore.Charger learned) {
        if (detection != null) return detection;
        return setting == null ? learned : null;
    }

    /**
     * The charger a running automatic recording is relabelled to, as DETECTED, or null to keep its label. The source
     * follows what is known: a label the owner chose during the recording (CHOSEN) never changes on detection; a detected
     * one follows detection when it settles on the other family; the charger setting's label and a guess become DETECTED
     * as soon as detection settles, whichever family it is (the store renames only when the family is the same). A
     * recording keeps the setting's label or a guess only when detection never settled (another model, a charge first
     * seen near full, too short).
     */
    public static ChargeSessionStore.Charger relabel(ChargeSessionStore.LabelSource source, ChargeSessionStore.Charger current, ChargeSessionStore.Charger detected) {
        if (source == null || source == ChargeSessionStore.LabelSource.CHOSEN || detected == null) return null;
        if (detected == current && source == ChargeSessionStore.LabelSource.DETECTED) return null;
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
     * recording's (null source) and a chosen, detected or setting one. A guess is not.
     */
    public static boolean countsAsCharger(ChargeSessionStore.LabelSource source) { return source != ChargeSessionStore.LabelSource.GUESS; }

    /**
     * Whether the owner may choose the charger now. The lock during a recording is for a label the owner chose: a manual
     * recording's (null source), an automatic CHOSEN one, or one that has the charger setting's label (SETTING: the
     * setting is the owner's choice, and detection may still replace it). An automatic recording labelled by a guess or by
     * detection takes the owner's choice, which relabels it CHOSEN; from then on detection never changes it (relabel).
     */
    public static boolean choiceAllowed(boolean recording, ChargeSessionStore.LabelSource source) {
        return !recording || source == ChargeSessionStore.LabelSource.GUESS || source == ChargeSessionStore.LabelSource.DETECTED;
    }

    /** The held scooters stored in HOLD_KEY. */
    public static Set<String> holds(String stored) {
        Set<String> result = new LinkedHashSet<>();
        if (stored != null) for (String id : stored.trim().split(" +")) if (id.matches("[A-Za-z0-9_-]{1,128}")) result.add(id);
        return result;
    }

    public static String store(Set<String> holds) { return String.join(" ", holds); }
}
