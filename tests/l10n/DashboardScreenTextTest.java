package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * B13 (the Home dashboard screen), beyond the characterization test, which pins the numbers and names
 * the screen formats. DashboardScreen is an Android screen, so this suite cannot call it; it checks the
 * catalog side of what the batch changed, against the base's own concatenations written out here:
 *
 *  - Whole templates a ternary picks (G2 checks each template alone): every branch of the reading,
 *    drive mode, charging hero, battery overview, shortcut, Add widget, slot and duration choices
 *    renders what the base's chain printed, over live and stale readings, missing parts and empty
 *    status text.
 *  - Plurals (manual rows): dashboard_battery_percent, dashboard_selected_count, dashboard_window_days
 *    and dashboard_count_of_shortcuts print the base's "Battery N percent", "N selected", "N days" and
 *    "N of M shortcuts" for counts 0, 1, 2, 5, 21 and 100.
 *  - The reading pickers' names: ScooterReadingLabels.full() and group() are, in English, Telemetry's
 *    label and group.
 *  - Format templates: the capacity widget's energy, rides, comparison and charge-per-gauge templates,
 *    fed Fmt.decimal and Fmt.integer, print the base's String.format(Locale.US, ...) byte for byte under
 *    five phone locales.
 *  - The charging time ladder: each rung of dashboard_charge_time_* equals the base's three names.
 *  - Shortcut names: QuickAccessActions.Entry.label() and groupLabel(), which the screen now shows, are
 *    in English the entry's label and group, which the base showed.
 *  - Setting tile values: QuickAccessGrid now shows a feature's value through
 *    DisplayUnits.featureValueText, which in English is the base's featureValue for every shortcut's
 *    feature, every value a readback can carry (0 to 255) and its range, far outside it, and each units
 *    preference, under five phone locales.
 *  - The save-slot dialog: an empty slot's name is B12's default slot name, "Slot N" in English, and
 *    the dialog's source starts a saved slot at its shown name and saves through
 *    DashboardConfig.slotNameToSave (DashboardSlotsValidation runs that on an emulator), which keeps
 *    the example's English name only when the save leaves slot 3 the example's: the example's own
 *    widgets saved back do (DashboardExampleIds.exampleSlot, as savesExample asks it), the owner's
 *    own layout (DefaultDashboard's widgets, random ids) or the example with a widget of theirs does
 *    not, so the field's text is saved there.
 */
public final class DashboardScreenTextTest {
    private static int checks;

    public static void main(String[] args) {
        T.Source saved = T.source();
        Locale phone = Locale.getDefault();
        try {
            T.install(null);
            choices();
            plurals();
            ladder();
            shortcuts();
            slotDialog();
            for (Locale l : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR"), new Locale("ar", "EG"),
                    new Locale("hi", "IN")}) {
                Locale.setDefault(l);
                formats();
                tileValues();
            }
        } finally {
            Locale.setDefault(phone);
            T.install(saved);
        }
        System.out.println("DashboardScreenTextTest: " + checks + " checks passed (choice templates, plurals, "
                + "format templates and Setting tile values under 5 locales, charge time ladder, shortcut names, "
                + "save-slot dialog)");
    }

    private static void same(String expected, CharSequence actual, String what) {
        checks++;
        if (!expected.equals(actual.toString()))
            throw new AssertionError(what + ": expected \"" + expected + "\" but was \"" + actual + "\"");
    }

    private static void choices() {
        String[] labels = {"Battery", "Speed", ""};
        String[] values = {"42 %", "18.5 km/h", "—", ""};
        String[] whens = {"today 14:05", "Oct 3, 09:12"};
        for (String label : labels)
            for (String text : values)
                for (String when : whens)
                    for (boolean stale : new boolean[]{false, true}) {
                        same(label + ", " + text + (stale ? ", last seen " + when : ""),
                                stale ? T.dashboardReadingSeen(label, text, when) : T.dashboardReading(label, text),
                                "reading " + label + "/" + text);
                        same("Drive mode, " + text + (stale ? ", last seen " + when : ""),
                                stale ? T.dashboardDriveModeReadingSeen(text, when) : T.dashboardDriveModeReading(text),
                                "drive mode " + text);
                    }
        // The charging hero's spoken time: chip and status, each optional.
        for (String tag : new String[]{null, "Low current"})
            for (String status : new String[]{null, "", "Waiting for the first reading"}) {
                String name = "Time to 80%", eta = "1h 05m";
                boolean noStatus = status == null || status.isEmpty();
                String base = name + ", " + eta + (tag != null ? ", " + tag : "") + (noStatus ? "" : ". " + status);
                String head = tag != null
                        ? (noStatus ? T.dashboardHeroTimeTag(name, eta, tag) : T.dashboardHeroTimeTagStatus(name, eta, tag, status))
                        : (noStatus ? T.dashboardHeroTime(name, eta) : T.dashboardHeroTimeStatus(name, eta, status));
                same(base, head, "hero time " + tag + "/" + status);
            }
        // The charging hero's spoken level and limit.
        for (double level : new double[]{Double.NaN, 0, 42.4, 99.5, 100})
            for (boolean hasLimit : new boolean[]{false, true}) {
                double limit = 80;
                String base = (Double.isNaN(level) ? "Charge not reported" : "Charge " + Math.round(level) + " percent")
                        + (hasLimit ? ", charge limit " + Math.round(limit) + " percent" : "");
                String head = Double.isNaN(level)
                        ? (hasLimit ? T.dashboardHeroChargeNotReportedLimit(Fmt.integer(Math.round(limit)))
                                : T.dashboardHeroChargeNotReported())
                        : (hasLimit ? T.dashboardHeroChargeLimit(Fmt.integer(Math.round(level)), Fmt.integer(Math.round(limit)))
                                : T.dashboardHeroCharge(Fmt.integer(Math.round(level))));
                same(base, head, "hero charge " + level + "/" + hasLimit);
            }
        // The battery overview's spoken text.
        for (String amount : new String[]{null, "42"})
            for (String line : new String[]{"", "about 31 mi left", "Charging"})
                for (boolean seen : new boolean[]{false, true}) {
                    String when = "today 14:05";
                    String base = "Battery " + (amount == null ? "not reported" : amount + " percent")
                            + (line.isEmpty() ? "" : ", " + line) + (seen ? ", last seen " + when : "");
                    String head = amount == null
                            ? (line.isEmpty()
                                    ? (seen ? T.dashboardOverviewNotReportedSeen(when) : T.dashboardOverviewNotReported())
                                    : (seen ? T.dashboardOverviewNotReportedLineSeen(line, when) : T.dashboardOverviewNotReportedLine(line)))
                            : (line.isEmpty()
                                    ? (seen ? T.dashboardOverviewPercentSeen(amount, when) : T.dashboardOverviewPercent(amount))
                                    : (seen ? T.dashboardOverviewPercentLineSeen(amount, line, when) : T.dashboardOverviewPercentLine(amount, line)));
                    same(base, head, "overview " + amount + "/" + line + "/" + seen);
                }
        // The charging card's description and time name.
        for (String detail : new String[]{"", "Waiting for the first reading"}) {
            String base = "Charging power" + ", " + "412 W" + (detail.isEmpty() ? "" : ". " + detail);
            same(base, detail.isEmpty() ? T.dashboardChargingDescription("Charging power", "412 W")
                    : T.dashboardChargingDescriptionDetail("Charging power", "412 W", detail), "charging description");
        }
        same("To 80% limit (Low current)", T.dashboardChargingTimeTag("To 80% limit", "Low current"), "charging time tag");
        // Durations, with and without hours.
        for (long value : new long[]{1, 59, 60, 61, 125, 1439}) {
            String base = value == 0 ? "at target"
                    : "≈ " + (value >= 60 ? value / 60 + "h " : "") + value % 60 + "m";
            same(base, value >= 60 ? T.dashboardDurationHours(Fmt.integer(value / 60), Fmt.integer(value % 60))
                    : T.dashboardDurationMinutes(Fmt.integer(value % 60)), "duration " + value);
        }
        // Notes, shortcuts, widget types and slots.
        same("Empty note. Edit note", T.dashboardEmptyNoteEdit(), "empty note");
        same("Buy tyres" + ". Edit note", T.dashboardEditNote("Buy tyres"), "note");
        for (boolean selected : new boolean[]{false, true})
            for (boolean toggle : new boolean[]{false, true}) {
                String label = "Headlight", group = "Lights";
                String base = label + ", " + group + (selected ? ", selected" : toggle ? ", toggle" : "");
                same(base, selected ? T.dashboardShortcutSelected(label, group)
                        : toggle ? T.dashboardShortcutToggle(label, group) : T.dashboardShortcut(label, group), "shortcut");
            }
        for (String type : new String[]{"capacity", "gauge"}) {
            String name = type.equals("capacity") ? "Usable ride energy" : "Gauge";
            same("Add " + name + (type.equals("capacity") ? ", experimental" : ""),
                    type.equals("capacity") ? T.dashboardAddTypeExperimental(name) : T.dashboardAddType(name), "add " + type);
        }
        for (int index = 0; index < 4; index++) {
            for (String name : new String[]{null, "Commute"})
                same("Restore slot " + (index + 1) + (name == null ? "" : ", " + name),
                        name == null ? T.dashboardRestoreSlot(Fmt.integer(index + 1))
                                : T.dashboardRestoreSlotNamed(Fmt.integer(index + 1), name), "restore slot");
            for (boolean replacing : new boolean[]{false, true})
                same((replacing ? "Replace slot " : "Save to slot ") + (index + 1) + "?",
                        replacing ? T.dashboardReplaceSlotTitle(Fmt.integer(index + 1))
                                : T.dashboardSaveSlotTitle(Fmt.integer(index + 1)), "slot title");
        }
    }

    private static void plurals() {
        for (long n : new long[]{0, 1, 2, 5, 21, 100}) {
            same("Battery " + n + " percent", T.dashboardBatteryPercent(n, n), "battery percent " + n);
            same(n + " selected", T.dashboardSelectedCount(n, n), "selected " + n);
            // shortWindow: days == 1 keeps "1 day"; every other count says "days".
            same(n + " days", T.dashboardWindowDays(n, n), "window days " + n);
            for (long most : new long[]{0, 1, 4, 12})
                same(n + " of " + most + " shortcuts", T.dashboardCountOfShortcuts(most, n, most),
                        "count of shortcuts " + n + "/" + most);
        }
        // The Choose parameter and Choose readings sheets show these display names; in English they
        // are Telemetry's label and group, which the base showed and searched.
        for (Telemetry.Metric m : new Telemetry().metrics()) {
            same(m.label, ScooterReadingLabels.full(m.key, m.label), "picker name of " + m.key);
            same(m.group, ScooterReadingLabels.group(m.group), "picker group of " + m.key);
        }
    }

    private static void ladder() {
        String target = String.format(Locale.US, "%.0f%%", 80d);
        same("Time to " + target, T.dashboardChargeTimeTo(target), "time to limit");
        same("To " + target, T.dashboardChargeTimeToCompact(target), "to limit, compact");
        same("To " + target + " limit", T.dashboardChargeToLimit(target), "widget limit name");
        same("Time to 100% if uncapped", T.dashboardChargeTimeUncapped(), "uncapped");
        same("To 100% if uncapped", T.dashboardChargeTimeUncappedShort(), "uncapped, short");
        same("100% if uncapped", T.dashboardChargeTimeUncappedCompact(), "uncapped, compact");
        same("Time to full (est.)", T.dashboardChargeTimeFull(), "full");
        same("Time to full (est.)", T.dashboardChargeTimeFullShort(), "full, short");
        same("To full (est.)", T.dashboardChargeTimeFullCompact(), "full, compact");
    }

    private static void shortcuts() {
        for (QuickAccessActions.Entry e : QuickAccessActions.catalog()) {
            same(e.label, e.label(), "label() of " + e.id);
            same(e.group, e.groupLabel(), "groupLabel() of " + e.id);
        }
    }

    /**
     * Every value a Setting tile or Quick access cell can show through DisplayUnits (QuickAccessGrid's
     * last branch, after the dash, Updating and the OEM text): the feature of every shortcut, any value
     * a readback can carry (a byte, 0 to 255), the feature's range and two steps past each end (its first
     * thousand values for a time), and values far outside, for each units preference. featureValueText
     * must print the base's featureValue.
     */
    private static void tileValues() {
        int[] far = {Integer.MIN_VALUE, -1000000, -65536, -1000, -256, 999, 1000, 1001, 9999, 10000, 65535, 65536,
                1000000, 86399, 86400, 1700000000, Integer.MAX_VALUE};
        int features = 0;
        for (QuickAccessActions.Entry e : QuickAccessActions.catalog()) {
            ScooterCommands.Feature f = e.feature();
            if (f == null) continue;
            features++;
            java.util.TreeSet<Integer> values = new java.util.TreeSet<>();
            for (int v = -2; v <= 300; v++) values.add(v);
            // The range from both ends (charge_start, a time, runs to Integer.MAX_VALUE).
            for (long v = (long) f.min - 2; v <= Math.min((long) f.max + 2, (long) f.min + 1000); v++) values.add((int) v);
            for (long v = Math.max((long) f.max - 2, (long) f.min); v <= Math.min((long) f.max + 2, Integer.MAX_VALUE); v++)
                values.add((int) v);
            for (int v : f.optionValues) values.add(v);
            for (int v : far) values.add(v);
            for (int v : values)
                for (String units : new String[]{"mi", "km", "dual", null, ""})
                    same(DisplayUnits.featureValue(f, v, units), DisplayUnits.featureValueText(f, v, units),
                            e.id + " tile value " + v + " in " + units);
        }
        check(features > 20, "the tile sweep sees the feature shortcuts (" + features + ")");
        String grid = read("src/com/jjricks/volt/QuickAccessGrid.java");
        check(grid.contains(": DisplayUnits.featureValueText(feature, value, units);") && !grid.contains("DisplayUnits.featureValue("),
                "a Setting tile shows DisplayUnits.featureValueText");
    }

    /** The save-slot dialog: an empty slot's name is "Slot N" in English, and the dialog keeps saved names. */
    private static void slotDialog() {
        for (int index = 0; index < 3; index++)
            same("Slot " + (index + 1), T.dashboardModelSlotDefault(Fmt.integer(index + 1)), "empty slot " + index + " name");
        String screen = read("src/com/jjricks/volt/DashboardScreen.java");
        check(screen.contains("name.setText(saved == null ? T.dashboardModelSlotDefault(Fmt.integer(index + 1)) : saved.name);")
                        && screen.contains("String shown = saved == null ? DashboardConfig.defaultSlotName(index) : DashboardConfig.displaySlotName(saved, index);")
                        && screen.contains("if (saved != null) name.setText(shown);"),
                "the save dialog starts at the slot's shown name");
        check(screen.contains("config.saveSlot(index, config.slotNameToSave(name.getText().toString(), shown, saved, index),")
                        && !screen.contains("config.saveSlot(index, name.getText().toString(),"),
                "the save dialog saves through DashboardConfig.slotNameToSave");
        String config = read("src/com/jjricks/volt/DashboardConfig.java");
        check(config.contains("public String slotNameToSave(String typed, String shown, SavedSlot saved, int index) {\n"
                        + "        if (!slotName(typed, index).equals(slotName(shown, index))) return typed;\n"
                        + "        if (saved == null) return null;\n"
                        + "        return saved.example && !savesExample(index) ? typed : saved.name;\n    }"),
                "slotNameToSave keeps the saved name for the name the dialog showed, the example's only when the save keeps it");
        // savesExample asks exampleSlot about the stored form of the widgets saveSlot(index, name, columns) saves.
        check(config.contains("return exampleSlot(index, EXAMPLE_SLOT_NAME, writeWidgets(widgets));")
                        && config.contains("return saveSlot(index, name, columns, widgets);")
                        && config.contains("snapshot.put(\"widgets\", writeWidgets(definitions));")
                        && config.contains("o.put(\"id\", w.id);") && config.contains("o.put(\"type\", w.type);"),
                "savesExample does not check the widgets the dialog's save writes");
        // What savesExample decides for slot 3. The example's own widgets saved back (as restoring the
        // example and saving it unchanged does) keep it the example's, so its English name is saved. The
        // owner's own layout, as a new dashboard starts (DefaultDashboard: new Widget(type), a random id
        // each), or the example with one widget of the owner's does not, so the field's text is saved.
        String example = read("src/com/jjricks/volt/AllWidgetsDashboard.java");
        String own = read("src/com/jjricks/volt/DefaultDashboard.java");
        check(example.contains("new DashboardConfig.Widget(DashboardExampleIds.create(), type);")
                        && own.contains("DashboardConfig.Widget widget = new DashboardConfig.Widget(type);")
                        && config.contains("this(UUID.randomUUID().toString(), type);"),
                "the example's and a new dashboard's widget ids are not made as this check makes them");
        String[] exampleTypes = madeTypes(example, "add\\(result, \"([a-z]+)\"\\)");
        String[] ownTypes = madeTypes(own, "add\\(result, \"([a-z]+)\",");
        check(exampleTypes.length > 10 && ownTypes.length >= 5, "widget types read: " + exampleTypes.length + ", " + ownTypes.length);
        String[] exampleIds = new String[exampleTypes.length], ownIds = new String[ownTypes.length];
        for (int i = 0; i < exampleIds.length; i++) exampleIds[i] = DashboardExampleIds.create();
        for (int i = 0; i < ownIds.length; i++) ownIds[i] = java.util.UUID.randomUUID().toString();
        check(DashboardExampleIds.exampleSlot(2, true, exampleIds, exampleTypes),
                "the example's own widgets saved back to slot 3 do not keep its name");
        check(!DashboardExampleIds.exampleSlot(2, true, ownIds, ownTypes),
                "the owner's own layout saved to slot 3 keeps the example's name");
        String[] mixedIds = java.util.Arrays.copyOf(exampleIds, exampleIds.length), mixedTypes = exampleTypes.clone();
        mixedIds[0] = java.util.UUID.randomUUID().toString();
        check(!DashboardExampleIds.exampleSlot(2, true, mixedIds, mixedTypes),
                "the example with an owner's widget saved to slot 3 keeps the example's name");
    }

    /** The widget types a factory's add(result, "type"...) calls make, in order. */
    private static String[] madeTypes(String source, String call) {
        java.util.List<String> types = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(call).matcher(source);
        while (m.find()) types.add(m.group(1));
        return types.toArray(new String[0]);
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError(path + " not readable: " + e);
        }
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static void formats() {
        String wh = T.unitsWattHour(), ah = T.unitsAmpHour();
        for (double v : new double[]{0, 0.5, 1.5, 412.5, 610 * .95, 716, 1234.5, -0.0}) {
            same(String.format(Locale.US, "≈ %.0f Wh", v), T.dashboardApproxEnergy(Fmt.decimal(Locale.US, v, 0), wh),
                    "approximate energy " + v);
            same(String.format(Locale.US, "8 rides · %.0f–%.0f Wh", v * .96, v * 1.04),
                    T.dashboardSampleRides(Fmt.decimal(Locale.US, v * .96, 0), Fmt.decimal(Locale.US, v * 1.04, 0), wh),
                    "sample rides " + v);
            for (int rides : new int[]{0, 1, 8, 12}) {
                same(String.format(Locale.US, "%d rides · %.0f–%.0f Wh", rides, v, v * 1.1),
                        T.dashboardRidesRange(Fmt.integer(rides), Fmt.decimal(Locale.US, v, 0),
                                Fmt.decimal(Locale.US, v * 1.1, 0), wh), "rides range " + rides);
                same(String.format(Locale.US, "%d rides · %.0f points used", rides, v),
                        T.dashboardRidesPoints(Fmt.integer(rides), Fmt.decimal(Locale.US, v, 0)), "rides points " + rides);
            }
            same(String.format(Locale.US, "%.0f%% of %.0f Wh", v / 10, v),
                    T.dashboardPercentOf(Fmt.decimal(Locale.US, v / 10, 0), Fmt.decimal(Locale.US, v, 0), wh), "percent of");
            same(String.format(Locale.US, "%.1f Ah (%.1f–%.1f)", v / 50, v / 60, v / 40),
                    T.dashboardAhRange(Fmt.decimal(Locale.US, v / 50, 1), ah, Fmt.decimal(Locale.US, v / 60, 1),
                            Fmt.decimal(Locale.US, v / 40, 1)), "charge per gauge " + v);
        }
    }
}
