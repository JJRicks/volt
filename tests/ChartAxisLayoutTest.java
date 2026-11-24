package com.jjricks.volt;

import java.util.Arrays;

/** The ride graph's value-axis strip (ChartAxisLayout): fitted columns and a budget that grows with the chart. */
public final class ChartAxisLayoutTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        float d = 2.625f; // 420 dpi
        // Owner's case (README #613): Power, Wheel speed, Battery % and Elevation, four units.
        float power = ChartAxisLayout.column(24 * d, d, 48 * d), speed = ChartAxisLayout.column(17 * d, d, 48 * d),
                battery = ChartAxisLayout.column(16 * d, d, 48 * d), elevation = ChartAxisLayout.column(20 * d, d, 48 * d),
                distance = ChartAxisLayout.column(18 * d, d, 48 * d);
        float[] four = {power, speed, battery, elevation}, five = {power, speed, battery, elevation, distance};
        float[] wide = {48 * d, 48 * d, 48 * d, 48 * d};
        // Before: a 48 dp column each and at most 144 dp, so the fourth axis was behind the fade at every width.
        float before = ChartAxisLayout.strip(wide, Math.min(144 * d, 760 * d * .34f));
        check(ChartAxisLayout.shown(wide, before) == 3, "the old strip showed three of four: " + ChartAxisLayout.shown(wide, before));
        // Columns fit their labels, between 30 dp and the set width.
        check(Math.abs(power - 38 * d) < .01 && Math.abs(battery - 30 * d) < .01, "fitted columns: " + power / d + " " + battery / d);
        check(ChartAxisLayout.column(0, d, 48 * d) == 30 * d, "the narrowest column keeps room for the unit and line");
        check(ChartAxisLayout.column(80 * d, d, 48 * d) == 48 * d, "never wider than the set width");
        check(ChartAxisLayout.column(Float.NaN, d, 48 * d) == 30 * d, "a label that can't be measured gets the narrowest column");
        // The unfolded Fold's expanded graph (~760 dp) shows all four, and all five with distance.
        float foldExpanded = ChartAxisLayout.budget(760 * d);
        check(ChartAxisLayout.shown(four, ChartAxisLayout.strip(four, foldExpanded)) == 4, "Fold expanded: four of four");
        check(ChartAxisLayout.shown(five, ChartAxisLayout.strip(five, foldExpanded)) == 5, "Fold expanded: five of five");
        // The Fold's detail pane (~440 dp) shows the four; the phone's card (~355 dp) shows three, the rest scroll.
        check(ChartAxisLayout.shown(four, ChartAxisLayout.strip(four, ChartAxisLayout.budget(440 * d))) == 4, "Fold pane: four");
        int phone = ChartAxisLayout.shown(four, ChartAxisLayout.strip(four, ChartAxisLayout.budget(355 * d)));
        check(phone == 3, "phone card: three, the fourth a drag away: " + phone);
        // The plot keeps two thirds at any width.
        for (float width : new float[]{220, 355, 412, 760, 1200}) {
            float strip = ChartAxisLayout.strip(five, ChartAxisLayout.budget(width * d));
            check(strip <= width * d * ChartAxisLayout.SHARE + .5f || strip == maxOf(five), "the strip stays within a third at " + width + " dp");
        }
        // Whole columns only, but always the widest one (so a scrolled-to axis fits), and never more than all of them.
        check(ChartAxisLayout.strip(four, 10) == maxOf(four), "a tiny budget still fits the widest column");
        check(ChartAxisLayout.strip(four, 10000) == sum(four), "a large budget is all of them, no empty room");
        check(ChartAxisLayout.strip(new float[0], 500) == 0 && ChartAxisLayout.shown(new float[0], 0) == 0, "no axes: no strip");
        check(ChartAxisLayout.strip(new float[]{100, 100, 100}, 250) == 200, "two whole columns, not two and a half");
        check(ChartAxisLayout.budget(Float.NaN) == 0 && ChartAxisLayout.budget(-5) == 0, "an unmeasured chart has no budget");
        check(Arrays.equals(four, new float[]{power, speed, battery, elevation}), "inputs untouched");
        System.out.println("ChartAxisLayoutTest: " + checks + " assertions passed");
    }
    static float sum(float[] values) { float s = 0; for (float v : values) s += v; return s; }
    static float maxOf(float[] values) { float m = 0; for (float v : values) m = Math.max(m, v); return m; }
}
