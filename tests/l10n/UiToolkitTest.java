package com.jjricks.volt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B2 (the shared UI toolkit), beyond the characterization tests:
 *
 *  - LockSliderGeometry right to left: position 0 at the right edge, mirroring the left-to-right
 *    geometry exactly, with touch and drawing agreeing.
 *  - The English-label fallbacks, counted. Ui keeps overloads that guess an icon or a drive mode from
 *    English text (labelIcon, toggleRow and settingRow without an icon id, styleDriveMode with the
 *    shown text or DriveMode.of) for the screens later batches convert; the plan's gate before any
 *    language ships (part E) needs this count at zero, so it may only shrink from B2's
 *    {@link #FALLBACK_LIMIT}. Every caller is listed. Ui itself may guess only in its three legacy
 *    overloads, and the other toolkit files of B2 call none of them. A styleDriveMode call counts as
 *    converted only when it passes a DriveMode constant or a variable this file declares as DriveMode.
 *  - The whole templates G1 cannot prove: where a ternary picks one of several whole templates, the
 *    ledger's manual row proves one of them, and this suite checks every one against the original
 *    + chain, for every condition.
 */
public final class UiToolkitTest {
    private static int checks;

    /** The B2 toolkit files besides Ui, which must not call an English-label fallback. */
    private static final String[] TOOLKIT = {
            "SegmentedSelector.java", "DashboardCaption.java", "ReorderList.java", "SearchFocus.java", "Motion.java",
            "WidgetReveal.java", "WidgetRemovalEffect.java", "FoldMonitor.java", "LiveStatsTitle.java",
            "StatsWindowPicker.java", "ModelPicker.java", "ScooterPhotos.java", "LockSliderGeometry.java",
            "LockSliderTrail.java", "Haptics.java"};

    /** The English-label fallback calls outside Ui when B2 left them (CompatibilityPanel, DashboardScreen, MoreScreen, StatsScreen). */
    static final int FALLBACK_LIMIT = 15;

    public static void main(String[] args) {
        rightToLeft();
        wholeTemplates();
        int legacy = fallbacks();
        require(legacy <= FALLBACK_LIMIT, legacy + " English-label fallback calls; B2 left " + FALLBACK_LIMIT
                + " and the count may only shrink: pass an icon id or a DriveMode instead");
        System.out.println("UiToolkitTest: " + checks + " checks passed (right-to-left lock geometry; "
                + legacy + " English-label fallback call(s) left for later batches, at most " + FALLBACK_LIMIT + ")");
    }

    /**
     * Every whole template a ternary picks, against the + chain it replaced (base Ui.java, ScooterPhotos),
     * for every condition. Each new expression is written as Ui writes it at head.
     */
    private static void wholeTemplates() {
        String[] texts = {"", "Speed", "18.7 km/h", "Full in 40 min", "\u0001A", "a  b", "%1$s", "62%"};
        String[] labels = {null, "", "Speed", "\u0001A", "%1$s"};
        for (String label : labels) {
            for (String value : new String[]{null, "", "18.7 km/h", "%1$s"}) {
                for (String hint : new String[]{null, "", "Peak 32 km/h", "%2$s"}) {
                    String before = label + ", " + (value == null ? "Not reported" : value)
                            + (hint == null || hint.isEmpty() ? "" : ", " + hint);
                    String after = hint == null || hint.isEmpty()
                            ? T.uiMetricDescription(label, (value == null ? T.commonNotReported() : value))
                            : T.uiMetricDescriptionHint(label, (value == null ? T.commonNotReported() : value), hint);
                    same(before, after, "metric card");
                }
                Boolean[] states = {null, true, false};
                for (Boolean state : states) {
                    same(label + (state == null ? ", state not reported" : ""),
                            state == null ? T.uiToggleStateNotReported(label) : String.valueOf(label), "switch");
                }
            }
        }
        for (String label : texts) {
            for (String subtitle : texts) {
                for (double percent : new double[]{Double.NaN, 0, 0.4, 1, 1.5, 62.4, 99.5, 100}) {
                    for (boolean charging : new boolean[]{false, true}) {
                        for (boolean live : new boolean[]{false, true}) {
                            String before = "Battery " + (Double.isNaN(percent) ? "not reported" : Math.round(percent) + " percent")
                                    + (charging ? ", charging" : "") + (live ? "" : ", last seen reading")
                                    + (subtitle.isEmpty() ? "" : ", " + subtitle);
                            String after = ((java.util.function.Function<String, String>) level -> charging
                                    ? live ? (subtitle.isEmpty() ? T.uiBatteryDescriptionCharging(level) : T.uiBatteryDescriptionChargingCaption(level, subtitle))
                                            : (subtitle.isEmpty() ? T.uiBatteryDescriptionChargingLastSeen(level) : T.uiBatteryDescriptionChargingLastSeenCaption(level, subtitle))
                                    : live ? (subtitle.isEmpty() ? T.uiBatteryDescription(level) : T.uiBatteryDescriptionCaption(level, subtitle))
                                            : (subtitle.isEmpty() ? T.uiBatteryDescriptionLastSeen(level) : T.uiBatteryDescriptionLastSeenCaption(level, subtitle)))
                                    .apply(Double.isNaN(percent) ? T.uiBatteryNotReported() : T.uiBatteryPercent(Math.round(percent), Math.round(percent)));
                            same(before, after, "battery ring description");
                            same(charging ? "Charging" + (subtitle.isEmpty() ? "" : " · " + subtitle) : subtitle,
                                    charging ? (subtitle.isEmpty() ? T.uiBatteryRingCharging() : T.uiBatteryRingChargingCaption(subtitle)) : subtitle,
                                    "battery ring label");
                        }
                    }
                }
                String number = label, unit = subtitle, time = "Nov 3, 2026 4:05:06 AM";
                same(number + (unit.isEmpty() ? "" : " " + unit) + "  ·  " + time,
                        unit.isEmpty() ? T.uiChartTooltipUnitless(number, time) : T.uiChartTooltip(number, unit, time), "chart tooltip");
            }
            for (int count : new int[]{0, 1, 2, 5, 21, 1600}) {
                same("Measured " + label + " chart, " + count + (count == 1 ? " sample" : " samples") + ". Displayed axis " + "0" + " to " + "1.5k" + ". Touch to inspect readings.",
                        T.uiChartDescription(count, label, count, "0", "1.5k"), "chart description");
            }
            same("NAVEE " + (label.isEmpty() ? "" : label + " ") + "scooter",
                    label.isEmpty() ? T.uiPhotoScooter() : T.uiPhotoModel(label), "scooter photo");
        }
    }

    private static void same(String before, String after, String what) {
        require(before.equals(after), what + ": the whole template says \"" + after + "\", the + chain said \"" + before + "\"");
    }

    private static void rightToLeft() {
        for (float density : new float[]{1, 2.75f, 3.5f}) {
            for (int[] size : new int[][]{{117, 182}, {150, 182}, {312, 374}, {312, 54}, {48, 48}, {144, 54}}) {
                float width = size[0] * density, height = size[1] * density;
                LockSliderGeometry ltr = new LockSliderGeometry(width, height, 58 * density, 5 * density);
                LockSliderGeometry also = new LockSliderGeometry(width, height, 58 * density, 5 * density, false);
                LockSliderGeometry rtl = new LockSliderGeometry(width, height, 58 * density, 5 * density, true);
                require(!ltr.rightToLeft && !also.rightToLeft && rtl.rightToLeft, "direction flag");
                require(rtl.diameter == ltr.diameter && rtl.radius == ltr.radius && rtl.firstCenter == ltr.firstCenter
                        && rtl.travel == ltr.travel, "a mirrored switch keeps its shape");
                for (float position : new float[]{-1, 0, .25f, .5f, .75f, 1, 2}) {
                    require(also.centerFor(position) == ltr.centerFor(position), "the explicit left-to-right switch is the same");
                    float center = rtl.centerFor(position);
                    require(Math.abs(center - (width - ltr.centerFor(position))) < .001f,
                            "right to left mirrors the thumb at " + position + " in " + size[0] + "x" + size[1]);
                    require(center - rtl.radius >= -.001f && center + rtl.radius <= width + .001f, "thumb stays in the card");
                    float clamped = Math.max(0, Math.min(1, position));
                    require(Math.abs(clamped - rtl.positionFor(center)) < .0001f, "touch and drawing agree right to left");
                }
                require(rtl.centerFor(0) > rtl.centerFor(1) || rtl.travel == 0, "position 0 is at the right edge");
                require(rtl.positionFor(width + 100) == 0 && rtl.positionFor(-100) == 1, "touch clamps at the mirrored ends");
            }
        }
        LockSliderGeometry ordinary = new LockSliderGeometry(312, 54, 58, 5, true);
        require(ordinary.centerFor(0) == 285 && ordinary.centerFor(1) == 27, "ordinary switch mirrored");
    }

    private static int fallbacks() {
        Map<String, String> sources = L10nFiles.appSources();
        Map<String, List<String>> found = new TreeMap<>();
        int total = 0;
        for (Map.Entry<String, String> file : sources.entrySet()) {
            String name = file.getKey(), code = L10nFiles.code(file.getValue());
            for (String call : calls(name, code)) {
                found.computeIfAbsent(name, k -> new ArrayList<>()).add(call);
                total++;
            }
        }
        // Ui's own uses: the three overloads that hand an English guess to the explicit ones.
        List<String> ui = found.getOrDefault("Ui.java", new ArrayList<>());
        require(ui.size() == 3 && ui.stream().filter(c -> c.startsWith("labelIcon")).count() == 2
                        && ui.stream().filter(c -> c.startsWith("styleDriveMode")).count() == 1,
                "Ui guesses from English only in its three legacy overloads: " + ui);
        for (String name : TOOLKIT) {
            require(sources.containsKey(name), name + " exists");
            require(!found.containsKey(name), name + " calls an English-label fallback: " + found.get(name));
        }
        for (Map.Entry<String, List<String>> file : found.entrySet()) {
            if (file.getKey().equals("Ui.java")) continue;
            System.out.println("  English-label fallback: " + file.getKey() + ": " + String.join(", ", file.getValue()));
        }
        return total - ui.size();
    }

    private static final Pattern CALL = Pattern.compile("\\b(labelIcon|toggleRow|settingRow|styleDriveMode)\\s*\\(");

    /**
     * The legacy calls in one file, as "name:line". Declarations (a type before the name) are skipped.
     * Outside Ui only calls on a receiver (ui.settingRow(...)) count: a screen's own method of the same
     * name (MoreScreen.toggleRow, which takes an icon id) is not Ui's.
     */
    private static List<String> calls(String name, String code) {
        List<String> result = new ArrayList<>();
        Matcher m = CALL.matcher(code);
        while (m.find()) {
            String before = code.substring(0, m.start()).replaceAll("\\s+$", "");
            if (before.matches("(?s).*\\b(String|View|LinearLayout|void)$")) continue;
            if (!name.equals("Ui.java") && !before.endsWith(".")) continue;
            int close = L10nFiles.closing(code, m.end() - 1);
            if (close < 0) continue;
            List<String> arguments = arguments(code.substring(m.end(), close - 1));
            String method = m.group(1);
            boolean legacy = method.equals("labelIcon")
                    || method.equals("toggleRow") && arguments.size() == 5
                    || method.equals("settingRow") && arguments.size() == 3
                    || method.equals("styleDriveMode") && arguments.size() == 3 && !driveMode(code, arguments.get(1).trim());
            if (legacy) result.add(method + ":" + L10nFiles.line(code, m.start()));
        }
        return result;
    }

    /** True when a styleDriveMode argument is a DriveMode: a constant, or a name the file declares with that type. */
    private static boolean driveMode(String code, String argument) {
        if (argument.matches("(Ui\\.)?DriveMode\\.(SPORT|ECO|WALK|OTHER)")) return true;
        return argument.matches("[A-Za-z_][A-Za-z0-9_]*")
                && Pattern.compile("\\bDriveMode\\s+" + argument + "\\b").matcher(code).find();
    }

    /** A call's arguments split at its top-level commas (string contents are already blanked). */
    private static List<String> arguments(String inside) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) { parts.add(inside.substring(start, i)); start = i + 1; }
        }
        if (!inside.trim().isEmpty()) parts.add(inside.substring(start));
        return parts;
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
