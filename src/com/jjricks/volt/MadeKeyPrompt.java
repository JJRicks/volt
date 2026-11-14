package com.jjricks.volt;

import java.util.Locale;

/**
 * "Write down your owner key": the popup over setup's last page for a key Volt made (owner, 2026-11-14). A made key
 * (NewOwnerKey) exists only on this phone and in backup files, so once the scooter has accepted it, setup shows it
 * one time, big, a byte at a time, and asks the rider to write it down or save a backup file. Done waits
 * {@link #WAIT_SECONDS} seconds unless a backup holding the key was saved (KeyBackups).
 *
 * Shown once: setup's own preferences record the profile whose key was shown ({@link #SHOWN}), written only when the
 * rider dismisses the popup, so a process death or a new activity before that shows it again. The key is never logged,
 * and nothing here throws with the key in its message.
 */
final class MadeKeyPrompt {
    /** Setup's preference (OnboardingScreen.PREFS) holding the profile ID whose made key the rider has seen. */
    static final String SHOWN = "made_key_shown";
    /** How long Done stays disabled, unless a backup holding the key is saved first. */
    static final int WAIT_SECONDS = 10;

    private MadeKeyPrompt() { }

    /**
     * Whether setup's last page shows the popup: the profile holds the key this setup made, the scooter accepted it
     * (the profile has a saved scooter address, which Volt stores only after the owner check and a serial match), and
     * the rider hasn't dismissed it for this profile yet. Never for a captured, typed or imported key.
     */
    static boolean due(boolean madeHere, String profileId, String identity, String savedMac, String shownFor) {
        if (!madeHere || profileId == null || profileId.isEmpty() || bytes(identity) == null) return false;
        if (savedMac == null || savedMac.isEmpty()) return false;
        return !profileId.equals(shownFor);
    }

    /** What Go to Home (and Back) on setup's last page does: {@link #home}. */
    static final int HOME_NOW = 0, HOME_WAIT = 1, HOME_AFTER_PROMPT = 2;
    /** How long Go to Home waits for the profile before finishing setup without it. */
    static final long HOME_WAIT_MS = 4000;

    /**
     * Whether a made key's popup may still be owed before its profile can be read: setup made a key on the new-key
     * route ({@code generatedId}) and the rider hasn't dismissed its popup. Finishing setup clears setup's preferences,
     * this record among them, so while it holds, Go to Home first waits for the profile (home).
     */
    static boolean mayBeOwed(boolean newKeyRoute, String generatedId, String shownFor, boolean dismissedHere) {
        return newKeyRoute && !dismissedHere && generatedId != null && !generatedId.isEmpty() && !generatedId.equals(shownFor);
    }

    /**
     * Go to Home (and Back) on setup's last page. With the profile loaded: the popup first when it is due, and setup
     * finishes once it is dismissed; otherwise at once. Before that (the service not bound yet, after Volt reopened), a
     * popup that may be owed makes it wait, up to {@link #HOME_WAIT_MS}; after that, or when none can be owed, setup
     * finishes as it does when no profile exists. Never a wait with nothing owed, so no other setup waits at all.
     */
    static int home(boolean profileLoaded, boolean due, boolean mayBeOwed, long waitedMs) {
        if (profileLoaded) return due ? HOME_AFTER_PROMPT : HOME_NOW;
        return mayBeOwed && Math.max(0, waitedMs) < HOME_WAIT_MS ? HOME_WAIT : HOME_NOW;
    }

    /** Whole seconds Done still waits after {@code elapsedMs} on screen: WAIT_SECONDS down to 0. */
    static int secondsLeft(long elapsedMs) {
        long left = WAIT_SECONDS * 1000L - Math.max(0, elapsedMs);
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    /** Done (and Back) work once the wait is over, or at once after a backup holding the key was saved. */
    static boolean doneEnabled(long elapsedMs, boolean backupSaved) {
        return backupSaved || secondsLeft(elapsedMs) == 0;
    }

    /** The key on one row, upper-case, a space between bytes: "88 6B 33 AF 01 C2"; "" if it isn't a key. */
    static String oneRow(String identity) {
        int[] b = bytes(identity);
        return b == null ? "" : String.format(Locale.ROOT, "%02X %02X %02X %02X %02X %02X", b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /** The key on two rows of three bytes, for a window too narrow for one; never broken inside a byte. */
    static String twoRows(String identity) {
        int[] b = bytes(identity);
        return b == null ? "" : String.format(Locale.ROOT, "%02X %02X %02X%n%02X %02X %02X", b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /** One of the two rows, to measure the widest line (monospace, so both are the same width). */
    static String firstRow(String identity) {
        int[] b = bytes(identity);
        return b == null ? "" : String.format(Locale.ROOT, "%02X %02X %02X", b[0], b[1], b[2]);
    }

    /**
     * What TalkBack reads: each digit on its own, a pause between bytes ("8 8, 6 B, 3 3, …"), so a rider writing it
     * down hears every character; "" if it isn't a key.
     */
    static String spoken(String identity) {
        int[] b = bytes(identity);
        if (b == null) return "";
        return String.format(Locale.ROOT, "%X %X, %X %X, %X %X, %X %X, %X %X, %X %X",
                b[0] >> 4, b[0] & 15, b[1] >> 4, b[1] & 15, b[2] >> 4, b[2] & 15,
                b[3] >> 4, b[3] & 15, b[4] >> 4, b[4] & 15, b[5] >> 4, b[5] & 15);
    }

    /** The six bytes of a 12-hex-digit key (ASCII digits only), or null: never an exception holding the key. */
    private static int[] bytes(String identity) {
        if (identity == null || identity.length() != 12) return null;
        int[] result = new int[6];
        for (int i = 0; i < 12; i++) {
            char c = identity.charAt(i);
            int digit = c < 0x80 ? Character.digit(c, 16) : -1;
            if (digit < 0) return null;
            result[i / 2] = result[i / 2] << 4 | digit;
        }
        return result;
    }
}
