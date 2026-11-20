package com.jjricks.volt;

import java.util.Map;

/**
 * Home's estimate tip: one small, quiet slot under the charging view's time tile that suggests how to make the charge
 * time estimate better (owner, Nov 19 2026: "a subtle but noticeable UI element around the charge time estimation in
 * charging mode"). This class picks which tip shows, if any; DashboardScreen draws the chosen tip (its icon and its
 * label's full, short and icon-only rungs) and runs its tap. Pure Java, so the JVM suite tests every gate.
 *
 * <p>The tips, in the order they are tried (the first that applies shows; one at a time):
 * <ul>
 *   <li>{@link Tip#NO_RECORDINGS}, "Improve this estimate": the active scooter has no saved charge recording yet
 *   (manual or automatic, either charger) and none is running. Its tap opens a short sheet: record your charges for
 *   your own estimate first, then send them to Volt for your model (RecordTipSheet). It goes once a
 *   recording for this scooter is saved, and comes back if every recording is deleted.</li>
 *   <li>{@link Tip#OTHER_CHARGER} comes first: this charge's power is far off the only charger the scooter has recordings
 *   for (RecordChargerHint, through ScooterService.recordChargerHint). Its tap records this charge as the other charger,
 *   or files the running automatic recording under it.</li>
 * </ul>
 *
 * <p>Every tip needs the charging view's time on screen, and a tip the owner hid with its sheet's "Hide this tip" stays
 * hidden for good ({@link Tip#hiddenKey}, one app preference per tip, backed up with App preferences).
 *
 * <p>Adding a tip: a {@link Tip} value with its id, its rule in {@link #choose} (in its place in the order), its label's
 * rungs in HubText.estimateTip and estimateTipShort, its icon in {@link #icon}, and its action in DashboardScreen.openTip.
 * The slot (EstimateTipSlot), its motion, its narrow-label ladder and its hide preference come with it.
 */
final class ChargeEstimateTip {
    private ChargeEstimateTip() { }

    /** A tip the slot can show. Its id names its hide preference; never rename one once shipped. */
    enum Tip {
        NO_RECORDINGS("no_recordings"),
        /** "Different charger? Record it": this charge is far off the only recorded charger (RecordChargerHint). */
        OTHER_CHARGER("other_charger");

        final String id;
        Tip(String id) { this.id = id; }

        /** app_settings: TRUE once the owner hid this tip with "Hide this tip"; absent (or anything else) while it may show. */
        String hiddenKey() { return HIDDEN_PREFIX + id; }
    }

    /** The hide preferences' common start (BackupRegistry backs every one up as an App preference). */
    static final String HIDDEN_PREFIX = "estimate_tip_hidden_";
    /** View tags: the slot, its pill, and the record tip's sheet and its controls. */
    static final String TAG_SLOT = "home-estimate-tip", TAG_PILL = "home-estimate-tip-pill",
            TAG_SHEET = "estimate-tip-sheet", TAG_RECORD = "estimate-tip-record", TAG_AUTO = "estimate-tip-auto",
            TAG_OPEN = "estimate-tip-open", TAG_NOT_NOW = "estimate-tip-not-now", TAG_HIDE = "estimate-tip-hide";
    /** View tags: the charger tip's sheet and its main button (its Not now and Hide this tip keep the tags above). */
    static final String TAG_CHARGER_SHEET = "charger-tip-sheet", TAG_CHARGER_ACTION = "charger-tip-action";

    /**
     * The tip to show now, or null for none.
     *
     * @param timeShown the charging view is on screen with its charge time (Widgets' takeover or Clean's charging view)
     * @param recordingsSaved whether the active scooter has a saved charge recording: TRUE, FALSE, or null while that is
     *                        not known yet (no tip then: one that showed and went at once would only flicker)
     * @param recording a charge recording is running now
     * @param stored the app preferences (only each tip's {@link Tip#hiddenKey} is read); null reads as none stored
     */
    static Tip choose(boolean timeShown, Boolean recordingsSaved, boolean recording, Map<String, ?> stored) {
        return choose(timeShown, recordingsSaved, recording, false, stored);
    }

    /**
     * As above, with the charger tip first: otherCharger is ScooterService.recordChargerHint (this charge far off the only
     * recorded charger, sustained, with its own gates, a running automatic guess allowed). It comes before the record tip.
     */
    static Tip choose(boolean timeShown, Boolean recordingsSaved, boolean recording, boolean otherCharger, Map<String, ?> stored) {
        if (!timeShown) return null;
        if (otherCharger && !hidden(Tip.OTHER_CHARGER, stored)) return Tip.OTHER_CHARGER;
        if (Boolean.FALSE.equals(recordingsSaved) && !recording && !hidden(Tip.NO_RECORDINGS, stored)) return Tip.NO_RECORDINGS;
        return null;
    }

    /**
     * The tip Home shows: the charger tip when otherCharger holds and it isn't hidden, else chosen (the 4-argument
     * {@link #choose}'s answer). The same order as the 5-argument choose, for a caller that already has chosen.
     */
    static Tip withCharger(Tip chosen, boolean timeShown, boolean otherCharger, Map<String, ?> stored) {
        return timeShown && otherCharger && !hidden(Tip.OTHER_CHARGER, stored) ? Tip.OTHER_CHARGER : chosen;
    }

    /** Whether the owner hid this tip: only a stored TRUE hides it (a wrong type or damaged restore is no answer). */
    static boolean hidden(Tip tip, Map<String, ?> stored) {
        return stored != null && Boolean.TRUE.equals(stored.get(tip.hiddenKey()));
    }

    /**
     * Whether the active scooter has saved recordings, from what the charge store's last snapshot already holds: FALSE
     * when nothing is saved for any scooter; TRUE when the snapshot has this scooter's evidence (a learned curve or a
     * completed charge's gauge data); null when it can't tell (recordings are saved, but none taught this scooter
     * anything: they may be another scooter's, or this one's guessed or partial ones), and the caller counts this
     * scooter's recordings themselves.
     */
    static Boolean savedFromSnapshot(int savedSessions, boolean scooterEvidence) {
        if (savedSessions <= 0) return Boolean.FALSE;
        return scooterEvidence ? Boolean.TRUE : null;
    }

    /** The tip's icon, from the app's line icons (Ui.drawIcon). */
    static String icon(Tip tip) {
        switch (tip) {
            case NO_RECORDINGS: return "chart";
            case OTHER_CHARGER: return "bolt";
            default: return "chart";
        }
    }
}
