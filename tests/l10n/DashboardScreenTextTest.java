package com.jjricks.volt;

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
 *  - Plurals (manual rows): dashboard_battery_percent and dashboard_selected_count print the base's
 *    "Battery N percent" and "N selected" for counts 0, 1, 2, 5, 21 and 100.
 *  - Format templates: the capacity widget's energy, rides, comparison and charge-per-gauge templates,
 *    fed Fmt.decimal and Fmt.integer, print the base's String.format(Locale.US, ...) byte for byte under
 *    five phone locales.
 *  - The charging time ladder: each rung of dashboard_charge_time_* equals the base's three names.
 *  - Shortcut names: QuickAccessActions.Entry.label() and groupLabel(), which the screen now shows, are
 *    in English the entry's label and group, which the base showed.
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
            for (Locale l : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR"), new Locale("ar", "EG"),
                    new Locale("hi", "IN")}) {
                Locale.setDefault(l);
                formats();
            }
        } finally {
            Locale.setDefault(phone);
            T.install(saved);
        }
        System.out.println("DashboardScreenTextTest: " + checks + " checks passed (choice templates, plurals, "
                + "format templates under 5 locales, charge time ladder, shortcut names)");
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
