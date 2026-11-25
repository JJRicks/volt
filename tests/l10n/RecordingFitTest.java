package com.jjricks.volt;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clean Home's recording moment, the Ride clock (Ollie's mock-up, Nov 24 2026): RecordingFit's sizes for every window the
 * maintainers check (the mock-up's 390x844 dp phone, 344x882, 412x915, a short 360x640, a 280x640 phone, the wide
 * 725x604 two panes and tabletop's floor) at 1x, 1.3x and 1.4x text; the give-way order; the ribbon's trace from a real
 * RideStore recording (gaps, stops, missing speed, the scale's floor); the one recording time every place shows; and
 * RangeSource, the range choice ScooterService.dashboardReadings makes for the parked card and the moment alike.
 */
public final class RecordingFitTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        reference();
        windows();
        giveWay();
        pieces();
        trace();
        clock();
        rangeSource();
        rangeFigure();
        readings();
        longName();
        System.out.println("RecordingFitTest: " + checks + " checks");
    }

    /** The mock-up's phone, 390x844 dp at 1x: every part at the mock-up's size, 12 dp under the button. */
    private static void reference() {
        int area = TopArea.heightDp(390, 844, false, 1f);
        check(area == 342, "390x844 dp: the mock-up's 342 dp top area, got " + area);
        RecordingFit.Layout l = RecordingFit.layout(area, 358, 1f);
        check(l.head == 28 && l.clock == 76 && l.clockSize == 78 && l.ribbon == 92 && l.stats == 44 && l.button == 50,
                "390x844 at 1x: the mock-up's sizes " + describe(l));
        check(l.bottom == 12 && l.total() == area, "390x844 at 1x: the mock-up's 12 dp under the button " + describe(l));
    }

    /** Every checked window: nothing runs past the area, no band of 40 dp or more, the button a whole 48 dp target. */
    private static void windows() {
        int[][] phones = {{344, 882}, {390, 844}, {412, 915}, {360, 640}, {280, 640}, {411, 731}, {393, 852}};
        for (float text : new float[]{1f, 1.3f, 1.4f, 1.6f}) {
            for (int[] w : phones) {
                int area = TopArea.heightDp(w[0], w[1], false, text);
                fits(w[0] + "x" + w[1] + " at " + text + "x", area, Math.min(w[0] - 32, 560), text);
            }
            // The wide two-pane Home: the scooter pane (HubSplit) a little over 330 dp, the area a share of the height.
            boolean wide = WideLayout.wide(725, text);
            fits("725x604 at " + text + "x", TopArea.heightDp(725, 604, wide, text), wide ? 330 : 560, text);
            // Tabletop: the pane above the hinge, down to the area's floor.
            fits("tabletop at " + text + "x", TopArea.tabletopDp(TopArea.heightDp(790, 820, true, text), 150), 330, text);
            fits("the area's floor at " + text + "x", TopArea.FLOOR_DP, 300, text);
        }
        RecordingFit.Layout l = RecordingFit.layout(TopArea.heightDp(412, 915, false, 1f), 380, 1f);
        check(l.clockSize > 78 && l.clockSize <= 78 * RecordingFit.CLOCK_MAX_GROWTH && l.ribbon > 92,
                "412x915: the time grows with the area, as the parked card does, and the ribbon takes the rest " + describe(l));
        l = RecordingFit.layout(TopArea.heightDp(344, 882, false, 1f), 312, 1f);
        check(l.clockSize == 78 && l.ribbon >= RecordingFit.MIN_RIBBON_DP && l.bottom == 0,
                "344x882 at 1x: the time at full size, the ribbon shorter, the room under the button given first " + describe(l));
        l = RecordingFit.layout(TopArea.heightDp(344, 882, false, 1.3f), 312, 1.3f);
        check(l.ribbon == 0 && l.clockSize >= 78, "344x882 at 1.3x: the ribbon gives way before the time " + describe(l));
    }

    private static void fits(String name, int area, float content, float text) {
        RecordingFit.Layout l = RecordingFit.layout(area, content, text);
        check(l.total() <= area, name + ": the parts fit the " + area + " dp area " + describe(l));
        check(l.button >= 48 && l.head >= RecordingFit.HEAD_DP, name + ": the button keeps a 48 dp target, the head row its line " + describe(l));
        check(l.clockSize >= RecordingFit.CLOCK_FLOOR_SP - .01f, name + ": the time stays at or over its floor " + describe(l));
        // Above the time: its gap and the box's spare, then the figures' own room over their tops (Saira's ascent over its
        // cap height, about 0.43 of the size), all under 40 dp.
        float overTime = l.gap + l.spread + (l.clock - l.clockSize * RecordingFit.LINE_EM) / 2f + .435f * l.clockSize;
        check(l.bottom < 40 && l.clockSpare() < 40 && area - l.total() < 40 && l.gap + l.spread < 40 && l.gap >= RecordingFit.MIN_GAP_DP && overTime < 40,
                name + ": no band of 40 dp or more (" + overTime + " over the time) " + describe(l));
        check(l.ribbon == 0 || l.ribbon >= RecordingFit.MIN_RIBBON_DP, name + ": the ribbon shown at its least height or not at all " + describe(l));
        check(l.ribbon == 0 || l.clockSize == RecordingFit.clockDp(content, area, text), name + ": the time shrinks only once the ribbon is gone " + describe(l));
    }

    /** As the area gets shorter: the room under the button goes, then the ribbon shrinks and goes, then the time. */
    private static void giveWay() {
        RecordingFit.Layout before = null;
        boolean ribbonGone = false;
        for (int area = 420; area >= 180; area--) {
            RecordingFit.Layout l = RecordingFit.layout(area, 358, 1f);
            if (before != null) {
                // The room under the button only comes back where the ribbon goes, as the share of the room it leaves.
                // The room left over (under the button and shared into the gaps) only comes back where the ribbon goes.
                int spare = l.total() - l.head - l.clock - l.ribbon - l.stats - l.button;
                int spareBefore = before.total() - before.head - before.clock - before.ribbon - before.stats - before.button;
                check(l.ribbon <= before.ribbon && l.clockSize <= before.clockSize && l.gap <= before.gap
                        && (spare <= spareBefore || l.ribbon == 0 && before.ribbon > 0), area + " dp: nothing grows as the area shrinks " + describe(l));
                if (l.ribbon < before.ribbon && l.ribbon > 0 && l.ribbon < RecordingFit.RIBBON_DP) check(l.bottom == 0, area + " dp: the room under the button went first");
                if (l.clockSize < before.clockSize) check(l.ribbon == 0, area + " dp: the ribbon went before the time shrank");
            }
            ribbonGone |= l.ribbon == 0;
            before = l;
        }
        check(ribbonGone && before.clockSize < 78, "a short enough area shrinks the time too");
        int[] split = RecordingFit.split(500, 76, 39, 92, 56, 132, 10, 12);
        check(split[1] == 132 && split[3] == 500 - 86 - 132, "a tall area: the ribbon at its most, the rest under the button");
        split = RecordingFit.split(120, 76, 39, 92, 56, 132, 10, 12);
        check(split[0] == 76 && split[1] == 0 && split[2] == 0 && split[3] == 44, "no room for the ribbon: the time keeps its box, the rest is shared out");
        split = RecordingFit.split(20, 76, 39, 92, 56, 132, 10, 12);
        check(split[0] == 39, "never under the floor's box");
    }

    private static void pieces() {
        check(RecordingFit.startedFits(80, 120, 12, 300) && !RecordingFit.startedFits(80, 120, 12, 200), "Started stays only beside Recording on one line");
        check(!RecordingFit.startedFits(80, 0, 12, 300), "nothing to show: no Started");
        check(RecordingFit.rung(new float[]{90, 60, 40}, 100) == 0 && RecordingFit.rung(new float[]{90, 60, 40}, 70) == 1
                && RecordingFit.rung(new float[]{90, 60, 40}, 10) == 2 && RecordingFit.rung(new float[]{90}, 0) == 0, "the narrow-label ladder");
        check(RecordingFit.clockFit(78, 40, 200, 300) == 78 && RecordingFit.clockFit(78, 40, 400, 300) < 78
                && RecordingFit.clockFit(78, 40, 4000, 300) == 40, "the time shrinks to its width, not under its floor");
        check(RecordingFit.columns(716, 2f) == 179, "one trace point per 2 dp");
        check(RecordingFit.headDp(1.4f) > 28 && RecordingFit.statsDp(1.3f) > 44 && RecordingFit.buttonDp(1.4f) >= 50, "large text grows the rows");
    }

    /** A real recording: 6 min riding, a 2 min stop, a gap with no link, then riding again; read as the moment reads it. */
    private static void trace() throws Exception {
        File dir = Files.createTempDirectory("recording-fit").toFile();
        RideStore rides = new RideStore(dir);
        long wall = 1_700_000_000_000L, mono = 1_000_000L;
        String id = rides.start("scooter", "Synthetic ride clock", wall, mono);
        check(rides.trend(id, RideStore.MAX_TREND_POINTS).isEmpty() || RecordingFit.trace(rides.trend(id, RideStore.MAX_TREND_POINTS), wall, wall, 179).isEmpty(),
                "a brand-new recording draws no line");
        int t = 0;
        for (; t <= 360; t++) sample(rides, wall, mono, t, 20 + 8 * Math.sin(t / 30.0));
        for (; t <= 480; t++) sample(rides, wall, mono, t, 0);
        rides.markGap(wall + (t + 1) * 1000L, mono + (t + 1) * 1000L, "Bluetooth disconnected");
        t += 90;
        for (; t <= 840; t++) sample(rides, wall, mono, t, 22);
        List<RideStore.Point> points = rides.trend(id, RideStore.MAX_TREND_POINTS);
        RecordingFit.Trace trace = RecordingFit.trace(points, wall, wall + 845_000L, 179);
        check(trace.size() > 100 && trace.size() <= 179, "downsampled to at most one point per 2 dp: " + trace.size());
        int breaks = 0;
        for (int i = 1; i < trace.size(); i++) if (trace.breaks[i]) breaks++;
        check(breaks == 1, "the line breaks once, at the gap: " + breaks);
        boolean flat = false;
        for (int i = 0; i < trace.size(); i++) if (trace.x[i] > .45f && trace.x[i] < .55f && trace.y[i] == 0) flat = true;
        check(flat, "the stop lies on the baseline");
        check(trace.scaleKmh >= 27.9 && trace.scaleKmh <= 28.1, "the scale runs to the ride's fastest: " + trace.scaleKmh);
        check(trace.x[trace.size() - 1] > .98f && trace.y[trace.size() - 1] > .75f, "the line ends at now, at the last speed");
        for (int i = 1; i < trace.size(); i++) check(trace.x[i] > trace.x[i - 1], "time runs left to right");
        // A slow ride (a walk at 4 km/h) keeps the floor of the scale, so it is drawn low, not as tall spikes.
        String slow = null;
        rides.stop(wall + 900_000L, mono + 900_000L);
        slow = rides.start("scooter", "Synthetic walk", wall + 1_000_000L, mono + 1_000_000L);
        for (int s = 0; s <= 60; s++) sample(rides, wall + 1_000_000L, mono + 1_000_000L, s, 4);
        RecordingFit.Trace walk = RecordingFit.trace(rides.trend(slow, RideStore.MAX_TREND_POINTS), wall + 1_000_000L, wall + 1_060_000L, 100);
        check(walk.scaleKmh == RecordingFit.MIN_SCALE_KMH && walk.y[walk.size() - 1] <= .21f, "a slow ride keeps the 20 km/h scale");
        rides.stop(wall + 1_070_000L, mono + 1_070_000L);
        check(RecordingFit.trace(null, wall, wall + 1000, 50).isEmpty() && RecordingFit.trace(points, wall, wall + 1000, 0).isEmpty(), "no points, no line");
    }

    private static void sample(RideStore rides, long wall, long mono, int second, double kmh) throws Exception {
        long at = mono + second * 1000L;
        Map<String, RideStore.Sample> s = new LinkedHashMap<>();
        s.put("speed_kmh", new RideStore.Sample(kmh, at));
        s.put("battery_percent", new RideStore.Sample(80 - second / 100.0, at));
        rides.sample(wall + second * 1000L, at, s);
    }

    /** The one recording time: reboot-safe, the larger of the recorded and the wall clock's; ticking on the second. */
    private static void clock() throws Exception {
        File dir = Files.createTempDirectory("recording-clock").toFile();
        RideStore rides = new RideStore(dir);
        long wall = 1_700_000_000_000L;
        rides.start("scooter", "Synthetic", wall, 5_000L);
        RideStore.Summary active = rides.activeSummary();
        check(RecordingFit.elapsedMs(active, wall + 878_400L) == 878_400L, "14:38 after the start by the wall clock, before any sample");
        check(RecordingFit.elapsedMs(active, wall - 10_000L) == 0, "a clock set back never shows a negative time");
        check(RecordingFit.elapsedMs(null, wall) == 0, "no summary yet: 0:00");
        check(RecordingFit.elapsedMs(active, wall + 3_768_000L) == 3_768_000L, "over an hour");
        check(RecordingFit.elapsedMs(active, wall + 1000) == RecordingPill.elapsedMs(active.elapsedMs, active.startWallMs, wall + 1000),
                "the same value as riding mode's REC pill");
        // Held for the hand-off, the time is the ride's at its stop, not the time Home next looked (or now).
        check(RecordingFit.shownElapsedMs(active, wall + 878_400L, wall + 878_400L + 30 * 60_000L) == 878_400L,
                "frozen at the stop: 14:38, though Home looked half an hour later");
        check(RecordingFit.shownElapsedMs(active, -1, wall + 878_400L) == 878_400L, "while it records: now");
        check(RecordingFit.shownElapsedMs(active, wall + 900_000L, wall + 878_400L) == 878_400L, "a stop after now (clock skew): never later than now");
        check(RecordingFit.untilNextSecond(878_400L) == 615 && RecordingFit.untilNextSecond(878_000L) == 1015, "the next tick is on the second");
        rides.stop(wall + 2000, 7_000L);
    }

    /** The range every glanceable figure shows follows the scooter's range source (ScooterService.dashboardReadings). */
    private static void rangeSource() {
        check(RangeSource.learnedKm(false, false, 50d, 11d) == null, "the scooter's own range by default");
        check(RangeSource.learnedKm(true, false, 50d, 11d) == 11d, "learned: Volt's figure at this battery reading");
        check(RangeSource.learnedKm(true, true, 50d, 11d) == null, "never in simulation");
        check(RangeSource.learnedKm(true, false, 50d, null) == null, "no learned figure yet: the scooter's");
        check(RangeSource.learnedKm(true, false, null, 11d) == null, "no battery reading: the scooter's");
        check(RangeSource.learnedKm(true, false, 50d, Double.NaN) == null, "an unusable figure: the scooter's");
        check(RangeSource.learnedKm(true, false, 50d, Double.POSITIVE_INFINITY) == null
                && RangeSource.learnedKm(true, false, 50d, Double.NEGATIVE_INFINITY) == null, "an infinite figure: the scooter's (the new guard)");
        check(RangeSource.learned("p1", "learned") && !RangeSource.learned("p1", "scooter") && !RangeSource.learned("p1", null)
                && !RangeSource.learned("", "learned") && !RangeSource.learned(null, "learned"), "only a stored learned choice for a scooter");
        check(RangeSource.key("p1").equals("range_source_p1"), "the key per scooter");
    }

    /**
     * RangeFigure, the figure the parked card's "about 31 mi left" and the moment's Range cell share: byte for byte what
     * DashboardScreen.rangeText wrote before it moved there, in every unit setting, and a text-only reading as its text.
     */
    private static void rangeFigure() {
        T.install(null);
        for (double km : new double[] {0, 0.8, 1.6, 12.87, 30, 37.2, 48.28, 80.47, 124.5}) {
            String metric = T.fmtQuantity(Fmt.decimal(java.util.Locale.US, km, 0), T.unitsKm());
            String miles = T.fmtQuantity(Fmt.decimal(java.util.Locale.US, km / 1.609344, 0), T.unitsMi());
            check(RangeFigure.of(km, "km").toString().equals(metric), "km: " + km);
            check(RangeFigure.of(km, "mi").toString().equals(miles), "mi: " + km);
            check(RangeFigure.of(km, "dual").toString().equals(T.fmtDual(miles, metric)), "both units: " + km);
            check(RangeFigure.of(km, "elsewhere").toString().equals(miles), "an unknown setting reads as miles, as before: " + km);
            check(RangeFigure.primary(km, "dual").toString().equals(miles) && RangeFigure.primary(km, "km").toString().equals(metric), "the first unit");
        }
        check(RangeFigure.of(null, "30 km", "km").toString().equals(DisplayUnits.metricText("30 km", "km")), "a text-only reading: its text");
        check(RangeFigure.of(null, "30 km", "mi").toString().equals(DisplayUnits.metricText("30 km", "mi")), "in the rider's units");
        check(RangeFigure.of(Double.NaN, "30 km", "mi").toString().equals(DisplayUnits.metricText("30 km", "mi")), "a number that is not one: the text");
        check(RangeFigure.of(null, null, "mi") == null, "no reading: none");
    }

    /**
     * The readings row (RecordingFit.row) with Saira's own widths: with units set to both, Trip and Range keep both units
     * at 1x text on every phone checked and on two panes, as the parked card does ("about 23 mi / 37 km left"); only on
     * the narrowest phone with large text do they take their first unit, together. Without both units, the mock-up's
     * equal thirds at full size.
     */
    private static void readings() throws Exception {
        System.setProperty("java.awt.headless", "true");
        Font bold = Font.createFont(Font.TRUETYPE_FONT, new File("res/font/saira_bold.ttf"));
        Font semibold = Font.createFont(Font.TRUETYPE_FONT, new File("res/font/saira_semibold.ttf"));
        Font regular = Font.createFont(Font.TRUETYPE_FONT, new File("res/font/saira_regular.ttf"));
        // {content width (dp), text scale, both units shown}: 344, 360, 390 and 412 dp phones and two panes at 1x.
        float[][] keep = {{312, 1}, {328, 1}, {358, 1}, {380, 1}, {330, 1}};
        for (float[] c : keep) {
            float[] fit = row(bold, semibold, regular, c[0], c[1], true);
            check(fit != null && fit[0] >= 12 * c[1] - .01f, c[0] + " dp wide at " + c[1] + "x: both units, at the readable floor or larger");
            check(Math.abs(fit[1] + fit[2] + fit[3] - (c[0] - 16)) < .5f, c[0] + " dp: the columns fill the row");
        }
        // The narrowest phone with large text (280x640 dp at 1.4x): the first unit alone, together.
        check(row(bold, semibold, regular, 248, 1.4f, true) == null, "280 dp at 1.4x: both units don't fit, the first unit is shown");
        float[] mi = row(bold, semibold, regular, 358, 1, false);
        check(mi != null && mi[0] == 20 && mi[1] == mi[2] && mi[2] == mi[3], "miles at 390 dp: the mock-up's equal thirds at 20 sp");
        // 344 dp at 1.3x: recorded for the PR, either way the row fits (both units, or the first).
        float[] large = row(bold, semibold, regular, 312, 1.3f, true);
        System.out.println("RecordingFitTest: 344 dp at 1.3x, both units " + (large == null ? "don't fit: the first unit" : "fit at " + large[0] / 1.3f + " sp"));
    }

    /** The row's fit for a content width (dp) and text scale, with Trip and Range in both units or miles. */
    private static float[] row(Font bold, Font semibold, Font regular, float content, float scale, boolean dual) {
        float top = 20 * scale, unit = 13 * scale, label = 13 * scale;
        float trip = dual ? width(bold, top, "2.5") + width(semibold, unit, " mi") + width(bold, top, " / 4.0") + width(semibold, unit, " km")
                : width(bold, top, "2.5") + width(semibold, unit, " mi");
        float range = dual ? width(bold, top, "\u2248 23") + width(semibold, unit, " mi") + width(bold, top, " / 37") + width(semibold, unit, " km")
                : width(bold, top, "\u2248 23") + width(semibold, unit, " mi");
        float battery = width(bold, top, "73%");
        float[] labels = {width(regular, label, "Trip"), width(regular, label, "Battery"), width(regular, label, "Range")};
        return RecordingFit.row(labels, new float[] {trip + 2, battery + 2, range + 2}, new float[] {0, 13, 13}, content - 16, top, 12 * scale);
    }

    private static float width(Font font, float size, String text) {
        return (float) font.deriveFont(size).getStringBounds(text, new FontRenderContext(null, true, true)).getWidth();
    }

    /**
     * A scooter name on two lines makes the header taller; in tabletop posture that comes out of the area (the pane above
     * the hinge keeps its height): the moment still fits, nothing cut, no band.
     */
    private static void longName() {
        for (float text : new float[] {1f, 1.3f, 1.4f}) {
            int area = TopArea.heightDp(790, 820, true, text);
            int oneLine = TopArea.tabletopDp(area, 330), twoLines = TopArea.tabletopDp(area, 330 - Math.round(23 * text * 1.574f));
            check(twoLines <= oneLine, "a second line of name takes from the area");
            fits("tabletop, a two-line name, at " + text + "x", twoLines, 330, text);
        }
    }

    private static String describe(RecordingFit.Layout l) {
        return "{head " + l.head + ", time " + l.clock + " (" + l.clockSize + "), ribbon " + l.ribbon + "+" + l.ribbonGap
                + ", gap " + l.gap + ", readings " + l.stats + ", button " + l.button + ", under " + l.bottom + ", gaps +" + l.spread + ", total " + l.total() + "}";
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
