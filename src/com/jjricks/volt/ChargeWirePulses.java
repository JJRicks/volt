package com.jjricks.volt;

/**
 * The charging picture's animation over time (the contributor's charging animation, 14 Nov 2026 on #383): green
 * pulses travel down the cable into the plug, each lighting the stretch behind it, and each landing lights the cells
 * column by column from the connector before they fade back to grey. Pure Java; the view asks for the state at a time
 * and draws it, with no allocation per frame.
 *
 * <p>The mode follows the reported charging power alone: under {@link #FAST_WATTS} one pulse at a time (the next
 * leaves after the last lands, after a short rest); from it, three on the cable at once, evenly spaced and faster. A
 * fast charge that tapers below it simply slows to the single pulse.
 */
final class ChargeWirePulses {
    /** From this charging power (W) three pulses run at once. */
    static final double FAST_WATTS = 200;
    /** Pulse slots: a pulse n uses slot n mod SLOTS; never more than this many are on the cable at once. */
    static final int SLOTS = 5;
    /** The most landings still lighting the cells at once (2.5 s of the fast mode's). */
    static final int MAX_LANDS = 8;
    /** How long a landing keeps lighting the cells (s). */
    static final double LAND_SPAN = 2.5;
    /** The cells' rise when a pulse lands (s). */
    static final double ATTACK = 0.10;

    /** One mode's timing (seconds; the trail as a share of a trip's time). */
    static final class Mode {
        final String name;
        /** A pulse's trip down the cable; a new one leaves every period. */
        final double trip, period;
        /** The lit trail behind the head (as time at the pulse's speed), the cells' sweep across the pack and their fade. */
        final double trail, sweep, decay;
        /** The landing curve's highest value, so a landing lights the cells fully. */
        final double peak;

        Mode(String name, double trip, double period, double trail, double sweep, double decay) {
            this.name = name; this.trip = trip; this.period = period; this.trail = trail; this.sweep = sweep; this.decay = decay;
            double m = 0;
            for (double x = 0; x < 2; x += 0.002) m = Math.max(m, landing(x, decay));
            peak = m;
        }

        /** How long a pulse stays on the cable: its trip, then a moment while its lit trail runs into the plug. */
        double onCable() { return 1 + trail * 1.2 / trip; }

        @Override public String toString() { return name; }
    }

    /** Under 200 W: one pulse on the cable at a time, the next leaving after the last lands and a short rest. */
    static final Mode STOCK = new Mode("stock", 1.8, 2.3, 0.45, 0.40, 0.42);
    /** 200 W and over: three at once, evenly spaced, faster. */
    static final Mode FAST = new Mode("fast", 1.1, 1.1 / 3, 0.35, 0.30, 0.30);

    private ChargeWirePulses() {}

    /** The mode for a reported charging power (W); unknown power runs the single pulse. */
    static Mode mode(Double watts) { return watts != null && watts >= FAST_WATTS ? FAST : STOCK; }

    /** How brightly a landing x seconds ago lights the cells: a quick rise, then an exponential fade. */
    static double landing(double x, double decay) {
        if (x < 0) return 0;
        return (1 - Math.exp(-x / ATTACK)) * Math.exp(-x / decay);
    }

    /** A touch of acceleration into the plug: the trip's progress p (0..1) as a share of the cable covered. */
    static double easeIn(double p) { return p * p * 0.3 + p * 0.7; }

    /** Brightness along a comet layer, 1 at the head to 0 at its tail's end, flat at both ends; k > 1 holds it longer. */
    static double easeOut(double u, double k) { return 0.5 + 0.5 * Math.cos(Math.PI * Math.pow(u, k)); }

    /** The state at one time: the pulses on the cable and the landings still lighting the cells. Reused per frame. */
    static final class State {
        int pulses;
        /** Each pulse's number (for its slot) and its trip's progress (0 leaving the right edge, 1 at the plug, more after). */
        final long[] number = new long[SLOTS];
        final double[] progress = new double[SLOTS];
        int lands;
        /** Seconds since each landing. */
        final double[] since = new double[MAX_LANDS];
    }

    /** The state at t seconds into the animation: pulse n leaves the right edge at n periods and lands a trip later. */
    static void state(double t, Mode m, State out) {
        out.pulses = 0; out.lands = 0;
        long hi = (long) Math.floor(t / m.period), lo = (long) Math.floor((t - m.trip - LAND_SPAN) / m.period);
        for (long n = lo; n <= hi; n++) {
            double start = n * m.period, p = (t - start) / m.trip;
            if (p >= 0 && p <= m.onCable() && out.pulses < SLOTS) {
                out.number[out.pulses] = n; out.progress[out.pulses] = p; out.pulses++;
            }
            double x = t - (start + m.trip);
            if (x >= 0 && x < LAND_SPAN && out.lands < MAX_LANDS) out.since[out.lands++] = x;
        }
    }

    /** A pulse's slot. */
    static int slot(long n) { return (int) (((n % SLOTS) + SLOTS) % SLOTS); }

    /** The head's arc length along a path of this length (0 at the plug's lead-in, the length at the right end). */
    static double head(double length, double progress) { return length * (1 - easeIn(progress)); }

    /** The head's visibility: it vanishes into the plug over the trip's last 4%. */
    static double headVisible(double progress) { return Math.max(0, Math.min(1, (1 - progress) / 0.04)); }

    /** The lit trail's length along a path of this length. */
    static double trail(double length, Mode m) { return length / m.trip * m.trail; }

    /**
     * Each half-pitch column's light (0..1, connector side first) into levels, the sweep reaching the far side
     * {@link Mode#sweep} seconds after a landing; returns the pack's overall light (for its bloom).
     */
    static double cells(State s, Mode m, float[] levels) {
        int columns = levels.length;
        double step = m.sweep / columns, energy = 0;
        for (int c = 0; c < columns; c++) levels[c] = 0;
        for (int i = 0; i < s.lands; i++) {
            double x = s.since[i];
            energy = Math.max(energy, landing(x, m.decay) / m.peak);
            for (int c = 0; c < columns; c++)
                levels[c] = (float) Math.max(levels[c], Math.min(1, landing(x - c * step, m.decay) / m.peak));
        }
        return Math.min(1, energy);
    }

    // ---- the glow ----

    /**
     * The glow's rings, outermost first, in sigmas outside the glow stroke's edge: the prototype blurs a stroke of
     * width w by a Gaussian of sigma; the view draws it as nested solid strokes, each a little narrower and each adding
     * its share, so the cross-section steps through the blurred stroke's profile.
     */
    static final double[] GLOW_Z = {2.2, 1.5, 0.8, 0.2, -0.4, -1.0, -1.6};

    /**
     * The glow's nested strokes for a stroke {@code width} wide blurred by {@code sigma}: each ring's half-width into
     * half[] and the blurred profile across the band just inside it into profile[] (both as long as GLOW_Z); returns how
     * many rings there are (the inner ones narrower than half a pixel are left out).
     */
    static int glowRings(double width, double sigma, double[] half, double[] profile) {
        int n = 0;
        for (double z : GLOW_Z) {
            double h = width / 2 + sigma * z;
            if (h < .5) break;
            half[n++] = h;
        }
        for (int k = 0; k < n; k++) {
            double inner = k + 1 < n ? half[k + 1] : 0, mid = (half[k] + inner) / 2;
            profile[k] = blurred(mid, width, sigma);
        }
        return n;
    }

    /** A stroke {@code width} wide blurred by sigma: its opacity at distance d from its centre line (1 = solid). */
    static double blurred(double d, double width, double sigma) {
        return ChargePercentShade.phi((width / 2 - d) / sigma) - ChargePercentShade.phi((-width / 2 - d) / sigma);
    }

    /**
     * Each ring's opacity, into alpha[], so that drawn over each other (each over the band it covers and every band
     * inside it) they build {@code peak} times the blurred profile in every band.
     */
    static void glowAlphas(double peak, double[] profile, int n, double[] alpha) {
        double below = 0;
        for (int k = 0; k < n; k++) {
            double target = Math.max(below, Math.min(1, peak * profile[k]));
            alpha[k] = below >= 1 ? 0 : 1 - (1 - target) / (1 - below);
            below = target;
        }
    }
}
