package com.jjricks.volt;

/**
 * The connection notice (id 71) as a Live Update beside the live charging card (owner's picks, Nov 24 2026): the ride
 * recording's notice asks Android 16 to promote it, its status-bar chip the ride's running clock, and while a ride
 * records or the live charging card shows, the notice goes on Volt's quiet Rides and charging channel. Pure and
 * Android-free; the service builds the notice and calls the platform.
 *
 * <p>The quiet channel is Android's Default importance with no sound, no vibration and no lights, so nothing ever
 * sounds or buzzes, and it never pops up over other apps (that takes High). Default rather than the connection
 * channel's Low: on a Pixel a Low channel's Live Update never shows on the lock screen, and on Android 8 to 15 the
 * cards sit above the Silent section. The everyday "Scooter connected" notice stays on the Low connection channel.
 *
 * <p>A ride's Live Update follows Android's rules for one: ongoing, a title, no style of its own (the plain notice,
 * which Android accepts), no custom views, not colorized, not a group summary. Its chip is the chronometer the notice
 * already carries (setUsesChronometer from the ride's start), so it counts up without a repost and has no short text.
 */
public final class LiveNotices {
    private LiveNotices() {}

    /** Volt's everyday connection channel (Low importance). */
    public static final String SCOOTER_CHANNEL = "scooter";
    /** The quiet channel for a recording ride and the live charging card: Default importance, silent. */
    public static final String QUIET_CHANNEL = "rides_charging";

    /**
     * The channel the connection notice posts on. ride: the notice is a recording ride's (its clock, its error or its
     * sensor warning, with Stop recording); card: it is the live charging card. quietOpen: the quiet channel isn't
     * turned off in Android's settings. With it turned off the notice goes back to the connection channel, where it
     * was before, so a ride's Stop recording and the card's Disconnect never disappear with it.
     */
    public static String channel(boolean ride, boolean card, boolean quietOpen) {
        return (ride || card) && quietOpen ? QUIET_CHANNEL : SCOOTER_CHANNEL;
    }

    /**
     * Whether the live card (and a ride's notice) can show, the gate for the live charging hold, its Battery row and
     * sheet warning and a charge recording's start (#645 F03): app notifications on, and the channel the notice will
     * post on (channel()) open. That is the quiet channel when it is open, else the connection channel, so turning off
     * only "Scooter connection & recording" leaves the card showing on "Rides and charging".
     */
    public static boolean cardCanShow(boolean appEnabled, boolean quietOpen, boolean scooterOpen) {
        return appEnabled && (QUIET_CHANNEL.equals(channel(false, true, quietOpen)) ? quietOpen : scooterOpen);
    }

    /**
     * Whether the ride's notice asks Android to promote it: Android 16 or later with a promotion request and Live
     * Updates allowed for Volt (as the live card, ChargeLiveUpdate.promote), and the notice shows the ride's running
     * clock, which is the chip (a phone sensor warning keeps it). A notice with a recording error in place of the clock
     * stays plain.
     */
    public static boolean promoteRide(int sdk, boolean requestAvailable, boolean allowed, boolean rideClock) {
        return sdk >= ChargeLiveUpdate.SDK && requestAvailable && allowed && rideClock;
    }

    /**
     * A ride's Live Update that was swiped away (Android 16 lets the owner dismiss even a promoted foreground notice):
     * Volt doesn't put it back for the rest of that ride, as Android asks of a Live Update. Only that notice is held: each
     * built connection notice says whether it is the ride clock's Live Update, and the first one that isn't (a recording
     * error, a phone sensor warning, the plain notice with Live updates off, or the ride ended) posts and clears the mark
     * (#644 F08). Anything that shows the notice again clears it too (reshown: opening Volt restarts the foreground notice,
     * #644 F06), so a notice that shows is never one whose updates are dropped. The mark lives only in the running service:
     * if Android restarts Volt's service during the ride, the notice comes back (#644 F04, documented). Not thread-safe;
     * the service calls it on its handler.
     */
    public static final class Dismissal {
        private boolean dismissed;

        /** The owner dismissed the ride's notice (its delete intent). */
        public void dismissed() { dismissed = true; }

        /**
         * For each built connection notice: true when this post is held back. holdable: the notice is the ride clock's
         * Live Update (LiveNotices.promoteRide, asked); any other notice is never held and ends the hold.
         */
        public boolean holds(boolean holdable) {
            if (!holdable) dismissed = false;
            return dismissed;
        }

        /** The notice was shown again some other way (startForeground): the hold is over. */
        public void reshown() { dismissed = false; }

        public boolean active() { return dismissed; }
    }
}
