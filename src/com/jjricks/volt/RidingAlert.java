package com.jjricks.volt;

/**
 * A battery alert that fired while riding mode was open, as riding mode's banner shows it (RidingAlertBanner)
 * instead of a dialog over the speed (tester feedback, Nov 14 2026). ScooterService keeps the one showing, so a
 * rebuild or a recreated activity shows it again; a newer alert replaces it, so banners never stack.
 *
 * While the alarm sounds the banner stays, with Stop sound. An alert that sounded hides once the sound ends (Stop
 * sound, or the alarm's own timeout); a silent one hides by itself once it has been in view for SILENT_MS. Time
 * under the riding settings sheet, a dialog or another window doesn't count (Seen). Pure, so the host tests run it.
 */
final class RidingAlert {
    /** How long a silent alert's banner shows. */
    static final long SILENT_MS = 10_000L;

    /** Which alert, and the level it was set for (the low level, the charge level, or 100). */
    final ChargeAlertPolicy.Event event;
    final int level;
    /** One number per alert the banner took, so a close meant for an older alert never closes a newer one. */
    final long id;
    /** How long it has been in view so far (Seen), and whether the alarm was sounding when the banner took it. */
    final long seenMs;
    final boolean sounded;
    /** The alert's sentence ("My scooter is at 29%."), rendered when this was read, for TalkBack. */
    final String text;

    RidingAlert(ChargeAlertPolicy.Event event, int level, long id, long seenMs, boolean sounded, String text) {
        this.event = event;
        this.level = level;
        this.id = id;
        this.seenMs = seenMs;
        this.sounded = sounded;
        this.text = text == null ? "" : text;
    }

    /**
     * Whether the banner shows. Always while the alarm sounds, so a sounding alarm always has its Stop sound. Once
     * an alarm that sounded has ended it never shows again; a silent alert shows until it has been in view SILENT_MS.
     */
    boolean shown(boolean sounding) {
        if (sounding) return true;
        return !sounded && seenMs < SILENT_MS;
    }

    /** For a silent banner: how much of its time in view is left, 0 once it is used up. */
    long silentLeft() {
        return sounded || seenMs >= SILENT_MS ? 0 : SILENT_MS - seenMs;
    }

    /**
     * A banner's time in view: it counts only while riding mode is in front with nothing over it (window focus), so
     * an alert that fires under the settings sheet, or a dialog, still gets its ten seconds once that closes.
     */
    static final class Seen {
        private long ms, lastMs = -1;

        /** One look at nowMs (elapsedRealtime): adds the time since the last look if both were in view. */
        long tick(long nowMs, boolean inView) {
            if (!inView) lastMs = -1;
            else {
                if (lastMs >= 0 && nowMs > lastMs) ms += nowMs - lastMs;
                lastMs = nowMs;
            }
            return ms;
        }
    }

    /** The banner's title rungs, longest first (the narrow-label ladder); the icon alone is the last step. */
    String[] titles() {
        String level = Fmt.integer(this.level);
        if (event == ChargeAlertPolicy.Event.LOW) return new String[]{T.ridingAlertLow(level), T.ridingAlertLowShort(level)};
        if (event == ChargeAlertPolicy.Event.CHARGE) return new String[]{T.ridingAlertCharged(level), T.ridingAlertChargedShort()};
        if (event == ChargeAlertPolicy.Event.FULL) return new String[]{T.ridingAlertFull(), T.ridingAlertFullShort()};
        // Paused and time-limit alerts never fire in sight; their notice title stands in should one ever arrive.
        return new String[]{ChargeAlertPolicy.alertTitle(event, this.level)};
    }

    /** What TalkBack reads when the banner appears: the full title and the alert's sentence. */
    String spoken() {
        return T.ridingAlertSpoken(titles()[0], text);
    }

    /** What leaving riding mode does with its banner's alarm (ScooterService.ridingModeClosed). */
    enum Leave { NOTHING, NOTICE, STOP }

    /**
     * Riding mode closed while its banner showed (showing): the alert counts as shown and no dialog follows, and a
     * sounding alarm keeps its own timeout, with the alert notice's Stop to end it sooner. Where Android shows Volt no
     * notice, nothing would be left to stop it, so it stops now.
     */
    static Leave leave(boolean showing, boolean sounding, boolean noticesAllowed) {
        if (!showing || !sounding) return Leave.NOTHING;
        return noticesAllowed ? Leave.NOTICE : Leave.STOP;
    }

    /**
     * Whether the banner sits over the top bar (true) or on its own row under it. Over the bar it covers only the
     * connected status pill, and the tune disc and moon when no warning chip shows: never the warning chip, and never
     * a Reconnecting or Not connected pill, so with the scooter not connected it always takes its own row. Over the bar
     * it also needs room (pixels, between back and the chip or the bar's end) for at least its short title and button.
     */
    static boolean inline(boolean connected, int room, int needed) {
        return connected && room >= needed;
    }

    /** The button's rungs while the alarm sounds, longest first. */
    static String[] stopLabels() {
        return new String[]{T.ridingAlertStop(), T.ridingAlertStopShort()};
    }
}
