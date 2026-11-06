package com.jjricks.volt;

import java.io.IOException;
import java.util.*;

/**
 * The Scooter tab's menu layout for one scooter: the section order, each section's row order and
 * what the owner hid (scooter-menu-edit plan). Pure Java, so it is unit-tested without Android.
 *
 * <p>The app supplies {@code defaults}, today's order, built from the same code that builds the
 * page. {@link #resolve} lays the saved choices over it:
 * <ul>
 * <li>an id the app no longer has is dropped from the order (it stays in {@code known} and keeps
 *     its hidden flag, so it doesn't come back by surprise if it returns);</li>
 * <li>an id the saved order lacks is inserted right after its nearest default-order predecessor
 *     that is there, or first when none is (new settings appear in their usual spot);</li>
 * <li>rows move within their own section only: a row's section is always its default section.</li>
 * </ul>
 * Hiding only tidies the menu; the scooter keeps every setting. Nothing is locked from hiding
 * (owner, Q33 A). Unreadable text gives the default layout and never throws.
 */
final class ScooterMenuLayout {
    /** Per scooter in app_settings, like the dashboard ({@code dashboard_v1_<pid>}); BackupRegistry. */
    static final String PREFIX = "scooter_menu_v1_";
    static final int VERSION = 1;
    /**
     * The top speed card is a one-row section of its own (owner, Q36 A). Other section ids are the groups'
     * English names (MainActivity.settingsGroups); this one is new, so it is a plain id. PR 2 names it on screen.
     */
    static final String TOP_SPEED_SECTION = "speed", TOP_SPEED = "top_speed";
    /** Ids of the hand-built rows; feature rows use their feature key. "Done by" rows follow their parent. */
    static final String HEADLIGHT_INFO = "headlight_info", LIGHT_ZONES = "light_zones", CLOCK_SYNC = "clock_sync", SCHEDULE_TEST = "schedule_test",
            CHARGER = "charger", CHARGE_ALERT = "charge_alert", LOW_ALERT = "low_alert", SECURITY_LINK = "security_link", SCOOTER_NAME = "scooter_name";
    private static final int MAX_DEPTH = 4, MAX_IDS = 512, MAX_ID = 64;

    private final LinkedHashMap<String, List<String>> defaults;
    private final List<String> sections = new ArrayList<>();
    private final LinkedHashMap<String, List<String>> rows = new LinkedHashMap<>();
    private final LinkedHashSet<String> hiddenSections = new LinkedHashSet<>(), hiddenRows = new LinkedHashSet<>(), known = new LinkedHashSet<>();

    private ScooterMenuLayout(LinkedHashMap<String, List<String>> defaults) {
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : defaults.entrySet()) copy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        this.defaults = copy;
    }

    /** The default layout: every section and row in {@code defaults} order, nothing hidden. */
    static ScooterMenuLayout defaults(LinkedHashMap<String, List<String>> defaults) {
        ScooterMenuLayout layout = new ScooterMenuLayout(defaults);
        layout.reset();
        return layout;
    }

    /** The saved layout laid over {@code defaults}. Null, malformed or newer-format text gives the default layout. */
    static ScooterMenuLayout resolve(LinkedHashMap<String, List<String>> defaults, String json) {
        ScooterMenuLayout layout = defaults(defaults);
        if (json == null || json.isEmpty()) return layout;
        Map<String, Object> root;
        try { root = BackupJson.object(BackupJson.parse(json, MAX_DEPTH)); }
        catch (IOException | RuntimeException unreadable) { return layout; }
        // A newer format may mean different things by the same ids: show the default rather than guess.
        if (!Long.valueOf(VERSION).equals(root.get("v"))) return layout;
        List<String> savedSections = ids(root.get("sections"));
        Map<String, List<String>> savedRows = new HashMap<>();
        if (root.get("rows") instanceof Map)
            for (Map.Entry<?, ?> e : ((Map<?, ?>) root.get("rows")).entrySet()) savedRows.put(String.valueOf(e.getKey()), ids(e.getValue()));
        layout.known.clear();
        layout.known.addAll(ids(root.get("known")));
        layout.hiddenSections.addAll(ids(root.get("hiddenSections")));
        layout.hiddenRows.addAll(ids(root.get("hiddenRows")));
        layout.sections.clear();
        layout.sections.addAll(merge(new ArrayList<>(defaults.keySet()), savedSections));
        for (Map.Entry<String, List<String>> e : defaults.entrySet()) {
            List<String> saved = savedRows.get(e.getKey());
            layout.rows.put(e.getKey(), merge(e.getValue(), saved == null ? Collections.<String>emptyList() : saved));
        }
        layout.know();
        return layout;
    }

    /** Saved order filtered to ids the defaults have, then every missing default id after its nearest present predecessor. */
    static List<String> merge(List<String> defaults, List<String> saved) {
        List<String> order = new ArrayList<>();
        for (String id : saved) if (defaults.contains(id) && !order.contains(id)) order.add(id);
        for (int i = 0; i < defaults.size(); i++) {
            String id = defaults.get(i);
            if (order.contains(id)) continue;
            int at = 0;
            for (int j = i - 1; j >= 0; j--) { int found = order.indexOf(defaults.get(j)); if (found >= 0) { at = found + 1; break; } }
            order.add(at, id);
        }
        return order;
    }

    private static List<String> ids(Object value) {
        List<String> out = new ArrayList<>();
        if (!(value instanceof List)) return out;
        for (Object item : (List<?>) value) {
            if (out.size() >= MAX_IDS) break;
            if (item instanceof String && !((String) item).isEmpty() && ((String) item).length() <= MAX_ID && !out.contains(item)) out.add((String) item);
        }
        return out;
    }

    private void know() {
        known.addAll(defaults.keySet());
        for (List<String> list : defaults.values()) known.addAll(list);
    }

    // ---- Reading ----

    List<String> sections() { return Collections.unmodifiableList(sections); }
    /** A section's rows in menu order (hidden ones included); empty for an unknown section. */
    List<String> rows(String section) { List<String> list = rows.get(section); return list == null ? Collections.<String>emptyList() : Collections.unmodifiableList(list); }
    boolean sectionHidden(String section) { return hiddenSections.contains(section); }
    boolean rowHidden(String row) { return hiddenRows.contains(row); }
    /** Hidden by itself or with its section. */
    boolean hidden(String section, String row) { return sectionHidden(section) || rowHidden(row); }
    /** The section a row belongs to by default, or null. */
    String sectionOf(String row) { for (Map.Entry<String, List<String>> e : defaults.entrySet()) if (e.getValue().contains(row)) return e.getKey(); return null; }
    Set<String> known() { return Collections.unmodifiableSet(known); }
    /** Exactly the default order with nothing hidden. */
    boolean isDefault() {
        if (!hiddenSections.isEmpty() || !hiddenRows.isEmpty() || !sections.equals(new ArrayList<>(defaults.keySet()))) return false;
        for (Map.Entry<String, List<String>> e : defaults.entrySet()) if (!e.getValue().equals(rows.get(e.getKey()))) return false;
        return true;
    }
    /**
     * Wide screens' main page: how many leading sections fill the left column. That's every section up to and
     * including the first one that is neither Top speed nor hidden, so the default is Top speed and Ride on the
     * left, the rest on the right, exactly as before layouts existed.
     */
    int wideLeftCount() {
        for (int i = 0; i < sections.size(); i++) if (!sections.get(i).equals(TOP_SPEED_SECTION) && !sectionHidden(sections.get(i))) return i + 1;
        return sections.size();
    }
    /**
     * Hidden items among those {@code offered}: each hidden section with an offered row counts once,
     * and each hidden offered row outside a hidden section counts once.
     */
    int hiddenCount(Set<String> offered) {
        int count = 0;
        for (String section : sections) {
            boolean any = false;
            for (String row : rows(section)) any |= offered.contains(row);
            if (!any) continue;
            if (sectionHidden(section)) { count++; continue; }
            for (String row : rows(section)) if (offered.contains(row) && rowHidden(row)) count++;
        }
        return count;
    }

    // ---- Changes (the editor, PR 2) ----

    /** Moves a section by {@code delta} places, clamped; false when nothing moved. */
    boolean moveSection(String section, int delta) { return moveIn(sections, section, delta); }
    /** Moves a row within its own section, clamped; false when nothing moved. */
    boolean moveRow(String section, String row, int delta) { List<String> list = rows.get(section); return list != null && moveIn(list, row, delta); }
    /** Drops a section at {@code index} (drag and drop). */
    boolean moveSectionTo(String section, int index) { return moveTo(sections, section, index); }
    boolean moveRowTo(String section, String row, int index) { List<String> list = rows.get(section); return list != null && moveTo(list, row, index); }
    private static boolean moveIn(List<String> list, String id, int delta) { int from = list.indexOf(id); return from >= 0 && moveTo(list, id, from + delta); }
    private static boolean moveTo(List<String> list, String id, int index) {
        int from = list.indexOf(id);
        if (from < 0) return false;
        int to = Math.max(0, Math.min(list.size() - 1, index));
        if (to == from) return false;
        list.remove(from);
        list.add(to, id);
        return true;
    }
    /** Hides a section or a row; false when it was already hidden or isn't in this layout. */
    boolean hide(String id) {
        if (defaults.containsKey(id)) return hiddenSections.add(id);
        return sectionOf(id) != null && hiddenRows.add(id);
    }
    /** Shows a section or row again at its saved position (a section keeps its rows' order and their own flags). */
    boolean show(String id) { return defaults.containsKey(id) ? hiddenSections.remove(id) : hiddenRows.remove(id); }
    /** The default order with everything shown. */
    void reset() {
        sections.clear();
        sections.addAll(defaults.keySet());
        rows.clear();
        for (Map.Entry<String, List<String>> e : defaults.entrySet()) rows.put(e.getKey(), new ArrayList<>(e.getValue()));
        hiddenSections.clear();
        hiddenRows.clear();
        know();
    }

    // ---- Storage ----

    String toJson() {
        StringBuilder out = new StringBuilder("{\"v\":").append(VERSION).append(",\"sections\":");
        list(out, sections);
        out.append(",\"rows\":{");
        boolean first = true;
        for (Map.Entry<String, List<String>> e : rows.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(BackupJson.quote(e.getKey())).append(':');
            list(out, e.getValue());
        }
        out.append("},\"hiddenSections\":");
        list(out, hiddenSections);
        out.append(",\"hiddenRows\":");
        list(out, hiddenRows);
        out.append(",\"known\":");
        list(out, known);
        return out.append('}').toString();
    }
    private static void list(StringBuilder out, Collection<String> ids) {
        out.append('[');
        boolean first = true;
        for (String id : ids) { if (!first) out.append(','); first = false; out.append(BackupJson.quote(id)); }
        out.append(']');
    }
}
