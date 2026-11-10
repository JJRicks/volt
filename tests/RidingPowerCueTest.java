package com.jjricks.volt;

/** The inferred regen reading's ladder, on synthesized widths (pixels) and readings. */
public final class RidingPowerCueTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    static final float FULL = 188, CUE = 72;

    public static void main(String[] args) {
        rungs();
        labelRungs();
        watts();
        signedScootersUntouched();
        spoken();
        inferredPeakNeverConfirmed();
        confirmedPeakSurvivesAnUnknownStretch();
        signReturnsAfterAReconnect();
        System.out.println("RidingPowerCueTest: " + checks + " checks passed");
    }

    private static void rungs() {
        check(RidingPowerCue.rung(FULL, CUE, 260) == RidingPowerCue.FULL, "the full caption fits");
        check(RidingPowerCue.rung(FULL, CUE, FULL) == RidingPowerCue.FULL, "the full caption fits exactly");
        check(RidingPowerCue.rung(FULL, CUE, FULL - 1) == RidingPowerCue.CUE, "just short of the full caption takes the cue");
        check(RidingPowerCue.rung(FULL, CUE, 104) == RidingPowerCue.CUE, "the 640 dp landscape Halo column takes the cue");
        check(RidingPowerCue.rung(FULL, CUE, CUE) == RidingPowerCue.CUE, "the cue fits exactly");
        check(RidingPowerCue.rung(FULL, CUE, CUE - 1) == RidingPowerCue.NONE, "no room for the cue");
        check(RidingPowerCue.rung(FULL, CUE, 0) == RidingPowerCue.NONE, "a label hidden or not laid out has no cue");
    }

    private static void labelRungs() {
        check(RidingPowerCue.rungOfLabel(0) == RidingPowerCue.FULL, "rung 0 is the full caption");
        check(RidingPowerCue.rungOfLabel(1) == RidingPowerCue.CUE, "rung 1 is the cue");
        check(RidingPowerCue.rungOfLabel(2) == RidingPowerCue.NONE, "rung 2 is the plain label");
        check(RidingPowerCue.rungOfLabel(-1) == RidingPowerCue.NONE, "an icon alone is no cue");
    }

    private static void watts() {
        RidingValues.Direction d = RidingValues.Direction.REGEN_INFERRED;
        check(RidingPowerCue.shownWatts(-151, d, RidingPowerCue.FULL) == -151, "the full caption keeps the sign");
        check(RidingPowerCue.shownWatts(-151, d, RidingPowerCue.CUE) == -151, "the cue keeps the sign");
        check(RidingPowerCue.shownWatts(-151, d, RidingPowerCue.NONE) == 151, "no cue, no sign, the same size");
        check(RidingPowerCue.shownWatts(-151.4, d, RidingPowerCue.NONE) == 151.4, "the magnitude is never changed");
    }

    private static void signedScootersUntouched() {
        check(RidingPowerCue.applies(RidingValues.Direction.REGEN_INFERRED), "inferred regen is guarded");
        for (RidingValues.Direction d : new RidingValues.Direction[]{RidingValues.Direction.DRAW, RidingValues.Direction.REGEN,
                RidingValues.Direction.UNKNOWN, RidingValues.Direction.DRAW_INFERRED}) {
            check(!RidingPowerCue.applies(d), d + " is not guarded");
            for (int rung : new int[]{RidingPowerCue.FULL, RidingPowerCue.CUE, RidingPowerCue.NONE}) {
                check(RidingPowerCue.shownWatts(-151, d, rung) == -151, d + " keeps its sign at rung " + rung);
                check(RidingPowerCue.shownWatts(151, d, rung) == 151, d + " keeps its value at rung " + rung);
            }
        }
    }
    static final String UNKNOWN = "power (direction unknown)", DRAW_PEAK = "power · peak 450 W", REGEN_PEAK = "regen · peak 600 W";

    /** The shown number as the readout and a tile print it (a plain stand-in for Fmt.quantity). */
    private static String shown(double watts, RidingValues.Direction d, int rung) {
        return Math.round(RidingPowerCue.shownWatts(watts, d, rung)) + " W";
    }

    /** One description carries the watts as shown and the whole note, at every rung, a signed scooter's included. */
    private static void spoken() {
        RidingValues.Direction inferred = RidingValues.Direction.REGEN_INFERRED;
        String[] expected = {"-151 W, " + UNKNOWN, "-151 W, " + UNKNOWN, "151 W, " + UNKNOWN};
        int[] rungs = {RidingPowerCue.FULL, RidingPowerCue.CUE, RidingPowerCue.NONE};
        for (int i = 0; i < rungs.length; i++) {
            // The readout passes its fitted-rung caption's full form; inferred regen always hears the unknown note.
            String said = RidingPowerCue.spokenValue(shown(-151, inferred, rungs[i]), RidingPowerCue.readoutNote(inferred, UNKNOWN, "power"));
            check(said.equals(expected[i]), "readout at rung " + rungs[i] + ": " + said);
            check(said.contains("151 W") && said.contains(UNKNOWN), "the value and the note together at rung " + rungs[i]);
            // A tile: its note is the unknown caption whichever label rung it landed on (its icon alone included).
            String tile = RidingPowerCue.spokenValue(shown(-151, inferred, RidingPowerCue.rungOfLabel(i == 2 ? -1 : i)), UNKNOWN);
            check(tile.equals(expected[i]), "tile at label rung " + i + ": " + tile);
        }
        check(RidingPowerCue.readoutNote(RidingValues.Direction.UNKNOWN, UNKNOWN, "power").equals(UNKNOWN), "an unsigned scooter's unknown direction says so");
        // Signed scooters: the value and the caption they mean (the peak once there is one), never the unknown note.
        check(RidingPowerCue.spokenValue(shown(320, RidingValues.Direction.DRAW, RidingPowerCue.FULL),
                RidingPowerCue.readoutNote(RidingValues.Direction.DRAW, UNKNOWN, DRAW_PEAK)).equals("320 W, " + DRAW_PEAK), "signed draw");
        check(RidingPowerCue.spokenValue(shown(-210, RidingValues.Direction.REGEN, RidingPowerCue.FULL),
                RidingPowerCue.readoutNote(RidingValues.Direction.REGEN, UNKNOWN, REGEN_PEAK)).equals("-210 W, " + REGEN_PEAK), "signed regen keeps its sign and its peak");
        check(RidingPowerCue.spokenValue(shown(-210, RidingValues.Direction.REGEN, RidingPowerCue.NONE),
                RidingPowerCue.readoutNote(RidingValues.Direction.REGEN, UNKNOWN, "regen")).equals("-210 W, regen"), "signed regen before a peak: the brief word");
        check(RidingPowerCue.readoutNote(RidingValues.Direction.DRAW_INFERRED, UNKNOWN, DRAW_PEAK).equals(DRAW_PEAK), "inferred draw keeps its draw caption");
        // A reading with no note (a tile on a signed scooter) is its value alone.
        check(RidingPowerCue.spokenValue("320 W", null).equals("320 W"), "no note, the value alone");
        check(RidingPowerCue.spokenValue("320 W", "").equals("320 W"), "an empty note adds nothing");
    }

    // An inferred regen peak is never named as a confirmed one (Codex, #353 at 6f02fd26). A later 0/1 sign
    // re-signs the newest reading only, never the earlier ones Volt inferred, so the confirmed caption names
    // the confirmed peak alone. Synthesized 0x79 / 0x72 frames, through Telemetry, as a scooter sends them.

    /** Stand-in captions in the catalog's English shape (riding_power_regen_peak, riding_power_draw_peak). */
    private static final RidingPowerCue.Captions CAPTIONS = new RidingPowerCue.Captions() {
        @Override public String unknown() { return UNKNOWN; }
        @Override public String peak(boolean regen, long watts) { return (regen ? "regen" : "power") + " · peak " + watts + " W"; }
    };

    /** The readout's caption at its full rung and what a screen reader hears, built as RidingScreen.showPower builds them. */
    private static String[] readout(RidingValues v, long now) {
        RidingValues.Power p = v.power(now);
        String caption = RidingPowerCue.readoutCaption(p, CAPTIONS);
        String brief = p.direction == RidingValues.Direction.REGEN && p.watts < 0 ? "regen" : "power";
        String shownCaption = caption == null ? brief : caption;
        String said = RidingPowerCue.spokenValue(shown(p.watts, p.direction, RidingPowerCue.FULL), RidingPowerCue.readoutNote(p.direction, UNKNOWN, shownCaption));
        return new String[]{shownCaption, said};
    }

    private static void frame(Telemetry telemetry, RidingValues v, long t, double kmh, int millivolts, int milliamps) {
        v.sample("speed_kmh", kmh, 0x92, t);
        telemetry.accept(0x72, batteryRead(80, millivolts, milliamps), t);
        v.update(telemetry.metrics());
    }
    private static void signs(Telemetry telemetry, RidingValues v, int current, long t) {
        telemetry.accept(0x79, new byte[]{0, 0, (byte) current}, t);
        v.update(telemetry.metrics());
    }
    /** 0x72: status, percent, voltage mV (LE32), current mA (LE32). */
    private static byte[] batteryRead(int percent, int millivolts, int milliamps) {
        byte[] b = new byte[11];
        b[1] = 0; b[2] = (byte) percent;
        for (int i = 0; i < 4; i++) { b[3 + i] = (byte) (millivolts >> (8 * i)); b[7 + i] = (byte) (milliamps >> (8 * i)); }
        return b;
    }
    private static final double BRAKE_W = 3 * 50.25, BRAKE_END_KMH = 24 - 4 * 3.2;
    /** Unsigned readings: rest, a pull-away (0.08 ohm), a cruise, then a 3 A brake at +0.25 V (150.75 W). Returns the next time. */
    private static long unsignedBrake(Telemetry telemetry, RidingValues v, long t) {
        double kmh = 0;
        for (int i = 0; i < 8; i++) { frame(telemetry, v, t, 0, 50000, 200); t += 1000; }
        for (int i = 0; i < 8; i++) { kmh += 3; frame(telemetry, v, t, kmh, 48560, 18000); t += 1000; }
        for (int i = 0; i < 8; i++) { frame(telemetry, v, t, kmh, 50000, 300); t += 1000; }
        for (int i = 0; i < 4; i++) { kmh -= 3.2; frame(telemetry, v, t, kmh, 50250, 3000); t += 1000; }
        return t;
    }
    private static boolean near(double a, double b) { return Math.abs(a - b) < 0.01; }

    /** The brake is inferred regen, and reads that way: the unknown caption and note, no peak named. */
    private static void inferredBrake(RidingValues v, long now, String label) {
        RidingValues.Power p = v.power(now);
        check(p.direction == RidingValues.Direction.REGEN_INFERRED && near(p.watts, -BRAKE_W), label + ": the brake is inferred regen: " + p.direction + " " + p.watts);
        check(near(p.peakInferredRegenW, BRAKE_W), label + ": its inferred peak: " + p.peakInferredRegenW);
        String[] r = readout(v, now);
        check(r[0].equals(UNKNOWN) && r[1].equals("-151 W, " + UNKNOWN), label + ": inferred regen says the direction is unknown: " + r[0] + " / " + r[1]);
    }

    /** Confirmed regen of 20 W after the brake: the caption and the spoken text name 20 W, never 151 W. */
    private static void confirmedTwenty(RidingValues v, long now, String label, double confirmedPeak) {
        RidingValues.Power p = v.power(now);
        check(p.direction == RidingValues.Direction.REGEN && near(p.watts, -20), label + ": confirmed regen: " + p.direction + " " + p.watts);
        check(near(p.peakRegenW, confirmedPeak) && near(p.peakInferredRegenW, BRAKE_W), label + ": the peaks stay apart: confirmed " + p.peakRegenW + ", inferred " + p.peakInferredRegenW);
        String[] r = readout(v, now);
        String expected = "regen · peak " + Math.round(confirmedPeak) + " W";
        check(r[0].equals(expected), label + ": the caption names the confirmed peak only: " + r[0]);
        check(r[1].equals("-20 W, " + expected), label + ": and so does the spoken description: " + r[1]);
        check(!r[0].contains("151") && !r[1].contains("151"), label + ": the inferred 151 W is never named as confirmed");
    }

    /**
     * Codex's sequence: an unsigned 150.75 W brake inferred as regen, a newer unsigned reading, then known
     * sign states and a smaller confirmed regen of -20 W. Once by a 0x79 re-signing that newer reading in
     * place, once by a later reading measured under the sign.
     */
    private static void inferredPeakNeverConfirmed() {
        for (boolean resigned : new boolean[]{true, false}) {
            String label = resigned ? "re-signed in place" : "measured under the sign";
            Telemetry telemetry = new Telemetry();
            RidingValues v = new RidingValues();
            v.setNominalIntervals(1000, 2000);
            signs(telemetry, v, 254, 0);
            long t = unsignedBrake(telemetry, v, 1000);
            inferredBrake(v, t - 900, label);
            check(v.power(t - 900).peakRegenW == 0, label + ": no confirmed regen yet");
            // The newer unsigned reading: 0.4 A at 50 V, speed steady.
            frame(telemetry, v, t, BRAKE_END_KMH, 50000, 400);
            check(v.power(t + 100).direction == RidingValues.Direction.UNKNOWN && readout(v, t + 100)[0].equals(UNKNOWN), label + ": the newer reading is unknown");
            long now;
            if (resigned) {
                signs(telemetry, v, 1, t + 500);
                now = t + 600;
            } else {
                signs(telemetry, v, 0, t + 500);
                RidingValues.Power draw = v.power(t + 600);
                check(draw.direction == RidingValues.Direction.DRAW && readout(v, t + 600)[0].equals("power · peak 20 W"), label + ": re-signed as draw first: " + readout(v, t + 600)[0]);
                signs(telemetry, v, 1, t + 1500);
                frame(telemetry, v, t + 2000, BRAKE_END_KMH, 50000, 400);
                now = t + 2100;
            }
            confirmedTwenty(v, now, label, 20);
        }
    }

    /**
     * The reverse: confirmed regen of 100 W, then the sign goes unknown and a stronger brake is only
     * inferred, then the sign comes back. The inferred stretch claims nothing; the caption afterwards
     * still names the real confirmed 100 W, never the inferred 151 W.
     */
    private static void confirmedPeakSurvivesAnUnknownStretch() {
        Telemetry telemetry = new Telemetry();
        RidingValues v = new RidingValues();
        v.setNominalIntervals(1000, 2000);
        // Each sign state reaches riding mode with the reading after it (one snapshot), so none re-signs the one before.
        signs(telemetry, v, 0, 0);
        frame(telemetry, v, 1000, 20, 49400, 8000);
        telemetry.accept(0x79, new byte[]{0, 0, 1}, 1500);
        frame(telemetry, v, 2000, 17, 50000, 2000);
        check(v.power(2100).direction == RidingValues.Direction.REGEN && readout(v, 2100)[0].equals("regen · peak 100 W"), "confirmed 100 W regen first: " + readout(v, 2100)[0]);
        // Back to draw, then the sign state goes unknown (254): Telemetry keeps its last sign, draw.
        telemetry.accept(0x79, new byte[]{0, 0, 0}, 2500);
        telemetry.accept(0x79, new byte[]{0, 0, (byte) 254}, 2800);
        long t = unsignedBrake(telemetry, v, 3000);
        inferredBrake(v, t - 900, "unknown after confirmed");
        check(near(v.power(t - 900).peakRegenW, 100), "the confirmed peak is untouched by the inference: " + v.power(t - 900).peakRegenW);
        telemetry.accept(0x79, new byte[]{0, 0, 1}, t + 500);
        frame(telemetry, v, t + 1000, BRAKE_END_KMH, 50000, 400);
        confirmedTwenty(v, t + 1100, "sign back after an unknown stretch", 100);
    }

    /**
     * A reconnect mid-ride before the sign frame arrives: the old connection's inferred peak carries over in
     * its own field, the new connection's first reading is unknown, and its 0x79 makes it confirmed regen.
     */
    private static void signReturnsAfterAReconnect() {
        Telemetry telemetry = new Telemetry();
        RidingValues v = new RidingValues();
        v.setNominalIntervals(1000, 2000);
        v.setLinkGeneration(1, 0);
        signs(telemetry, v, 254, 0);
        long t = unsignedBrake(telemetry, v, 1000);
        inferredBrake(v, t - 900, "before the reconnect");
        telemetry.clear();
        v.setLinkGeneration(2, t);
        frame(telemetry, v, t + 500, BRAKE_END_KMH, 50000, 400);
        check(v.power(t + 600).direction == RidingValues.Direction.UNKNOWN && readout(v, t + 600)[0].equals(UNKNOWN), "the new connection's first reading has no sign yet: " + v.power(t + 600).direction);
        check(near(v.power(t + 600).peakInferredRegenW, BRAKE_W) && v.power(t + 600).peakRegenW == 0, "the old inferred peak stays inferred across the reconnect");
        signs(telemetry, v, 1, t + 900);
        confirmedTwenty(v, t + 1000, "after the reconnect", 20);
    }
}
