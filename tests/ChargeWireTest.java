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
        band();
        switching();
        animatorScale();
        scaleChange();
        landing();
        glow();
        glowStamps();
        comets();
        System.out.println("ChargeWire: " + checks + " checks passed");
    }

    private static void models() {
        ChargeWireModels.Model m = ChargeWireModels.forKey("ut5max");
        require(m == ChargeWireModels.UT5_MAX, "the UT5 MAX has the picture");
        ChargeWireModels.Model x = ChargeWireModels.forKey("ut5ultrax");
        require(x == ChargeWireModels.UT5_ULTRA_X, "the UT5 Ultra X has the picture");
        // The Ultra X is the MAX's body with its gold pieced in: exactly the same geometry and place.
        require(x.width == m.width && x.height == m.height && x.wire == m.wire && x.cells == m.cells
                && x.panelAbove == m.panelAbove && x.panelBelow == m.panelBelow && x.panelRight == m.panelRight
                && x.panelLeftTop == m.panelLeftTop && x.panelLeftBottom == m.panelLeftBottom && x.panelRadius == m.panelRadius
                && x.cableWidth == m.cableWidth && x.widthShare == m.widthShare && x.centreShare == m.centreShare
                && x.tyreGapDp == m.tyreGapDp, "the Ultra X: the MAX's geometry");
        require(ChargeWireModels.all().length == 2, "two animated pictures");
        require(ChargeWireModels.forKey(null) == null && ChargeWireModels.forKey("") == null, "no model, no picture");
        for (String other : new String[] {"n65i", "st3pro", "gt5max", "gt3max", "xt5ultra", "UT5 MAX", "ut5ultra"})
            require(ChargeWireModels.forKey(other) == null, other + " has no animated picture");
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
        // Each key is a bundled photo key (ModelRegistry.photo), so the model's identity picks it as it picks the photo,
        // and its cut-out is bundled at the table's size.
        for (ChargeWireModels.Model each : ChargeWireModels.all()) {
            require(ModelRegistryKeys.contains(each.key), each.key + " is a photo key with a cut-out");
            int[] size = WebpSize.of("res/drawable-nodpi/" + each.key + "_charge.webp");
            require(size[0] == each.width && size[1] == each.height, each.key + ": the cut-out is the table's size");
        }
    }

    /** The prototype's phone: 344 dp, 18 dp padding, the picture 283.6 dp wide from 39.3 dp, its tyres 9.4 dp over the track. */
    private static void placement() {
        ChargeWireModels.Model m = ChargeWireModels.UT5_MAX;
        // At 3 px per dp: a 308 dp content width; a tall stage.
        float box = ChargePictureFit.widthBox(924);
        ChargeWireModels.Placement p = ChargeWireModels.place(m, 924, 600, box);
        near(p.width(m) / 3, 283.6, .05, "about 92% of the content width: 283.6 dp wide on a 344 dp phone");
        near(p.left / 3 + 18, 39.3, .05, "its left edge at 39.3 dp on screen");
        near(p.height(m) / 3, 283.6 * 691 / 1438, .05, "its height follows the cut-out");
        near(p.top + p.height(m), 600, .01, "its tyres stand at the floor");
        // A smaller box (ChargePictureFit): smaller, same centre, still standing on the floor.
        ChargeWireModels.Placement q = ChargeWireModels.place(m, 924, 600, 240);
        near(q.height(m), 240, .01, "a smaller box: the picture its height");
        require(q.width(m) < p.width(m), "and is narrower");
        near(q.left + q.width(m) / 2, p.left + p.width(m) / 2, .01, "about the same centre");
        near(q.top + q.height(m), 600, .01, "on the floor");
        // 412 dp: wider in proportion.
        ChargeWireModels.Placement r = ChargeWireModels.place(m, (412 - 36) * 3, 900, ChargePictureFit.widthBox((412 - 36) * 3));
        near(r.width(m) / 3, 283.6 * 376 / 308, .05, "412 dp: the same share of the width");
        require(ChargeWireModels.place(m, 0, 100, 50).scale == 0 && ChargeWireModels.place(m, 100, 100, 0).scale == 0, "no room, no picture");
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

    /** The mode's band round 200 W (review of #483, P3-3): up at 205 W, down below 195 W while one runs. */
    private static void band() {
        ChargeWirePulses.Mode stock = ChargeWirePulses.STOCK, fast = ChargeWirePulses.FAST;
        require(ChargeWirePulses.mode(null, 200d) == fast && ChargeWirePulses.mode(null, 199d) == stock, "nothing running: the 200 W rule");
        require(ChargeWirePulses.mode(stock, 199d) == stock && ChargeWirePulses.mode(stock, 204.9) == stock, "one pulse: stays below 205 W");
        require(ChargeWirePulses.mode(stock, 205d) == fast && ChargeWirePulses.mode(stock, 540d) == fast, "one pulse: three from 205 W");
        require(ChargeWirePulses.mode(fast, 199d) == fast && ChargeWirePulses.mode(fast, 195d) == fast, "three: stay down to 195 W");
        require(ChargeWirePulses.mode(fast, 194.9) == stock && ChargeWirePulses.mode(fast, 119d) == stock, "three: one below 195 W");
        require(ChargeWirePulses.mode(fast, null) == stock && ChargeWirePulses.mode(fast, Double.NaN) == stock, "unknown power: one");
        // The review's readings, one a second: 205, 199, 205 W never change the mode once it runs.
        for (double first : new double[] {205, 199}) {
            ChargeWirePulses.Schedule schedule = new ChargeWirePulses.Schedule();
            ChargeWirePulses.Mode at = schedule.follow(first, 0);
            int switches = 0;
            double[] readings = {205, 199, 205, 199, 205};
            for (int i = 0; i < readings.length; i++) {
                ChargeWirePulses.Mode next = schedule.follow(readings[i], 1 + i);
                if (next != at) switches++;
                at = next;
            }
            require(at == fast && switches == (first == 205 ? 0 : 1), "from " + first + " W then 205, 199, 205 W: " + switches + " switch(es), ends " + at);
        }
        // From 205 W the frames are the steady fast mode's, frame for frame: no jump at all.
        ChargeWirePulses.Schedule schedule = new ChargeWirePulses.Schedule();
        ChargeWirePulses.State a = new ChargeWirePulses.State(), b = new ChargeWirePulses.State();
        for (int f = 0; f < 60 * 6; f++) {
            double t = f / 60.0, w = t < 2 ? 205 : t < 4 ? 199 : 205;
            schedule.follow(w, t);
            schedule.state(t, a);
            ChargeWirePulses.state(t, fast, b);
            require(a.pulses == b.pulses && a.lands == b.lands, "205/199/205 W: the fast mode's pulses at " + t);
            for (int i = 0; i < a.pulses; i++) near(a.progress[i], b.progress[i], 0, "205/199/205 W: in place at " + t);
        }
    }

    /**
     * A real switch carries the phase over (review of #483, P3-3): every pulse on the cable keeps moving at its own
     * speed and lands where it would have, no pulse appears part-way down the cable, a landing keeps fading as it began;
     * the new mode's first pulse follows at its own spacing. So the pulses speed up and slow down without a jump.
     */
    private static void switching() {
        ChargeWirePulses.Schedule schedule = new ChargeWirePulses.Schedule();
        ChargeWirePulses.State s = new ChargeWirePulses.State();
        java.util.Map<String, Double> seen = new java.util.HashMap<>();
        double step = 1 / 60.0;
        int fastMost = 0, stockAfter = 0, frames = 0;
        double upAt = 5.03, downAt = 12.4, firstFastLeaves = Double.NaN;
        for (int f = 0; f < 60 * 25; f++) {
            double t = f * step, w = t < upAt ? 119 : t < downAt ? 250 : 150;
            schedule.follow(w, t);
            schedule.state(t, s);
            java.util.Map<String, Double> now = new java.util.HashMap<>();
            int travelling = 0;
            for (int i = 0; i < s.pulses; i++) {
                ChargeWirePulses.Mode m = s.mode[i];
                String id = m.name + s.number[i];
                double p = s.progress[i];
                Double before = seen.get(id);
                if (before == null) require(p <= step / m.trip + 1e-9 || f == 0, "a pulse appears part-way at " + t + ": " + id + " " + p);
                else near(p - before, step / m.trip, 1e-9, "a pulse moves at its own speed at " + t + ": " + id);
                now.put(id, p);
                // Travelling: left (not at the very instant it leaves, when the one ahead may be just landing) and not landed.
                if (p > 1e-6 && p < 1 - 1e-6) travelling++;
                if (m == ChargeWirePulses.FAST && Double.isNaN(firstFastLeaves)) firstFastLeaves = t;
            }
            // Pulses that were on the cable and still had their trip to finish are still there.
            for (java.util.Map.Entry<String, Double> e : seen.entrySet())
                if (e.getValue() + step / (e.getKey().startsWith("fast") ? 1.1 : 1.8) < 1) require(now.containsKey(e.getKey()), "a pulse vanished at " + t + ": " + e.getKey());
            seen = now;
            require(s.pulses <= ChargeWirePulses.SLOTS && s.lands <= ChargeWirePulses.MAX_LANDS, "fits the pool at " + t);
            if (t > upAt + 4 && t < downAt) fastMost = Math.max(fastMost, travelling);
            if (t > downAt + 4) stockAfter = Math.max(stockAfter, travelling);
            frames++;
        }
        require(fastMost == 3, "three at once after speeding up: " + fastMost);
        require(stockAfter == 1, "one at a time after slowing down: " + stockAfter);
        // Speeding up: the first fast pulse lands at least a fast period after the single one in flight did.
        require(firstFastLeaves >= upAt - 1e-9, "the first fast pulse leaves after the switch: " + firstFastLeaves);
        // A landing keeps its own fade across a switch: the cells' light changes about as fast through a switch as a
        // landing's quick rise makes it change in either steady mode (within 10%: two landings in different modes can
        // overlap); never the jump of every landing re-timed at once.
        double steady = Math.max(cellJump(250, 250), cellJump(120, 120)) * 1.1;
        require(cellJump(250, 120) <= steady,
                "the cells' light never jumps across a switch: " + cellJump(250, 120) + " vs steady " + Math.max(cellJump(250, 250), cellJump(120, 120)));
        require(cellJump(120, 250) <= steady, "nor speeding up: " + cellJump(120, 250));
        require(frames == 60 * 25, "every frame checked");
    }

    /** The largest change of the cells' overall light between two frames at 60 fps over 10 s, switching at 3.9 s. */
    private static double cellJump(double before, double after) {
        ChargeWirePulses.Schedule light = new ChargeWirePulses.Schedule();
        ChargeWirePulses.State s = new ChargeWirePulses.State();
        float[] levels = new float[ChargeWire.HALF_COLUMNS];
        double last = -1, worst = 0;
        for (int f = 0; f < 60 * 10; f++) {
            double t = f / 60.0;
            light.follow(t < 3.9 ? before : after, t);
            light.state(t, s);
            double e = ChargeWirePulses.cells(s, ChargeWirePulses.STOCK, levels);
            if (last >= 0) worst = Math.max(worst, Math.abs(e - last));
            last = e;
        }
        return worst;
    }

    /**
     * The animator duration scale (review of #483, P1): read from Settings.Global, never ValueAnimator.getDurationScale()
     * (public only from Android 13; a blocked hidden API on Android 9 and 10, where Home would crash while a UT5 MAX
     * charges). The app's sources must not call it again.
     */
    private static void animatorScale() {
        near(ChargeWirePulses.timeScale(1), 1, 0, "normal speed");
        near(ChargeWirePulses.timeScale(2), 2, 0, "2x slower");
        near(ChargeWirePulses.timeScale(.5f), .5, 0, "faster");
        near(ChargeWirePulses.timeScale(0), 1, 0, "animations off: as the platform's animators");
        near(ChargeWirePulses.timeScale(Float.NaN), 1, 0, "unknown: 1");
        near(ChargeWirePulses.timeScale(Float.POSITIVE_INFINITY), 1, 0, "unreadable: 1");
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(java.nio.file.Paths.get("src"))) {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String code = withoutComments(new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
                require(!code.matches("(?s).*\\bgetDurationScale\\s*\\(.*"), file + " calls ValueAnimator.getDurationScale() (API 33; it throws on Android 9 and 10)");
            }
            String view = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/com/jjricks/volt/ChargeWireView.java")), java.nio.charset.StandardCharsets.UTF_8);
            require(withoutComments(view).contains("Settings.Global.ANIMATOR_DURATION_SCALE"), "ChargeWireView reads the scale from Settings.Global");
            int onDraw = view.indexOf("private void drawLive(");
            require(onDraw > 0 && !withoutComments(view.substring(onDraw, view.indexOf("private void stretch(", onDraw))).contains("Settings.Global"), "not per frame: drawLive reads no setting");
        } catch (java.io.IOException e) {
            throw new AssertionError("reading the sources: " + e);
        }
    }

    /**
     * The pulses' time when the animator duration scale changes while Home stays on screen (review of #483, P3): it
     * carries on from where it is at the new speed, never jumping or restarting; 0 runs as 1 (the view rests then).
     * The view watches the setting only while its live layer is attached.
     */
    private static void scaleChange() {
        ChargeWirePulses.Clock c = new ChargeWirePulses.Clock();
        c.start(10_000, 1);
        near(c.seconds(10_000), 0, 0, "starts at 0");
        near(c.seconds(12_000), 2, 1e-9, "2 s at 1x");
        c.rescale(12_000, .5f);
        near(c.seconds(12_000), 2, 1e-9, "a new scale keeps the time");
        near(c.seconds(13_000), 4, 1e-9, "then runs twice as fast at 0.5x");
        c.rescale(13_000, 2);
        near(c.seconds(13_000), 4, 1e-9, "no jump at 2x");
        near(c.seconds(15_000), 5, 1e-9, "half as fast at 2x");
        near(c.divisor(), 2, 0, "the divisor in force");
        c.rescale(15_000, 2);
        near(c.seconds(17_000), 6, 1e-9, "the same scale again changes nothing");
        c.rescale(17_000, 0);
        near(c.divisor(), 1, 0, "0 runs as 1 (timeScale)");
        near(c.seconds(18_000), 7, 1e-9, "0: at 1x from where it was");
        c.rescale(18_000, Float.NaN);
        near(c.seconds(19_000), 8, 1e-9, "unknown: 1x");
        for (float scale : new float[] {.5f, 1, 1.5f, 2, 5, 10}) {
            ChargeWirePulses.Clock a = new ChargeWirePulses.Clock(), b = new ChargeWirePulses.Clock();
            a.start(0, 1); b.start(0, 1);
            a.rescale(1_000, scale);
            // Changing speed part-way is the same as the time so far plus the rest at the new speed, at any moment.
            for (long at = 1_000; at <= 9_000; at += 1_000)
                near(a.seconds(at), b.seconds(1_000) + (at - 1_000) / 1000.0 / scale, 1e-9, scale + "x from 1 s, at " + at + " ms");
            double before = a.seconds(999), after = a.seconds(1_001);
            require(after > before && after - before < .01, scale + "x: continuous across the change");
        }
        c.start(20_000, 2);
        near(c.seconds(20_000), 0, 0, "starting again starts from 0");
        near(c.seconds(22_000), 1, 1e-9, "at its own scale");
        try {
            String view = withoutComments(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/com/jjricks/volt/ChargeWireView.java")), java.nio.charset.StandardCharsets.UTF_8));
            require(view.contains("registerContentObserver(") && view.contains("unregisterContentObserver("), "ChargeWireView watches the setting and stops watching it");
            int attach = view.indexOf("onAttachedToWindow() {"), detach = view.indexOf("onDetachedFromWindow() { watchScale(false)");
            require(attach > 0 && view.indexOf("watchScale(true)", attach) > attach && view.indexOf("watchScale(true)", attach) < view.indexOf('\n', attach), "registered on attach");
            require(detach > 0, "unregistered on detach");
        } catch (java.io.IOException e) {
            throw new AssertionError("reading the sources: " + e);
        }
    }

    /** Java source without its comments (strings are left; none in the app holds a comment marker that matters here). */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
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

    /** The glow's nested strokes build the prototype's blurred stroke (44 px wide, blurred by 11) band by band. */
    private static void glow() {
        for (double width : new double[] {44, 44 * 0.55}) {
            double[] half = new double[ChargeWirePulses.GLOW_Z.length], profile = new double[half.length], alpha = new double[half.length];
            int n = ChargeWirePulses.glowRings(width, 11, half, profile);
            require(n >= 4, width + ": enough rings for a smooth blur");
            near(half[0], width / 2 + 11 * 2.2, 1e-9, width + ": the outermost ring 2.2 sigmas out");
            for (int k = 1; k < n; k++) require(half[k] < half[k - 1] && profile[k] > profile[k - 1], width + ": rings narrower, brighter inwards");
            for (double peak : new double[] {1, .46, .1}) {
                ChargeWirePulses.glowAlphas(peak, profile, n, alpha);
                double covered = 0;
                for (int k = 0; k < n; k++) {
                    require(alpha[k] >= 0 && alpha[k] <= 1, width + ": an opacity");
                    covered = covered + (1 - covered) * alpha[k];
                    near(covered, peak * profile[k], 1e-9, width + " at " + peak + ": band " + k + " as blurred");
                }
            }
        }
        near(ChargeWirePulses.blurred(0, 44, 11), 2 * ChargePercentShade.phi(2) - 1, 1e-12, "the blurred stroke's middle");
        require(ChargeWirePulses.blurred(22 + 33, 44, 11) < .002, "nothing 3 sigmas past its edge");
    }

    /**
     * The glow's soft stamps (the owner, 17 Nov 2026: banding on the balls of energy): along a straight stretch of cable
     * their blend is one smooth band (no step between neighbouring pixels across it or along it bigger than a few 8-bit
     * levels), as bright along its centre as the blurred stroke it replaces, falling off from the centre, inside the
     * widest glow's reach, and with no visible ripple between stamps.
     */
    private static void glowStamps() {
        ChargeWireComet c = new ChargeWireComet(7.6f, 0xFF5FCB7E, 0xFF79DB95, 0xFFD6FBE0, 0xFF9BE8B2, 0xFFF4FFF6, 1f);
        int glows = 0;
        for (ChargeWireComet.Layer layer : c.layers) {
            if (!layer.glow) continue;
            glows++;
            require(layer.radius > 0 && layer.radius <= ChargeWireComet.REACH + 1e-9, "a stamp inside the glow's reach: " + layer.radius);
            near(layer.stamp(0), 1, 1e-12, "a stamp is solid at its centre");
            require(layer.stamp(layer.radius) == 0 && layer.stamp(layer.radius * .999) < .02, "a stamp fades to nothing at its edge");
            for (double r = 0; r < layer.radius; r += .25) require(layer.stamp(r + .25) <= layer.stamp(r), "a stamp falls off outwards");
            for (double target : new double[] {.46 * layer.centre, .2, .05}) {
                double a = layer.stampAlpha(target);
                // A straight cable along x with stamps every spacing: the band's opacity at (x, d), stamps composited over.
                double centre = band(layer, a, 0, 0), between = band(layer, a, layer.spacing / 2, 0);
                near(centre, target, .01, "the band's centre as bright as the blurred stroke's (" + target + ")");
                require(Math.abs(centre - between) * 255 < .5, "no ripple between stamps along the band: " + centre + " vs " + between);
                double last = centre;
                for (double d = .5; d < layer.radius + 2; d += .5) {
                    double now = band(layer, a, 0, d);
                    require(now <= last + 1e-9, "the band falls off from its centre");
                    // A cut-out pixel is at most about one screen pixel (the picture is drawn at or below its size).
                    require((last - now) * 255 <= 4, "no step across the band bigger than four 8-bit levels at " + d + ": " + (last - now) * 255);
                    last = now;
                }
                require(last * 255 < .5, "nothing past the band's reach");
            }
        }
        require(glows == 2, "two glow layers");
    }

    /** The opacity of a straight band of stamps (every spacing along x, each at opacity a) at (x, d), composited over. */
    private static double band(ChargeWireComet.Layer layer, double a, double x, double d) {
        double clear = 1;
        int reach = (int) Math.ceil(layer.radius / layer.spacing) + 2;
        for (int k = -reach; k <= reach; k++) clear *= 1 - a * layer.stamp(Math.hypot(x - k * layer.spacing, d));
        return 1 - clear;
    }

    /**
     * The comets' strokes per frame (review of #483, C3): averaged and at most over many frames at 60 fps, under a
     * budget for each mode; nothing past the cable's fade; every stroke inside the drawn layer's stretch.
     */
    private static void comets() {
        ChargeWireComet c = new ChargeWireComet(7.6f, 0xFF5FCB7E, 0xFF79DB95, 0xFFD6FBE0, 0xFF9BE8B2, 0xFFF4FFF6, 1f);
        ChargeWire w = new ChargeWire(ChargeWireModels.UT5_MAX, 400);
        final double[] last = new double[1];
        final int[] beyond = new int[1];
        for (ChargeWirePulses.Mode m : new ChargeWirePulses.Mode[] {ChargeWirePulses.STOCK, ChargeWirePulses.FAST}) {
            ChargeWirePulses.State st = new ChargeWirePulses.State();
            long sum = 0; int max = 0, frames = 0;
            ChargeWireComet.Sink count = new ChargeWireComet.Sink() {
                @Override public void piece(double s0, double s1, int argb, float width) {
                    if (s0 < -1e-9 || s1 > w.length + 1e-9 || s1 <= s0) throw new AssertionError("a stroke outside the cable: " + s0 + ".." + s1);
                }
                @Override public void cap(double s, int argb, float r) {}
                @Override public void glow(double s, int layer, float alpha) {
                    if (s < -1e-9 || s > w.length + 1e-9) throw new AssertionError("a glow stamp off the cable: " + s);
                    if (!(alpha > 0 && alpha <= 1)) throw new AssertionError("a glow stamp's opacity: " + alpha);
                    if (!c.layers[layer].glow) throw new AssertionError("a stamp for a layer that is not a glow: " + layer);
                }
            };
            for (double t = 10; t < 10 + m.period * (m == ChargeWirePulses.STOCK ? 4 : 12); t += 1 / 60.0, frames++) {
                ChargeWirePulses.state(t, m, st);
                int d = c.draw(w, st, m, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, count);
                sum += d; max = Math.max(max, d);
            }
            double avg = sum / (double) frames;
            System.out.println("ChargeWire: comet strokes per frame, " + m + ": avg " + Math.round(avg) + ", max " + max);
            require(max <= (m == ChargeWirePulses.STOCK ? 110 : 320), m + ": at most " + max + " strokes a frame");
            require(avg > 10, m + ": the comets draw");
            // Faded out from x 1500 to 1600 (cut-out pixels): nothing drawn whose middle is past 1600.
            ChargeWireComet.Sink faded = new ChargeWireComet.Sink() {
                @Override public void piece(double s0, double s1, int argb, float width) { if (w.xAt((s0 + s1) / 2) >= 1600) beyond[0]++; }
                @Override public void cap(double s, int argb, float r) { if (w.xAt(s) >= 1600) beyond[0]++; }
                @Override public void glow(double s, int layer, float alpha) { if (w.xAt(s) >= 1600) beyond[0]++; }
            };
            for (double t = 10; t < 10 + m.period * 4; t += 1 / 60.0) { ChargeWirePulses.state(t, m, st); c.draw(w, st, m, 1500, 1600, faded); }
        }
        require(beyond[0] == 0, "nothing drawn past the cable's fade: " + beyond[0]);
        near(ChargeWireComet.fadeAt(1400, 1500, 1600), 1, 0, "full before the fade");
        near(ChargeWireComet.fadeAt(1550, 1500, 1600), .5, 1e-9, "half way");
        near(ChargeWireComet.fadeAt(1600, 1500, 1600), 0, 0, "gone at its end");
        near(ChargeWireComet.fadeAt(1e9, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY), 1, 0, "no fade: full everywhere");
        require(ChargeWireComet.withAlpha(0xFF123456, 128) == 0x80123456, "an opacity");
        require(ChargeWireComet.mix(0xFF000000, 0xFFFFFFFF, .5f) == 0xFF808080, "a mix");
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        require(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " (expected " + expected + ")");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    /** A bundled WebP's canvas size (its VP8X header), read without Android. */
    static final class WebpSize {
        static int[] of(String path) {
            try {
                byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
                if (b.length < 30 || !new String(b, 12, 4, "US-ASCII").equals("VP8X")) return new int[] {-1, -1};
                int w = 1 + ((b[24] & 255) | (b[25] & 255) << 8 | (b[26] & 255) << 16);
                int h = 1 + ((b[27] & 255) | (b[28] & 255) << 8 | (b[29] & 255) << 16);
                return new int[] {w, h};
            } catch (java.io.IOException missing) { return new int[] {-1, -1}; }
        }
    }

    /** The bundled photo keys (ScooterPhotos.resource), read without Android. */
    private static final class ModelRegistryKeys {
        static boolean contains(String key) {
            return new java.io.File("res/drawable-nodpi/" + key + ".webp").isFile()
                    && new java.io.File("res/drawable-nodpi/" + key + "_charge.webp").isFile();
        }
    }
}
