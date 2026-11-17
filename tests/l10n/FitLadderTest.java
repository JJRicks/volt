package com.jjricks.volt;

/**
 * FitLadder: the charging time's rung and size beside Charging power (owner, Nov 15 2026). Widths are synthetic:
 * each rung is a fixed number of em, and a rung fits when em times size is within the room.
 */
public final class FitLadderTest {
    private static int checks;

    public static void main(String[] args) {
        // English at 412 dp, 1x: "≈ 1h 33m" (about 4 em) fits a 480 px room at 26 sp: full, full size.
        same(pick(new float[] {4f, 3.6f}, 480, 26, 12), 0, 26, "full fits at the ceiling");
        // The owner's "≈ 54m": one rung, plenty of room: the ceiling.
        same(pick(new float[] {3f}, 480, 26, 12), 0, 26, "one short rung with room");
        // German at 1.3x: "≈ 1 h 33 min" is too wide at 34 sp, its short rung fits at 34: short before smaller.
        same(pick(new float[] {16f, 14f}, 34 * 14, 34, 16), 1, 34, "short rung at the ceiling before shrinking");
        // Exactly at the room: fits (no off-by-one at the threshold).
        same(pick(new float[] {20f, 10f}, 20 * 26, 26, 12), 0, 26, "full exactly at the room");
        same(pick(new float[] {20f, 10f}, 20 * 26 - 1, 26, 12), 1, 26, "one pixel short takes the short rung");
        // Neither rung fits at the ceiling: the largest size any rung fits, the full rung first at that size.
        same(pick(new float[] {20f, 19f}, 19 * 24, 26, 12), 1, 24, "short rung, smaller, where it fits first");
        same(pick(new float[] {20f, 19.9f}, 20 * 23, 26, 12), 0, 23, "full rung where both fit at the same size");
        // Nothing fits even at the floor: the last rung at the floor (it wraps there, as a plain value does).
        same(pick(new float[] {100f, 90f}, 10, 26, 12), 1, 12, "nothing fits: last rung at the floor");
        // A ceiling below the floor is drawn as given, unmeasured.
        int[] measured = {0};
        int[] low = FitLadder.pick(2, 10, 12, (rung, size) -> { measured[0]++; return false; });
        same(low, 0, 10, "ceiling below the floor");
        require(measured[0] == 0, "a ceiling below the floor was measured");
        // No rungs counts as one; zero room (a degenerate width) ends at the floor without a crash.
        same(FitLadder.pick(0, 26, 12, (rung, size) -> size <= 20), 0, 20, "zero rungs counts as one");
        same(pick(new float[] {4f, 3f}, 0, 26, 12), 1, 12, "zero room");
        // Stable when re-measured with its own result: the same room gives the same choice.
        int[] first = pick(new float[] {16f, 14f}, 34 * 14, 34, 16), again = pick(new float[] {16f, 14f}, 34 * 14, 34, 16);
        same(again, first[0], first[1], "re-measured");
        System.out.println("FitLadderTest: " + checks + " checks passed");
    }

    private static int[] pick(float[] em, float room, int ceiling, int floor) {
        return FitLadder.pick(em.length, ceiling, floor, (rung, size) -> em[rung] * size <= room);
    }

    private static void same(int[] got, int rung, int size, String what) {
        require(got[0] == rung && got[1] == size, what + ": expected rung " + rung + " at " + size + " sp, got rung " + got[0] + " at " + got[1]);
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
