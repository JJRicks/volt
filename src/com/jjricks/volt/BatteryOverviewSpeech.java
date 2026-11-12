package com.jjricks.volt;

/**
 * The Home battery overview card's spoken range part. The card draws its range line short (in some
 * languages the value alone, "cerca de 38 km") and hides that line from accessibility, so the card's
 * description says the slot again in a form that names the remaining range. Charging is spoken as drawn.
 */
final class BatteryOverviewSpeech {
    private BatteryOverviewSpeech() {}

    /** The slot as spoken: "Charging", "about 31 mi of range left", or empty when neither applies. */
    static String line(boolean charging, String distance) {
        return charging ? T.dashboardCharging() : distance.isEmpty() ? "" : T.dashboardRangeLeftSpoken(distance);
    }
}
