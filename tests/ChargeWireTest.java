package com.jjricks.volt;

/**
 * The animated charging picture's pure parts (the contributor's charging animation, 14 Nov 2026 on #383), checked
 * against the prototype it ports (scratchpad proto-charge, anim.js): the per-model table and the picture's place in
 * Clean's charging view (ChargeWireModels), the cable's path sampled by arc length, the cells and the panel
 * (ChargeWire), and the pulses' timing and mode from the charging power (ChargeWirePulses).
 */
public final class ChargeWireTest {
    private static int checks;

    public static void main(String[] args) {
        models();
        placement();
        path();
        cells();
        modes();
        timing();
        landing();
        System.out.println("ChargeWire: " + checks + " checks passed");
    }

    private static void models() {
        ChargeWireModels.Model m = ChargeWireModels.forKey("ut5max");
        require(m == ChargeWireModels.UT5_MAX, "the UT5 MAX has the picture");
        require(ChargeWireModels.forKey(null) == null && ChargeWireModels.forKey("") == null, "no model, no picture");
        for (String other : new String[] {"n65i", "st3pro", "gt5max", "gt3max", "xt5ultra", "UT5 MAX"})
            require(ChargeWireModels.forKey(other) == null, other + " keeps its photo");
        for (ChargeWireModels.Model each : ChargeWireModels.all()) {
            require(each.width > 0 && each.height > 0, each.key + ": a size");
            require(each.wire.length >= 1, each.key + ": a cable");
            for (int i = 0; i < each.wire.length; i++) {
                require(each.wire[i].length == 8, each.key + ": four points per segment");
                if (i > 0) require(each.wire[i][0] == each.wire[i - 1][6] && each.wire[i][1] == each.wire[i - 1][7],
                        each.key + ": each segment starts where the last ends");
            }
            double[] last = each.wire[each.wire.length - 1];
            require(Math.abs(last[6] - each.width) < .5, each.key + ": the cable reaches the picture's right edge");
            double[] c = each.cells;
            require(c[0] < c[2] && c[1] < c[3] && c[0] >= 0 && c[2] <= each.width && c[3] <= each.height, each.key + ": the cells inside the picture");
            require(each.widthShare > 0 && each.widthShare <= 1 && each.centreShare > 0 && each.centreShare < 1, each.key + ": a place");
        }
        // Each key is a bundled photo key (ModelRegistry.photo), so the model's identity picks it as it picks the photo.
        require(ModelRegistryKeys.contains(m.key), "ut5max is a photo key");
    }

    /** The prototype's phone: 344 dp, 18 dp padding, the picture 283.6 dp wide from 39.3 dp, its tyres 9.4 dp over the track. */
    private static void placement() {
        ChargeWireModels.Model m = ChargeWireModels.UT5_MAX;
        // At 3 px per dp: a 308 dp content width; a tall stage.
        ChargeWireModels.Placement p = ChargeWireModels.place(m, 924, 600);
        near(p.width(m) / 3, 283.6, .05, "87% picture: 283.6 dp wide on a 344 dp phone");
        near(p.left / 3 + 18, 39.3, .05, "its left edge at 39.3 dp on screen");
        near(p.height(m) / 3, 283.6 * 691 / 1438, .05, "its height follows the cut-out");
        near(p.top + p.height(m), 600, .01, "its tyres stand at the floor");
        // A short stage: smaller, same centre, still standing on the floor.
        ChargeWireModels.Placement q = ChargeWireModels.place(m, 924, 240);
        near(q.height(m), 240, .01, "a short stage: the picture fills its height");
        require(q.width(m) < p.width(m), "and is narrower");
        near(q.left + q.width(m) / 2, p.left + p.width(m) / 2, .01, "about the same centre");
        near(q.top, 0, .01, "reaching the stage's top");
        // 412 dp: wider in proportion.
        ChargeWireModels.Placement r = ChargeWireModels.place(m, (412 - 36) * 3, 900);
        near(r.width(m) / 3, 283.6 * 376 / 308, .05, "412 dp: the same share of the width");
        require(ChargeWireModels.place(m, 0, 100).scale == 0 && ChargeWireModels.place(m, 100, 0).scale == 0, "no room, no picture");
    }

    private static void path() {
        ChargeWireModels.Model m = ChargeWireModels.UT5_MAX;
        ChargeWire w = new ChargeWire(m);
        float[] pt = new float[2];
        w.point(0, pt);
        double[] first = m.wire[0], last = m.wire[m.wire.length - 1];
        double tx = first[2] - first[0], ty = first[3] - first[1], tl = Math.hypot(tx, ty);
        near(pt[0], first[0] - ChargeWire.LEAD * tx / tl, .01, "the path starts 18 px inside the plug");
        near(pt[1], first[1] - ChargeWire.LEAD * ty / tl, .01, "along the cable's first tangent");
        near(w.cableStart, ChargeWire.LEAD, 1e-6, "the grey cable starts at the plug's tip");
        w.point(w.cableStart, pt);
        near(pt[0], first[0], .01, "the plug's tip x");
        near(pt[1], first[1], .01, "the plug's tip y");
        w.point(w.length, pt);
        double ex = last[6] - last[4], ey = last[7] - last[5], el = Math.hypot(ex, ey);
        near(pt[0], last[6] + ChargeWire.EXT * ex / el, .01, "it ends 320 px past the picture's edge");
        near(pt[1], last[7] + ChargeWire.EXT * ey / el, .01, "straight on the last tangent");
        // Its length: the lead, the curves (longer than their chords), the run.
        double chords = 0;
        for (double[] s : m.wire) chords += Math.hypot(s[6] - s[0], s[7] - s[1]);
        require(w.length > ChargeWire.LEAD + ChargeWire.EXT + chords && w.length < ChargeWire.LEAD + ChargeWire.EXT + chords * 1.05,
                "the path's length: " + w.length);
        // Even arc-length steps, and a point between steps is on the segment between them.
        for (int i = 1; i < w.count - 1; i++) {
            double step = Math.hypot(w.xs[i] - w.xs[i - 1], w.ys[i] - w.ys[i - 1]);
            if (step > w.step + 1e-3 || step < w.step * .98) { require(false, "step " + i + " is " + step); }
        }
        checks++;
        // The cable only runs left to right (the comet's gradient is horizontal): x grows with arc length.
        for (int i = 1; i < w.count; i++) if (w.xs[i] <= w.xs[i - 1]) require(false, "x falls at " + i);
        checks++;
        require(w.step <= ChargeWire.STEP && w.step > ChargeWire.STEP * .99, "the table's step: " + w.step);
        near(w.xAt(-10), w.xs[0] - 10 * (w.xs[1] - w.xs[0]) / w.step, .01, "x before the start continues the slope");
        require(w.xAt(w.length + 100) > w.xs[w.count - 1], "x past the end continues");
        require(w.after(0) == 1 && w.after(w.step * 3.5) == 4, "the first sample after an arc length");
        // The run reaches further for a far screen edge, never shorter than 320 px.
        ChargeWire far = new ChargeWire(m, 900);
        near(far.length - w.length, 580, .01, "a longer run past the picture");
        near(new ChargeWire(m, 10).length, w.length, 1e-6, "never shorter than the least run");
        // The cable passes above the cells (it never crosses them, so the grey cable can be drawn over the panel).
        for (int i = 0; i < w.count; i++)
            if (w.xs[i] >= m.cells[0] && w.xs[i] <= m.cells[2] && w.ys[i] + m.cableWidth / 2 >= m.cells[1]) require(false, "the cable crosses the cells at " + w.xs[i]);
        checks++;
    }

    private static void cells() {
        ChargeWireModels.Model m = ChargeWireModels.UT5_MAX;
        ChargeWire w = new ChargeWire(m);
        require(w.cellCount == 4 * 16 + 4 * 15, "8 staggered rows of 16 and 15: " + w.cellCount);
        double pitch = (m.cells[2] - m.cells[0] - 2) / 16;
        near(w.rx, pitch / 2 - .9, 1e-4, "a cell's radius: half the pitch less 0.9");
        near(w.ry, w.rx * .57, 1e-4, "SQUASH 0.57");
        near(w.wall, 3.4 * Math.sqrt(1 - .57 * .57) / Math.sqrt(1 - .16), 1e-4, "the wall shrinks with the tilt");
        int[] perRow = new int[ChargeWire.ROWS];
        int[] perColumn = new int[ChargeWire.HALF_COLUMNS];
        for (int i = 0; i < w.cellCount; i++) {
            perRow[w.cellRow[i]]++;
            perColumn[w.cellColumn[i]]++;
            require(w.cellX[i] - w.rx >= m.cells[0] && w.cellX[i] + w.rx <= m.cells[2], "cell " + i + " inside the box across");
            require(w.cellY[i] - w.ry >= m.cells[1] && w.cellY[i] + w.wall + w.ry <= m.cells[3] + .01, "cell " + i + " inside the box down");
            if (i > 0) require(w.cellRow[i] >= w.cellRow[i - 1], "back row first");
        }
        for (int r = 0; r < ChargeWire.ROWS; r++) require(perRow[r] == (r % 2 == 0 ? 16 : 15), "row " + r + ": " + perRow[r]);
        // Half-pitch columns: even rows light on the even ones, odd rows on the odd ones, 4 cells each, but the last.
        for (int c = 0; c < ChargeWire.HALF_COLUMNS; c++) require(perColumn[c] == (c == 31 ? 0 : 4), "half column " + c + ": " + perColumn[c]);
        require(w.cellColumn[0] == 0 && w.cellColumn[16] == 1, "the connector side's columns first");
        // The panel: 10.4 above, 2 below, 1 right, the left wall leaning from 1.8 to 6 px out.
        near(w.panelTop(), 442 - 10.4, 1e-9, "the panel's top");
        near(w.panelBottom(), 553, 1e-9, "the panel's bottom");
        near(w.panelRight(), 777, 1e-9, "the panel's right edge");
        near(w.panelLeft(w.panelTop()), 434 - 1.8, 1e-9, "its left edge at the top");
        near(w.panelLeft(w.panelBottom()), 434 - 6, 1e-9, "and at the bottom");
    }

    private static void modes() {
        require(ChargeWirePulses.mode(119d) == ChargeWirePulses.STOCK, "119 W: one pulse at a time");
        require(ChargeWirePulses.mode(199.9) == ChargeWirePulses.STOCK, "under 200 W: one");
        require(ChargeWirePulses.mode(200d) == ChargeWirePulses.FAST, "200 W: three, faster");
        require(ChargeWirePulses.mode(540d) == ChargeWirePulses.FAST, "540 W: three");
        require(ChargeWirePulses.mode(null) == ChargeWirePulses.STOCK, "unknown power: one");
        require(ChargeWirePulses.mode(Double.NaN) == ChargeWirePulses.STOCK, "no number: one");
        near(ChargeWirePulses.STOCK.trip, 1.8, 0, "stock: 1.8 s per trip");
        near(ChargeWirePulses.FAST.trip, 1.1, 0, "fast: 1.1 s");
        near(ChargeWirePulses.FAST.period * 3, ChargeWirePulses.FAST.trip, 1e-12, "fast: three on the cable, evenly spaced");
        require(ChargeWirePulses.STOCK.period > ChargeWirePulses.STOCK.trip, "stock: the next leaves after the last lands");
    }

    private static void timing() {
        ChargeWirePulses.State s = new ChargeWirePulses.State();
        // Stock: over a long run, never more than one pulse travelling (progress < 1) at once.
        int most = 0;
        for (double t = 0; t < 60; t += 0.01) {
            ChargeWirePulses.state(t, ChargeWirePulses.STOCK, s);
            int travelling = 0;
            for (int i = 0; i < s.pulses; i++) if (s.progress[i] < 1) travelling++;
            most = Math.max(most, travelling);
            require(s.pulses <= ChargeWirePulses.SLOTS && s.lands <= ChargeWirePulses.MAX_LANDS, "fits the pool at " + t);
        }
        require(most == 1, "stock: one pulse at a time, " + most);
        most = 0;
        int least = 9;
        for (double t = 3; t < 60; t += 0.01) {
            ChargeWirePulses.state(t, ChargeWirePulses.FAST, s);
            int travelling = 0;
            for (int i = 0; i < s.pulses; i++) if (s.progress[i] < 1) travelling++;
            most = Math.max(most, travelling); least = Math.min(least, travelling);
            // Slots: the pulses on the cable never share one.
            for (int i = 0; i < s.pulses; i++) for (int j = i + 1; j < s.pulses; j++)
                if (ChargeWirePulses.slot(s.number[i]) == ChargeWirePulses.slot(s.number[j])) require(false, "two pulses share a slot at " + t);
        }
        require(most == 3 && least == 3, "fast: three at once, " + least + ".." + most);
        // The prototype's stills: at 1.0 s (119 W) one pulse part-way; at 2.0 s it has landed and the cells are lit.
        ChargeWirePulses.state(1.0, ChargeWirePulses.STOCK, s);
        boolean partWay = false;
        for (int i = 0; i < s.pulses; i++) if (s.number[i] == 0) { near(s.progress[i], 1 / 1.8, 1e-9, "pulse 0 at 1.0 s"); partWay = true; }
        require(partWay, "a pulse on the cable at 1.0 s");
        ChargeWirePulses.state(2.0, ChargeWirePulses.STOCK, s);
        float[] levels = new float[ChargeWire.HALF_COLUMNS];
        double energy = ChargeWirePulses.cells(s, ChargeWirePulses.STOCK, levels);
        require(energy > .5, "the pack lit 0.2 s after landing: " + energy);
        require(levels[0] >= levels[31], "lit from the connector side first");
        // Head: at the right end leaving, at the plug's lead-in landing, past it after (hidden).
        double length = 1000;
        near(ChargeWirePulses.head(length, 0), length, 0, "leaves at the right end");
        near(ChargeWirePulses.head(length, 1), 0, 1e-9, "lands at the lead-in");
        require(ChargeWirePulses.head(length, 0.5) < length * 0.6, "accelerating a little into the plug");
        near(ChargeWirePulses.headVisible(0.5), 1, 0, "the head shows on the way");
        near(ChargeWirePulses.headVisible(1), 0, 0, "and vanishes into the plug");
        near(ChargeWirePulses.trail(length, ChargeWirePulses.STOCK), length / 1.8 * .45, 1e-9, "the lit trail");
        require(ChargeWirePulses.slot(-1) == 4 && ChargeWirePulses.slot(7) == 2, "slots wrap");
    }

    private static void landing() {
        for (ChargeWirePulses.Mode m : new ChargeWirePulses.Mode[] {ChargeWirePulses.STOCK, ChargeWirePulses.FAST}) {
            double best = 0;
            for (double x = 0; x < 3; x += 0.0005) best = Math.max(best, ChargeWirePulses.landing(x, m.decay) / m.peak);
            near(best, 1, .002, m + ": a landing lights the cells fully");
            near(ChargeWirePulses.landing(-0.1, m.decay), 0, 0, m + ": nothing before it lands");
            require(ChargeWirePulses.landing(ChargeWirePulses.LAND_SPAN, m.decay) / m.peak < .01, m + ": faded back to grey by 2.5 s");
        }
        near(ChargeWirePulses.easeOut(0, 1.3), 1, 1e-12, "a comet's head at full brightness");
        near(ChargeWirePulses.easeOut(1, 1.3), 0, 1e-12, "its tail's end at none");
        // No landing: no light.
        ChargeWirePulses.State s = new ChargeWirePulses.State();
        float[] levels = new float[ChargeWire.HALF_COLUMNS];
        levels[3] = 1;
        require(ChargeWirePulses.cells(s, ChargeWirePulses.STOCK, levels) == 0 && levels[3] == 0, "no landings: grey");
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        require(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " (expected " + expected + ")");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    /** The bundled photo keys (ScooterPhotos.resource), read without Android. */
    private static final class ModelRegistryKeys {
        static boolean contains(String key) {
            return new java.io.File("res/drawable-nodpi/" + key + ".webp").isFile()
                    && new java.io.File("res/drawable-nodpi/" + key + "_charge.webp").isFile();
        }
    }
}
