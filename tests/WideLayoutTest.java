package com.jjricks.volt;

/** WideLayout: the wide layout needs 650 dp of room after dividing by the text scale. */
public final class WideLayoutTest {
    private static int checks;
    public static void main(String[] args) {
        // The release sweep's WIDE size (725 dp): wide at 1x only.
        require(WideLayout.wide(725, 1.0f), "725 dp at 1.0x keeps the wide layout");
        require(!WideLayout.wide(725, 1.15f), "725 dp at 1.15x (630 text-dp) uses the phone layout");
        require(!WideLayout.wide(725, 1.3f), "725 dp at 1.3x (558 text-dp) uses the phone layout");
        require(!WideLayout.wide(725, 1.4f), "725 dp at 1.4x (518 text-dp) uses the phone layout");
        // Volt's own Text size multiplies the system scale: 1.4 x 1.0 behaves as 1.0 x 1.4.
        require(WideLayout.wide(725, 1.0f * 1.0f) && !WideLayout.wide(725, 1.4f * 1.0f) && !WideLayout.wide(725, 1.0f * 1.4f),
                "Text size and system font scale count alike");
        // The threshold edge at 1x and at 1.4x.
        require(WideLayout.wide(650, 1.0f), "650 dp at 1.0x is exactly the threshold: wide");
        require(!WideLayout.wide(649, 1.0f), "649 dp at 1.0x: phone");
        require(WideLayout.wide(910, 1.4f), "910 dp at 1.4x is exactly 650 text-dp: wide");
        require(!WideLayout.wide(909, 1.4f), "909 dp at 1.4x: phone");
        require(WideLayout.wide(748, 1.15f) && !WideLayout.wide(747, 1.15f), "1.15x edge sits at 747.5 dp");
        // Phone widths stay phone at any text size, including small text.
        for (float scale : new float[] {0.75f, 0.8f, 1.0f, 1.4f, 1.8f})
            require(!WideLayout.wide(411, scale), "411 dp phone at " + scale + "x stays phone");
        require(!WideLayout.wide(600, 0.8f), "Small text never turns a 600 dp window wide");
        require(WideLayout.wide(650, 0.75f), "Small text never takes the wide layout away");
        // Bad input falls back to 1x.
        require(WideLayout.wide(725, Float.NaN) && !WideLayout.wide(0, 1f), "Invalid scale counts as 1x");
        System.out.println("WideLayout: " + checks + " checks passed");
    }
    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
