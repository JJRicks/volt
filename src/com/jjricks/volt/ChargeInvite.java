package com.jjricks.volt;

/**
 * Battery & charging's invite to record charges and send them to Volt (owner, Nov 17 2026: "want to help make your
 * scooter's charge estimation time more accurate?"): a banner at the top of the page that opens a sheet explaining it
 * (ChargeInviteSheet). Recordings help tune the charge time estimate for every model, so it shows on every model,
 * whenever a scooter is chosen (a recording needs one).
 *
 * <p>The rule: it shows until the person closes it with its X ({@link #DISMISSED}) or emails their recordings to Volt
 * with Email to Volt ({@link #SENT}: it did its job), and then never again. The answer is one app preference that
 * travels with a backup (BackupRegistry), so a restored phone doesn't ask again. Closing the sheet, or tapping Not now
 * in it, keeps the banner. Pure, so the JVM suite tests it.
 */
final class ChargeInvite {
    private ChargeInvite() { }

    /** app_settings: the person's answer, {@link #DISMISSED} or {@link #SENT}; absent while the banner still shows. */
    static final String KEY = "charge_invite";
    static final String DISMISSED = "dismissed", SENT = "sent";
    /** View tags: the banner, its X, and the sheet's body and its controls. */
    static final String TAG_BANNER = "battery-charge-invite", TAG_HIDE = "battery-charge-invite-hide",
            TAG_SHEET = "charge-invite-sheet", TAG_RECORD = "charge-invite-record", TAG_AUTO = "charge-invite-auto",
            TAG_OPEN = "charge-invite-open", TAG_NOT_NOW = "charge-invite-not-now";
    /**
     * The anchor the sheet's Record this charge opens Charge history &amp; recordings with (HubNav.Args.anchor): the page
     * brings its Record this charge row on screen and starts it when a recording can start now, else flashes the row,
     * whose status line says why not (MoreScreen.openHeld). Not a view tag, so no search result ever names it.
     */
    static final String RECORD_ANCHOR = "charge-invite-record-this";

    /**
     * Whether the banner shows: a scooter is chosen and the stored answer is neither {@link #DISMISSED} nor {@link #SENT}.
     * Anything else stored (a wrong type, an unknown word from a newer Volt) is no answer, so the banner shows.
     */
    static boolean shows(boolean scooterChosen, Object stored) {
        return scooterChosen && !answered(stored);
    }

    /** Whether a stored value is an answer that hides the banner for good. */
    static boolean answered(Object stored) {
        return DISMISSED.equals(stored) || SENT.equals(stored);
    }

    /**
     * Email to Volt's hand-over to an email app, for the {@link #SENT} answer (#537 review, F03: backing out of Android's
     * email chooser marked the invite sent, because Volt stored SENT as soon as the chooser opened).
     *
     * <p>What Android lets an app know: nothing about whether the email was sent (ACTION_SEND has no result, and email
     * apps don't report one), and no callback from the "Open with" list Android shows when several email apps could take
     * it. What Volt can see is whether an app took the email: that app's screen covers Volt, so Volt stops. The "Open
     * with" list and the share sheet are see-through windows over Volt that only pause it, so backing out of either
     * resumes Volt without a stop, and the invite stays. So the answer is stored when Volt stops after handing the
     * email over (an app was picked, or the only email app opened), and dropped when Volt comes back without stopping.
     * A stop with the screen off, or for a configuration change, isn't an app taking the email: it waits. Two edges
     * remain, both rare: Home pressed while the list shows stores it (indistinguishable from an app opening), and an
     * email app opened beside Volt in split screen doesn't (Volt never stops); the banner's X still hides it then.
     * Pure, so the JVM suite tests it.
     */
    static final class HandOver {
        private static final int IDLE = 0, OPENED = 1, AWAY = 2;
        private int state = IDLE;

        /** The email was handed to Android (startActivity returned): an app or a chooser is opening. */
        void opened() { state = OPENED; }

        /** Volt paused: the chooser or the app is over it. */
        void paused() { if (state == OPENED) state = AWAY; }

        /**
         * Volt stopped: true when that means an app took the email, so the answer is stored (once). interactive: the
         * screen is on; changing: a configuration change is recreating Volt.
         */
        boolean stopped(boolean interactive, boolean changing) {
            if (state == IDLE || !interactive || changing) return false;
            state = IDLE;
            return true;
        }

        /** Volt is back on screen without having stopped: the chooser was closed and no app took the email. */
        void resumed() { if (state == AWAY) state = IDLE; }

        /** A hand-over is waiting for its answer. */
        boolean pending() { return state != IDLE; }
    }
}
