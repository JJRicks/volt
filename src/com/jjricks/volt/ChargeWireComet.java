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
 * prototype's blurred strokes, drawn as nested solid strokes that step through the blur's profile
 * (ChargeWirePulses.glowRings). Where the cable fades out short of the screen's edge (a wide window), every stretch
 * fades with it.
 */
final class ChargeWireComet {
    /** What the view draws. */
    interface Sink {
        /** The cable from arc length s0 to s1 as a solid stroke of this colour (with its opacity) and width. */
        void piece(double s0, double s1, int argb, float width);
        /** The comet's round front at arc length s: a half disc of this radius, ahead of it. */
        void cap(double s, int argb, float radius);
    }

    /** The pulse's sizes as the prototype draws them on the phone, in cut-out pixels. */
    static final float HALO = 44, BLUR = 11, HEAD = 30;
    static final int GRADIENT_STOPS = 24;

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

        Layer(boolean glow, boolean head, float width, double length, int pieces) {
            this.glow = glow; this.head = head; this.width = width; this.length = length; this.pieces = pieces;
            rings = glow ? ChargeWirePulses.glowRings(width, BLUR, half, profile) : 0;
        }

        /** The colour at u (0 head to 1 tail), between the table's stops. */
        int colorAt(double u) {
            double f = Math.max(0, Math.min(1, u)) * GRADIENT_STOPS;
            int i = Math.min(GRADIENT_STOPS - 1, (int) f);
            return mix(colors[i], colors[i + 1], (float) (f - i));
        }
    }

    final Layer[] layers;
    private final double[] ringAlpha = new double[ChargeWirePulses.GLOW_Z.length], capAlpha = new double[ChargeWirePulses.GLOW_Z.length];

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
        double length = w.length, trail = ChargeWirePulses.trail(length, mode);
        int drawn = 0;
        for (int p = 0; p < state.pulses; p++) {
            double progress = state.progress[p];
            double head = ChargeWirePulses.head(length, progress), visible = ChargeWirePulses.headVisible(progress);
            for (Layer layer : layers) {
                double len = layer.head ? HEAD * layer.length : trail * layer.length;
                double a = Math.max(0, head), b = Math.min(length, head + len);
                if (b - a <= 0.01 || (layer.head && visible <= 0)) continue;
                float x1 = w.xAt(head), span = Math.max(.01f, w.xAt(head + len) - x1);
                double fade = layer.head ? visible : 1;
                for (int k = 0; k < layer.pieces; k++) {
                    double s0 = Math.max(a, head + len * k / layer.pieces), s1 = Math.min(b, head + len * (k + 1) / layer.pieces);
                    if (s1 - s0 <= 0.01) continue;
                    float mid = w.xAt((s0 + s1) / 2);
                    int color = layer.colorAt((mid - x1) / span);
                    double peak = (color >>> 24) / 255.0 * fade * fadeAt(mid, fadeStart, fadeEnd);
                    if (peak <= .002) continue;
                    // The front end (the head's, where the comet starts) is round: a half disc ahead of it.
                    boolean front = s0 <= a + 0.01 && a > 0;
                    if (layer.glow) {
                        ChargeWirePulses.glowAlphas(peak, layer.profile, layer.rings, ringAlpha);
                        // A blurred stroke's end is half as bright as along it: the glow's round front at half.
                        if (front) ChargeWirePulses.glowAlphas(peak / 2, layer.profile, layer.rings, capAlpha);
                        for (int g = 0; g < layer.rings; g++) {
                            int alpha = (int) Math.round(255 * ringAlpha[g]);
                            if (alpha > 0) { sink.piece(s0, s1, withAlpha(color, alpha), (float) (2 * layer.half[g])); drawn++; }
                            if (front && (alpha = (int) Math.round(255 * capAlpha[g])) > 0) { sink.cap(a, withAlpha(color, alpha), (float) layer.half[g]); drawn++; }
                        }
                    } else {
                        int alpha = (int) Math.round(255 * peak);
                        sink.piece(s0, s1, withAlpha(color, alpha), layer.width); drawn++;
                        if (front) { sink.cap(a, withAlpha(color, alpha), layer.width / 2); drawn++; }
                    }
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
