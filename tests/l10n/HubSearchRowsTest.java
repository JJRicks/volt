package com.jjricks.volt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Search finds a page by the names its rows show in the current language, on every page (#533 review, F04/F05: only
 * Recording settings' rows were found outside English, so "Automatycznie zapisuj ładowania" found nothing in Polish).
 * HubText.pageRowTitles names each page's rows one for one with HubSearchTables.pageRows' English titles, then rows shown
 * only in some states (Developer options' Scene); MainActivity's search gates join them to Recording settings' own.
 * Each row name in Polish and German, typed as the row shows it, lists its page.
 */
public final class HubSearchRowsTest {
    private static int checks;

    /** A shipped language as the app shows it: res/values-<dir>/strings_*.xml, English (T's own) where a key has none. */
    private static final class Catalog implements T.Source {
        final Map<String, String> text = new HashMap<>();
        private final String language;
        Catalog(String dir) {
            language = dir;
            for (Map.Entry<String, String> e : strings("res/values-" + dir).entrySet()) text.put(e.getKey(), e.getValue());
        }
        @Override public String string(int id) { return text.get(T.key(id)); }
        @Override public String plural(int id, long quantity) { return null; }
        @Override public String language() { return language; }
    }

    /** As MainActivity's search gates: every page's rows by the catalog's names, Recording settings' by its own files. */
    private static final HubSearch.Gates GATES = new HubSearch.Gates() {
        @Override public boolean row(String id) { return true; }
        @Override public boolean lab(String key) { return false; }
        @Override public boolean page(String page) { return true; }
        @Override public CharSequence[] pageRowTitles(String page) {
            Msg[] named = HubText.pageRowTitles(page);
            List<CharSequence> all = new ArrayList<>();
            if (HubPages.RIDE_RECORDING.equals(page))
                for (String title : new String[] {T.morePrefsAwake(), T.morePrefsBattery(), T.morePrefsMap(), T.ridesGpsRoute(), T.ridesBarometer(), T.ridesMotionSensors()}) all.add(title);
            for (Msg name : named) all.add(name);
            return all.isEmpty() ? null : all.toArray(new CharSequence[0]);
        }
    };

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            oneForOne();
            wired();
            for (String language : new String[] {"pl", "de"}) {
                T.install(new Catalog(language));
                rowsIn(language);
            }
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("HubSearchRowsTest: " + checks + " checks passed");
    }

    /** The catalog's names are the strings whose English is each pageRows title, in its order; then own-words rows. */
    private static void oneForOne() {
        for (String page : HubPages.ALL) {
            String[] english = HubSearchTables.pageRows(page);
            Msg[] names = HubText.pageRowTitles(page);
            if (HubPages.RIDE_RECORDING.equals(page)) { check(names.length == 0, "Recording settings' rows are named by their own files"); continue; }
            check(names.length >= english.length, page + ": a shown name for each of its " + english.length + " rows (" + names.length + ")");
            for (int i = 0; i < english.length; i++)
                check(english[i].equals(names[i].english()), page + ": row " + i + " reads " + names[i].english() + ", pageRows lists " + english[i]);
            for (int i = english.length; i < names.length; i++)
                check(HubSearchTables.pageOwnWords(page).contains(names[i].english().toLowerCase(Locale.ROOT)), page + ": " + names[i].english() + " is in its own words");
        }
        check(HubText.pageRowTitles(null).length == 0 && HubText.pageRowTitles("gone").length == 0, "no page, no names");
    }

    /** MainActivity's gates join the catalog's names to Recording settings' own (the files that show those). */
    private static void wired() {
        String main = read("src/com/jjricks/volt/MainActivity.java");
        check(main.contains("String[] more=MoreScreen.searchRowTitles(page),rides=RidesScreen.searchRowTitles(page);")
                && main.contains("Msg[] named=HubText.pageRowTitles(page);")
                && main.contains("System.arraycopy(named,0,all,more.length+rides.length,named.length);"),
                "MainActivity's search gates add every page's row names");
    }

    /** Every row name the language translates, typed as it shows, lists its page; so does Developer options' Scene. */
    private static void rowsIn(String language) {
        Map<String, String> keyOf = new HashMap<>();
        for (Map.Entry<String, String> e : strings("res/values").entrySet()) keyOf.putIfAbsent(e.getValue(), e.getKey());
        Map<String, String> shown = strings("res/values-" + language);
        List<HubSearch.Entry> index = HubSearch.pages(GATES);
        List<String> missed = new ArrayList<>();
        int translated = 0;
        for (String page : HubPages.ALL)
            for (String title : HubSearchTables.pageRows(page)) {
                String key = keyOf.get(title);
                check(key != null, "a catalog string reads " + title);
                String name = shown.get(key);
                if (name == null || name.equals(title)) continue;
                translated++;
                if (!lists(index, name, page)) missed.add(page + ": \"" + name + "\" (" + title + ")");
            }
        check(translated > 20, language + " translates the rows' titles (" + translated + ")");
        check(missed.isEmpty(), "row names in " + language + " that do not list their page: " + missed);
        String scene = shown.get("more_dev_simulation_scene");
        check(scene != null && !scene.equals("Scene") && lists(index, scene, HubPages.SETTINGS_DEVELOPER), "\"" + scene + "\" lists Developer options in " + language);
        if (language.equals("pl")) check(lists(index, "Automatycznie zapisuj ładowania", HubPages.BATTERY_HISTORY), "the review's example finds Charge history & recordings");
        // In English nothing changes: the titles were in each page's English words already.
        T.install(null);
        List<HubSearch.Entry> english = HubSearch.pages(GATES);
        for (String page : HubPages.ALL) for (String title : HubSearchTables.pageRows(page)) check(lists(english, title, page), "\"" + title + "\" still lists " + page + " in English");
        T.install(new Catalog(language));
    }

    private static boolean lists(List<HubSearch.Entry> index, String query, String page) {
        for (HubSearch.Entry e : HubSearch.search(index, query)) if (e.kind == HubSearch.Kind.PAGE && page.equals(e.page)) return true;
        return false;
    }

    /** name to text for every <string> in a folder's strings_*.xml, unescaped as the app reads them. */
    private static Map<String, String> strings(String folder) {
        Map<String, String> out = new HashMap<>();
        java.util.regex.Pattern string = java.util.regex.Pattern.compile("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", java.util.regex.Pattern.DOTALL);
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(Paths.get(folder))) {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.sorted()::iterator) {
                if (!file.getFileName().toString().startsWith("strings_")) continue;
                java.util.regex.Matcher m = string.matcher(read(file.toString()));
                while (m.find()) out.put(m.group(1), m.group(2).replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
