package com.jjricks.volt;

/**
 * Where Home's range figure comes from, per scooter (range_source_&lt;profile id&gt; in app_settings, "scooter" or
 * "learned"; a local choice that never writes the scooter's firmware range mode): the scooter's own remaining range, or
 * Volt's estimate learned from this scooter's past rides at the current battery reading. Pure Java, so the choice every
 * glanceable range shows (ScooterService.dashboardReadings: the parked card's "about 31 mi left", Battery &amp; charging's
 * summary and Clean Home's recording moment) is tested as it runs (the recording moment's range follows this setting
 * exactly as the parked card does).
 */
final class RangeSource {
    /** The stored values. */
    static final String SCOOTER = "scooter", LEARNED = "learned";

    private RangeSource() {}

    /** The preference that holds a scooter's choice. */
    static String key(String profileId) { return "range_source_" + profileId; }

    /** Whether a scooter (its id; "" for none) shows Volt's learned range: only a stored "learned" says so. */
    static boolean learned(String profileId, String stored) {
        return profileId != null && !profileId.isEmpty() && LEARNED.equals(stored);
    }

    /**
     * The learned figure (km) glanceable UI shows in place of the scooter's own remaining_range_km, or null to keep the
     * scooter's: only when the scooter's choice is Volt's learned range, the readings are real (never in simulation),
     * there is a battery reading (batteryPercent) and a learned figure for it (learnedKm, from
     * LearnedRangeEstimate.Result.remainingKm). Without a learned figure the scooter's own reading stays: the learned
     * choice falls back, as the parked card always has. ScooterService dates the figure as the battery reading.
     */
    static Double learnedKm(boolean learned, boolean simulating, Double batteryPercent, Double learnedKm) {
        if (!learned || simulating || batteryPercent == null || learnedKm == null || !Double.isFinite(learnedKm)) return null;
        return learnedKm;
    }
}
