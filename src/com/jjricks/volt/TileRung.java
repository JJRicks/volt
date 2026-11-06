package com.jjricks.volt;

/**
 * The narrow-label ladder's choice for a one-line welcome tile label (OnboardingScreen.features): the full
 * word while it fits the room the tile gives it, otherwise its short rung. Pure, so the host tests run it.
 * In English both rungs are the same word, so the shown text never changes there.
 */
final class TileRung {
    private TileRung() {}

    /** The rung to show: full when its measured width fits room (pixels, after padding), else brief. */
    static CharSequence pick(CharSequence full, float fullWidth, CharSequence brief, int room) {
        return fullWidth <= room ? full : brief;
    }

    /**
     * The narrow-label ladder over any number of rungs (D2): the index of the first rung whose measured width
     * (pixels, at the size it may not go below) fits room, or -1 when none does, where the caller shows its icon
     * alone. widths run longest rung first; a rung is never shrunk or broken to make it fit.
     */
    static int first(float[] widths, float room) {
        for (int i = 0; i < widths.length; i++) if (widths[i] <= room) return i;
        return -1;
    }

    /** True when pick differs in content from the shown text, the only case the label's text is set. */
    static boolean needsSet(CharSequence pick, CharSequence shown) {
        if (pick == null || shown == null) return pick != shown;
        return !pick.toString().contentEquals(shown);
    }
}
