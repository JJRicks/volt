package com.jjricks.volt;

/**
 * What a ride Volt ended on its own says: the Ride saved automatically notice and the Saved
 * automatically pill's screen-reader text. Chosen from the stored stop (its token and parameters),
 * never from shown text. Rendered when called, in the current language.
 */
final class RideAutoStopText {
    private RideAutoStopText() {}

    /**
     * The notice's message. {@code ride} is the saved ride (null when it could not be read back, then
     * {@code reason} decides the wording); {@code shownEnd} is its shown end as a clock time, or null
     * or empty when the ride has no reading to end at.
     */
    static String notice(String reason, RideStore.Summary ride, CharSequence shownEnd) {
        boolean quiet = ride != null ? RideStore.STOP_AUTO_QUIET.equals(ride.stopReason) : RideStore.isQuietStop(reason);
        if (!quiet) return ride == null || ride.label.isEmpty() ? T.serviceRideBluetoothLost() : T.serviceRideBluetoothLostLabel(ride.label);
        boolean phone = ride != null && ride.stopPhoneBluetoothOff;
        boolean timed = ride != null && ride.stopLastReadingMonotonicMs >= 0 && shownEnd != null && shownEnd.length() > 0;
        if (!timed) return phone ? T.serviceRidePhoneBluetoothOff() : T.serviceRideQuietLost();
        if (ride.label.isEmpty()) return phone ? T.serviceRidePhoneBluetoothOffAt(shownEnd) : T.serviceRideQuietLostAt(shownEnd);
        return phone ? T.serviceRidePhoneBluetoothOffLabelAt(ride.label, shownEnd) : T.serviceRideQuietLostLabelAt(ride.label, shownEnd);
    }

    /** The Saved automatically pill's screen-reader text for an automatic stop. */
    static String description(RideStore.Summary ride) {
        if (ride == null || !RideStore.STOP_AUTO_QUIET.equals(ride.stopReason)) return T.ridesSavedAutomaticallyDescription();
        return ride.stopPhoneBluetoothOff ? T.ridesSavedAutomaticallyPhoneBluetoothDescription() : T.ridesSavedAutomaticallyQuietDescription();
    }
}
