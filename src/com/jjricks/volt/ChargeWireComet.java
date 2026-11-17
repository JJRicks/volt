package com.jjricks.volt;

/**
 * The pulses' comets as strokes along the cable (the contributor's charging animation, 14 Nov 2026 on #383): which
 * stretches of the cable each layer of each pulse covers at one moment, in what colour and how wide. Pure Java; the
 * view (ChargeWireView) draws what {@link #draw} hands its {@link Sink}, and the JVM suite counts it (the per-frame
 * draw budget, review of #483).
 *
 * <p>Each pulse is one comet: every layer a stroke of the path from the head down its length, its colour and opacity
 * fading from the head to the tail's end as the prototype's gradient does (by x: the cable only runs left to right),
 * its front end round. Each layer is a few solid strokes, one per stretch in that stretch's colour, never one stroke
 * with a gradient shader (a long thin stroke with a shader crashed the Android emulator's GPU translation); the
 * stretches meet end to end, and the front's round cap is a half disc ahead of the head. The glow layers are the
 * prototype's blurred strokes, drawn as soft round stamps along the cable (each a radial gradient that falls off as a
 * Gaussian, {@link Sink#glow}), close enough that they blend into one smooth band whose brightness follows the
 * layer's fade along its length. They were nested solid strokes stepping through the blur's profile
 * (ChargeWirePulses.glowRings), whose steps showed as bands round the comet's head and along its tail (the owner,
 * 17 Nov 2026: "there seems to be color banding on the balls of energy"). Where the cable fades out short of the
 * screen's edge (a wide window), every stretch and stamp fades with it.
 */
final class ChargeWireComet {
    /** What the view draws. */
    interface Sink {
        /** The cable from arc length s0 to s1 as a solid stroke of this colour (with its opacity) and width. */
        void piece(double s0, double s1, int argb, float width);
        /** The comet's round front at arc length s: a half disc of this radius, ahead of it. */
        void cap(double s, int argb, float radius);
        /**
         * A glow layer's soft stamp centred on the cable at arc length s: the layer's radial gradient (its colour, opacity
         * falling off as {@link Layer#stamp}, to nothing at {@link Layer#radius}) at this overall opacity (0..1).
         */
        void glow(double s, int layer, float alpha);
    }

    /** The pulse's sizes as the prototype draws them on the phone, in cut-out pixels. */
    static final float HALO = 44, BLUR = 11, HEAD = 30;
    static final int GRADIENT_STOPS = 24;
    /**
     * The widest a glow reaches from the cable's centre line (cut-out pixels): the wide glow's outermost ring, as
     * ChargeWireView.liveArea strokes it; a stamp never draws past it.
     */
    static final double REACH = HALO / 2.0 + BLUR * ChargeWirePulses.GLOW_Z[0];
    /**
     * The glow stamps' spacing along the cable, in sigmas: stamps this close blend into a band whose ripple along it stays
     * under one 8-bit level (0.8 left about 0.7 of one; tests/ChargeWireTest.glowStamps).
     */
    static final double STAMP_SPACING = .7;
    static final int STAMP_TABLE = 64;

    /** The blurred stroke's half-maximum test, apart so the Layer's constructor reads plainly. */
    private static final class ChargePulsesHalf {
        static boolean above(double d, double width) {
            return ChargeWirePulses.blurred(d, width, BLUR) > ChargeWirePulses.blurred(0, width, BLUR) / 2;
        }
    }

    /** One layer of a pulse: a stroke along the cable, brightest at the head and fading out down its tail. */
    static final class Layer {
        final boolean glow, head;
        /** Its width in cut-out pixels; its length: a share of the lit trail, or a number of head lengths. */
        final float width;
        final double length;
        /** How many solid stretches it is drawn as. */
        final int pieces;
        /** Its colour (with opacity) from the head (0) to the tail's end (GRADIENT_STOPS). */
        final int[] colors = new int[GRADIENT_STOPS + 1];
        /** A glow's nested strokes (ChargeWirePulses.glowRings): their count, half-widths and the profile inside each. */
        final int rings;
        final double[] half = new double[ChargeWirePulses.GLOW_Z.length], profile = new double[ChargeWirePulses.GLOW_Z.length];

        /**
         * A glow's stamps: their Gaussian's sigma (matched to the blurred stroke's half-maximum width), their radius (where
         * the gradient reaches nothing: three sigmas, never past the widest glow's reach, ChargeWireView.liveArea), their
         * spacing along the cable, and the blurred stroke's opacity along its centre line (as solid as the stroke gets).
         */
        final double sigma, radius, spacing, centre;
        /** The opacity each stamp needs at its centre for the band's centre to reach a target, at 1/64 steps. */
        private final float[] stampAlpha = new float[STAMP_TABLE + 1];

        Layer(boolean glow, boolean head, float width, double length, int pieces) {
            this.glow = glow; this.head = head; this.width = width; this.length = length; this.pieces = pieces;
            rings = glow ? ChargeWirePulses.glowRings(width, BLUR, half, profile) : 0;
            if (!glow) { sigma = radius = spacing = centre = 0; return; }
            centre = ChargeWirePulses.blurred(0, width, BLUR);
            // The blurred stroke's half-maximum distance, by bisection; a Gaussian of the same half-maximum.
            double lo = 0, hi = width / 2 + 6 * BLUR;
            for (int i = 0; i < 50; i++) { double mid = (lo + hi) / 2; if (ChargePulsesHalf.above(mid, width)) lo = mid; else hi = mid; }
            sigma = lo / Math.sqrt(2 * Math.log(2));
            radius = Math.min(3 * sigma, REACH);
            spacing = sigma * STAMP_SPACING;
            // The stamps' opacities at the band's centre, from one sitting on the point outwards both ways.
            int reach = (int) Math.ceil(radius / spacing);
            double[] g = new double[2 * reach + 1];
            for (int k = -reach; k <= reach; k++) g[k + reach] = stamp(Math.abs(k) * spacing);
            for (int i = 0; i <= STAMP_TABLE; i++) {
                double target = Math.min(.999, i / (double) STAMP_TABLE);
                double a0 = 0, a1 = 1;
                for (int it = 0; it < 40; it++) {
                    double a = (a0 + a1) / 2, clear = 1;
                    for (double v : g) clear *= 1 - a * v;
                    if (1 - clear < target) a0 = a; else a1 = a;
                }
                stampAlpha[i] = (float) a1;
            }
        }

        /** A stamp's opacity at distance r from its centre (1 at the centre, 0 from its radius out): a Gaussian, lowered to end at 0. */
        double stamp(double r) {
            if (!glow || r >= radius) return 0;
            double edge = Math.exp(-radius * radius / (2 * sigma * sigma));
            return Math.max(0, (Math.exp(-r * r / (2 * sigma * sigma)) - edge) / (1 - edge));
        }

        /** The opacity a stamp gets for the band's centre to show at opacity target (0..1). */
        float stampAlpha(double target) {
            double f = Math.max(0, Math.min(1, target)) * STAMP_TABLE;
            int i = Math.min(STAMP_TABLE - 1, (int) f);
            return (float) (stampAlpha[i] + (stampAlpha[i + 1] - stampAlpha[i]) * (f - i));
        }

        /** The colour at u (0 head to 1 tail), between the table's stops. */
        int colorAt(double u) {
            double f = Math.max(0, Math.min(1, u)) * GRADIENT_STOPS;
            int i = Math.min(GRADIENT_STOPS - 1, (int) f);
            return mix(colors[i], colors[i + 1], (float) (f - i));
        }
    }

    final Layer[] layers;

    /**
     * The layers for a cable this wide, coloured for the page: the glow's green, the lit cable's, the head's body from
     * its front to its back, its near-white core, and the glow's strength (1 on the dark page, less on the light).
     */
    ChargeWireComet(float cableWidth, int glowGreen, int litCable, int bodyHead, int bodyTail, int core, float glowShare) {
        layers = new Layer[] {
                new Layer(true, false, HALO, 0.55, 8),               // wide glow, strongest round the head
                new Layer(true, false, HALO * 0.55f, 0.85, 8),       // narrower glow down more of the tail
                new Layer(false, false, cableWidth, 1, 14),            // the cable itself, lit green
                new Layer(false, true, cableWidth * 1.22f, 2.4, 6),   // the head's bright body, a little fuller than the cable
                new Layer(false, true, cableWidth * 0.8f, 1.5, 4),    // near-white core
        };
        // Brightness along each layer: {peak, fade exponent}.
        double[][] fades = {{.46, 1.1}, {.50, 1.25}, {.90, 1.5}, {.92, 1.15}, {1, 1.2}};
        for (int j = 0; j < layers.length; j++) {
            for (int k = 0; k <= GRADIENT_STOPS; k++) {
                float u = k / (float) GRADIENT_STOPS;
                int base = j < 2 ? glowGreen : j == 2 ? litCable : j == 3 ? mix(bodyHead, bodyTail, Math.min(1, u * 2)) : core;
                double alpha = fades[j][0] * ChargeWirePulses.easeOut(u, fades[j][1]) * (j < 2 ? glowShare : 1);
                layers[j].colors[k] = withAlpha(base, (int) Math.round(255 * alpha));
            }
        }
    }

    /**
     * Hands the sink every stroke of the pulses in {@code state}; returns how many it drew. The cable keeps its full
     * opacity up to x = fadeStart (cut-out pixels) and fades to nothing at fadeEnd (both infinite: no fade).
     */
    int draw(ChargeWire w, ChargeWirePulses.State state, ChargeWirePulses.Mode mode, double fadeStart, double fadeEnd, Sink sink) {
        double length = w.length;
        int drawn = 0;
        for (int p = 0; p < state.pulses; p++) {
            double progress = state.progress[p];
            // Each pulse's trail in its own mode (pulses on the cable keep theirs across a switch).
            double trail = ChargeWirePulses.trail(length, state.mode[p] != null ? state.mode[p] : mode);
            double head = ChargeWirePulses.head(length, progress), visible = ChargeWirePulses.headVisible(progress);
            for (int j = 0; j < layers.length; j++) {
                Layer layer = layers[j];
                double len = layer.head ? HEAD * layer.length : trail * layer.length;
                double a = Math.max(0, head), b = Math.min(length, head + len);
                if (b - a <= 0.01 || (layer.head && visible <= 0)) continue;
                float x1 = w.xAt(head), span = Math.max(.01f, w.xAt(head + len) - x1);
                double fade = layer.head ? visible : 1;
                if (layer.glow) {
                    // Soft stamps from the head down the layer's length, each at the layer's colour there: their opacity
                    // the one that gives the blurred stroke's centre opacity, so the band is as bright as the rings were.
                    int first = head < 0 ? (int) Math.ceil(-head / layer.spacing) : 0;
                    for (int k = first; ; k++) {
                        double at = head + k * layer.spacing;
                        if (at > b + 1e-9) break;
                        if (at < a - 1e-9) continue;
                        float x = w.xAt(at);
                        int color = layer.colorAt((x - x1) / span);
                        double peak = (color >>> 24) / 255.0 * fade * fadeAt(x, fadeStart, fadeEnd);
                        if (peak <= .002) continue;
                        float alpha = layer.stampAlpha(peak * layer.centre);
                        if (alpha * 255 < .5f) continue;
                        sink.glow(at, j, alpha); drawn++;
                    }
                    continue;
                }
                for (int k = 0; k < layer.pieces; k++) {
                    double s0 = Math.max(a, head + len * k / layer.pieces), s1 = Math.min(b, head + len * (k + 1) / layer.pieces);
                    if (s1 - s0 <= 0.01) continue;
                    float mid = w.xAt((s0 + s1) / 2);
                    int color = layer.colorAt((mid - x1) / span);
                    double peak = (color >>> 24) / 255.0 * fade * fadeAt(mid, fadeStart, fadeEnd);
                    if (peak <= .002) continue;
                    // The front end (the head's, where the comet starts) is round: a half disc ahead of it.
                    boolean front = s0 <= a + 0.01 && a > 0;
                    int alpha = (int) Math.round(255 * peak);
                    sink.piece(s0, s1, withAlpha(color, alpha), layer.width); drawn++;
                    if (front) { sink.cap(a, withAlpha(color, alpha), layer.width / 2); drawn++; }
                }
            }
        }
        return drawn;
    }

    /** The cable's opacity at x: full to fadeStart, a smooth fade to nothing at fadeEnd. */
    static double fadeAt(double x, double fadeStart, double fadeEnd) {
        if (!(x > fadeStart)) return 1;
        if (x >= fadeEnd) return 0;
        double u = (x - fadeStart) / (fadeEnd - fadeStart);
        return 1 - u * u * (3 - 2 * u);
    }

    /** An ARGB colour with this opacity (0-255). */
    static int withAlpha(int argb, int alpha) { return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0xFFFFFF); }

    /** Between two ARGB colours, channel by channel (t 0 to 1). */
    static int mix(int a, int b, float t) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ca = (a >>> shift) & 255, cb = (b >>> shift) & 255;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }
}
