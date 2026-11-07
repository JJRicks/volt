package com.jjricks.volt;

/**
 * Whether Volt uses its wide layout (side rail, two-column More, Rides beside its list, Stats in
 * three panes, the dashboard's wide placements, onboarding's step list) or the phone layout.
 *
 * The rule is the room the text gets, not the raw window width: the window's width in dp divided by
 * the text scale (Volt's own Text size times the system font scale) must reach {@link #MIN_TEXT_DP},
 * the 650 dp every call site used before. Below that, each wide column is too narrow for one long
 * word at title size, and Spanish or German words broke mid-word («config / uración»). So:
 * <ul>
 *   <li>725 dp at 1.0x is 725 text-dp: wide, exactly as before.</li>
 *   <li>725 dp at 1.15x (630), 1.3x (558) or 1.4x (518): the phone layout, one column and the bottom bar.</li>
 *   <li>A 910 dp tablet at 1.4x is 650 text-dp: still wide.</li>
 * </ul>
 * A scale under 1 counts as 1: smaller text never turns a phone-width window wide, so every window
 * under 650 dp keeps the phone layout it always had. The owner chose this (Nov 5, "option A"):
 * one rule fixes every language, and large-text English on a tablet gets one column too.
 *
 * Pure Java (no Android), so the JVM tests reach it; {@link Ui#wideLayout()} reads the window. Public so
 * android-tests can predict the layout independently of the screen they check.
 */
public final class WideLayout {
    /** The wide layout's threshold, in dp at 1x text. */
    public static final int MIN_TEXT_DP = 650;

    private WideLayout() {}

    /** True when widthDp at this text scale leaves at least MIN_TEXT_DP of room for text. */
    public static boolean wide(int widthDp, float textScale) {
        float scale = Float.isFinite(textScale) ? Math.max(1f, textScale) : 1f;
        // Compare in thousandths so 650 x 1.4 is exactly 910, not one float step above it.
        return (long) widthDp * 1000L >= (long) MIN_TEXT_DP * Math.round(scale * 1000f);
    }
}
