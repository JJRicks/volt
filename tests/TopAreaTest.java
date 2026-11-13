package com.jjricks.volt;

/** TopArea: Clean Home's fixed top-area height (hub layout plan 1.6, PR 6). */
public final class TopAreaTest {
    private static int checks;

    public static void main(String[] args) {
        require(TopArea.heightDp(800, false, 1f) == 304, "360x800 phone: 38% of 800");
        require(TopArea.heightDp(882, false, 1f) == 335, "344x882 cover screen");
        require(TopArea.heightDp(915, false, 1f) == 348, "412x915 phone");
        require(TopArea.heightDp(915, false, 1.4f) >= 291, "Large text keeps at least 224 dp grown 1.3 times");
        require(TopArea.heightDp(500, false, 1.4f) == 291, "Large text on a short window: the grown minimum (224 x 1.3)");
        require(TopArea.heightDp(500, false, 1.15f) == 258, "Text growth below the cap scales the minimum");
        require(TopArea.heightDp(500, false, 0.85f) == 224, "Small text never shrinks the minimum");
        require(TopArea.heightDp(900, true, 1f) == 378, "Wide 840x900: 42%");
        require(TopArea.heightDp(360, true, 1f) == 224, "Wide landscape 800x360: clamped to the minimum");
        require(TopArea.heightDp(2000, false, 1f) == 380, "Tall phone window: phone maximum");
        require(TopArea.heightDp(2000, true, 1f) == 460, "Tall wide window: wide maximum");
        require(TopArea.heightDp(0, false, 1f) == 224 && TopArea.heightDp(-5, false, Float.NaN) == 224, "Nonsense input still gives the minimum");
        require(TopArea.heightPx(800, false, 1f, 2.75f) == 836, "Pixels at a density");
        for (int h = 300; h <= 1400; h += 7)
            for (float scale : new float[]{0.8f, 1f, 1.3f, 2f})
                for (boolean wide : new boolean[]{false, true}) {
                    int v = TopArea.heightDp(h, wide, scale);
                    require(v >= TopArea.MIN_DP && v <= (wide ? TopArea.WIDE_MAX_DP : TopArea.PHONE_MAX_DP), "Always within the clamp");
                    require(TopArea.heightDp(h + 7, wide, scale) >= v, "Never shrinks on a taller window");
                }
        System.out.println("TopArea: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
