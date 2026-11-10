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
}
