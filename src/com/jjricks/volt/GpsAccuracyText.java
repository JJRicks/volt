package com.jjricks.volt;

/**
 * GPS accuracy's text, shared by the ride graph and riding mode: the value as "±12 ft", the graph's axis marks
 * (whole numbers, the top one "≥40" when a few worse fixes draw at the top edge) and riding mode's plain states.
 */
final class GpsAccuracyText {
    private GpsAccuracyText() { }

    /** A shown accuracy, already in its unit ("ft" or "m"), as "±12 ft": a whole number, never ±0 for a real radius. */
    static Formatted value(double shown, String unit) {
        return Fmt.quantity(Fmt.compose(T.READINGS_ACCURACY_VALUE, Fmt.integerNumber(GpsAccuracy.whole(shown))), Fmt.unitText(unit));
    }

    /** The graph's sample text, seen at the cursor and heard in the chart's description: "±12 ft". */
    static String sample(RideChartData.Series series, double shown) {
        return value(shown, series.unit).toString();
    }

    /** An axis mark: a whole number, and the top one reads "≥40" when values above the scale draw at its top edge. */
    static String axis(RideChartData.Series series, double value) {
        Formatted number = Fmt.integerNumber(Math.round(Math.max(0, value)));
        boolean top = value >= series.scale.max - Math.abs(series.scale.max) * 1e-9;
        return top && series.clipsAbove() ? Fmt.compose(T.READINGS_CHART_AXIS_AT_LEAST, number).toString() : number.toString();
    }

    /** Riding mode's value in place of a number: not recording, off, waiting, or no fix (FIX shows value()). */
    static String state(GpsAccuracy.State state) {
        switch (state) {
            case NOT_RECORDING: return T.ridingGpsNotRecording();
            case OFF: return T.commonOff();
            case WAITING: return T.ridingGpsWaiting();
            default: return T.ridingGpsNoFix();
        }
    }
}
