package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B11: a language change shows the Backup screen's texts again with nothing left from the language
 * before. Most recorded texts are a Msg and render again by themselves; relabel passes each one through
 * fresh() first, for the ones that hold words rendered when they were given:
 *   - the Restore page's file line ("Full backup from <date>"): restoreCategories formats the date once,
 *     so fresh() formats it again with the same DateFormat call, for the same three keys;
 *   - the Save hint, built with the button's short name rendered (T.backupSaveShort);
 *   - message()'s unreadable-file String, found by its text through noteErrors;
 *   - the Readings history size on Back up, which relabelCounts formats again as backupCategories does;
 *   - the rides and charges rows on Back up ("12 · 3.4 MB"), which keep the count and " · " as shown and
 *     take the size formatted again.
 * BackupActivity is an Activity, so this JVM test reads its source (as BackupScreenTest does) and runs the
 * Msg behaviour fresh() relies on.
 */
public final class BackupRelabelTest {
    private static int checks;

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

    /** Every id answered with its key in marks, a stand-in for another language. */
    private static final T.Source KEYS = new T.Source() {
        public String string(int id) { return T.isPlural(id) ? null : "‹" + T.key(id) + "›" + (T.isFormatted(id) ? "%1$s" : ""); }
        public String plural(int id, long quantity) { return T.isPlural(id) ? "‹" + T.key(id) + "#" + quantity + "›" : null; }
        public String language() { return "de"; }
    };

    private static final String DATE = "java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(new Date(staged.createdAtMs))";

    public static void main(String[] args) throws Exception {
        String code = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/BackupActivity.java")), StandardCharsets.UTF_8);
        String relabel = body(code, "private void relabel(){");
        int refresh = relabel.indexOf("for(Map.Entry<TextView,CharSequence> e:words.entrySet())e.setValue(fresh(e.getValue()));");
        check(refresh >= 0, "relabel passes every recorded text through fresh()");
        check(refresh < relabel.indexOf("view.setText(e.getValue())"), "fresh() runs before the recorded texts are shown again");
        check(refresh > relabel.indexOf("destinations.run();") && refresh < relabel.indexOf("noteErrors();"),
                "fresh() also sees the Scooters page's kept status, and finds old-language text before noteErrors notes the new");

        String fresh = body(code, "private CharSequence fresh(CharSequence given){");
        String restore = body(code, "private void restoreCategories(){");
        check(restore.contains("String date=staged.createdAtMs>0?" + DATE + ":null;"), "restoreCategories formats the date as base did");
        check(fresh.contains("String date=" + DATE + ";"), "fresh() formats the date with restoreCategories' call, now");
        check(fresh.contains("if(staged==null||staged.createdAtMs<=0)return given;"), "a file without a date keeps its line");
        Set<String> shown = keys(restore, "M\\.(backupFile\\w+From)\\(date\\)"), redated = keys(fresh, "M\\.(backupFile\\w+From)\\(date\\)");
        check(shown.equals(new TreeSet<>(Arrays.asList("backupFileFullFrom", "backupFileRidesFrom", "backupFileSelectedFrom"))),
                "the Restore page's dated lines: " + shown);
        check(redated.equals(shown), "fresh() dates again exactly the lines restoreCategories dates: " + redated);
        for (String key : shown) check(fresh.contains("id==M." + key + "(date).id()?M." + key + "(date)"), key + " keeps its own key");

        check(code.contains("status.setText(M.backupPickerFailedSave(T.backupSaveShort()))"), "the Save hint is built with the short name rendered");
        check(fresh.contains("Msg hint=M.backupPickerFailedSave(M.backupSaveShort());if(id==hint.id())return hint;"),
                "fresh() gives the Save hint a short name that renders again");
        check(code.contains(":T.backupFileUnreadable();}"), "message() returns the unreadable-file text rendered");
        check(body(code, "private void noteErrors(){").contains("M.backupFileUnreadable()"), "noteErrors knows it, so fresh() finds its Msg");
        check(fresh.contains("if(given instanceof String){CharSequence known=shownErrors.get(given);return known==null?given:known;}"),
                "a rendered String finds its Msg by its text");

        check(body(code, "private void backupCategories(){").contains("else if(id.equals(\"history\"))detail=size(bytes);"), "Back up shows the history size");
        check(BackupRegistry.HISTORY.equals("history"), "BackupRegistry.HISTORY is that id");
        check(body(code, "private void relabelCounts(){").contains("else if(id.equals(BackupRegistry.HISTORY))detail=size(inventory.get(id)[1]);"),
                "relabelCounts formats the history size again");

        String backUp = body(code, "private void backupCategories(){"), recount = body(code, "private void relabelCounts(){");
        check(backUp.contains("else detail=items+\" · \"+size(bytes);"), "Back up shows a rides or charges row as its count, \" · \" and its size");
        check(BackupRegistry.RIDES.equals("rides") && BackupRegistry.CHARGES.equals("charges") && !backUp.contains("\"rides\"") && !backUp.contains("\"charges\""),
                "BackupRegistry.RIDES and CHARGES are ids no earlier branch of backupCategories takes");
        // By name or by constant: "rides", "charges", BackupRegistry.RIDES or CHARGES (also in a || of another id).
        Pattern ridesOrCharges = Pattern.compile("\"(rides|charges)\"|\\b(RIDES|CHARGES)\\b");
        int chain = backUp.indexOf("String detail;"), last = backUp.indexOf("else detail=items+\" · \"+size(bytes);");
        check(chain >= 0 && last > chain, "backupCategories declares detail before its last else");
        String earlier = first(ridesOrCharges, backUp.substring(chain, last));
        check(earlier == null, "no branch of backupCategories before its last else takes rides or charges: " + earlier);
        int branch = recount.indexOf("else if(id.equals(BackupRegistry.RIDES)||id.equals(BackupRegistry.CHARGES)){");
        check(branch >= 0, "relabelCounts has its rides and charges branch");
        String before = first(ridesOrCharges, recount.substring(0, branch));
        check(before == null, "no branch of relabelCounts before its rides and charges branch takes them: " + before);
        String rows = body(recount, "else if(id.equals(BackupRegistry.RIDES)||id.equals(BackupRegistry.CHARGES)){").replaceAll("\\s+", " ");
        check(rows.equals("View shown=((android.view.ViewGroup)e.getValue().getParent()).getChildAt(1);int cut=Long.toString(inventory.get(id)[0]).length()+3; "
                        + "if(shown instanceof TextView&&((TextView)shown).length()>cut)detail=((TextView)shown).getText().toString().substring(0,cut)+size(inventory.get(id)[1]);"),
                "relabelCounts keeps a rides or charges row's count and separator as shown and formats its size again: " + rows);
        Set<String> separator = keys(backUp, "else detail=items\\+\"([^\"]*)\"\\+size\\(bytes\\);"), kept = keys(rows, "\\.length\\(\\)\\+(\\d+);");
        check(separator.size() == 1 && kept.equals(Collections.singleton(Integer.toString(separator.iterator().next().length()))),
                "the kept part ends after the count's digits and backupCategories' separator " + separator + ": +" + kept);
        check(recount.indexOf("if(!hasItems(id))detail=T.backupDetailNone();") >= 0
                && recount.indexOf("if(!hasItems(id))detail=T.backupDetailNone();") < recount.indexOf("else if(id.equals(BackupRegistry.RIDES)"),
                "an empty rides or charges row keeps None, as on Back up");

        rendering();
        System.out.println("BackupRelabelTest: " + checks + " checks passed (Restore date, Save hint, unreadable file, "
                + "history, rides and charges sizes formatted again on a language change)");
    }

    private static void rendering() {
        T.install(null);
        try {
            Msg full = M.backupFileFullFrom("Nov 4, 2026 12:59 PM");
            check(full.toString().equals("Full backup from Nov 4, 2026 12:59 PM"), "English: the dated line is base's");
            check(full.id() == M.backupFileFullFrom("4 nov. 2026").id(), "a dated line's key does not depend on its date");
            check(new HashSet<>(Arrays.asList(full.id(), M.backupFileRidesFrom("x").id(), M.backupFileSelectedFrom("x").id())).size() == 3,
                    "the three dated lines have their own keys");
            Msg built = M.backupPickerFailedSave(T.backupSaveShort()), again = M.backupPickerFailedSave(M.backupSaveShort());
            check(built.toString().equals(again.toString()) && again.toString().equals("A file picker could not open. Tap Save to try again."),
                    "English: the Save hint reads the same either way");
            check(built.id() == again.id(), "the Save hint keeps its key");
            String unreadable = T.backupFileUnreadable();
            T.install(KEYS);
            check(built.toString().contains("Save") && !again.toString().contains("Save") && again.toString().contains("‹backup_save_short›"),
                    "only the rebuilt Save hint names the button in the new text: " + built + " / " + again);
            check(!M.backupFileUnreadable().toString().equals(unreadable), "the unreadable-file Msg renders in the new text");
        } finally { T.install(null); }
    }

    private static Set<String> keys(String code, String pattern) {
        Set<String> found = new TreeSet<>();
        Matcher m = Pattern.compile(pattern).matcher(code);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    /** The first text the pattern finds in code, or null. */
    private static String first(Pattern pattern, String code) {
        Matcher m = pattern.matcher(code);
        return m.find() ? m.group() : null;
    }

    /** The text between a member's opening line and its matching closing brace. */
    private static String body(String code, String opening) {
        int start = code.indexOf(opening);
        check(start >= 0, "BackupActivity has " + opening);
        int depth = 1, i = start + opening.length();
        for (; i < code.length() && depth > 0; i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return code.substring(start + opening.length(), i - 1).trim();
    }
}
