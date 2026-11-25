package com.jjricks.volt;

import java.util.List;

/**
 * Clean Home's recording moment, the Ride clock (Ollie's mock-up, Nov 24 2026: its "C · Ride clock" phone):
 * the pure rules behind RecordingView. Pure Java. Its sizes are the mock-up's, drawn on a 390x844 dp phone whose top
 * area is {@link #REF_WIDTH_DP} by {@link #REF_HEIGHT_DP} dp: top to bottom, a head row (a pulsing red dot and
 * "Recording", "Started 9:24 AM" at the right), the recording's time in large figures, a speed ribbon of this ride on a
 * rounded panel, three readings (Trip, Battery, Range) between hairlines, and a full-width Open riding mode button, with
 * {@link #GAP_DP} between each and {@link #BOTTOM_DP} left under the button.
 *
 * <ul>
 *   <li>{@link #clockDp}: the time's size, about 78 sp at the reference, growing as the area grows (the parked card's
 *       rule, TopArea.parkedGrowth) and only a little with large text, which makes it large already.</li>
 *   <li>{@link #split}: how the fixed top area's height is shared once the head row, the readings and the button have
 *       theirs. Give-way order: the room under the button first, then the ribbon shrinks to {@link #MIN_RIBBON_DP} and
 *       goes, then the time gets smaller down to its floor. "Started" drops from the head row when the row cannot hold
 *       it beside "Recording" ({@link #startedFits}); the readings take their short rungs where a third is too narrow
 *       ({@link #rung}). The button and "Recording" always stay whole, the button at least 48 dp high.</li>
 *   <li>{@link #trace}: the ribbon's line from this recording's own readings (RideStore.Point), downsampled to about
 *       one point per {@link #TRACE_STEP_DP} dp, broken where readings stop (a gap in the journal or a reading with no
 *       speed), stopped stretches on the baseline, the scale from 0 to the ride's fastest but never under
 *       {@link #MIN_SCALE_KMH}.</li>
 * </ul>
 */
final class RecordingFit {
    /** View tags: the moment (it opens the Ride page), its time, its ribbon, its readings and its Open riding mode button. */
    static final String TAG = "home-recording", TAG_CLOCK = "home-recording-clock", TAG_RIBBON = "home-recording-speed",
            TAG_STATS = "home-recording-stats", TAG_RIDING = "home-recording-riding";
    /** A reading the moment has no value for, as Just back's tiles show one. */
    static final String DASH = "—";

    /** The mock-up's top area: 358 dp of content in a 390 dp phone, 342 dp high. */
    static final float REF_WIDTH_DP = 358, REF_HEIGHT_DP = 342;
    /** The head row's least height, the gaps between the parts, and the room the mock-up leaves under the button. */
    static final int HEAD_DP = 28, GAP_DP = 10, BOTTOM_DP = 12;
    /** The least gap, where the area is too short for the time at its floor with the usual gaps (the area's floor with large text). */
    static final int MIN_GAP_DP = 4;
    /** The ribbon: its height at the reference, the least it shows at (lower, a line is too flat to read), its most. */
    static final int RIBBON_DP = 92, MIN_RIBBON_DP = 56, MAX_RIBBON_DP = 132, RIBBON_RADIUS_DP = 16;
    /** The readings row's least height at 1x (a label over a value), and the button's height (the mock-up's 50 dp). */
    static final int STATS_DP = 44, BUTTON_DP = 50;
    /** The time: 78 sp at the reference, its line box 76 dp (the mock-up's line-height), growth caps and its floor. */
    static final float CLOCK_SP = 78, CLOCK_LINE = 76f / 78f, CLOCK_MAX_GROWTH = 1.15f, CLOCK_FLOOR_SP = 36;
    /** Text sizes (sp at 1x): the head row's "Recording" and "Started", the ribbon's labels, a reading's label and value. */
    static final float HEAD_SP = 15, STARTED_SP = 14, RIBBON_LABEL_SP = 12.5f, LABEL_SP = 13, VALUE_SP = 20, BUTTON_SP = 16;
    /** A value's unit, as the mock-up's 13 px unit beside a 20 px value. */
    static final float UNIT_RATIO = 13f / 20f;
    /** The space between a value and its unit, as a share of its width at the unit's size: about the mock-up's 2 dp. */
    static final float UNIT_SPACE = .6f;
    /** The ribbon's line, its fill (about 7% of the text colour), the now dot and its ring in the panel's colour. */
    static final float LINE_DP = 1.8f;
    static final int FILL_ALPHA = 18, NOW_DOT_DP = 10, NOW_RING_DP = 3, DOT_DP = 9;
    /** The ribbon's inset at the right (the now dot sits there), its baseline above the bottom, its labels' top. */
    static final int RIBBON_END_DP = 12, BASELINE_DP = 2, LABEL_TOP_DP = 8, LABEL_SIDE_DP = 12;
    /** The plotted line keeps this much under the labels (the mock-up's fastest point sits about 38 dp down a 92 dp panel). */
    static final int PLOT_TOP_GAP_DP = 12;
    /** One trace point per this many dp of the ribbon's width. */
    static final float TRACE_STEP_DP = 2;
    /** The least top of the speed scale (km/h): a slow ride is drawn as the slow ride it is, not as tall spikes. */
    static final double MIN_SCALE_KMH = 20;
    /** How often the ribbon is read again while the moment shows (ms): a modest cadence, never every frame. */
    static final long RIBBON_EVERY_MS = 5000;

    private RecordingFit() {}

    /**
     * How much the moment grows with its area, as the parked card does (TopArea.parkedGrowth): as much as the area grew
     * in both directions from the reference, from 1 up to {@link #CLOCK_MAX_GROWTH}. contentDp is the area's width.
     */
    static float growth(float contentDp, float areaDp) {
        if (Float.isNaN(contentDp) || Float.isNaN(areaDp)) return 1f;
        float grown = Math.min(contentDp / REF_WIDTH_DP, areaDp / REF_HEIGHT_DP);
        return Math.max(1f, Math.min(CLOCK_MAX_GROWTH, grown));
    }

    /**
     * The time's size in dp (not grown again by the text scale): 78 at the reference, times the area's growth; large
     * text grows it to at most {@link #CLOCK_MAX_GROWTH} times too (it is already the largest text on Home), whichever
     * is more.
     */
    static float clockDp(float contentDp, float areaDp, float textScale) {
        float scale = Float.isNaN(textScale) || textScale <= 0 ? 1f : textScale;
        return CLOCK_SP * Math.min(CLOCK_MAX_GROWTH, Math.max(growth(contentDp, areaDp), scale));
    }

    /** The time's floor in dp: {@link #CLOCK_FLOOR_SP}, never over its full size. */
    static float clockFloorDp(float fullDp) { return Math.min(fullDp, CLOCK_FLOOR_SP); }

    /** The time's line box for a size (the mock-up's 76 dp for 78). */
    static int lineBox(float sizePx) { return Math.round(sizePx * CLOCK_LINE); }

    /**
     * The size (px) the time takes in its width: {@code top} when it fits, else the size its width shrinks to, no smaller
     * than {@code floor} (where a long "10:02:48" on a narrow phone still ends whole: the floor is small enough). widthAtTop
     * is the time's width at top.
     */
    static float clockFit(float top, float floor, float widthAtTop, float room) {
        if (widthAtTop <= room || widthAtTop <= 0) return top;
        if (room <= 0) return floor;
        return Math.max(floor, (float) Math.floor(top * room / widthAtTop * 100) / 100);
    }

    /**
     * How the height left once the head row, the readings, the button and the gaps between them have theirs (any unit,
     * px in RecordingView) is shared between the time's box, the ribbon and the room under the button. Returns {time box,
     * ribbon, ribbon gap, under the button}:
     * <ol>
     *   <li>everything at full size, the ribbon taking what is over (up to {@code ribbonMax}), the rest under the button,
     *       at least {@code bottom} when there is room for it;</li>
     *   <li>short of that, the room under the button goes first;</li>
     *   <li>then the ribbon shrinks to {@code ribbonMin};</li>
     *   <li>then the ribbon goes (0, with its gap) and the time keeps its full box, the rest under the button (layout
     *       shares it out between the gaps, so it never makes one band);</li>
     *   <li>then the time's box gets smaller, down to {@code clockFloor}.</li>
     * </ol>
     */
    static int[] split(int room, int clockFull, int clockFloor, int ribbonFull, int ribbonMin, int ribbonMax, int gap, int bottom) {
        int left = Math.max(0, room);
        int besideRibbon = clockFull + gap;
        if (left - besideRibbon - bottom >= ribbonFull) {
            int ribbon = Math.min(Math.max(ribbonFull, ribbonMax), left - besideRibbon - bottom);
            return new int[] {clockFull, ribbon, gap, left - besideRibbon - ribbon};
        }
        if (left - besideRibbon >= ribbonFull) return new int[] {clockFull, ribbonFull, gap, left - besideRibbon - ribbonFull};
        if (left - besideRibbon >= ribbonMin) return new int[] {clockFull, left - besideRibbon, gap, 0};
        int box = Math.min(clockFull, Math.max(Math.min(clockFull, clockFloor), left));
        return new int[] {box, 0, 0, Math.max(0, left - box)};
    }

    /**
     * Saira's line (its ascent and descent, 1135 + 439 per 1000 units of size): the height a line of text takes with no
     * extra padding (TextView's includeFontPadding off), and the CSS "normal" line height the mock-up's rows use.
     */
    static final float LINE_EM = 1.574f;
    /** The mock-up's line heights for a reading's label (13 px over 1.25) and value (20 px over 1.15). */
    static final float LABEL_LINE = 1.25f, VALUE_LINE = 1.15f;
    /** The button's least padding above and below its label (large text grows it past the mock-up's 50 dp only then). */
    static final int BUTTON_PAD_DP = 8;

    /** The head row's height (dp) at a text scale: the mock-up's 28, or its text's line where that is taller. */
    static int headDp(float textScale) { return (int) Math.ceil(Math.max(HEAD_DP, HEAD_SP * scale(textScale) * LINE_EM)); }

    /** The readings row's height (dp): the mock-up's 44, or a label's line over a value's line where that is taller. */
    static int statsDp(float textScale) {
        return (int) Math.ceil(Math.max(STATS_DP, (LABEL_SP * LABEL_LINE + VALUE_SP * VALUE_LINE) * scale(textScale)));
    }

    /** The button's height (dp): the mock-up's 50 (a 48 dp target at least), or its label's line with its padding. */
    static int buttonDp(float textScale) {
        return (int) Math.ceil(Math.max(BUTTON_DP, BUTTON_SP * scale(textScale) * LINE_EM + 2 * BUTTON_PAD_DP));
    }

    private static float scale(float textScale) { return Float.isNaN(textScale) || textScale <= 0 ? 1f : Math.max(1f, textScale); }

    /** The moment's parts in dp for an area (RecordingView lays them out from it; the fit tests check every window). */
    static final class Layout {
        final int head, clock, ribbon, ribbonGap, stats, button, bottom;
        /** The gap between the parts (dp): {@link #GAP_DP}, less only where the area is too short for it. */
        final int gap;
        /** What each gap between the parts takes over {@link #gap} (dp), so the room left never makes one band. */
        final int spread;
        /** The time's text size (dp): full, or what its box holds when the area is short, never under its floor. */
        final float clockSize;

        Layout(int head, int gap, int clock, float clockSize, int ribbon, int ribbonGap, int stats, int button, int bottom, int spread) {
            this.head = head; this.gap = gap; this.clock = clock; this.clockSize = clockSize; this.ribbon = ribbon; this.ribbonGap = ribbonGap;
            this.stats = stats; this.button = button; this.bottom = bottom; this.spread = spread;
        }

        /** The gaps between the parts: three, or four with the ribbon. */
        int gaps() { return ribbon > 0 ? 4 : 3; }

        /** Everything the parts and their gaps take, the room under the button included. */
        int total() { return head + gap + clock + ribbonGap + ribbon + gap + stats + gap + button + bottom + gaps() * spread; }

        /** The largest stretch the time's box leaves empty above or below its text (dp), for the no-band rule. */
        float clockSpare() { return Math.max(0, (clock - clockSize * CLOCK_LINE) / 2f); }
    }

    /**
     * The moment's layout for a top area areaDp high and contentDp wide at a text scale (the app's and the system's
     * together, as TopArea takes it): the head row, the readings and the button take their heights, and the time and the
     * ribbon share the rest by {@link #split}.
     */
    static Layout layout(int areaDp, float contentDp, float textScale) {
        int head = headDp(textScale), stats = statsDp(textScale), button = buttonDp(textScale);
        float full = clockDp(contentDp, areaDp, textScale);
        int clockFull = lineBox(full), clockFloor = lineBox(clockFloorDp(full));
        int fixed = head + stats + button, gap = GAP_DP, room = areaDp - fixed - 3 * gap;
        // Last of all, the gaps close up (to MIN_GAP_DP) so the time keeps its floor (the area's floor with large text).
        if (room < clockFloor) {
            gap = Math.max(MIN_GAP_DP, GAP_DP - (int) Math.ceil((clockFloor - room) / 3.0));
            room = areaDp - fixed - 3 * gap;
        }
        int[] split = split(room, clockFull, clockFloor, RIBBON_DP, MIN_RIBBON_DP, MAX_RIBBON_DP, gap, BOTTOM_DP);
        // With the gaps closed up the time stays at its floor: what the whole-dp gaps leave over goes under the button,
        // so a shorter area never shows a larger time.
        if (gap < GAP_DP && split[0] > clockFloor) { split[3] += split[0] - clockFloor; split[0] = clockFloor; }
        float size = split[0] >= clockFull ? full : Math.max(clockFloorDp(full), Math.min(full, split[0] / CLOCK_LINE));
        // Room over the mock-up's 12 dp under the button (a tall area past the ribbon's most, or a short one without the
        // ribbon) is shared between that room and the gaps, so no stretch of it reads as a band.
        int bottom = split[3], gaps = split[1] > 0 ? 4 : 3;
        int extra = split[1] > 0 ? Math.max(0, bottom - BOTTOM_DP) : bottom, spread = extra / (gaps + 1);
        return new Layout(head, gap, split[0], size, split[1], split[2], stats, button, bottom - gaps * spread, spread);
    }

    /**
     * Whether "Started 9:24 AM" stays in the head row: it fits on the one line beside the dot and "Recording" (widths in
     * the same unit as room, gap the least space between them). Otherwise it drops and "Recording" keeps the row.
     */
    static boolean startedFits(float recordingWidth, float startedWidth, float gap, float room) {
        return startedWidth > 0 && recordingWidth + gap + startedWidth <= room;
    }

    /**
     * The narrow-label ladder: the first rung (widths, longest first) that fits room, else the last (which the view
     * ends with an ellipsis rather than break a word). Before the first layout (room &lt;= 0) the first.
     */
    static int rung(float[] widths, float room) {
        if (widths.length == 0) return -1;
        if (room <= 0) return 0;
        for (int i = 0; i < widths.length; i++) if (widths[i] <= room) return i;
        return widths.length - 1;
    }

    /**
     * The ribbon's line, normalised: x from 0 (the recording's start) to 1 (now), y from 0 (the baseline: stopped) to 1
     * (the scale's top, {@link #scaleKmh}). breaks[i] says the line does not join point i to the one before it. Empty
     * (no points) when the recording has no speed reading yet: the panel then shows its baseline and labels only.
     */
    static final class Trace {
        final float[] x, y;
        final boolean[] breaks;
        final double scaleKmh;

        Trace(float[] x, float[] y, boolean[] breaks, double scaleKmh) {
            this.x = x; this.y = y; this.breaks = breaks; this.scaleKmh = scaleKmh;
        }

        int size() { return x.length; }
        boolean isEmpty() { return x.length == 0; }
    }

    /** The scale's top for the ride's fastest speed (km/h): that speed, never under {@link #MIN_SCALE_KMH}. */
    static double scaleKmh(double fastestKmh) {
        return Double.isFinite(fastestKmh) ? Math.max(MIN_SCALE_KMH, fastestKmh) : MIN_SCALE_KMH;
    }

    /**
     * The ribbon's trace from the active ride's points (RideStore.trend: this recording's own readings, from memory),
     * across the recording's wall-clock time from its start (startWallMs, the left edge) to now (nowWallMs, the right
     * edge: the same clock RecordingPill.elapsedMs, and so the time above it, counts), in about {@code columns}
     * buckets (the panel's width over {@link #TRACE_STEP_DP}). A bucket's point is the mean of its readings at their mean
     * time; a bucket that holds a gap (a new journal segment) or follows a point with no speed starts a new piece of the
     * line, so a stretch with no readings is drawn as a break, never bridged.
     */
    static Trace trace(List<RideStore.Point> points, long startWallMs, long nowWallMs, int columns) {
        int n = points == null ? 0 : points.size();
        if (n == 0 || columns <= 0) return new Trace(new float[0], new float[0], new boolean[0], MIN_SCALE_KMH);
        long span = Math.max(1, Math.max(nowWallMs, points.get(n - 1).wallMs) - startWallMs);
        double[] sumX = new double[columns], sumY = new double[columns];
        int[] count = new int[columns];
        boolean[] gapIn = new boolean[columns];
        double fastest = 0;
        int lastSegment = Integer.MIN_VALUE;
        boolean missing = true;
        for (RideStore.Point p : points) {
            int bucket = (int) Math.min(columns - 1, Math.max(0, (long) Math.floor(Math.max(0, p.wallMs - startWallMs) / (double) span * columns)));
            Double kmh = p.speedKmh;
            boolean valid = kmh != null && Double.isFinite(kmh) && kmh >= 0;
            // A new segment (the journal's gap count went up) or a point with no speed: the line breaks before the next one.
            if (lastSegment != Integer.MIN_VALUE && p.segment != lastSegment) gapIn[bucket] = true;
            lastSegment = p.segment;
            if (!valid) { missing = true; continue; }
            if (missing && count[bucket] == 0) gapIn[bucket] = true;
            missing = false;
            sumX[bucket] += Math.max(0, p.wallMs - startWallMs) / (double) span;
            sumY[bucket] += kmh;
            count[bucket]++;
            fastest = Math.max(fastest, kmh);
        }
        double scale = scaleKmh(fastest);
        int shown = 0;
        for (int c : count) if (c > 0) shown++;
        float[] x = new float[shown], y = new float[shown];
        boolean[] breaks = new boolean[shown];
        int at = 0;
        boolean pending = false;
        for (int b = 0; b < columns; b++) {
            if (count[b] == 0) { pending |= gapIn[b]; continue; }
            x[at] = (float) Math.min(1, sumX[b] / count[b]);
            y[at] = (float) Math.min(1, sumY[b] / count[b] / scale);
            breaks[at] = at == 0 || pending || gapIn[b];
            pending = false;
            at++;
        }
        return new Trace(x, y, breaks, scale);
    }

    /**
     * The time the moment shows: the recording's time now, or, once the ride has stopped and the moment stays for the
     * hand-off to Just back (HomeMoment.holdRecording), its time at the stop (stopWallMs, RideStore.lastStopWallMs; -1
     * while it records), never later than now.
     */
    static long shownElapsedMs(RideStore.Summary active, long stopWallMs, long wallNowMs) {
        return elapsedMs(active, stopWallMs >= 0 ? Math.min(stopWallMs, wallNowMs) : wallNowMs);
    }

    /**
     * The readings row, Trip, Battery and Range: each column's width (any unit) and the one value size, as the mock-up
     * lays it out where it can, three equal columns. Where a value does not fit its third (both units, "2.5 mi / 4.0
     * km"), the columns take the width their values need, Battery's short value giving its room to the other two, and
     * the values all get smaller together, down to {@code floor}. labels are each name's narrowest rung, values each
     * value's width at {@code top}, pads the room each column keeps before its words (the hairline and its gap). Returns
     * {size, width of each column...}, or null when even the floor needs more than room: then the values with both units
     * take their first unit alone, together.
     */
    static float[] row(float[] labels, float[] values, float[] pads, float room, float top, float floor) {
        int n = values.length;
        float equal = room / n;
        boolean fits = true;
        for (int i = 0; i < n; i++) fits &= Math.max(labels[i], values[i]) <= equal - pads[i];
        float[] out = new float[n + 1];
        if (fits) {
            out[0] = top;
            for (int i = 0; i < n; i++) out[i + 1] = equal;
            return out;
        }
        float step = Math.max(.01f, top / 200);
        for (float size = top; size >= floor - 1e-3f; size -= step) {
            float sum = 0;
            for (int i = 0; i < n; i++) sum += Math.max(labels[i], values[i] * size / top) + pads[i];
            if (sum > room) continue;
            float extra = (room - sum) / n;
            out[0] = size;
            for (int i = 0; i < n; i++) out[i + 1] = Math.max(labels[i], values[i] * size / top) + pads[i] + extra;
            return out;
        }
        return null;
    }

    /**
     * The recording's time so far for the active ride (null: none yet, 0), the one value Home's Ride row, this moment and
     * riding mode's REC pill all show (RecordingPill.elapsedMs: reboot-safe, the larger of the recorded time and the wall
     * clock's since the start), written by RidingFocus.clockText everywhere, so the three never disagree.
     */
    static long elapsedMs(RideStore.Summary active, long wallNowMs) {
        return active == null ? 0 : RecordingPill.elapsedMs(active.elapsedMs, active.startWallMs, wallNowMs);
    }

    /**
     * How long until the time's next whole second (ms), so the time ticks on the second, with {@link #TICK_SLACK_MS}
     * over it: a tick that runs a moment early would show the old second again. Home's Ride row and this moment both tick
     * by it, so they change together.
     */
    static long untilNextSecond(long elapsedMs) {
        return 1000 - Math.floorMod(Math.max(0, elapsedMs), 1000L) + TICK_SLACK_MS;
    }

    /** See {@link #untilNextSecond}. */
    static final long TICK_SLACK_MS = 15;

    /**
     * The look the moment's dot and head row take (riding mode's REC pill's, RecordingPill.look), and the problem look
     * too while the service has paused saving the ride (ScooterService.recordingError: storage full): a healthy, pulsing
     * Recording over a ride that is not being saved would hide it. elapsedMs is the recording's time.
     */
    static RecordingPill.Look look(long elapsedMs, RidingFocus.Link link, boolean off, boolean paused) {
        RecordingPill.Look look = RecordingPill.look(elapsedMs, link, null, false, off);
        return paused && look == RecordingPill.Look.RECORDING ? RecordingPill.Look.RECORDING_PROBLEM : look;
    }

    /**
     * Whether the ribbon's speed now shows: a live reading (TelemetryHistory.Reading.live) taken no more than riding
     * mode's {@link RidingValues#SPEED_GONE_MS} ago (capturedWallMs, the reading's own time, against wallNowMs), the link
     * good and the scooter on, and the ride being saved (not paused). The reading's 15 s freshness alone would label a
     * speed up to 15 s old as now; a reading from the future by more than that (a clock moved back) is not now either.
     */
    static boolean speedNowShown(boolean live, long capturedWallMs, long wallNowMs, boolean linkGood, boolean paused) {
        long age = wallNowMs - capturedWallMs;
        return live && linkGood && !paused && capturedWallMs > 0 && age <= RidingValues.SPEED_GONE_MS && age >= -RidingValues.SPEED_GONE_MS;
    }

    /**
     * The Open riding mode button's parts that fit beside its words (px): 2 the gauge and the chevron, 1 the gauge alone,
     * 0 the words alone. room is the button's inner width (its width less its padding); the gauge takes icon plus gap
     * before the words, the chevron gap plus chevron after them. Each part is counted once.
     */
    static int buttonParts(float words, float room, float icon, float gap, float chevron) {
        if (words + icon + gap + gap + chevron <= room) return 2;
        return words + icon + gap <= room ? 1 : 0;
    }

    /** The number of trace buckets for a ribbon width (px) at a density (px per dp): about one per TRACE_STEP_DP. */
    static int columns(int widthPx, float density) {
        float dp = density > 0 ? widthPx / density : widthPx;
        return Math.max(1, Math.round(dp / TRACE_STEP_DP));
    }
}
