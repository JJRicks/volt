package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B12 (dashboard model and quick access), beyond the characterization test, which pins the English the
 * shortcut catalogue keeps (label, group, icon, feature key):
 *
 *  - English: every shortcut's label(), shortLabel() and groupLabel() renders exactly its English
 *    label and group, and english() is that text too.
 *  - Another language: with a source that marks every catalogue text, a Volt shortcut's names come from
 *    the catalogue and a scooter control's are B4's (feature.label(), groupLabel(), the experimental
 *    group), while label, group and every id stay English.
 *  - Templates and fragments: the grid tile's state and spoken-description pieces, and the default slot
 *    name, equal the base's inline concatenations.
 *  - Widget names: dashboard_model_type_* match DashboardConfig.NAMES, in TYPES order, and the example
 *    titles and note match AllWidgetsDashboard's saved English (both read from src/, since DashboardConfig
 *    needs org.json; DashboardSlotsValidation checks the accessors on Android).
 *  - Example ids (DashboardExampleIds): only the shipped example's widgets show its titles, note and slot
 *    name through the catalogue, and their ids say which. Every id it makes is a canonical UUID, distinct,
 *    tagged and version 8, which no random (version 4) UUID is: not another widget's, not an older
 *    version's example. Only AllWidgetsDashboard makes them, and the accessors check them (read from src/;
 *    DashboardSlotsValidation.exampleProvenance runs them on Android with the real example).
 */
public final class DashboardModelTextTest {
    private static int checks;

    static final class Marked implements T.Source {
        public String string(int id) { return "«" + EnglishText.INSTANCE.string(id) + "»"; }
        public String plural(int id, long quantity) { return "«" + EnglishText.INSTANCE.plural(id, quantity) + "»"; }
        public String language() { return "en"; }
    }

    public static void main(String[] args) throws Exception {
        T.Source saved = T.source();
        try {
            T.install(null);
            english();
            templates();
            widgetNames();
            exampleIds();
            T.install(new Marked());
            marked();
        } finally {
            T.install(saved);
        }
        System.out.println("DashboardModelTextTest: " + checks + " checks passed (shortcut names in English and marked, tile templates, widget and slot names, example ids)");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static void same(String expected, CharSequence actual, String what) {
        check(expected.equals(actual.toString()), what + ": expected \"" + expected + "\" but was \"" + actual + "\"");
    }

    private static void english() {
        for (QuickAccessActions.Entry e : QuickAccessActions.catalog()) {
            same(e.label, e.label().render(), e.id + " label()");
            same(e.label, e.label().english(), e.id + " label().english()");
            same(e.label, e.shortLabel().render(), e.id + " shortLabel()");
            same(e.label, e.shortLabel().english(), e.id + " shortLabel().english()");
            same(e.group, e.groupLabel().render(), e.id + " groupLabel()");
            same(e.group, e.groupLabel().english(), e.id + " groupLabel().english()");
            check(e.id.equals(QuickAccessActions.find(e.id).id), e.id + " find");
        }
        same("Top speed", T.dashboardModelTileTopSpeed(), "tile top speed");
        same("Charge schedule", T.dashboardModelTileChargeSchedule(), "tile charge schedule");
    }

    private static void marked() {
        for (QuickAccessActions.Entry e : QuickAccessActions.catalog()) {
            ScooterCommands.Feature feature = e.featureKey == null ? null : ScooterCommands.find(e.featureKey);
            if (feature == null) {
                check(e.featureKey == null, e.id + ": a feature shortcut without its feature");
                same("«" + e.label + "»", e.label().render(), e.id + " marked label()");
                same("«" + e.label + "»", e.shortLabel().render(), e.id + " marked shortLabel()");
                same("«" + e.group + "»", e.groupLabel().render(), e.id + " marked groupLabel()");
            } else {
                same(feature.label().render(), e.label().render(), e.id + " marked label() is the feature's");
                same(feature.label().render(), e.shortLabel().render(), e.id + " marked shortLabel() is the feature's");
                same(feature.experimental ? "«Experimental controls»" : feature.groupLabel().render(), e.groupLabel().render(),
                        e.id + " marked groupLabel()");
                same(QuickAccessActions.FEATURE_PREFIX + feature.key, e.id, e.id + " id");
            }
            // The data the picker searches, the saved ids and older screens read stay English.
            check(!e.label.contains("«") && !e.group.contains("«") && !e.id.contains("«"), e.id + " English fields marked");
            same(e.label, e.label().english(), e.id + " marked label().english()");
            same(e.group, e.groupLabel().english(), e.id + " marked groupLabel().english()");
        }
        same("«Updating…»", T.dashboardModelTileUpdating(), "marked updating");
        same("«On • 7:00»", T.dashboardModelTileOnAt("7:00"), "marked on at");
        same("«Slot 2»", T.dashboardModelSlotDefault(Fmt.integer(2)), "marked slot");
    }

    /** Each template renders the base's concatenation for sample values. */
    private static void templates() {
        for (String v : new String[]{"", "7:00 PM", "19:00", "80%", "Wait for the current change to finish", "a%sb", "$1"}) {
            same("On • " + v, T.dashboardModelTileOnAt(v), "tile on at");
            same(" • " + v, T.dashboardModelTileAtTime(v), "tile at time");
            same(", " + v, T.dashboardModelTileDescPart(v), "tile description part");
        }
        for (int index = 0; index < 12; index++)
            same("Slot " + (index + 1), T.dashboardModelSlotDefault(Fmt.integer(index + 1)), "slot " + index);
        String[][] exact = {
            {"Updating…", T.dashboardModelTileUpdating()}, {"Volt (learned)", T.dashboardModelTileRangeLearned()},
            {"Scooter", T.dashboardModelTileRangeScooter()}, {"Measuring", T.dashboardModelTileMeasuring()},
            {"Not scheduled", T.dashboardModelTileNotScheduled()}, {"Select a scooter first", T.dashboardModelTileSelectScooter()},
            {"Connect your scooter first", T.dashboardModelTileConnectScooter()},
            {", not offered for this region", T.dashboardModelTileDescRegion()}, {", not on this model", T.dashboardModelTileDescModel()},
            {", unreadable value", T.dashboardModelTileDescUnreadable()}, {", last seen", T.dashboardModelTileDescLastSeen()},
            {", toggle", T.dashboardModelTileDescToggle()}, {", open", T.dashboardModelTileDescOpen()},
            {"All widgets", T.dashboardModelSlotExample()},
        };
        for (String[] pair : exact) same(pair[0], pair[1], "exact");
    }

    /** dashboard_model_type_* against NAMES, and the example's keys against its saved English, read from src/. */
    private static void widgetNames() throws Exception {
        String config = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/DashboardConfig.java")), StandardCharsets.UTF_8);
        List<String> types = strings(block(config, "public static final String[] TYPES = {"));
        List<String> names = strings(block(config, "public static final String[] NAMES = {"));
        List<String> calls = new ArrayList<>();
        Matcher m = Pattern.compile("M\\.(dashboardModelType\\w+)\\(\\)").matcher(block(config, "private static final Msg[] TYPE_NAMES = {"));
        while (m.find()) calls.add(m.group(1));
        check(types.size() == 19 && names.size() == types.size() && calls.size() == types.size(), "TYPES, NAMES and TYPE_NAMES sizes");
        for (int i = 0; i < types.size(); i++) {
            String key = "dashboardModelType" + camel(types.get(i));
            same(key, calls.get(i), "TYPE_NAMES[" + i + "] follows TYPES");
            same(names.get(i), (String) T.class.getMethod(key).invoke(null), "type name " + types.get(i));
        }
        String example = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/AllWidgetsDashboard.java")), StandardCharsets.UTF_8);
        String[][] pairs = {
            {"SPEED_TITLE", T.dashboardModelExampleSpeed()}, {"BATTERY_CHARGE_TITLE", T.dashboardModelExampleBatteryCharge()},
            {"ELECTRICAL_TITLE", T.dashboardModelExampleElectrical()}, {"BATTERY_POWER_TITLE", T.dashboardModelExampleBatteryPower()},
            {"CHARGE_HISTORY_TITLE", T.dashboardModelExampleChargeHistory()}, {"NOTE_TEXT", T.dashboardModelExampleNote()},
        };
        for (String[] pair : pairs) {
            Matcher c = Pattern.compile("\\b" + pair[0] + " = \"([^\"]*)\"").matcher(example);
            check(c.find(), pair[0] + " not declared");
            same(c.group(1), pair[1], "example " + pair[0]);
        }
        Matcher slot = Pattern.compile("EXAMPLE_SLOT_NAME = \"([^\"]*)\"").matcher(config);
        check(slot.find(), "EXAMPLE_SLOT_NAME not declared");
        same(slot.group(1), T.dashboardModelSlotExample(), "example slot name");
    }

    /** DashboardExampleIds against random UUIDs and malformed ids, and who makes and checks them (src/). */
    private static void exampleIds() throws Exception {
        Set<String> made = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            String id = DashboardExampleIds.create();
            UUID uuid = UUID.fromString(id);
            check(made.add(id), "example id repeated: " + id);
            // The form every widget id has, which older versions save, export and import as they always have.
            same(id, uuid.toString(), "example id is a canonical UUID");
            check(id.length() == 36 && id.startsWith("566f6c74-") && uuid.version() == 8 && uuid.variant() == 2,
                    "example id layout: " + id);
            check(DashboardExampleIds.is(id), "example id not recognised: " + id);
            check(!DashboardExampleIds.is(id.toUpperCase(Locale.ROOT)) && !DashboardExampleIds.is(" " + id)
                    && !DashboardExampleIds.is(id + " ") && !DashboardExampleIds.is(id.substring(1)), "altered example id: " + id);
            // Every other widget's id, and every example id an older version saved.
            String random = UUID.randomUUID().toString();
            check(!DashboardExampleIds.is(random), "random UUID taken for the example's: " + random);
            String tagged = "566f6c74" + random.substring(8);
            check(UUID.fromString(tagged).version() == 4 && !DashboardExampleIds.is(tagged), "version 4 with the tag taken: " + tagged);
        }
        String sample = DashboardExampleIds.create();
        String[] refused = {null, "", "note", "Slot 1", "566f6c74", "566f6c74-0-8000-8000-0",
            "566f6c74-0000-8000-8000-00000000000g", "566f6c75" + sample.substring(8),
            sample.substring(0, 14) + "7" + sample.substring(15), sample.substring(0, 19) + "c" + sample.substring(20),
            "3b241101-e2bb-4255-8caf-4136c566a962", "00000000-0000-0000-0000-000000000000"};
        for (String id : refused) check(!DashboardExampleIds.is(id), "not an example id, yet taken: " + id);

        // Only the example makes them; every other widget (new, duplicated, read back) keeps its random or saved id.
        String example = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/AllWidgetsDashboard.java")), StandardCharsets.UTF_8);
        String config = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/DashboardConfig.java")), StandardCharsets.UTF_8);
        int makers = 0;
        for (java.io.File file : new java.io.File("src/com/jjricks/volt").listFiles()) {
            if (!file.getName().endsWith(".java")) continue;
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (source.contains("DashboardExampleIds.create()")) {
                makers++;
                same("AllWidgetsDashboard.java", file.getName(), "only the example makes example ids");
            }
        }
        check(makers == 1, "example ids made in " + makers + " files");
        check(example.contains("new DashboardConfig.Widget(DashboardExampleIds.create(), type)"), "the example's widgets get example ids");
        check(config.contains("this(UUID.randomUUID().toString(), type);"), "other widgets get random ids");
        // The example title and note, and the example slot name, ask for them.
        Matcher title = Pattern.compile("static Msg exampleTitle\\(DashboardConfig\\.Widget widget\\) \\{\\s*if \\(!isExample\\(widget\\)\\) return null;").matcher(example);
        check(title.find(), "exampleTitle does not check the widget is the example's");
        check(example.contains("return isExample(widget) && \"note\".equals(widget.type) && NOTE_TEXT.equals(widget.note)"),
                "exampleNote does not check the widget is the example's");
        check(example.contains("return widget != null && DashboardExampleIds.is(widget.id);"), "isExample does not read the id");
        check(config.contains("if (!DashboardExampleIds.is(widget.optString(\"id\")) || !types.add(widget.optString(\"type\"))) return false;"),
                "exampleSlot does not check every widget's id");
        check(config.contains("if (slot.example) return M.dashboardModelSlotExample();")
                && !config.contains("EXAMPLE_SLOT_NAME.equals(name) ? T.dashboardModelSlotExample()"),
                "a slot shows the example's name for its name alone");
    }

    private static String block(String source, String start) {
        int at = source.indexOf(start);
        check(at >= 0, start + " not found");
        return source.substring(at + start.length(), source.indexOf("};", at));
    }

    private static List<String> strings(String block) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(block);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String camel(String id) {
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }
}
