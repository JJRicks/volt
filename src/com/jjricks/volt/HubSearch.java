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

        Entry(Kind kind, String page, String english, CharSequence shown, String anchor, Target target, Supplier<CharSequence> value) {
            this(kind, page, english, shown, null, anchor, target, value, null);
        }

        Entry(Kind kind, String page, String english, CharSequence shown, CharSequence[] words, String anchor, Target target,
                Supplier<CharSequence> value, Supplier<Boolean> live) {
            this.kind = kind; this.page = page; this.english = normalize(english); this.shown = shown;
            this.words = words == null ? new CharSequence[0] : words;
            this.anchor = anchor; this.target = target; this.value = value; this.live = live;
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
     * other words (Entry.words).
     */
    static boolean matches(Entry entry, String query) {
        String needle = normalize(query);
        if (needle.isEmpty()) return false;
        if (entry.english.contains(needle)) return true;
        String[] shown = new String[entry.words.length + 1];
        shown[0] = entry.shown == null ? null : entry.shown.toString();
        for (int i = 0; i < entry.words.length; i++) shown[i + 1] = entry.words[i] == null ? null : entry.words[i].toString();
        return StatsLabels.matchesShown(query, shown);
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

    /** The matching entries, grouped by kind, ranked inside each group, in the index's order otherwise; at most MAX. */
    static List<Entry> search(List<Entry> index, String query) {
        List<Entry> found = new ArrayList<>();
        if (index == null || normalize(query).isEmpty()) return found;
        final List<int[]> keys = new ArrayList<>();
        for (int i = 0; i < index.size(); i++) {
            Entry entry = index.get(i);
            if (entry == null || !matches(entry, query)) continue;
            keys.add(new int[] {entry.kind.ordinal(), rank(entry, query), i});
        }
        Collections.sort(keys, (a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[2], b[2]));
        for (int[] key : keys) {
            if (found.size() >= MAX) break;
            found.add(index.get(key[2]));
        }
        return found;
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
            String english = title.english() + " " + HubSearchTables.pageWords(page) + (more == null ? "" : " " + more.english());
            entries.add(new Entry(Kind.PAGE, page, english, title, more == null ? null : new CharSequence[] {more}, null, Target.PAGE, null, null));
        }
        return entries;
    }

    /**
     * Search words in every shipped language for a page whose rows search has no entries of their own: Charge history
     * & recordings (MoreScreen's Charging section: record, export, email and clear charge recordings) is found by
     * them (#387 review). Null for every other page.
     */
    static Msg pageSearchWords(String page) {
        return HubPages.BATTERY_HISTORY.equals(page) ? HubText.batteryHistorySearchWords() : null;
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
            entries.add(new Entry(Kind.SETTING, page, title.english() + " " + HubSearchTables.settingWords(tag), title, tag, Target.PAGE, null));
        }
        return entries;
    }

    /**
     * A live reading: its key, the name and search words Live Stats shows, its value as Live Stats writes it, and
     * whether that value is live now (a saved one shows muted, as on Live Stats).
     */
    static Entry reading(String key, String english, CharSequence shown, Supplier<CharSequence> value, Supplier<Boolean> live) {
        return new Entry(Kind.READING, HubPages.DATA, english + " " + key, shown, null, key, Target.READING, value, live);
    }

    /** A ride: its id, its name and when it was ridden (both shown), and its date and distance as its value. */
    static Entry ride(String id, String english, CharSequence shown, Supplier<CharSequence> value) {
        return new Entry(Kind.RIDE, HubPages.RIDE_RIDES, english, shown, id, Target.RIDE, value);
    }
}
