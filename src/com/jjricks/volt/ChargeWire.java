package com.jjricks.volt;

/**
 * The animated charging picture's geometry in the cut-out's pixels (ChargeWireModels): the cable's path sampled by
 * arc length, the procedural battery cells and the dark panel behind them. Pure Java, built once per model; the view
 * maps it to the screen with one scale and offset, so nothing here is recomputed per frame.
 *
 * <p>The path pulses travel runs from {@link #LEAD} pixels inside the plug (back along the cable's first tangent),
 * along the Bézier segments, then straight on (at least {@link #EXT} pixels) past the picture's right edge (the curve ends with
 * zero curvature, so the straight run continues it smoothly) to run off the screen's edge. The grey cable is drawn
 * from the plug's tip ({@link #cableStart}) to the end.
 */
final class ChargeWire {
    /**
     * The lead-in inside the plug and the least straight run past the picture's edge, in cut-out pixels (the view
     * makes the run longer where the screen's edge is further away, so the cable always runs off it).
     */
    static final double LEAD = 18, EXT = 320;
    /** The arc-length table's greatest step, in cut-out pixels (its own {@link #step} divides the path evenly). */
    static final double STEP = 2;
    /** The cells: 8 staggered rows of 16 and 15, their tops squashed to SQUASH (height over width: the view's tilt). */
    static final int COLUMNS = 16, ROWS = 8;
    static final double SQUASH = 0.57;
    /** The short wall under each top shows the cosine of the same tilt (3.4 px at the prototype's first 0.4). */
    static final double WALL = 3.4 * Math.sqrt(1 - SQUASH * SQUASH) / Math.sqrt(1 - 0.4 * 0.4);
    /** The lit cells light in half-pitch columns, connector side first. */
    static final int HALF_COLUMNS = COLUMNS * 2;

    final ChargeWireModels.Model model;
    /** The straight run past the picture's right edge. */
    final double ext;
    /** The path's length, the plug tip's arc length, and the points every STEP along it ({@link #count} of each). */
    final double length, cableStart;
    /** The table's step: the path's length over its intervals, at most STEP. */
    final double step;
    final int count;
    final float[] xs, ys;
    /** The cells: centre, row and half-pitch column of each; one radius and wall for all. */
    final int cellCount;
    final float[] cellX, cellY;
    final int[] cellRow, cellColumn;
    final float rx, ry, wall;
    /** Each row's top centre line, back row first. */
    final float[] rowY = new float[ROWS];

    ChargeWire(ChargeWireModels.Model model) { this(model, EXT); }

    ChargeWire(ChargeWireModels.Model model, double ext) {
        this.model = model;
        this.ext = ext = Math.max(EXT, ext);
        double[][] bez = model.wire;
        double[] first = bez[0], last = bez[bez.length - 1];
        double t0x = first[2] - first[0], t0y = first[3] - first[1], t0 = Math.hypot(t0x, t0y);
        double t1x = last[6] - last[4], t1y = last[7] - last[5], t1 = Math.hypot(t1x, t1y);
        double leadX = first[0] - LEAD * t0x / t0, leadY = first[1] - LEAD * t0y / t0;
        double extX = last[6] + ext * t1x / t1, extY = last[7] + ext * t1y / t1;
        // Dense polyline: the lead, each segment in fine steps, the straight run.
        int perSegment = 2000;
        int n = 2 + bez.length * perSegment + 1;
        double[] px = new double[n], py = new double[n], acc = new double[n];
        int k = 0;
        px[k] = leadX; py[k] = leadY; k++;
        px[k] = first[0]; py[k] = first[1]; k++;
        for (double[] s : bez)
            for (int i = 1; i <= perSegment; i++) {
                double t = i / (double) perSegment, u = 1 - t;
                double a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, d = t * t * t;
                px[k] = a * s[0] + b * s[2] + c * s[4] + d * s[6];
                py[k] = a * s[1] + b * s[3] + c * s[5] + d * s[7];
                k++;
            }
        px[k] = extX; py[k] = extY; k++;
        for (int i = 1; i < k; i++) acc[i] = acc[i - 1] + Math.hypot(px[i] - px[i - 1], py[i] - py[i - 1]);
        length = acc[k - 1];
        cableStart = acc[1];
        count = (int) Math.ceil(length / STEP) + 1;
        step = length / (count - 1);
        xs = new float[count]; ys = new float[count];
        int j = 0;
        for (int i = 0; i < count; i++) {
            double s = Math.min(length, i * step);
            while (j < k - 2 && acc[j + 1] < s) j++;
            double span = acc[j + 1] - acc[j], f = span > 0 ? (s - acc[j]) / span : 0;
            xs[i] = (float) (px[j] + f * (px[j + 1] - px[j]));
            ys[i] = (float) (py[j] + f * (py[j + 1] - py[j]));
        }

        // The cells, inside the box less 1 px: back row first, so front rows overlap it.
        double[] box = model.cells;
        double x0 = box[0] + 1, y0 = box[1] + 1, x1 = box[2] - 1, y1 = box[3] - 1;
        double pitch = (x1 - x0) / COLUMNS, radius = pitch / 2 - 0.9, squash = radius * SQUASH;
        double rowPitch = ((y1 - y0) - 2 * squash - WALL - 1) / (ROWS - 1);
        rx = (float) radius; ry = (float) squash; wall = (float) WALL;
        cellCount = ROWS / 2 * COLUMNS + ROWS / 2 * (COLUMNS - 1);
        cellX = new float[cellCount]; cellY = new float[cellCount]; cellRow = new int[cellCount]; cellColumn = new int[cellCount];
        int c = 0;
        for (int r = 0; r < ROWS; r++) {
            double stagger = r % 2 == 1 ? pitch / 2 : 0, cy = y0 + squash + 0.5 + r * rowPitch;
            int across = r % 2 == 1 ? COLUMNS - 1 : COLUMNS;
            rowY[r] = (float) cy;
            for (int i = 0; i < across; i++) {
                double cx = x0 + pitch / 2 + stagger + i * pitch;
                cellX[c] = (float) cx; cellY[c] = (float) cy; cellRow[c] = r;
                int column = (int) Math.round((cx - x0) / (pitch / 2)) - 1;
                cellColumn[c] = column >= 0 && column < HALF_COLUMNS ? column : 0;
                c++;
            }
        }
    }

    /** The point at arc length s (clamped to the path), into out[0..1]. */
    void point(double s, float[] out) {
        double u = Math.max(0, Math.min(length, s)) / step;
        int i = Math.min(count - 2, (int) Math.floor(u));
        float f = (float) Math.min(1, u - i);
        out[0] = xs[i] + f * (xs[i + 1] - xs[i]);
        out[1] = ys[i] + f * (ys[i + 1] - ys[i]);
    }

    /** x at arc length s; outside the path it continues the end's slope (the comet's gradient runs past the ends). */
    float xAt(double s) {
        double u = s / step;
        if (u <= 0) return (float) (xs[0] + u * (xs[1] - xs[0]));
        if (u >= count - 1) return (float) (xs[count - 1] + (u - (count - 1)) * (xs[count - 1] - xs[count - 2]));
        int i = (int) Math.floor(u);
        return (float) (xs[i] + (u - i) * (xs[i + 1] - xs[i]));
    }

    /** The table index of the first sample strictly after arc length s. */
    int after(double s) { return Math.max(0, Math.min(count, (int) Math.floor(s / step) + 1)); }

    // ---- the dark panel behind the cells ----

    double panelTop() { return model.cells[1] - model.panelAbove; }
    double panelBottom() { return model.cells[3] + model.panelBelow; }
    double panelRight() { return model.cells[2] + model.panelRight; }
    /** The panel's leaning left edge at y. */
    double panelLeft(double y) {
        double top = panelTop(), bottom = panelBottom();
        return model.cells[0] - model.panelLeftTop - (model.panelLeftBottom - model.panelLeftTop) * (y - top) / (bottom - top);
    }
}
