package com.jjricks.volt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every row whose English sits only in a page's own words (HubSearchTables.pageOwnWords: rows shown only in some
 * states) has a translated name in HubText.pageRowTitles, so typing it as it reads in Polish or German finds its page
 * as its English does (review of #630, P3: Developer options' Drive mode and Load all widgets into slot 3, About's
 * Connect selected scooter and View full notices, and Charge history's Record this charge, Stop charge recording, Clear
 * this scooter's charge recordings and Live Update status were found only in English).
 *
 * The rows are found, not listed: every catalog string marked volt:purpose="row" whose English, lower-cased, is a whole
 * phrase of a page's own words. NOT_ROWS names the few such phrases that are no row on that page, with the reason.
 */
public final class HubSearchOwnRowsTest {
    private static int checks;

    /** page + "/" + key: own words that match a row string but are not that page's row. */
    private static final Map<String, String> NOT_ROWS = new LinkedHashMap<>();
    static {
        NOT_ROWS.put(HubPages.CONTROLS + "/more_dev_simulation_drive_mode", "Controls' \"drive mode\" names its drive mode control; the row is Developer options'");
        NOT_ROWS.put(HubPages.SETTINGS_UNITS + "/more_prefs_units", "Units is the page's own title");
        NOT_ROWS.put(HubPages.SETTINGS_DEVELOPER + "/more_dev_hide", "Hide developer options is no row any more (an unused string; HubSearchTables)");
    }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            List<String[]> rows = ownWordRows();
            named(rows);
            for (String language : new String[] {"pl", "de"}) found(rows, language);
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("HubSearchOwnRowsTest: " + checks + " checks passed");
    }

    /** {page, key, English} for each row string whose English is a whole phrase of a page's own words. */
    private static List<String[]> ownWordRows() {
        Map<String, String> rows = rowStrings("res/values");
        List<String[]> out = new ArrayList<>();
        List<String> unused = new ArrayList<>(NOT_ROWS.keySet());
        for (String page : HubPages.ALL) {
            // Recording settings' rows are named by the files that show them (MoreScreen and RidesScreen), not HubText.
            if (HubPages.RIDE_RECORDING.equals(page)) continue;
            String own = " " + HubSearchTables.pageOwnWords(page) + " ";
            for (Map.Entry<String, String> row : rows.entrySet()) {
                if (!own.contains(" " + row.getValue().toLowerCase(Locale.ROOT) + " ")) continue;
                if (unused.remove(page + "/" + row.getKey())) continue;
                out.add(new String[] {page, row.getKey(), row.getValue()});
            }
        }
        check(unused.isEmpty(), "NOT_ROWS entries that no longer match anything (remove them): " + unused);
        check(out.size() >= 9, "the own-words rows are found (" + out.size() + ")");
        return out;
    }

    /** Each one is among its page's translated row names, by the string the row shows. */
    private static void named(List<String[]> rows) {
        List<String> missing = new ArrayList<>();
        for (String[] row : rows) {
            boolean found = false;
            for (Msg name : HubText.pageRowTitles(row[0])) found |= T.key(name.id()).equals(row[1]);
            if (!found) missing.add(row[0] + ": " + row[2] + " (" + row[1] + ")");
        }
        check(missing.isEmpty(), "rows in a page's own words with no translated name in HubText.pageRowTitles: " + missing);
    }

    /** Typed as each row reads in the language, its name lists its page. */
    private static void found(List<String[]> rows, String language) {
        Map<String, String> shown = strings("res/values-" + language);
        T.install(new Catalog(language, shown));
        try {
            List<HubSearch.Entry> index = HubSearch.pages(GATES);
            List<String> missed = new ArrayList<>();
            int translated = 0;
            for (String[] row : rows) {
                String name = shown.get(row[1]);
                if (name == null || name.equals(row[2])) continue;
                translated++;
                if (!lists(index, name, row[0])) missed.add(row[0] + ": \"" + name + "\" (" + row[2] + ")");
            }
            check(translated >= 5, language + " translates these rows (" + translated + ")");
            check(missed.isEmpty(), "row names in " + language + " that do not list their page: " + missed);
        } finally {
            T.install(null);
        }
    }

    /** As MainActivity's search gates, for every page but Recording settings (whose rows its own files name). */
    private static final HubSearch.Gates GATES = new HubSearch.Gates() {
        @Override public boolean row(String id) { return true; }
        @Override public boolean lab(String key) { return false; }
        @Override public boolean page(String page) { return true; }
        @Override public CharSequence[] pageRowTitles(String page) {
            Msg[] named = HubText.pageRowTitles(page);
            return named.length == 0 ? null : named;
        }
    };

    private static boolean lists(List<HubSearch.Entry> index, String query, String page) {
        for (HubSearch.Entry e : HubSearch.search(index, query)) if (e.kind == HubSearch.Kind.PAGE && page.equals(e.page)) return true;
        return false;
    }

    private static final class Catalog implements T.Source {
        private final String language;
        private final Map<String, String> text;
        Catalog(String language, Map<String, String> text) { this.language = language; this.text = text; }
        @Override public String string(int id) { return text.get(T.key(id)); }
        @Override public String plural(int id, long quantity) { return null; }
        @Override public String language() { return language; }
    }

    private static final Pattern STRING = Pattern.compile("<string name=\"([^\"]+)\"([^>]*)>(.*?)</string>", Pattern.DOTALL);

    /** name to English for every <string> marked volt:purpose="row". */
    private static Map<String, String> rowStrings(String folder) {
        Map<String, String> out = new HashMap<>();
        forEach(folder, (m) -> { if (m.group(2).contains("volt:purpose=\"row\"")) out.put(m.group(1), unescape(m.group(3))); });
        return out;
    }

    /** name to text for every <string> in a folder's strings_*.xml, unescaped as the app reads them. */
    private static Map<String, String> strings(String folder) {
        Map<String, String> out = new HashMap<>();
        forEach(folder, (m) -> out.put(m.group(1), unescape(m.group(3))));
        return out;
    }

    private static void forEach(String folder, java.util.function.Consumer<Matcher> each) {
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(Paths.get(folder))) {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.sorted()::iterator) {
                if (!file.getFileName().toString().startsWith("strings_")) continue;
                Matcher m = STRING.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                while (m.find()) each.accept(m);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String unescape(String text) {
        return text.replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
