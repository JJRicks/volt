package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B11: the Backup screen builds the views base f529f43 built, and still relabels them. The emulator census
 * keys every record by a path of simple class names, so a paragraph or password field that became a
 * subclass (Line extends TextView, Secret extends EditText) changed every Backup page. BackupActivity is
 * an Activity, so this JVM test reads its source, as BackupScreenTest does:
 *   - BackupActivity declares no class but itself: no view subclass;
 *   - a paragraph is ui.caption(...) followed by base's calls in base's order, a password field is a
 *     plain new EditText(this);
 *   - relabel finds paragraphs, the status line, hints and errors through maps (words, hints, shownErrors),
 *     not by class: each paragraph records what it is given (paragraph, and its keep filter on every later
 *     setText), each password field its hint, and every Msg passed to setError is in noteErrors;
 *   - the Msg values relabel keeps render again in the newly installed language, and the password errors
 *     render apart in English and in another installed text, so the rendered error finds its Msg.
 */
public final class BackupViewsTest {
    private static int checks;

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

    /** Every id answered with its key in marks, a stand-in for another language. */
    private static final T.Source KEYS = new T.Source() {
        public String string(int id) { return T.isPlural(id) ? null : "‹" + T.key(id) + "›"; }
        public String plural(int id, long quantity) { return T.isPlural(id) ? "‹" + T.key(id) + "#" + quantity + "›" : null; }
        public String language() { return "de"; }
    };

    public static void main(String[] args) throws Exception {
        String code = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/BackupActivity.java")), StandardCharsets.UTF_8);
        classes(code);
        relabel(code);
        rendering();
        System.out.println("BackupViewsTest: " + checks + " checks passed (base view classes and calls, relabel through "
                + "maps, Msg re-rendering, distinct password errors)");
    }

    private static void classes(String code) {
        Matcher declared = Pattern.compile("\\b(class|interface|enum)\\s+(\\w+)").matcher(code);
        List<String> names = new ArrayList<>();
        while (declared.find()) names.add(declared.group(2));
        check(names.equals(Collections.singletonList("BackupActivity")), "BackupActivity declares no other class (a view subclass changes the census): " + names);
        check(!Pattern.compile("new\\s+(TextView|EditText)\\s*\\(\\s*this\\s*\\)\\s*\\{").matcher(code).find(), "no anonymous TextView or EditText subclass");

        String paragraph = body(code, "private TextView paragraph(LinearLayout parent,CharSequence message){");
        // Base f529f43: ui.caption(message); setTextSize(COMPLEX_UNIT_SP,15*textScale); setPadding(0,dp(12),0,dp(12)); addView.
        check(paragraph.startsWith("TextView text=ui.caption("), "a paragraph is a Ui caption, a plain TextView: " + paragraph);
        List<String> calls = new ArrayList<>();
        Matcher call = Pattern.compile("\\b(text|parent)\\.(\\w+)\\(").matcher(paragraph);
        while (call.find()) calls.add(call.group(1) + "." + call.group(2));
        check(calls.equals(Arrays.asList("text.setFilters", "text.setTextSize", "text.setPadding", "parent.addView")),
                "a paragraph makes base's view calls in base's order, plus only the recording filter: " + calls);
        check(paragraph.contains("text.setTextSize(TypedValue.COMPLEX_UNIT_SP,15*textScale);text.setPadding(0,ui.dp(12),0,ui.dp(12));parent.addView(text);return text;"),
                "a paragraph's size, padding and parent are base's");
        check(code.contains("private TextView paragraph(CharSequence message){return paragraph(content,message);}"), "paragraph(message) returns a plain TextView");

        String field = body(code, "private EditText passwordField(CharSequence hint){");
        check(field.startsWith("EditText field=new EditText(this);"), "a password field is a plain EditText: " + field);
        check(code.contains("private TextView status, action, closeAction;"), "the status line is a TextView field");
    }

    private static void relabel(String code) {
        String paragraph = body(code, "private TextView paragraph(LinearLayout parent,CharSequence message){");
        check(paragraph.contains("words.put(text,message);") && paragraph.contains("text.setFilters(new InputFilter[]{keep(text)});"),
                "a paragraph records its text and keeps every later one");
        check(code.contains("private InputFilter keep(TextView view){return (source,start,end,dest,dstart,dend)->{words.put(view,source);return null;};}"),
                "the keep filter records the text as given and changes nothing");
        String field = body(code, "private EditText passwordField(CharSequence hint){");
        check(field.contains("hints.put(field,hint);field.setHint(hint);"), "a password field records its hint");

        String relabel = body(code, "private void relabel(){");
        check(relabel.contains("CharSequence note=status==null?null:words.get(status);"), "the Scooters page keeps its status note through words");
        check(relabel.contains("for(Map.Entry<TextView,CharSequence> e:new ArrayList<>(words.entrySet())){TextView view=e.getKey();if(view!=null)view.setText(e.getValue());}"),
                "relabel shows every recorded text again (paragraphs and the status line included)");
        check(relabel.contains("field.setHint(e.getValue())"), "relabel shows every hint again");
        check(relabel.contains("CharSequence shown=field.getError();CharSequence error=shown==null?null:shownErrors.get(shown.toString());if(error!=null)field.setError(error);"),
                "relabel shows a shown error again through its Msg");
        check(relabel.indexOf("noteErrors();") > relabel.indexOf("shownErrors.get("), "relabel notes the errors in the new language after using the old ones");
        check(code.contains("shownLocales=locales();noteErrors();"), "onCreate notes the errors in the shown language");
        String views = body(code, "private void relabel(View view){");
        check(!views.contains("instanceof Line") && !views.contains("instanceof Secret") && !views.contains("instanceof EditText"),
                "relabel(View) finds no view by its class: " + views);

        Set<String> errors = new TreeSet<>();
        Matcher set = Pattern.compile("\\.setError\\((M\\.\\w+\\(\\))\\)").matcher(code);
        while (set.find()) errors.add(set.group(1));
        check(errors.size() == 2, "the screen's password errors: " + errors);
        String note = body(code, "private void noteErrors(){");
        for (String error : errors) check(note.contains(error), "noteErrors knows " + error + ", so relabel can show it again");
        Matcher any = Pattern.compile("\\.setError\\(([^)]*\\)?)\\)").matcher(code);
        while (any.find()) check(any.group(1).equals("error") || errors.contains(any.group(1)), "every setError passes a listed Msg: " + any.group());
    }

    private static void rendering() {
        T.install(null);
        try {
            CharSequence shortError = M.backupPasswordShort(), differ = M.backupPasswordsDiffer(), paragraph = M.backupRestoreIntro();
            String englishShort = shortError.toString(), englishDiffer = differ.toString(), englishParagraph = paragraph.toString();
            check(englishShort.equals("Use at least 8 characters.") && englishDiffer.equals("The passwords do not match."), "English errors are base's");
            check(!englishShort.equals(englishDiffer), "English errors render apart");
            Map<String, CharSequence> shown = new HashMap<>();
            shown.put(englishShort, shortError); shown.put(englishDiffer, differ);
            T.install(KEYS);
            check(paragraph.toString().equals("‹backup_restore_intro›") && !paragraph.toString().equals(englishParagraph),
                    "a kept paragraph Msg renders in the newly installed text");
            check(shown.get(englishShort).toString().equals("‹backup_password_short›"), "the English error finds its Msg, which renders in the new text");
            check(shown.get(englishDiffer).toString().equals("‹backup_passwords_differ›"), "the other English error finds its Msg");
            check(!M.backupPasswordShort().toString().equals(M.backupPasswordsDiffer().toString()), "the errors render apart in another installed text");
            T.install(null);
            check(shown.get(englishShort).toString().equals(englishShort), "and back in English");
        } finally { T.install(null); }
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
