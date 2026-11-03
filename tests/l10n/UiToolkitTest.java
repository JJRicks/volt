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
 *    shown text) for the screens later batches convert; the plan's gate before any language ships
 *    (part E) needs this count at zero. Every caller is listed. Ui itself may use labelIcon only in
 *    the two legacy overloads, and the other toolkit files of B2 call none of them.
 */
public final class UiToolkitTest {
    private static int checks;

    /** The B2 toolkit files besides Ui, which must not call an English-label fallback. */
    private static final String[] TOOLKIT = {
            "SegmentedSelector.java", "DashboardCaption.java", "ReorderList.java", "SearchFocus.java", "Motion.java",
            "WidgetReveal.java", "WidgetRemovalEffect.java", "FoldMonitor.java", "LiveStatsTitle.java",
            "StatsWindowPicker.java", "ModelPicker.java", "ScooterPhotos.java", "LockSliderGeometry.java",
            "LockSliderTrail.java", "Haptics.java"};

    public static void main(String[] args) {
        rightToLeft();
        int legacy = fallbacks();
        System.out.println("UiToolkitTest: " + checks + " checks passed (right-to-left lock geometry; "
                + legacy + " English-label fallback call(s) left for later batches)");
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
        // Ui's own uses: the two overloads that hand the label's guess to the explicit ones.
        List<String> ui = found.getOrDefault("Ui.java", new ArrayList<>());
        require(ui.size() == 2 && ui.stream().allMatch(c -> c.startsWith("labelIcon")),
                "Ui uses labelIcon only in its two legacy overloads: " + ui);
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

    /** The legacy calls in one file, as "name:line". Declarations (a type before the name) are skipped. */
    private static List<String> calls(String name, String code) {
        List<String> result = new ArrayList<>();
        Matcher m = CALL.matcher(code);
        while (m.find()) {
            String before = code.substring(0, m.start()).replaceAll("\\s+$", "");
            if (before.matches("(?s).*\\b(String|View|LinearLayout|void)$")) continue;
            int close = L10nFiles.closing(code, m.end() - 1);
            if (close < 0) continue;
            List<String> arguments = arguments(code.substring(m.end(), close - 1));
            String method = m.group(1);
            boolean legacy = method.equals("labelIcon")
                    || method.equals("toggleRow") && arguments.size() == 5
                    || method.equals("settingRow") && arguments.size() == 3
                    || method.equals("styleDriveMode") && arguments.size() == 3
                            && !arguments.get(1).trim().matches("(?s)(Ui\\.)?DriveMode\\..*|mode");
            if (legacy) result.add(method + ":" + L10nFiles.line(code, m.start()));
        }
        return result;
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
