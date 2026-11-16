package com.jjricks.volt;

/**
 * SheetMorph: the dock growing into its All sheet. Geometry in px at a 2.625 density (a 420 dpi phone): a 412 x 915 dp
 * phone with today's floating dock, the same phone with an edge-to-edge dock whose bottom is square (the contributor's
 * draft #508), and a 790 x 820 dp unfolded Fold with a centred, narrower dock. Each is swept from the dock to open and
 * past it in 0.25 px steps.
 */
public final class SheetMorphTest {
    private static int checks;
    private static final double DP = 2.625, CAP = SheetSpring.CAP_DP * DP;

    public static void main(String[] args) {
        for (Case c : cases()) {
            exactEnds(c);
            monotonic(c);
            pastOpen(c);
            topFollowsOffset(c);
        }
        contentWindows();
        easing();
        colours();
        System.out.println("SheetMorph: " + checks + " checks passed");
    }

    private static final class Case {
        final String name; final SheetMorph.Box dock, open; final double travel;
        Case(String name, SheetMorph.Box dock, SheetMorph.Box open) { this.name = name; this.dock = dock; this.open = open; travel = dock.top - open.top; }
    }

    private static Case[] cases() {
        double w = 412 * DP, h = 915 * DP, nav = 24 * DP, r = 26 * DP, s = 24 * DP;
        int surface = 0xff1f201e, bg = 0xff111211, hair = 0x1ff4f5f0, raised = 0xff2a2b28;
        // The open sheet: full width, its top 430 dp from the bottom, reaching 24 dp below the screen.
        SheetMorph.Box phoneOpen = new SheetMorph.Box(0, h - 430 * DP, w, h + 24 * DP, new double[]{s, s, 0, 0}, bg, raised, DP, 0);
        SheetMorph.Box floating = new SheetMorph.Box(14 * DP, h - nav - 10 * DP - 94 * DP, w - 14 * DP, h - nav - 10 * DP, new double[]{r, r, r, r}, surface, hair, DP, 7 * DP);
        SheetMorph.Box edge = new SheetMorph.Box(-DP, h - 120 * DP, w + DP, h + DP, new double[]{r, r, 0, 0}, surface, hair, DP, 7 * DP);
        double fw = 790 * DP, fh = 820 * DP, dw = 742 * DP;
        SheetMorph.Box foldOpen = new SheetMorph.Box(0, fh - 600 * DP, fw, fh + 24 * DP, new double[]{s, s, 0, 0}, bg, raised, DP, 0);
        SheetMorph.Box centred = new SheetMorph.Box((fw - dw) / 2, fh - nav - 10 * DP - 80 * DP, (fw + dw) / 2, fh - nav - 10 * DP, new double[]{r, r, r, r}, surface, hair, DP, 7 * DP);
        return new Case[]{new Case("phone, floating dock", floating, phoneOpen), new Case("phone, edge-to-edge square-bottomed dock (#508)", edge, phoneOpen),
                new Case("unfolded Fold, centred dock", centred, foldOpen)};
    }

    /** At the dock (offset = travel, and anything past it) every value is the dock's exactly; at open, the sheet's exactly. */
    private static void exactEnds(Case c) {
        same(SheetMorph.at(c.dock, c.open, c.travel, c.travel), c.dock, c.name + ": at the dock");
        same(SheetMorph.at(c.dock, c.open, c.travel + 40, c.travel), c.dock, c.name + ": below the dock (never happens) still the dock");
        same(SheetMorph.at(c.dock, c.open, 0, c.travel), c.open, c.name + ": at open");
        near(SheetMorph.progress(c.travel, c.travel), 0, 0, c.name + ": progress 0 at the dock");
        near(SheetMorph.progress(0, c.travel), 1, 0, c.name + ": progress 1 at open");
    }

    /** From the dock to open: the top only rises, the sides only widen, the bottom only falls, and radii, fill and elevation only move toward the sheet's. */
    private static void monotonic(Case c) {
        SheetMorph.Box last = SheetMorph.at(c.dock, c.open, c.travel, c.travel);
        for (double offset = c.travel - .25; offset >= 0; offset -= .25) {
            SheetMorph.Box now = SheetMorph.at(c.dock, c.open, offset, c.travel);
            require(now.top < last.top, c.name + ": the top rises at " + offset);
            require(toward(last.left, now.left, c.open.left) && toward(last.right, now.right, c.open.right), c.name + ": the sides only move toward the sheet's at " + offset);
            require(now.bottom >= last.bottom - 1e-9, c.name + ": the bottom only falls at " + offset);
            for (int i = 0; i < 4; i++) require(toward(last.radii[i], now.radii[i], c.open.radii[i]), c.name + ": corner " + i + " moves toward the sheet's at " + offset);
            require(toward(last.elevation, now.elevation, c.open.elevation), c.name + ": the elevation moves toward the sheet's at " + offset);
            for (int shift = 0; shift <= 24; shift += 8)
                require(toward((last.fill >>> shift) & 255, (now.fill >>> shift) & 255, (c.open.fill >>> shift) & 255), c.name + ": the fill moves toward the sheet's at " + offset);
            require(between(now.left, c.dock.left, c.open.left) && between(now.right, c.dock.right, c.open.right), c.name + ": the sides stay between the dock's and the sheet's at " + offset);
            // The surface always holds the dock's place on screen, so the dock's buttons fading where they are stay on it
            // (an edge-to-edge dock's outline lies 1 px past the screen's sides, where the sheet's does not reach).
            require(now.top <= c.dock.top && now.bottom >= c.dock.bottom - 1e-9 && now.left <= Math.max(c.dock.left, 0) + 1e-9 && now.right >= Math.min(c.dock.right, c.open.right) - 1e-9,
                    c.name + ": the surface covers the dock's place at " + offset);
            // No edge jumps: a 0.25 px step of the sheet moves no edge by more than a few px.
            require(Math.abs(now.left - last.left) < 3 && Math.abs(now.right - last.right) < 3 && Math.abs(now.bottom - last.bottom) < 3, c.name + ": no edge jumps at " + offset);
            last = now;
        }
    }

    /** Past open (the bounce and the rubber band, up to the cap): the sheet's shape, the top and bottom raised by the offset, the bottom still past the screen. */
    private static void pastOpen(Case c) {
        for (double offset = -.25; offset >= -CAP; offset -= .25) {
            SheetMorph.Box now = SheetMorph.at(c.dock, c.open, offset, c.travel);
            near(now.top, c.open.top + offset, 1e-9, c.name + ": past open the top is open's plus the offset");
            near(now.bottom, c.open.bottom + offset, 1e-9, c.name + ": past open the bottom rides up with it");
            require(now.top >= c.open.top - CAP - 1e-9, c.name + ": never past open by more than the cap");
            require(now.bottom >= c.open.bottom - CAP - 1e-9, c.name + ": the bottom stays within the cap of its place (the open sheet reaches further below the screen)");
            near(now.left, c.open.left, 0, c.name + ": past open the left side is the sheet's");
            near(now.right, c.open.right, 0, c.name + ": past open the right side is the sheet's");
            for (int i = 0; i < 4; i++) near(now.radii[i], c.open.radii[i], 0, c.name + ": past open corner " + i + " is the sheet's");
            require(now.fill == c.open.fill && now.elevation == c.open.elevation, c.name + ": past open the fill and elevation are the sheet's");
        }
    }

    /** The top is open's top plus the offset everywhere: 1:1 with the finger and the spring. */
    private static void topFollowsOffset(Case c) {
        for (double offset = c.travel; offset >= -CAP; offset -= 7.3)
            near(SheetMorph.at(c.dock, c.open, offset, c.travel).top, c.open.top + Math.min(offset, c.travel), 1e-6, c.name + ": the top follows the offset at " + offset);
    }

    /** The dock's buttons are gone by a quarter, the sheet's content shows from 35%: never both, each monotonic, exact at the ends. */
    private static void contentWindows() {
        double lastButtons = 1, lastContent = 0, lastLift = SheetMorph.LIFT_DP * DP;
        for (double p = -.2; p <= 1.1; p += .0005) {
            double buttons = SheetMorph.buttons(p), content = SheetMorph.content(p), lift = SheetMorph.lift(p, SheetMorph.LIFT_DP * DP);
            require(buttons >= 0 && buttons <= 1 && content >= 0 && content <= 1, "opacities stay within 0..1 at " + p);
            require(Math.min(buttons, content) == 0, "the dock's buttons and the sheet's content never show together (" + p + ": " + buttons + ", " + content + ")");
            if (p >= SheetMorph.BUTTONS_GONE) require(buttons == 0, "the dock's buttons are gone by a quarter (" + p + ": " + buttons + ")");
            if (p <= SheetMorph.CONTENT_FROM) require(content == 0, "the sheet's content is hidden up to 35% (" + p + ": " + content + ")");
            if (p <= 0) require(buttons == 1, "the dock's buttons are whole at the dock");
            if (p >= 1) require(content == 1 && lift == 0, "the sheet's content is whole and in place at open and past it");
            require(buttons <= lastButtons + 1e-12 && content >= lastContent - 1e-12, "the fades only go one way at " + p);
            require(lift <= lastLift + 1e-12 && lift >= 0 && lift <= SheetMorph.LIFT_DP * DP + 1e-9, "the content settles up by at most 8 dp, only upward, at " + p);
            lastButtons = buttons; lastContent = content; lastLift = lift;
        }
        require(SheetMorph.BUTTONS_GONE < SheetMorph.CONTENT_FROM, "a gap between the two fades");
    }

    private static void easing() {
        near(SheetMorph.ease(0), 0, 0, "ease(0)"); near(SheetMorph.ease(1), 1, 0, "ease(1)");
        near(SheetMorph.ease(-3), 0, 0, "ease clamps below"); near(SheetMorph.ease(4), 1, 0, "ease clamps above");
        double last = 0;
        for (double p = .001; p < 1; p += .001) { double e = SheetMorph.ease(p); require(e >= last && e > 0 && e < 1, "ease rises at " + p); last = e; }
        // Each curve lies on its Bézier: for points sampled along the parameter, y(x) gives back that point's y.
        for (double[] c : new double[][]{{.2, 0, 0, 1}, {.4, 0, 1, 1}, {0, 0, .2, 1}}) {
            SheetMorph.Curve curve = new SheetMorph.Curve(c[0], c[1], c[2], c[3]);
            for (double t = .05; t < 1; t += .05) {
                double u = 1 - t, x = 3 * u * u * t * c[0] + 3 * u * t * t * c[2] + t * t * t, y = 3 * u * u * t * c[1] + 3 * u * t * t * c[3] + t * t * t;
                near(curve.y(x), y, 1e-9, "the curve (" + c[0] + ", " + c[1] + ", " + c[2] + ", " + c[3] + ") at t " + t);
            }
        }
    }

    private static void colours() {
        require(SheetMorph.mix(0xff000000, 0xffffffff, 0) == 0xff000000 && SheetMorph.mix(0xff000000, 0xffffffff, 1) == 0xffffffff, "mix ends exactly");
        require(SheetMorph.mix(0x00ff0000, 0xff0000ff, .5) == 0x80800080, "mix blends each channel, alpha too: " + Integer.toHexString(SheetMorph.mix(0x00ff0000, 0xff0000ff, .5)));
    }

    // ---- Plumbing -----------------------------------------------------------------------------------------------------

    private static boolean between(double value, double a, double b) { return value >= Math.min(a, b) - 1e-9 && value <= Math.max(a, b) + 1e-9; }

    private static boolean toward(double before, double now, double end) {
        return Math.abs(now - end) <= Math.abs(before - end) + 1e-9;
    }

    private static void same(SheetMorph.Box a, SheetMorph.Box b, String what) {
        boolean equal = a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom && a.fill == b.fill && a.stroke == b.stroke
                && a.strokeWidth == b.strokeWidth && a.elevation == b.elevation;
        for (int i = 0; i < 4; i++) equal &= a.radii[i] == b.radii[i];
        require(equal, what + ": " + a + " is not " + b);
    }

    private static void near(double actual, double expected, double tolerance, String what) {
        require(Math.abs(actual - expected) <= tolerance, what + ": " + actual + " (expected " + expected + " ± " + tolerance + ")");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
