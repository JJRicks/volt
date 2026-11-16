package com.jjricks.volt;

/**
 * The dock turning into its All sheet (the owner's request of Nov 15, after #510: "it should move the actual dock"), in
 * plain Java so it can be tested off the phone: a container transform. One surface is the dock at rest and the open
 * sheet at the other end; DragSheet draws it from these numbers on every frame of a pull, a drag or a settle.
 *
 * <p>Progress comes from the sheet's own spring position (SheetSpring's offset, px below open): 0 is the dock, 1 is
 * open, above 1 is the bounce or the rubber band past open (at most SheetSpring.CAP_DP). On the way:
 * <ul>
 *   <li>the surface's top edge is the sheet's top, 1:1 with the finger and the spring: open's top plus the offset;</li>
 *   <li>its sides, bottom, corner radii, fill, outline and elevation ease from the dock's to the open sheet's along
 *   {@link #ease} of the progress (Motion.STANDARD's curve), and past open stay the sheet's (the bottom rides up with
 *   the bounce, as #510's whole sheet did, never above the screen's edge: the sheet reaches CAP_DP and more below it);</li>
 *   <li>the content fades through: the dock's buttons fade out where they are by {@link #BUTTONS_GONE} of the way, and
 *   the sheet's handle, title and list fade in from {@link #CONTENT_FROM} to open, settling up by {@link #LIFT_DP} as
 *   they come, so the two never show at once.</li>
 * </ul>
 * Positions are px in one coordinate space (DragSheet's frame), y down.
 */
final class SheetMorph {
    /** The dock's buttons are gone by here (progress). */
    static final double BUTTONS_GONE = .25;
    /** The sheet's content starts to show here (progress), and is fully shown at open. */
    static final double CONTENT_FROM = .35;
    /** The sheet's content arrives this far (dp) below its place and settles up into it. */
    static final float LIFT_DP = 8;

    private SheetMorph() {}

    /**
     * A surface: its edges, its corner radii (top left, top right, bottom right, bottom left), fill and outline colours
     * (ARGB), outline width and elevation, all px.
     */
    static final class Box {
        final double left, top, right, bottom;
        final double[] radii;
        final int fill, stroke;
        final double strokeWidth, elevation;

        Box(double left, double top, double right, double bottom, double[] radii, int fill, int stroke, double strokeWidth, double elevation) {
            if (radii == null || radii.length != 4) throw new IllegalArgumentException("four corner radii");
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            this.radii = radii.clone(); this.fill = fill; this.stroke = stroke; this.strokeWidth = strokeWidth; this.elevation = elevation;
        }

        double width() { return right - left; }
        double height() { return bottom - top; }

        /** The same surface moved down by dy. */
        Box shifted(double dy) { return new Box(left, top + dy, right, bottom + dy, radii, fill, stroke, strokeWidth, elevation); }

        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "[%.1f, %.1f, %.1f, %.1f r %.1f/%.1f/%.1f/%.1f fill %08x stroke %08x/%.1f z %.1f]",
                    left, top, right, bottom, radii[0], radii[1], radii[2], radii[3], fill, stroke, strokeWidth, elevation);
        }
    }

    /** How far from the dock (0) to open (1) a sheet offset px below open is, over travel px (the dock's top less open's). */
    static double progress(double offset, double travel) {
        if (!(travel > 0)) return 1;
        return 1 - offset / travel;
    }

    /**
     * The surface at offset (px below open; travel at the dock, negative past open), between the dock's surface and the
     * open sheet's (open: its top where the sheet's top is when open, its bottom below the screen). travel is
     * dock.top - open.top, so the top edge runs 1:1 from one to the other.
     */
    static Box at(Box dock, Box open, double offset, double travel) {
        double p = progress(offset, travel), e = ease(p), past = Math.min(0, offset);
        double[] radii = new double[4];
        for (int i = 0; i < 4; i++) radii[i] = lerp(dock.radii[i], open.radii[i], e);
        double top = offset >= travel ? dock.top : open.top + offset;
        return new Box(lerp(dock.left, open.left, e), top, lerp(dock.right, open.right, e), lerp(dock.bottom, open.bottom, e) + past,
                radii, mix(dock.fill, open.fill, e), mix(dock.stroke, open.stroke, e), lerp(dock.strokeWidth, open.strokeWidth, e), lerp(dock.elevation, open.elevation, e));
    }

    /** The dock's buttons' opacity at progress p: 1 at the dock, fading out to 0 at BUTTONS_GONE (ease-in, so they go quickly). */
    static double buttons(double p) {
        if (p <= 0) return 1;
        if (p >= BUTTONS_GONE) return 0;
        return 1 - FADE_OUT.y(p / BUTTONS_GONE);
    }

    /** The sheet's content's opacity at progress p: 0 up to CONTENT_FROM, then easing in (ease-out) to 1 at open. */
    static double content(double p) {
        if (p <= CONTENT_FROM) return 0;
        if (p >= 1) return 1;
        return FADE_IN.y((p - CONTENT_FROM) / (1 - CONTENT_FROM));
    }

    /** How far (px) below its place the sheet's content shows at progress p: lift at CONTENT_FROM and before, 0 at open and past it. */
    static double lift(double p, double lift) {
        if (p >= 1) return 0;
        if (p <= CONTENT_FROM) return lift;
        return lift * (1 - STANDARD.y((p - CONTENT_FROM) / (1 - CONTENT_FROM)));
    }

    /** The shape's easing of progress p (clamped to 0..1): Motion.STANDARD's curve, quick through the middle, gentle at both ends. */
    static double ease(double p) {
        if (!(p > 0)) return 0;
        if (p >= 1) return 1;
        return STANDARD.y(p);
    }

    static double lerp(double from, double to, double t) { return t <= 0 ? from : t >= 1 ? to : from + (to - from) * t; }

    /** from blended toward to by t (0..1), each ARGB channel. */
    static int mix(int from, int to, double t) {
        if (t <= 0) return from;
        if (t >= 1) return to;
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 255, b = (to >>> shift) & 255;
            out |= ((int) Math.round(a + (b - a) * t) & 255) << shift;
        }
        return out;
    }

    // ---- Cubic Bézier easing (as Android's PathInterpolator, from (0, 0) to (1, 1)) ---------------------------------------

    /** Motion.STANDARD (.2, 0, 0, 1); the fades' ease-in (.4, 0, 1, 1) and ease-out (0, 0, .2, 1), as Motion's FADE_OUT and FADE_IN. */
    static final Curve STANDARD = new Curve(.2, 0, 0, 1), FADE_OUT = new Curve(.4, 0, 1, 1), FADE_IN = new Curve(0, 0, .2, 1);

    static final class Curve {
        private final double x1, y1, x2, y2;
        Curve(double x1, double y1, double x2, double y2) { this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; }

        /** The curve's y where its x is x (0..1). */
        double y(double x) {
            if (x <= 0) return 0;
            if (x >= 1) return 1;
            // x(t) only rises (both control x within 0..1): bisection finds t.
            double lo = 0, hi = 1, t = x;
            for (int i = 0; i < 60; i++) {
                t = (lo + hi) / 2;
                if (bezier(t, x1, x2) < x) lo = t; else hi = t;
            }
            return bezier((lo + hi) / 2, y1, y2);
        }

        private static double bezier(double t, double a, double b) {
            double u = 1 - t;
            return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t;
        }
    }
}
