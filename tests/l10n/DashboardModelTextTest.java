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
 *  - The example slot (DashboardExampleIds.exampleSlot): slot 3 under the example's name, holding only
 *    example widgets with no type twice, against create()'s widgets (read from src/): it still counts
 *    with a type fewer or more, so a type added later keeps every saved example slot, and does not with
 *    any widget not the example's.
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
            exampleSlotRule();
            T.install(new Marked());
            marked();
        } finally {
            T.install(saved);
        }
        System.out.println("DashboardModelTextTest: " + checks + " checks passed (shortcut names in English and marked, tile templates, widget and slot names, example ids, example slot)");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static void same(String expected, CharSequence actual, String what) {
        check(expected.equals(actual.toString()), what + ": expected \"" + expected + "\" but was \"" + actual + "\"");
    }

    /**
     * Shortcuts whose short rung has English of its own, for a Setting tile too narrow for the full name (the owner's
     * ladder: full name, a shorter whole-word name, then the icon). Every other shortcut's short rung is its label.
     */
    /** Entries whose short rung differs in English: Battery alerts' Alerts, and the search tile's Search (part 11). */
    static final java.util.Map<String, String> SHORT_ENGLISH = new java.util.HashMap<>();
    static { SHORT_ENGLISH.put("charge_alerts", "Alerts"); SHORT_ENGLISH.put("controls", "Search"); }
    static String shortEnglish(QuickAccessActions.Entry e) { return SHORT_ENGLISH.getOrDefault(e.id, e.label); }

    private static void english() {
        for (QuickAccessActions.Entry e : QuickAccessActions.catalog()) {
            same(e.label, e.label().render(), e.id + " label()");
            same(e.label, e.label().english(), e.id + " label().english()");
            same(shortEnglish(e), e.shortLabel().render(), e.id + " shortLabel()");
            same(shortEnglish(e), e.shortLabel().english(), e.id + " shortLabel().english()");
            same(e.group, e.groupLabel().render(), e.id + " groupLabel()");
            same(e.group, e.groupLabel().english(), e.id + " groupLabel().english()");
            check(e.id.equals(QuickAccessActions.find(e.id).id), e.id + " find");
        }
        same("Max. speed", T.dashboardModelTileTopSpeed(), "tile top speed (the abbreviated rung of Maximum speed)");
        same("Charge schedule", T.dashboardModelTileChargeSchedule(), "tile charge schedule");
    }

    private static void marked() {
        for (QuickAccessActions.Entry e : QuickAccessActions.catalog()) {
            ScooterCommands.Feature feature = e.featureKey == null ? null : ScooterCommands.find(e.featureKey);
            if (feature == null) {
                check(e.featureKey == null, e.id + ": a feature shortcut without its feature");
                same("«" + e.label + "»", e.label().render(), e.id + " marked label()");
                same("«" + shortEnglish(e) + "»", e.shortLabel().render(), e.id + " marked shortLabel()");
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
            {"CHARGE_HISTORY_TITLE", T.dashboardModelExampleChargeHistory()},
        };
        for (String[] pair : pairs) {
            Matcher c = Pattern.compile("\\b" + pair[0] + " = \"([^\"]*)\"").matcher(example);
            check(c.find(), pair[0] + " not declared");
            same(c.group(1), pair[1], "example " + pair[0]);
        }
        // NOTE_TEXT is the example note's stored value and keeps its saved spelling, so saved example notes still
        // read as Volt's; the catalog shows the US spelling (an owner-approved change, l10n/english-changes.tsv).
        Matcher note = Pattern.compile("\\bNOTE_TEXT = \"([^\"]*)\"").matcher(example);
        check(note.find(), "NOTE_TEXT not declared");
        same("Check tyre pressure", note.group(1), "example NOTE_TEXT stays the stored value");
        same("Check tire pressure", T.dashboardModelExampleNote(), "example note shown");
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
        check(config.contains("ids[i] = widget.optString(\"id\");") && config.contains("types[i] = widget.optString(\"type\");")
                        && config.contains("return DashboardExampleIds.exampleSlot(index, EXAMPLE_SLOT_NAME.equals(name), ids, types);"),
                "exampleSlot does not pass every widget's id and type to DashboardExampleIds.exampleSlot");
        check(config.contains("if (slot.example) return M.dashboardModelSlotExample();")
                && !config.contains("EXAMPLE_SLOT_NAME.equals(name) ? T.dashboardModelSlotExample()"),
                "a slot shows the example's name for its name alone");
    }

    /**
     * DashboardExampleIds.exampleSlot against the example create() makes (its types read from src/, its
     * ids made as add() makes them): the whole example in slot 3 under its name counts, and so does it
     * with any one type fewer (an example this version saves, read by a version with a type more). This
     * method alone does not compare types with TYPES, so a tagged widget of an unknown type also counts;
     * DashboardConfig.validateSlot refuses such a slot before this runs. Any widget not the example's, a
     * type twice, another slot, another name or no widgets does not count. And where the example is
     * saved: slot index 2, EXAMPLE_SLOT_NAME.
     */
    private static void exampleSlotRule() throws Exception {
        String example = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/AllWidgetsDashboard.java")), StandardCharsets.UTF_8);
        String config = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/DashboardConfig.java")), StandardCharsets.UTF_8);
        int start = example.indexOf("static ArrayList<DashboardConfig.Widget> create() {");
        check(start >= 0, "AllWidgetsDashboard.create() not found");
        String body = example.substring(start, example.indexOf("return result;", start));
        List<String> made = new ArrayList<>();
        Matcher m = Pattern.compile("\\badd\\(result, \"([a-z]+)\"\\)").matcher(body);
        while (m.find()) made.add(m.group(1));
        List<String> types = strings(block(config, "public static final String[] TYPES = {"));
        check(made.size() == types.size() && new HashSet<>(made).equals(new HashSet<>(types)),
                "create() makes one widget of every type: " + made);
        String[] ids = new String[made.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = DashboardExampleIds.create();
        String[] kinds = made.toArray(new String[0]);

        check(DashboardExampleIds.exampleSlot(2, true, ids, kinds), "the whole example is not the example's slot");
        for (int i = 0; i < ids.length; i++) {
            check(DashboardExampleIds.exampleSlot(2, true, without(ids, i), without(kinds, i)),
                    "the example without its " + kinds[i] + " widget is not the example's slot");
            String[] other = ids.clone();
            other[i] = UUID.randomUUID().toString();
            check(!DashboardExampleIds.exampleSlot(2, true, other, kinds), "an owner's " + kinds[i] + " widget in the example's slot");
            other[i] = "566f6c74" + other[i].substring(8);
            check(!DashboardExampleIds.exampleSlot(2, true, other, kinds), "a tagged version 4 " + kinds[i] + " widget in the example's slot");
            other[i] = "";
            check(!DashboardExampleIds.exampleSlot(2, true, other, kinds), "a " + kinds[i] + " widget without an id in the example's slot");
            check(!DashboardExampleIds.exampleSlot(2, true, with(ids, DashboardExampleIds.create()), with(kinds, kinds[i])),
                    "the example with a second " + kinds[i] + " widget is the example's slot");
        }
        check(!DashboardExampleIds.exampleSlot(2, true, with(ids, UUID.randomUUID().toString()), with(kinds, "future")),
                "the example with an owner's widget of a new type is the example's slot");
        check(DashboardExampleIds.exampleSlot(2, true, with(ids, DashboardExampleIds.create()), with(kinds, "future")),
                "exampleSlot alone does not check types against TYPES (validateSlot does that first)");
        check(DashboardExampleIds.exampleSlot(2, true, new String[] {ids[0]}, new String[] {kinds[0]}), "one example widget");
        for (int index : new int[] {-1, 0, 1, 3})
            check(!DashboardExampleIds.exampleSlot(index, true, ids, kinds), "the example in slot index " + index + " is the example's slot");
        check(!DashboardExampleIds.exampleSlot(2, false, ids, kinds), "the example under another name is the example's slot");
        check(!DashboardExampleIds.exampleSlot(2, true, new String[0], new String[0]), "an empty slot is the example's slot");
        check(!DashboardExampleIds.exampleSlot(2, true, ids, without(kinds, 0)), "ids and types of different lengths");

        // Where the example is saved: index 2 under EXAMPLE_SLOT_NAME, both when seeded and when forced.
        check(config.contains("saveSlot(2, EXAMPLE_SLOT_NAME, AllWidgetsDashboard.COLUMNS, exampleFactory.get());")
                        && config.contains("saveSlot(2, EXAMPLE_SLOT_NAME, AllWidgetsDashboard.COLUMNS, AllWidgetsDashboard.create());")
                        && config.contains("AllWidgetsDashboard::create"),
                "the example is not saved to slot index 2 under EXAMPLE_SLOT_NAME");
        check(!config.contains("definitions.length() != TYPES.length") && !config.contains("TYPES.length) return false"),
                "exampleSlot counts the example's widgets against TYPES again");
        check(!config.contains("public static String displaySlotName(String"), "displaySlotName takes a name alone again");
    }

    private static String[] without(String[] values, int index) {
        String[] out = new String[values.length - 1];
        for (int i = 0, j = 0; i < values.length; i++) if (i != index) out[j++] = values[i];
        return out;
    }

    private static String[] with(String[] values, String more) {
        String[] out = java.util.Arrays.copyOf(values, values.length + 1);
        out[values.length] = more;
        return out;
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
