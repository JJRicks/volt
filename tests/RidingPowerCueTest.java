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
}
