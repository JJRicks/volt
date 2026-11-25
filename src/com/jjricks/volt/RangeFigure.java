package com.jjricks.volt;

import java.util.Locale;

/**
 * The range figure every glanceable range shows, from the remaining_range_km reading (ScooterService.dashboardReadings,
 * which follows the range source: RangeSource): whole numbers in the rider's units, "31 mi", "50 km" or "31 mi / 50 km"
 * with both. The parked battery card's "about 31 mi left" (DashboardScreen.rangeText) and Clean Home's recording moment's
 * "≈ 31 mi" (HubText.recordingRangeValue) both take it from here, so the two always show the same figure. A reading with
 * text but no number shows its text, in the rider's units where it names a metric quantity, as the parked card always
 * has. No catalog text of its own; the numbers and units come from Fmt.
 */
final class RangeFigure {
    private RangeFigure() {}

    /** The figure for a reading: its number in the units, else its text (null for no reading). */
    static Formatted of(Double km, String text, String units) {
        if (km != null && Double.isFinite(km)) return of(km, units);
        return text == null ? null : Formatted.plain(DisplayUnits.metricText(text, units));
    }

    /** A remaining range in km as the units show it, rounded to whole numbers (Fmt.decimal, half up). */
    static Formatted of(double km, String units) {
        Formatted metric = Fmt.quantity(Fmt.decimalNumber(Locale.US, km, 0), Fmt.unit(UnitCode.KM));
        if ("km".equals(units)) return metric;
        Formatted miles = Fmt.quantity(Fmt.decimalNumber(Locale.US, km / RideStore.KM_PER_MILE, 0), Fmt.unit(UnitCode.MI));
        return "dual".equals(units) ? Fmt.dual(miles, metric) : miles;
    }

    /** The first unit alone, where both units do not fit (units set to both: miles; otherwise as of). */
    static Formatted primary(double km, String units) {
        return of(km, "dual".equals(units) ? "mi" : units);
    }
}
