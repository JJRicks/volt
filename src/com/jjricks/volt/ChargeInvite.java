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
}
