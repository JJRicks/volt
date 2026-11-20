package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The charging pill's states (ChargingPillStatus, the owner's concept B of Nov 19 2026): the state from the slowdown
 * and the limit, each state's icon and colours (4.5:1 or more on its tint in both themes), the temperature in the unit
 * setting, the words and their narrow ladder, the icon's size, and ChargingFit keeping a slowed pill's line on a short
 * slot. Synthesized inputs only. Runs from the repository root (it reads Ui.java's page colours).
 */
public final class ChargingPillStatusTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        kinds();
        icons();
        colours();
        degrees();
        words();
        ladder();
        iconSize();
        keptLine();
        System.out.println("ChargingPillStatusTest: " + checks + " checks");
    }

    private static void kinds() {
        check(ChargingPillStatus.kind(false, false, null) == ChargingPillStatus.Kind.CHARGING, "no limit, not slowed: Charging");
        check(ChargingPillStatus.kind(true, false, null) == ChargingPillStatus.Kind.LIMIT, "a limit, not slowed: Charging to the limit");
        check(ChargingPillStatus.kind(true, true, ChargeSlowdown.Cause.HOT) == ChargingPillStatus.Kind.HOT, "slowed and hot wins over the limit");
        check(ChargingPillStatus.kind(false, true, ChargeSlowdown.Cause.COLD) == ChargingPillStatus.Kind.COLD, "slowed and cold");
        check(ChargingPillStatus.kind(false, true, ChargeSlowdown.Cause.PLAIN) == ChargingPillStatus.Kind.SLOWED, "slowed for no named reason");
        check(ChargingPillStatus.kind(true, true, null) == ChargingPillStatus.Kind.SLOWED, "slowed without a cause is plain");
        check(ChargingPillStatus.kind(false, false, ChargeSlowdown.Cause.HOT) == ChargingPillStatus.Kind.CHARGING, "a cause without slowed changes nothing");
        ChargeSlowdown.State none = ChargeSlowdown.State.NONE;
        check(ChargingPillStatus.kind(true, none.slowed, none.cause) == ChargingPillStatus.Kind.LIMIT, "the service's NONE keeps the normal pill");
        for (ChargingPillStatus.Kind k : ChargingPillStatus.Kind.values())
            check(k.slowed() == (k == ChargingPillStatus.Kind.HOT || k == ChargingPillStatus.Kind.COLD || k == ChargingPillStatus.Kind.SLOWED), k + " slowed()");
    }

    private static void icons() {
        check("bolt".equals(ChargingPillStatus.glyph(ChargingPillStatus.Kind.CHARGING)), "Charging keeps the bolt");
        check("bolt".equals(ChargingPillStatus.glyph(ChargingPillStatus.Kind.LIMIT)), "the limit keeps the bolt");
        check("temperature".equals(ChargingPillStatus.glyph(ChargingPillStatus.Kind.HOT)), "hot: the existing thermometer");
        check("snowflake".equals(ChargingPillStatus.glyph(ChargingPillStatus.Kind.COLD)), "cold: the snowflake");
        check("slowed".equals(ChargingPillStatus.glyph(ChargingPillStatus.Kind.SLOWED)), "plain: the low-needle gauge");
        String ui = source("src/com/jjricks/volt/Ui.java");
        check(ui.contains("key.equals(ChargingPillStatus.GLYPH_COLD)") && ui.contains("key.equals(ChargingPillStatus.GLYPH_SLOWED)"),
                "Ui.drawSymbol draws the pill's two new icons");
        check(Pattern.compile("key\\.equals\\(\"temperature\"\\)").matcher(ui).find(), "Ui.drawSymbol draws the thermometer");
    }

    private static void colours() {
        String ui = source("src/com/jjricks/volt/Ui.java");
        Matcher bg = Pattern.compile("\\bbg = Color\\.parseColor\\(dark \\? \"#(\\w{6})\" : \"#(\\w{6})\"\\)").matcher(ui);
        check(bg.find(), "found the page colours in Ui.java");
        int bgDark = Integer.parseInt(bg.group(1), 16), bgLight = Integer.parseInt(bg.group(2), 16);
        // The decided inks (lead, Nov 19 2026): light hot #AE3A12, cold #245C9C, plain #8A5208; dark as the renders.
        check(ChargingPillStatus.ink(ChargingPillStatus.Kind.HOT, false) == 0xFFAE3A12, "light hot ink");
        check(ChargingPillStatus.ink(ChargingPillStatus.Kind.COLD, false) == 0xFF245C9C, "light cold ink");
        check(ChargingPillStatus.ink(ChargingPillStatus.Kind.SLOWED, false) == 0xFF8A5208, "light plain ink");
        check(ChargingPillStatus.ink(ChargingPillStatus.Kind.HOT, true) == 0xFFFF9473, "dark hot ink");
        check(ChargingPillStatus.ink(ChargingPillStatus.Kind.COLD, true) == 0xFF8FC2FF, "dark cold ink");
        check(ChargingPillStatus.ink(ChargingPillStatus.Kind.SLOWED, true) == 0xFFE8A23A, "dark plain ink");
        check(ChargingPillStatus.accent(ChargingPillStatus.Kind.CHARGING) && ChargingPillStatus.accent(ChargingPillStatus.Kind.LIMIT),
                "the normal states keep today's red (Ui.accent, Ui.accentOnTint; ChargePictureTest checks it)");
        check(ChargingPillStatus.base(ChargingPillStatus.Kind.CHARGING, true) == 0 && ChargingPillStatus.ink(ChargingPillStatus.Kind.LIMIT, false) == 0,
                "no own colours for the normal states");
        check(ChargingPillStatus.alpha(true) == 34 && ChargingPillStatus.alpha(false) == 24, "today's tint alphas");
        for (ChargingPillStatus.Kind k : new ChargingPillStatus.Kind[] {ChargingPillStatus.Kind.HOT, ChargingPillStatus.Kind.COLD, ChargingPillStatus.Kind.SLOWED}) {
            for (boolean dark : new boolean[] {true, false}) {
                int tint = over(ChargingPillStatus.base(k, dark) & 0xFFFFFF, ChargingPillStatus.alpha(dark), dark ? bgDark : bgLight);
                double ratio = contrast(ChargingPillStatus.ink(k, dark) & 0xFFFFFF, tint);
                System.out.println(String.format(Locale.US, "  %s, %s: #%06X on #%06X: %.2f:1", k, dark ? "dark" : "light",
                        ChargingPillStatus.ink(k, dark) & 0xFFFFFF, tint, ratio));
                check(ratio >= 4.5, k + (dark ? " dark" : " light") + " ink on its tint is " + ratio + ":1, under 4.5:1");
            }
        }
        check(ChargingPillStatus.base(ChargingPillStatus.Kind.HOT, true) != ChargingPillStatus.base(ChargingPillStatus.Kind.COLD, true)
                && ChargingPillStatus.base(ChargingPillStatus.Kind.COLD, true) != ChargingPillStatus.base(ChargingPillStatus.Kind.SLOWED, true),
                "each slowed state has its own colour");
    }

    private static void degrees() {
        check(ChargingPillStatus.degrees(45, true) == 113, "45 C is 113 F");
        check(ChargingPillStatus.degrees(11.7, true) == 53, "11.7 C is 53 F");
        check(ChargingPillStatus.degrees(45.4, false) == 45, "Celsius in whole degrees, rounded");
        check(ChargingPillStatus.degrees(45.5, false) == 46, "a half rounds up");
        check(ChargingPillStatus.degrees(-0.4, false) == 0, "never a negative zero");
        check(ChargingPillStatus.degrees(-17.9, true) == 0, "-17.9 C is 0 F");
        check(ChargingPillStatus.degrees(-5, false) == -5, "below zero keeps its sign");
    }

    private static void words() {
        same(ChargingPillStatus.rungs(ChargingPillStatus.Kind.HOT, 45.0, true, "Charging"),
                new String[] {"Charging slowed · battery hot (113°)", "Slowed · battery hot 113°", "Battery hot · 113°"}, "hot in F");
        same(ChargingPillStatus.rungs(ChargingPillStatus.Kind.COLD, 11.7, true, "Charging"),
                new String[] {"Charging slowed · battery cold (53°)", "Slowed · battery cold 53°", "Battery cold · 53°"}, "cold in F");
        same(ChargingPillStatus.rungs(ChargingPillStatus.Kind.HOT, 45.0, false, "Charging"),
                new String[] {"Charging slowed · battery hot (45°)", "Slowed · battery hot 45°", "Battery hot · 45°"}, "hot in C");
        same(ChargingPillStatus.rungs(ChargingPillStatus.Kind.COLD, 11.7, false, "Charging"),
                new String[] {"Charging slowed · battery cold (12°)", "Slowed · battery cold 12°", "Battery cold · 12°"}, "cold in C");
        same(ChargingPillStatus.rungs(ChargingPillStatus.Kind.SLOWED, 28.0, true, "Charging"),
                new String[] {"Charging slowed", "Slowed"}, "plain names no temperature");
        same(ChargingPillStatus.rungs(ChargingPillStatus.Kind.SLOWED, null, true, "Charging"),
                new String[] {"Charging slowed", "Slowed"}, "plain without a temperature");
        same(ChargingPillStatus.rungs(ChargingPillStatus.Kind.HOT, null, true, "Charging"),
                new String[] {"Charging slowed · battery hot", "Slowed · battery hot", "Battery hot"}, "hot without a fresh temperature");
        same(ChargingPillStatus.rungs(ChargingPillStatus.Kind.COLD, Double.NaN, false, "Charging"),
                new String[] {"Charging slowed · battery cold", "Slowed · battery cold", "Battery cold"}, "cold with an unusable temperature");
        same(ChargingPillStatus.rungs(ChargingPillStatus.Kind.LIMIT, 45.0, true, "Charging to your 85% limit"),
                new String[] {"Charging to your 85% limit"}, "the limit keeps its own words");
        same(ChargingPillStatus.rungs(ChargingPillStatus.Kind.CHARGING, null, true, "Charging"),
                new String[] {"Charging"}, "Charging keeps its own words");
        for (ChargingPillStatus.Kind k : ChargingPillStatus.Kind.values()) {
            String[] rungs = ChargingPillStatus.rungs(k, 45.0, true, "Charging");
            for (int i = 1; i < rungs.length; i++)
                check(rungs[i].length() < rungs[i - 1].length(), k + ": each rung is shorter than the one before");
        }
    }

    private static void ladder() {
        float[] needed = {244, 190, 150};
        check(ChargingPillStatus.pick(needed, 308) == 0, "room for the full rung");
        check(ChargingPillStatus.pick(needed, 244) == 0, "exactly the full rung's width fits");
        check(ChargingPillStatus.pick(needed, 243.9f) == 1, "a hair short: the short rung");
        check(ChargingPillStatus.pick(needed, 160) == 2, "the compact rung");
        check(ChargingPillStatus.pick(needed, 100) == 2, "nothing fits: the last rung (it wraps between words)");
        check(ChargingPillStatus.pick(new float[] {93}, 10) == 0, "one rung is always the one");
    }

    private static void iconSize() {
        check(ChargingPillStatus.glyphPx(12.5f * 3) == 42, "1x text at 3x density: 14 dp, as before");
        check(ChargingPillStatus.glyphPx(12.5f * 3 * 1.8f) == 76, "1.8x text: the icon grows with the words");
        check(ChargingPillStatus.glyphPx(0) == 1, "never zero");
    }

    private static void keptLine() {
        float density = 1;
        int line = 46, shortLine = 23, percent = 90, readings = 66;
        boolean dropped = false;
        for (int inner = 420; inner >= 0; inner--) {
            ChargingFit.Plan plain = ChargingFit.plan(inner, line, shortLine, percent, readings, density);
            ChargingFit.Plan kept = ChargingFit.plan(inner, line, shortLine, percent, readings, density, true);
            ChargingFit.Plan off = ChargingFit.plan(inner, line, shortLine, percent, readings, density, false);
            check(off.toString().equals(plain.toString()) && off.kept == 0, "keepLine false is the plain plan at " + inner);
            if (plain.line) {
                check(kept.toString().equals(plain.toString()) && kept.kept == 0, "a line that shows anyway: the plain plan at " + inner);
                continue;
            }
            dropped = true;
            check(kept.line && kept.shortLine, "slowed: the short line stays at " + inner);
            check(kept.kept == shortLine + ChargingFit.LINE_GAP_DP, "the kept line's height at " + inner);
            int stacked = kept.stacked(line, shortLine, ChargingFit.LINE_GAP_DP, readings);
            check(stacked <= Math.max(inner, kept.kept + readings), "the kept plan fits the slot at " + inner + " (" + stacked + ")");
            ChargingFit.Plan rest = ChargingFit.plan(inner - kept.kept, line, shortLine, percent, readings, density);
            check(kept.stageHeight == rest.stageHeight && kept.percentScale == rest.percentScale && kept.readings == rest.readings,
                    "the rest is planned in what the line leaves at " + inner);
        }
        check(dropped, "the slots tried include one where the plain plan lets the line go");
    }

    // ---- Helpers -----------------------------------------------------------------------------------------

    private static void same(String[] got, String[] want, String what) {
        check(java.util.Arrays.equals(got, want), what + ": " + java.util.Arrays.toString(got));
    }

    private static String source(String path) {
        try { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }
        catch (java.io.IOException e) { throw new AssertionError("cannot read " + path, e); }
    }

    /** The colour {@code rgb} at {@code alpha} (0-255) over {@code bg}, per channel, rounded as an 8-bit canvas does. */
    private static int over(int rgb, int alpha, int bg) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int c = rgb >> shift & 255, b = bg >> shift & 255;
            out |= Math.round((c * alpha + b * (255 - alpha)) / 255f) << shift;
        }
        return out;
    }

    private static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + .05) / (Math.min(la, lb) + .05);
    }

    private static double luminance(int rgb) {
        double[] w = {.2126, .7152, .0722};
        double sum = 0;
        for (int i = 0; i < 3; i++) {
            double c = (rgb >> (16 - 8 * i) & 255) / 255.0;
            sum += w[i] * (c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4));
        }
        return sum;
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
