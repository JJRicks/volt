package com.jjricks.volt;

/**
 * The value axes' strip at the left of a chart (RideChartView): how wide each axis column is and how many show at once.
 * Axes that don't fit stay reachable by dragging the strip, behind a fade. Widths are pixels; density is pixels per dp.
 * Pure: no Android, so tests/ChartAxisLayoutTest checks it.
 */
final class ChartAxisLayout {
    private ChartAxisLayout() { }

    /** The share of the chart's width the strip may take: the plot always keeps two thirds. */
    static final float SHARE = .34f;
    /** The narrowest column, so a one-digit axis keeps room for its unit and line. */
    static final float MIN_COLUMN_DP = 30;
    /** Room around a column's labels: the gap from the axis to its left and the axis line's own place. */
    static final float LABEL_ROOM_DP = 14;

    /** The strip's budget: a third of the chart's width, however wide, so a wide window shows more axes. */
    static float budget(float chartWidth) {
        return Float.isFinite(chartWidth) && chartWidth > 0 ? chartWidth * SHARE : 0;
    }

    /**
     * A column's width for its widest label (the five marks and the unit), between MIN_COLUMN_DP and max: an axis of
     * short numbers ("100", "%") takes less room than one of long ones ("-1500", "Wh/mi"), never more than max.
     */
    static float column(float widestLabel, float density, float max) {
        float wanted = (Float.isFinite(widestLabel) ? Math.max(0, widestLabel) : 0) + LABEL_ROOM_DP * density;
        return Math.min(max, Math.max(MIN_COLUMN_DP * density, wanted));
    }

    /**
     * The strip's width: the whole columns that fit the budget, from the first; never less than the widest column, so
     * any axis can be scrolled fully into view; never more than all of them.
     */
    static float strip(float[] columns, float budget) {
        float total = 0, widest = 0, complete = 0;
        for (float width : columns) {
            total += width; widest = Math.max(widest, width);
            if (total <= budget + .5f) complete = total;
        }
        return Math.min(total, Math.max(widest, complete));
    }

    /** How many whole columns the strip shows before any dragging. */
    static int shown(float[] columns, float strip) {
        float total = 0; int count = 0;
        for (float width : columns) { total += width; if (total > strip + .5f) break; count++; }
        return count;
    }
}
