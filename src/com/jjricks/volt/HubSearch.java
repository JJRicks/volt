package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Hub search's pure core (hub layout plan 1.12, PR 12): the entries search can find, how a query matches them, and
 * the order results show in. No Android imports, so the JVM suite tests it (tests/l10n/HubSearchTest.java).
 *
 * <p>An entry matches when the query (normalized as the Scooter tab's search did: lower case, no spaces, underscores
 * or hyphens) is in its English words, or, in a language other than English, when the name it shows holds the query
 * (StatsLabels.matchesShown). Results come in groups (pages, settings, live readings, rides); inside a group, a name
 * that starts with the query comes first, then one with a word that does, then the rest, each in the index's order.
 * At most {@link #MAX} results. Search only ever opens HubPages ids.
 */
final class HubSearch {
    private HubSearch() { }

    /** The most results a search shows. */
    static final int MAX = 50;

    /** The groups results show in, in this order. */
    enum Kind { PAGE, SETTING, READING, RIDE }

    /** What a result opens, besides its page: nothing more, a ride, a reading, or a control's Lab try. */
    enum Target { PAGE, RIDE, READING, INSPECT }

    /** One thing search can find. The shown name is kept as given (a Msg renders when shown, rule R2). */
    static final class Entry {
        final Kind kind;
        /** The HubPages id the result opens. */
        final String page;
        /** Normalized English words (normalize). */
        final String english;
        /** The name the result shows, in the current language. */
        final CharSequence shown;
        /** What the page brings on screen (a row id or view tag), a ride id, a reading key or a feature key; may be null. */
        final String anchor;
        final Target target;
        /** The result's value, read when shown (a live reading, a ride's date and distance, "Experimental"); may be null. */
        final Supplier<CharSequence> value;
        /**
         * More text in the current language the entry is found by, as the Scooter tab's search found it (a control's
         * description and short label, a hand-built row's main_search_* words); ranking still reads only the shown name.
         */
        final CharSequence[] words;
        /** Whether the value is live now (a reading: Live Stats mutes a saved one); null when that does not apply. */
        final Supplier<Boolean> live;
        /**
         * A line shown and spoken with the value, read when shown: a saved reading's age as Live Stats writes it beside
         * the value ("Last seen 2 h ago"); null, or a null or empty line, for none.
         */
        final Supplier<CharSequence> note;

        Entry(Kind kind, String page, String english, CharSequence shown, String anchor, Target target, Supplier<CharSequence> value) {
            this(kind, page, english, shown, null, anchor, target, value, null);
        }

        Entry(Kind kind, String page, String english, CharSequence shown, CharSequence[] words, String anchor, Target target,
                Supplier<CharSequence> value, Supplier<Boolean> live) {
            this(kind, page, english, shown, words, anchor, target, value, live, null);
        }

        Entry(Kind kind, String page, String english, CharSequence shown, CharSequence[] words, String anchor, Target target,
                Supplier<CharSequence> value, Supplier<Boolean> live, Supplier<CharSequence> note) {
            this.kind = kind; this.page = page; this.english = normalize(english); this.shown = shown;
            this.words = words == null ? new CharSequence[0] : words;
            this.anchor = anchor; this.target = target; this.value = value; this.live = live; this.note = note;
        }

        /** A stable id for the result's view tag: its kind and anchor (or page). */
        String id() { return kind.name().toLowerCase(Locale.ROOT) + "-" + (anchor != null ? anchor : page); }
    }

    /**
     * What the shown page offers on the connected model (MainActivity's controls rules): search never lists a control,
     * a row or a page the scooter pages would not show.
     */
    interface Gates {
        /** A feature row or a hand-built row (HubSearchTables.ROWS) shows on its page. */
        boolean row(String id);
        /** A control shows only as its "Experimental" entry (the Lab tries it on this model). */
        boolean lab(String key);
        /**
         * A sub-page has something to show on this model, as its link on the subject page would (Line lights and Star
         * lights, Assists, Limits, ...: ControlsPages.linkShown, an Experimental entry counting). Subjects always show.
         */
        boolean page(String page);
        /**
         * A control's or hand-built row's search words in the current language, besides its name (MainActivity: the
         * short label a control's row shows, a hand-built row's main_search_* words); null for none.
         */
        default CharSequence[] words(String id) { return null; }
    }

    /** The Scooter tab's search normalization (MainActivity.normalize): lower case, no spaces, underscores or hyphens. */
    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
    }

    /**
     * Whether an entry matches the query: its English words, or, in the current language, the name it shows or its
     * other words (Entry.words). A query of several words that doesn't match as a whole matches when each of its words
     * does, so "max speed" finds Maximum speed (#393 emulator check).
     */
    static boolean matches(Entry entry, String query) {
        if (matchesWhole(entry, query)) return true;
        String[] parts = query == null ? new String[0] : query.trim().split("\\s+");
        if (parts.length < 2) return false;
        for (String part : parts) if (!matchesWhole(entry, part)) return false;
        return true;
    }

    private static boolean matchesWhole(Entry entry, String query) {
        String needle = normalize(query);
        if (needle.isEmpty()) return false;
        // The English words have no accents: a query typed with them ("café", "batería") is also tried without.
        if (entry.english.contains(needle) || entry.english.contains(unaccented(needle))) return true;
        // A light page's shared zones words name both families: a query naming only the other family skips them.
        CharSequence[] words = entry.kind == Kind.PAGE && sharedPageWords(entry.page) && otherFamilyOnly(entry.page, query)
                ? new CharSequence[0] : entry.words;
        String[] shown = new String[words.length + 1];
        shown[0] = entry.shown == null ? null : entry.shown.toString();
        for (int i = 0; i < words.length; i++) shown[i + 1] = words[i] == null ? null : words[i].toString();
        return StatsLabels.matchesShown(query, shown);
    }

    /** text without its accents and other combining marks ("batería" is "bateria"); null is "". */
    static String unaccented(String text) {
        if (text == null) return "";
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    /**
     * Where a matching entry ranks inside its group: 0 when its shown name starts with the query, 1 when a word of it
     * does, 2 when the name holds it elsewhere, 3 when only the English words match.
     */
    static int rank(Entry entry, String query) {
        // Folded as Live Stats folds a search (case, accents, spaces and punctuation), one word onwards at a time.
        String needle = NaveeModels.displayFold(query == null ? "" : query.trim());
        String shown = entry.shown == null ? "" : entry.shown.toString();
        String name = NaveeModels.displayFold(shown);
        if (needle.isEmpty()) return 3;
        if (name.startsWith(needle)) return 0;
        String[] words = shown.split("[^\\p{L}\\p{N}]+");
        for (int i = 1; i < words.length; i++) {
            StringBuilder rest = new StringBuilder();
            for (int j = i; j < words.length; j++) rest.append(words[j]).append(' ');
            if (NaveeModels.displayFold(rest.toString()).startsWith(needle)) return 1;
        }
        return name.contains(needle) ? 2 : 3;
    }

    /**
     * The matching entries, grouped by kind, ranked inside each group, in the index's order otherwise; at most MAX. One
     * thing is listed once (#393 emulator check): an entry that would show as an earlier one does (same group, page, row
     * and name) is left out, as the Security link and the PIN control, both "PIN & proximity unlock" on Security; and a
     * setting named as a page that is also found is left out for that page, as the Units row on Settings for the Units
     * page it opens.
     */
    static List<Entry> search(List<Entry> index, String query) {
        List<Entry> found = new ArrayList<>();
        if (index == null || normalize(query).isEmpty()) return found;
        final List<int[]> keys = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>(), pageNames = new java.util.HashSet<>();
        for (Entry entry : index)
            if (entry != null && entry.kind == Kind.PAGE && matches(entry, query)) pageNames.add(sameName(entry));
        for (int i = 0; i < index.size(); i++) {
            Entry entry = index.get(i);
            if (entry == null || !matches(entry, query)) continue;
            if (!seen.add(entry.id() + "\n" + entry.page + "\n" + sameName(entry))) continue;
            if (entry.kind == Kind.SETTING && entry.target == Target.PAGE && pageNames.contains(sameName(entry))) continue;
            keys.add(new int[] {entry.kind.ordinal(), rank(entry, query), i});
        }
        Collections.sort(keys, (a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[2], b[2]));
        for (int[] key : keys) {
            if (found.size() >= MAX) break;
            found.add(index.get(key[2]));
        }
        return found;
    }

    /** An entry's shown name as two results are compared: folded as Live Stats folds a search (case, accents, spaces). */
    private static String sameName(Entry entry) {
        return entry.shown == null ? "" : NaveeModels.displayFold(entry.shown.toString());
    }

    // ===== The index's sources (each read when a search runs) =====

    /**
     * Every page under Home, by its title and PAGE words; a sub-page only while the model has something on it (its
     * subject page would show its link). A page whose rows another screen builds is found by their words too
     * (pageSearchWords).
     */
    static List<Entry> pages(Gates gates) {
        List<Entry> entries = new ArrayList<>();
        for (String page : HubPages.ALL) {
            if (page.equals(HubPages.HOME) || page.equals(HubPages.SEARCH)) continue;
            if (!page.equals(HubPages.subjectOf(page)) && !gates.page(page)) continue;
            Msg title = HubText.title(page);
            if (title == null) continue;
            Msg more = pageSearchWords(page);
            // Words shared by both light families match only as a translation: in English each family keeps its own
            // words and title (pageWords), so "star" doesn't list Line lights (#404 review).
            boolean shared = more != null && sharedPageWords(page);
            String english = title.english() + " " + HubSearchTables.pageWords(page) + (more == null || shared ? "" : " " + more.english());
            CharSequence[] words = more == null || (shared && more.toString().equals(more.english())) ? null : new CharSequence[] {more};
            entries.add(new Entry(Kind.PAGE, page, english, title, words, null, Target.PAGE, null, null));
        }
        return entries;
    }

    /**
     * Search words in every shipped language for a page whose rows search has no entries of their own: Charge history
     * & recordings (MoreScreen's Charging section: record, export, email and clear charge recordings) is found by
     * them (#387 review); Line lights and Star lights by the zones row's translated words (main_search_zones), so
     * "zonas" finds both light pages in Spanish as "zones" does in English (#404 review, P3 3). Null for every other
     * page.
     */
    static Msg pageSearchWords(String page) {
        if (HubPages.BATTERY_HISTORY.equals(page)) return HubText.batteryHistorySearchWords();
        if (sharedPageWords(page)) return HubText.lightZonesSearchWords();
        return null;
    }

    /** The light families' pages, whose pageSearchWords name both families (matched only in a translation). */
    static boolean sharedPageWords(String page) {
        return HubPages.LIGHTS_LINE.equals(page) || HubPages.LIGHTS_STAR.equals(page);
    }

    /**
     * Whether the query names the other light family (its title or group name in the current language) but not this
     * page's own: then this page's shared zones words don't match it, so "estrella" in Spanish lists Star lights only
     * while "zonas" (and "luces", in both names) still finds both pages (#404 review). False for any other page.
     */
    static boolean otherFamilyOnly(String page, String query) {
        if (!sharedPageWords(page) || query == null) return false;
        String other = HubPages.LIGHTS_LINE.equals(page) ? HubPages.LIGHTS_STAR : HubPages.LIGHTS_LINE;
        return StatsLabels.matchesShown(query, rendered(HubText.lightFamilyNames(other)))
                && !StatsLabels.matchesShown(query, rendered(HubText.lightFamilyNames(page)));
    }

    private static String[] rendered(Msg[] names) {
        String[] out = new String[names.length];
        for (int i = 0; i < names.length; i++) out[i] = names[i] == null ? null : names[i].toString();
        return out;
    }

    /**
     * The scooter's controls (the catalog, then other models' controls), each on the page that shows its row, matched
     * by the words the Scooter tab's search used. A control the Lab tries on this model shows as Experimental and
     * opens its entry on its subject page, or its try in Service › Experimental controls where no page lists it. The
     * lock, PIN and proximity unlock open Security. The UT5 MAX top speed card is a hand-built row (rows()).
     */
    static List<Entry> controls(Gates gates, Supplier<CharSequence> experimental) {
        List<Entry> entries = new ArrayList<>();
        List<ScooterCommands.Feature> features = new ArrayList<>(ScooterCommands.catalog());
        features.addAll(ScooterCommands.oemOnly());
        for (ScooterCommands.Feature feature : features) {
            String key = feature.key;
            String english = feature.searchText() + " " + key;
            // As a control row's matchesShown: its name, description and short label in the current language.
            CharSequence[] words = concat(feature.description(), gates.words(key));
            if (ControlsPages.NEVER.contains(key)) {
                if (!key.equals("lock") && !gates.row(key)) continue;
                entries.add(new Entry(Kind.SETTING, HubPages.SECURITY, english, feature.label(), words, null, Target.PAGE, null, null));
                continue;
            }
            String page = ControlsPages.pageOf(key);
            if (gates.row(key)) {
                entries.add(new Entry(Kind.SETTING, page != null ? page : subjectOf(feature), english, feature.label(), words, key, Target.PAGE, null, null));
            } else if (gates.lab(key)) {
                if (page != null) entries.add(new Entry(Kind.SETTING, page, english, feature.label(), words, key, Target.PAGE, experimental, null));
                else entries.add(new Entry(Kind.SETTING, HubPages.SERVICE_EXPERIMENTAL, english, feature.label(), words, key, Target.INSPECT, experimental, null));
            }
        }
        return entries;
    }

    /** The page a control with no row of its own on a page belongs to, by its group. */
    static String subjectOf(ScooterCommands.Feature feature) {
        if (HubRoutes.securityControl(feature)) return HubPages.SECURITY;
        if (feature.key.startsWith("line_")) return HubPages.LIGHTS_LINE;
        if (feature.key.startsWith("star_")) return HubPages.LIGHTS_STAR;
        if (feature.key.equals("headlight") || feature.key.equals("auto_sensor") || ScooterCommands.oemOnly(feature.key)) return HubPages.LIGHTS;
        // The group's English is its id (ScooterCommands' groups); read through its Msg, as the screens do.
        switch (feature.groupLabel().english()) {
            case ScooterCommands.GROUP_RIDE: return HubPages.CONTROLS;
            case ScooterCommands.GROUP_BATTERY: return HubPages.BATTERY;
            case ScooterCommands.GROUP_SECURITY: return HubPages.SECURITY;
            default: return HubPages.LIGHTS;
        }
    }

    /** The scooter menu's hand-built rows (HubSearchTables.ROWS) the model offers, by their shown names and English words. */
    static List<Entry> rows(Gates gates) {
        List<Entry> entries = new ArrayList<>();
        for (String row : HubSearchTables.ROWS) {
            String page = HubSearchTables.rowPage(row);
            Msg title = HubText.controlRowTitle(row);
            if (page == null || title == null || !gates.row(row)) continue;
            entries.add(new Entry(Kind.SETTING, page, title.english() + " " + HubSearchTables.rowWords(row), title, gates.words(row),
                    HubSearchTables.rowAnchor(row), Target.PAGE, null, null));
        }
        return entries;
    }

    /** first, then rest (either may be null). */
    private static CharSequence[] concat(CharSequence first, CharSequence[] rest) {
        int more = rest == null ? 0 : rest.length;
        CharSequence[] all = new CharSequence[more + 1];
        all[0] = first;
        for (int i = 0; i < more; i++) all[i + 1] = rest[i];
        return all;
    }

    /** The settings rows (HubSearchTables.SETTINGS), by their shown names and English words. */
    static List<Entry> settings() {
        List<Entry> entries = new ArrayList<>();
        for (String tag : HubSearchTables.SETTINGS) {
            Msg title = HubText.settingTitle(tag);
            String page = HubSearchTables.settingPage(tag);
            if (title == null || page == null) continue;
            entries.add(new Entry(Kind.SETTING, page, title.english() + " " + HubSearchTables.settingWords(tag), title, HubSearchTables.settingAnchor(tag), Target.PAGE, null));
        }
        return entries;
    }

    /**
     * A live reading: its key, the name and search words Live Stats shows, its value as Live Stats writes it, and
     * whether that value is live now (a saved one shows muted, as on Live Stats).
     */
    static Entry reading(String key, String english, CharSequence shown, Supplier<CharSequence> value, Supplier<Boolean> live) {
        return reading(key, english, shown, null, value, live, null);
    }

    /**
     * reading, with the words it is also found by in the current language (its full name and group, folded as the
     * shown name is: #393 review, note 8) and its age line (a saved value's "Last seen ...", shown and spoken with it:
     * #393 review, finding 2).
     */
    static Entry reading(String key, String english, CharSequence shown, CharSequence[] words, Supplier<CharSequence> value,
            Supplier<Boolean> live, Supplier<CharSequence> note) {
        return new Entry(Kind.READING, HubPages.DATA, english + " " + key, shown, words, key, Target.READING, value, live, note);
    }

    // ===== The search page's layout rules (HubSearchScreen) =====

    /**
     * The most of a result row's content width its value may take beside the name. A wider value goes under the name, so
     * the name always keeps at least the rest (60%): a raw reading (the battery serial answer's 32 hex digits) or a ride's
     * date and distance at large text never squeezes it away (#393 review, finding 1, and the emulator check).
     */
    static final float VALUE_SHARE = 0.4f;

    /**
     * Whether a result's value goes under its name instead of beside it: when its one-line width is more than
     * VALUE_SHARE of the row's content width. Both in the same unit (pixels); a row not measured yet (width 0 or less)
     * keeps it beside.
     */
    static boolean valueBelow(float valueWidth, int contentWidth) {
        return contentWidth > 0 && valueWidth > contentWidth * VALUE_SHARE;
    }

    /**
     * Whether the page header (back link, status line, title) gets out of the way so the field sits at the very top
     * (the Layout Plan's search mock-up): while the field has the cursor or holds a query, at every size. In a short
     * window (725 x 604 dp, a phone held sideways) that is what leaves room for results above the keyboard (#393
     * emulator check); the header comes back when the field is empty and left.
     */
    static boolean headerHidden(boolean fieldFocused, String query) {
        return fieldFocused || !normalize(query).isEmpty();
    }

    /**
     * Whether the field shows its long placeholder ("Search settings, readings and rides"): only when it fits whole in the room
     * the field has for text, else the short one ("Search"), so the placeholder is never cut, at 344 dp with 1.4x text
     * or in a pane 262 dp wide (part 13). Both in the same unit (pixels); a field not measured yet (room 0 or less)
     * keeps the long one.
     */
    static boolean longHintFits(float longHintWidth, int textRoom) {
        return textRoom <= 0 || longHintWidth <= textRoom;
    }

    /**
     * A result's caption from its page titles, joined with " › ". With two or more titles each title's own spaces become
     * no-break spaces, and so does the space before "›", so a caption too long for one line (344 dp at 1.4x text) wraps
     * only after a "›" and never inside a page's name ("Battery & charging ›" over "Battery modes", not "... › Battery"
     * over "modes"); a single title wraps as usual. Null for no titles.
     */
    static String captionText(List<String> titles) {
        StringBuilder text = new StringBuilder();
        boolean path = titles.size() > 1;
        for (String title : titles) {
            if (title == null || title.isEmpty()) continue;
            if (text.length() > 0) text.append("\u00a0\u203a ");
            text.append(path ? title.replace(' ', '\u00a0') : title);
        }
        return text.length() == 0 ? null : text.toString();
    }

    /**
     * Whether a result's value is drawn as a reading's (bold, in the text colour: "50.48 V" in the Layout Plan's mock-up)
     * or as quiet detail (a ride's date and distance: muted and regular, under the caption, so it never reads as a live
     * value; the mock-up's ride row has none of its own).
     */
    static boolean valueStrong(Kind kind) { return kind == Kind.READING; }

    /**
     * Whether the result count shows as a line under the field. The Layout Plan's mock-up has none: the groups follow the
     * field at once, which in a short window with the keyboard up (725 x 604 dp) is one more row of results. Only "No
     * matches" is shown, where there is nothing else to see; the count is still spoken (HubSearchScreen).
     */
    static boolean countShown(int found) { return found == 0; }

    /**
     * The pages a result's caption names, from its subject down (the Layout Plan's search mock-up: "Battery & charging ›
     * Modes", "Data", "Ride › ..."): the page that holds it and the pages above it under Home; for a page result, the
     * pages above it only, so a subject page (and Settings) has none.
     */
    static List<String> captionPages(Entry entry) {
        List<String> path = new ArrayList<>(HubPages.path(entry.page));
        path.remove(0);
        if (entry.kind == Kind.PAGE && !path.isEmpty()) path.remove(path.size() - 1);
        return path;
    }

    /** A ride: its id, its name and when it was ridden (both shown), and its date and distance as its value. */
    static Entry ride(String id, String english, CharSequence shown, Supplier<CharSequence> value) {
        return new Entry(Kind.RIDE, HubPages.RIDE_RIDES, english, shown, id, Target.RIDE, value);
    }
}
