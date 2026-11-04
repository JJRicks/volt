package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * G5 characterization for B11 (the Backup screen, BackupActivity), committed before the batch changes
 * anything.
 *
 * BackupActivity is an Activity, so a plain JVM test cannot run it; G2 (LedgerTest) proves each new
 * template, plural and choice renders the original concatenation, and G7's census covers the screen.
 * What this pins is the English the screen takes from the backup engine, which the batch stops reading
 * through getMessage():
 *   - the screen showed an IOException's message when it was 80 characters or shorter, else "The file
 *     could not be read." Every BackupException message (the engine's catalog English) is 80 characters
 *     or shorter, except the two that only a restore's apply or the startup recovery throw, which the
 *     screen never passes to that check (it shows fixed text there). So showing BackupException.msg()
 *     shows the same English the 80-character check let through;
 *   - the screen compared the message with "Saved data changed. Review again." and "Not enough free
 *     storage". Exactly one throw carries each, with the CHANGED and NO_SPACE codes, and no other
 *     engine message has either English, so comparing the code is the same test;
 *   - the counts the screen still builds with BackupArchive.plural ("1 ride", "2 rides"), which stay
 *     English in this batch.
 * The plural counts run with the phone set to en-US, de-DE, tr-TR and ar-EG.
 */
public final class BackupScreenTextTest {
    private static int checks;

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

    /** The two engine messages longer than 80 characters, and the one file and method kinds that throw each. */
    private static final Map<String, String> LONG = new LinkedHashMap<>();
    static {
        LONG.put("backup_engine_restore_was_interrupted_reopen", "AppDataArchive.java apply");
        LONG.put("backup_engine_restore_recovery_record_needs_app", "AppDataArchive.java recover");
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> english = engineEnglish();
        exceptionMessages(english);
        codes(english);
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR"), new Locale("ar", "EG")}) {
                Locale.setDefault(phone);
                counts(phone);
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("BackupScreenTextTest: " + checks + " checks passed (engine refusals the Backup screen shows, "
                + "the CHANGED and NO_SPACE refusals, and the screen's English counts under 4 phone locales)");
    }

    /** key -> English of every string in strings_backup_engine.xml and strings_common.xml (a few engine refusals use a
     * shared sentence), unescaped as aapt stores it. */
    private static Map<String, String> engineEnglish() throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : new String[]{"strings_backup_engine.xml", "strings_common.xml"}) {
            String xml = new String(Files.readAllBytes(Paths.get("res/values", name)), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL).matcher(xml);
            while (m.find()) {
                String text = m.group(2).replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
                result.put(m.group(1), text);
            }
        }
        check(result.size() > 100, "the backup engine catalog was read");
        return result;
    }

    private static String snake(String camel) {
        StringBuilder out = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) out.append('_').append(Character.toLowerCase(c));
            else out.append(c);
        }
        return out.toString();
    }

    /** Every `new BackupException(... M.key() ...)` in src, as [file, method, key]. */
    private static List<String[]> throwsInSource() throws IOException {
        List<String[]> found = new ArrayList<>();
        Pattern create = Pattern.compile("new BackupException\\(([^;]*?)M\\.([a-zA-Z0-9]+)\\(");
        Pattern method = Pattern.compile("(?:static|public|private)[^;{}=]*?\\b([a-zA-Z0-9_]+)\\s*\\([^;{}]*\\)\\s*(?:throws [^{;]+)?\\{");
        try (Stream<Path> files = Files.list(Paths.get("src/com/jjricks/volt"))) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                if (!file.toString().endsWith(".java")) continue;
                String code = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher m = create.matcher(code);
                while (m.find()) {
                    String owner = "?";
                    Matcher declared = method.matcher(code.substring(0, m.start()));
                    while (declared.find()) owner = declared.group(1);
                    found.add(new String[]{file.getFileName().toString(), owner, m.group(2)});
                }
            }
        }
        return found;
    }

    private static void exceptionMessages(Map<String, String> english) throws IOException {
        List<String[]> found = throwsInSource();
        check(found.size() > 200, "the engine's BackupException throws were found: " + found.size());
        Map<String, Set<String>> longWhere = new LinkedHashMap<>();
        for (String[] site : found) {
            String key = snake(site[2]);
            String text = english.get(key);
            check(text != null, site[0] + ": " + site[2] + " is a backup engine string");
            check(!text.contains("%"), key + " takes no argument, so its length is fixed");
            if (text.length() > 80) {
                longWhere.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(site[0] + " " + site[1]);
            }
        }
        check(longWhere.keySet().equals(LONG.keySet()), "the engine messages over 80 characters: " + longWhere.keySet());
        for (Map.Entry<String, String> e : LONG.entrySet()) {
            check(longWhere.get(e.getKey()).equals(Collections.singleton(e.getValue())),
                    e.getKey() + " is thrown only by " + e.getValue() + ": " + longWhere.get(e.getKey()));
        }
        check("The file could not be read.".length() <= 80, "the screen's fallback");
    }

    private static void codes(Map<String, String> english) throws IOException {
        String changed = "Saved data changed. Review again.", space = "Not enough free storage";
        List<String> changedKeys = new ArrayList<>(), spaceKeys = new ArrayList<>();
        for (Map.Entry<String, String> e : english.entrySet()) {
            if (e.getValue().equals(changed)) changedKeys.add(e.getKey());
            if (e.getValue().equals(space)) spaceKeys.add(e.getKey());
        }
        check(changedKeys.equals(Collections.singletonList("backup_engine_saved_data_changed_review")), "one engine string says " + changed);
        check(spaceKeys.equals(Collections.singletonList("backup_engine_not_enough_free_storage")), "one engine string says " + space);
        int changedThrows = 0, spaceThrows = 0;
        try (Stream<Path> files = Files.list(Paths.get("src/com/jjricks/volt"))) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                if (!file.toString().endsWith(".java")) continue;
                String code = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                changedThrows += count(code, "M.backupEngineSavedDataChangedReview()");
                spaceThrows += count(code, "M.backupEngineNotEnoughFreeStorage()");
                if (code.contains("M.backupEngineSavedDataChangedReview()"))
                    check(code.contains("new BackupException(BackupException.Code.CHANGED, M.backupEngineSavedDataChangedReview())"),
                            file + ": the changed-data refusal carries CHANGED");
                if (code.contains("M.backupEngineNotEnoughFreeStorage()"))
                    check(code.contains("new BackupException(BackupException.Code.NO_SPACE, M.backupEngineNotEnoughFreeStorage())"),
                            file + ": the free-space refusal carries NO_SPACE");
            }
        }
        check(changedThrows == 1, "one throw says " + changed + ": " + changedThrows);
        check(spaceThrows == 1, "one throw says " + space + ": " + spaceThrows);
    }

    private static int count(String code, String needle) {
        int n = 0;
        for (int at = code.indexOf(needle); at >= 0; at = code.indexOf(needle, at + 1)) n++;
        return n;
    }

    private static void counts(Locale phone) {
        String[][] expected = {
                {"ride", "0 rides", "1 ride", "2 rides", "21 rides", "1000 rides"},
                {"charge", "0 charges", "1 charge", "2 charges", "21 charges", "1000 charges"},
                {"setting", "0 settings", "1 setting", "2 settings", "21 settings", "1000 settings"},
                {"scooter", "0 scooters", "1 scooter", "2 scooters", "21 scooters", "1000 scooters"}};
        int[] quantities = {0, 1, 2, 21, 1000};
        for (String[] row : expected) {
            for (int i = 0; i < quantities.length; i++) {
                String shown = BackupArchive.plural(quantities[i], row[0]);
                check(shown.equals(row[i + 1]), phone + ": " + row[0] + " at " + quantities[i] + " is " + shown);
            }
        }
    }
}
