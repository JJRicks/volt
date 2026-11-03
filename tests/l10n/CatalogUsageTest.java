package com.jjricks.volt;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source rules for catalog text (plan section 1.4), checked by scanning src/com/jjricks/volt the way
 * BackupRegistryTest does:
 *   R2  rendered text is never kept in a field: no rendered text initializes a field or is assigned to
 *       one, with or without this. (a T call, a Msg's render() or toString(), or a Msg joined with +)
 *   R3  rendered text is never compared, parsed, re-cased, replaced in or cut
 *   R4  English-only files (reports, logs, the Shizuku shell process) never reference T, M, Msg or R
 *   R5  no rendered text in a static initializer or a static field (double-brace initializers included)
 *   R6  app code never reads catalog text with getString/getText/getQuantityString(R....)
 * plus the Shizuku shell process (see shellProcess) never runs catalog code and never names VoltApp,
 * AppLanguage or AndroidText; VoltApp is the manifest's Application.
 * R1 (every visible string comes from T or Msg) is G8's: tools/l10n/hardcoded_lint.py and l10n/baseline/.
 */
public final class CatalogUsageTest {
    private static int checks;

    /** R4: files whose output must stay English (reports, logs and the Shizuku shell process). */
    static final List<String> ENGLISH_ONLY = Arrays.asList(
            "CompatibilityReport.java", "ConnectionReport.java", "IssueReport.java", "ReportInvite.java", "OwnerNotes.java",
            "DiagnosticsBundle.java", "CommandLog.java", "ConnectionEventLog.java",
            "OwnerCaptureUserService.java", "OwnerCaptureProcess.java");
    /** The Shizuku shell process starts here (ShizukuCaptureClient binds it). */
    static final String SHELL_ENTRY = "OwnerCaptureUserService";
    /** Android-side runtime that needs Volt's Application: no class the shell process can load may name these. */
    static final List<String> APP_RUNTIME = Arrays.asList("VoltApp", "AppLanguage", "AndroidText");
    /** Locale-dependent runtime the shell process must never run, besides rendering catalog text. */
    private static final Pattern LOCALE_RUNTIME = Pattern.compile("\\b(?:AppLanguages|Fmt|VoltApp|AppLanguage|AndroidText)\\b");
    /**
     * The catalog runtime itself, not followed into: building a Msg or a UserFacingException defers the
     * text (getMessage() is English through EnglishText), so only rendering at a call site counts.
     */
    static final List<String> DEFERRED = Arrays.asList("Msg", "UserFacingException");

    private static final Pattern T_CALL = Pattern.compile("(?<![\\w.])T\\.[a-z]\\w*\\s*\\(");
    private static final Pattern MSG_CALL = Pattern.compile("(?<![\\w.])(?:M\\.[a-z]\\w*|Msg\\.\\w+)\\s*\\(");
    private static final Pattern CATALOG_CALL = Pattern.compile("(?<![\\w.])(?:T|M)\\.[a-z]\\w*\\s*\\(|\\.render\\s*\\(");
    private static final Pattern TEXT_OPERATION = Pattern.compile("^\\s*\\.\\s*(equals|equalsIgnoreCase|contentEquals|contains|startsWith|endsWith|indexOf|"
            + "lastIndexOf|matches|compareTo|compareToIgnoreCase|toLowerCase|toUpperCase|replace|replaceAll|replaceFirst|substring|subSequence|"
            + "split|charAt|trim|strip|length|isEmpty)\\s*\\(");
    private static final Pattern COMPARED_WITH = Pattern.compile("\\.(equals|equalsIgnoreCase|contentEquals|contains|startsWith|endsWith|indexOf|compareTo)"
            + "\\s*\\(\\s*(?:T\\.[a-z]\\w*\\s*\\(|[\\w.]+\\.render\\s*\\()");
    private static final Pattern RESOURCE_READ = Pattern.compile("\\b(?:getString|getText|getQuantityString|getQuantityText|getStringArray)\\s*\\(\\s*(?:[\\w.]+\\.)?R\\.(?:string|plurals|array)\\.");
    /** this.name = ... anywhere: the target is a field whatever locals exist. */
    private static final Pattern THIS_ASSIGN = Pattern.compile("\\bthis\\s*\\.\\s*\\w+\\s*\\+?=(?!=)");
    /** A local declaration at the start of a statement: [final] Type name [= ...], Type allowing generics and arrays. */
    private static final Pattern LOCAL_DECLARATION = Pattern.compile("^\\s*(?:final\\s+)?([\\w.]+)(?:\\s*<[^;()=]*>)?(?:\\s*\\[\\s*\\])*\\s+(\\w+)\\s*(?:=|,|$)");
    /** A parameter or resource in a block header: (Type name or , Type name, followed by : , ) or =. */
    private static final Pattern HEADER_DECLARATION = Pattern.compile("[(,]\\s*(?:final\\s+)?(?:@\\w+\\s+)?([\\w.]+)(?:\\s*<[^;()=]*?>)?(?:\\s*\\[\\s*\\])*(?:\\.\\.\\.)?\\s+(\\w+)\\s*(?=[:,)=])");
    /** An assignment at the start of a statement, or after if (...), else, a lambda arrow or a case label. */
    private static final Pattern PLAIN_ASSIGN = Pattern.compile("(?:^|\\)|\\belse\\b|->|:)\\s*(\\w+)\\s*\\+?=(?!=)");
    private static final Pattern REFERENCES_CATALOG = Pattern.compile("(?<![\\w.])(?:T|M|R)\\.\\w|\\bMsg\\b|\\bEnglishText\\b|\\bAndroidText\\b|\\bUserFacingException\\b");
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList("if", "for", "while", "switch", "catch", "synchronized",
            "return", "new", "throw", "else", "try", "do", "case", "assert", "super", "this"));

    public static void main(String[] args) {
        selfTest();
        Map<String, String> sources = L10nFiles.appSources();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> file : sources.entrySet()) problems.addAll(scan(file.getKey(), file.getValue()));
        for (String name : ENGLISH_ONLY) {
            check(sources.containsKey(name), "English-only file " + name + " exists");
            problems.addAll(englishOnly(name, sources.get(name)));
        }
        check(problems.isEmpty(), "catalog rules:\n  " + String.join("\n  ", problems));
        Set<String> reached = new TreeSet<>();
        List<String> shell = shellProcess(sources, reached);
        check(reached.contains("OwnerCaptureEngine#capture") && reached.contains("OwnerCaptureProcess#run"),
                "the shell-process reach is computed (" + reached + ")");
        check(shell.isEmpty(), "Shizuku shell process:\n  " + String.join("\n  ", shell));
        String manifest = L10nFiles.read(new File(L10nFiles.root(), "AndroidManifest.xml"));
        check(manifest.matches("(?s).*<application\\b[^>]*android:name=\"\\.VoltApp\".*"), "the manifest names VoltApp as the Application");
        System.out.println("CatalogUsageTest: " + checks + " checks passed (" + sources.size() + " sources scanned for R2-R6; the shell process runs "
                + reached.size() + " members)");
    }

    /** Every rule finds a planted violation, and leaves the allowed forms alone. */
    private static void selfTest() {
        String bad = "final class Bad {\n"
                + "  static final String A = T.commonCancel();\n"
                + "  private String b = T.commonOk();\n"
                + "  static { label = T.commonClose(); }\n"
                + "  void f(String s) {\n"
                + "    this.c = prefix + T.commonDone();\n"
                + "    if (T.commonOn().equals(s)) g();\n"
                + "    if (s.equals(T.commonOff())) g();\n"
                + "    String t = M.commonOk().render().toLowerCase();\n"
                + "    String u = getString(R.string.common_ok);\n"
                + "  }\n"
                + "}\n";
        expect("Bad.java", bad, "Bad.java:2: R5", "Bad.java:3: R2", "Bad.java:4: R5", "Bad.java:6: R2", "Bad.java:7: R3", "Bad.java:8: R3",
                "Bad.java:9: R3", "Bad.java:10: R6");
        // Text frozen through a Msg, a double-brace initializer, or a plain field assignment in a method.
        String frozen = "final class Frozen {\n"
                + "  static final String A = M.commonOk().render();\n"
                + "  static final String B = M.commonOk().toString();\n"
                + "  static final String C = \"\" + M.commonOk();\n"
                + "  static final String D;\n"
                + "  static { D = M.commonOk().render(); }\n"
                + "  static final String E = Msg.of(1).render();\n"
                + "  static final Map<String, String> F = new HashMap<String, String>() {{ put(\"a\", T.commonOk()); }};\n"
                + "  private String message, chargeStatus;\n"
                + "  private final String g = M.commonOk() + \"!\";\n"
                + "  void f(boolean done) {\n"
                + "    message = T.appName();\n"
                + "    if (done) chargeStatus = done ? T.commonDone() : null;\n"
                + "    else message = M.commonOk().render();\n"
                + "  }\n"
                + "}\n";
        expect("Frozen.java", frozen, "Frozen.java:2: R5", "Frozen.java:3: R5", "Frozen.java:4: R5", "Frozen.java:6: R5", "Frozen.java:7: R5",
                "Frozen.java:8: R5", "Frozen.java:10: R2", "Frozen.java:12: R2", "Frozen.java:13: R2", "Frozen.java:14: R2");
        // this.field = rendered text, through a T call, a Msg's render() or a Msg joined with +, even when a local hides the field.
        String qualified = "final class Qualified {\n"
                + "  private String message, status;\n"
                + "  void f(String message) {\n"
                + "    this.message = M.commonOk().render();\n"
                + "    this.status = \"\" + M.commonOk();\n"
                + "    this.message += T.commonDone();\n"
                + "    this.status = M.commonOk().english();\n"
                + "  }\n"
                + "}\n";
        expect("Qualified.java", qualified, "Qualified.java:4: R2", "Qualified.java:5: R2", "Qualified.java:6: R2");
        // A local (or parameter) named like a field is not the field; the field itself still counts.
        String shadowed = "final class Shadowed {\n"
                + "  static final class R { final String model; R(String m) { model = m; } }\n"
                + "  private String name, sub, status;\n"
                + "  String f(String d, String status) {\n"
                + "    String model = d; if (model.isEmpty()) model = T.appName();\n"
                + "    String sub, other = d;\n"
                + "    if (d == null) sub = T.commonOk(); else other = T.commonOn();\n"
                + "    status += T.commonDone();\n"
                + "    for (String row : rows) { String name = row; name = T.commonClose(); }\n"
                + "    name = T.commonCancel();\n"
                + "    return model + sub + other;\n"
                + "  }\n"
                + "  void g() { sub = T.commonOff(); }\n"
                + "}\n";
        expect("Shadowed.java", shadowed, "Shadowed.java:10: R2", "Shadowed.java:13: R2");
        String good = "final class Good {\n"
                + "  static final int LABEL = T.COMMON_CANCEL;\n"
                + "  private final Msg label = M.commonOk();\n"
                + "  private Msg status;\n"
                + "  private final Runnable later = () -> { show(T.commonClose()); };\n"
                + "  private final Map<String, Msg> map = new HashMap<String, Msg>() {{ put(\"a\", M.commonOk()); }};\n"
                + "  void f(TextView v, String s) {\n"
                + "    v.setText(T.commonDone());\n"
                + "    String local = T.commonOn();\n"
                + "    local = T.commonOff();\n"
                + "    status = M.commonOk();\n"
                + "    if (label.equals(M.commonOk())) g();\n"
                + "    new Thread() { public void run() { show(T.commonOk()); } }.start();\n"
                + "    // T.commonOff().equals(s) in a comment\n"
                + "    log(\"T.commonOff().equals(s) in text\");\n"
                + "  }\n"
                + "}\n";
        check(scan("Good.java", good).isEmpty(), "self-test: allowed forms pass: " + scan("Good.java", good));
        check(!englishOnly("Report.java", "class Report { String s = Msg.of(1).render(); }").isEmpty()
                && englishOnly("Report.java", "class Report { String s = \"T.x\"; /* Msg */ }").isEmpty(), "self-test: R4 sees code, not text");
        shellSelfTest();
    }

    private static void expect(String name, String source, String... rules) {
        List<String> found = scan(name, source);
        for (String rule : rules) {
            boolean hit = false;
            for (String problem : found) hit |= problem.startsWith(rule);
            check(hit, "self-test: the scan reports " + rule + " in " + found);
        }
        check(found.size() == rules.length, "self-test: exactly the planted problems: " + found);
    }

    static List<String> scan(String name, String source) {
        String code = L10nFiles.code(source);
        List<String> problems = new ArrayList<>();
        Set<String> fields = new HashSet<>();
        walk(name, code, fields, null);
        walk(name, code, fields, problems);
        Matcher assign = THIS_ASSIGN.matcher(code);
        while (assign.find()) {
            int end = code.indexOf(';', assign.end());
            if (rendered(code.substring(assign.end(), end < 0 ? code.length() : end)) >= 0)
                problems.add(where(name, code, assign.start()) + ": R2 rendered text kept in a field; store a Msg or an id");
        }
        Matcher call = CATALOG_CALL.matcher(code);
        while (call.find()) {
            int end = L10nFiles.closing(code, call.end() - 1);
            if (end < 0) continue;
            // A deferred Msg compared with another Msg is fine; rendered text is not.
            String rest = code.substring(end);
            Matcher op = TEXT_OPERATION.matcher(rest);
            boolean message = code.charAt(call.start()) == 'M' && !rest.matches("(?s)^\\s*\\.\\s*render\\s*\\(.*");
            if (op.find() && !message) problems.add(where(name, code, call.start()) + ": R3 rendered text used as data (." + op.group(1) + "); compare ids or enums");
        }
        Matcher compared = COMPARED_WITH.matcher(code);
        while (compared.find()) problems.add(where(name, code, compared.start()) + ": R3 compared with rendered text; compare ids or enums");
        Matcher read = RESOURCE_READ.matcher(code);
        while (read.find()) problems.add(where(name, code, read.start()) + ": R6 read catalog text through T (generated AndroidText), never getString(R...)");
        return problems;
    }

    /**
     * The index of the first rendered text in a piece of code, or -1: a T call, a render(), or a Msg made
     * into text by toString() or by joining it with +.
     */
    static int rendered(String code) {
        int first = -1;
        Matcher t = T_CALL.matcher(code);
        if (t.find()) first = t.start();
        Matcher render = Pattern.compile("\\.\\s*render\\s*\\(").matcher(code);
        if (render.find() && (first < 0 || render.start() < first)) first = render.start();
        Matcher m = MSG_CALL.matcher(code);
        while (m.find()) {
            int end = L10nFiles.closing(code, m.end() - 1);
            String before = code.substring(0, m.start()).trim();
            boolean joined = before.endsWith("+") || (end > 0 && code.substring(end).matches("(?s)^\\s*(?:\\.\\s*toString\\s*\\(|\\+[^=+]).*"));
            if (joined && (first < 0 || m.start() < first)) first = m.start();
        }
        return first;
    }

    private enum Block {
        TYPE, ANON_STATIC, ANON_FIELD, STATIC, STATIC_INNER, INIT_STATIC, INIT_FIELD, OTHER;
        boolean type() { return this == TYPE || this == ANON_STATIC || this == ANON_FIELD; }
        boolean staticInit() { return this == STATIC || this == STATIC_INNER || this == INIT_STATIC; }
    }

    /**
     * R2 and R5 by block structure: statements directly in a class, interface or enum body are field
     * declarations (or enum constants), and static { } blocks are static initializers. Rendered text there
     * is kept in whatever language was current. An anonymous class created in a field initializer (the
     * double-brace idiom) runs its initializer blocks then too. Inside methods, an assignment to one of
     * the file's fields is checked. Lambda and anonymous-class method bodies run later and are not.
     * With problems null, only collects the file's field names.
     */
    private static void walk(String name, String code, Set<String> fields, List<String> problems) {
        Deque<Block> blocks = new ArrayDeque<>();
        // The locals (parameters included) each block declares, parallel to blocks: a local hides a field.
        Deque<Set<String>> locals = new ArrayDeque<>();
        StringBuilder statement = new StringBuilder();
        int statementStart = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            Block top = blocks.peek();
            boolean inType = top != null && top.type();
            if (c == '{') {
                String header = statement.toString();
                boolean anonymous = header.matches("(?s).*\\bnew\\s+[\\w.<>, ]+\\([^;]*\\)\\s*");
                boolean staticField = inType && header.matches("(?s).*\\bstatic\\b.*");
                Block kind;
                if (header.matches("(?s).*\\b(class|interface|enum)\\b.*") && !header.contains("->")) kind = Block.TYPE;
                else if (header.trim().equals("static")) kind = Block.STATIC;
                else if (anonymous && inType) kind = staticField || top == Block.ANON_STATIC ? Block.ANON_STATIC : Block.ANON_FIELD;
                else if (anonymous && top != null && top.staticInit()) kind = Block.ANON_STATIC;
                else if (anonymous && top == Block.INIT_FIELD) kind = Block.ANON_FIELD;
                else if (anonymous) kind = Block.TYPE;
                else if ((top == Block.ANON_STATIC || top == Block.ANON_FIELD) && header.trim().isEmpty())
                    kind = top == Block.ANON_STATIC ? Block.INIT_STATIC : Block.INIT_FIELD;
                else if (top == Block.STATIC || top == Block.STATIC_INNER) kind = Block.STATIC_INNER;
                else if (top == Block.INIT_STATIC || top == Block.INIT_FIELD) kind = top;
                else kind = Block.OTHER;
                if (inType && kind != Block.TYPE && kind != Block.STATIC) field(name, code, header, statementStart, fields, problems);
                Set<String> declared = new HashSet<>();
                if (kind == Block.OTHER) {
                    Matcher parameter = HEADER_DECLARATION.matcher(header);
                    while (parameter.find()) if (!KEYWORDS.contains(parameter.group(1))) declared.add(parameter.group(2));
                }
                blocks.push(kind);
                locals.push(declared);
                statement.setLength(0);
                statementStart = i + 1;
            } else if (c == '}') {
                blocks.poll();
                locals.poll();
                // A field initialized with an anonymous class continues after its body: keep the statement.
                Block now = blocks.peek();
                if (now == null || !now.type()) { statement.setLength(0); statementStart = i + 1; }
            } else if (c == ';') {
                String text = statement.toString();
                if (inType) field(name, code, text, statementStart, fields, problems);
                else if (problems != null && top != null && top.staticInit()) {
                    int at = rendered(text);
                    if (at >= 0) problems.add(where(name, code, statementStart + at) + ": R5 rendered text in a static initializer; hold an id (T.KEY)");
                } else if (problems != null && top == Block.INIT_FIELD) {
                    int at = rendered(text);
                    if (at >= 0) problems.add(where(name, code, statementStart + at) + ": R2 rendered text kept by a field initializer; store a Msg or an id");
                } else if (top == Block.OTHER) {
                    Matcher local = LOCAL_DECLARATION.matcher(text);
                    if (local.find() && !KEYWORDS.contains(local.group(1))) {
                        locals.peek().add(local.group(2));
                        // String a = x, b: the later names at the top level of the statement.
                        for (String part : topLevelParts(text.substring(local.end(2)))) {
                            Matcher more = Pattern.compile("^\\s*(\\w+)\\s*(?:=|$)").matcher(part);
                            if (more.find()) locals.peek().add(more.group(1));
                        }
                    }
                    if (problems != null) assignment(name, code, text, statementStart, fields, visibleLocals(blocks, locals), problems);
                }
                statement.setLength(0);
                statementStart = i + 1;
            } else {
                if (statement.length() == 0 && Character.isWhitespace(c)) { statementStart = i + 1; continue; }
                statement.append(c);
            }
        }
    }

    /** A field declaration or enum constant: collect its name, and report rendered text in its initializer. */
    private static void field(String name, String code, String statement, int start, Set<String> fields, List<String> problems) {
        int arrow = statement.indexOf("->");
        String head = arrow < 0 ? statement : statement.substring(0, arrow);
        int equals = head.replace("==", "  ").indexOf('=');
        String declaration = equals < 0 ? head : head.substring(0, equals);
        if (!declaration.contains("(")) {
            // "String a, b": every comma part ends in a name (a generic's type argument may slip in; harmless).
            for (String part : declaration.split(",")) {
                Matcher id = Pattern.compile("(\\w+)\\s*$").matcher(part);
                if (id.find()) fields.add(id.group(1));
            }
        }
        if (problems == null) return;
        int at = rendered(head);
        if (at < 0) return;
        boolean isStatic = statement.matches("(?s).*\\bstatic\\b.*") || !statement.contains("=");
        problems.add(where(name, code, start + at) + (isStatic
                ? ": R5 rendered text in a static field or enum constant; hold an id (T.KEY) and render when shown"
                : ": R2 rendered text initializes a field; store a Msg (M...) or an id and render when shown"));
    }

    /** The locals declared so far in the enclosing method's blocks, innermost first, up to the nearest type body. */
    private static Set<String> visibleLocals(Deque<Block> blocks, Deque<Set<String>> locals) {
        Set<String> visible = new HashSet<>();
        java.util.Iterator<Set<String>> scope = locals.iterator();
        for (Block block : blocks) {
            Set<String> names = scope.next();
            if (block != Block.OTHER) break;
            visible.addAll(names);
        }
        return visible;
    }

    /** The comma-separated parts after a declaration's first name, splitting only outside brackets. */
    private static List<String> topLevelParts(String rest) {
        List<String> parts = new ArrayList<>();
        int depth = 0, from = -1;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '<') depth++;
            else if (c == ')' || c == ']' || c == '}' || c == '>') depth--;
            else if (c == ',' && depth == 0) { if (from >= 0) parts.add(rest.substring(from, i)); from = i + 1; }
        }
        if (from >= 0) parts.add(rest.substring(from));
        return parts;
    }

    /**
     * R2 in a method: one of the file's fields assigned rendered text (message = T.x()); a local of the same
     * name hides the field. this.message = ... is scan's, whatever the locals.
     */
    private static void assignment(String name, String code, String statement, int start, Set<String> fields, Set<String> locals,
            List<String> problems) {
        Matcher assign = PLAIN_ASSIGN.matcher(statement);
        while (assign.find()) {
            if (!fields.contains(assign.group(1)) || locals.contains(assign.group(1))) continue;
            int at = rendered(statement.substring(assign.end()));
            if (at >= 0) {
                problems.add(where(name, code, start + assign.start(1)) + ": R2 rendered text assigned to field " + assign.group(1)
                        + "; store a Msg (M...) or an id and render when shown");
                return;
            }
        }
    }

    static List<String> englishOnly(String name, String source) {
        List<String> problems = new ArrayList<>();
        String code = L10nFiles.code(source);
        Matcher m = REFERENCES_CATALOG.matcher(code);
        while (m.find()) problems.add(where(name, code, m.start()) + ": R4 an English-only file references the catalog (" + m.group() + ")");
        return problems;
    }

    // The Shizuku shell process

    /**
     * The Shizuku shell process runs OwnerCaptureUserService without Volt's Application, so:
     *  - no class it can load (every class named, transitively, from OwnerCaptureUserService) names
     *    VoltApp, AppLanguage or AndroidText;
     *  - no code it can run renders catalog text (a T call, render(), a Msg joined into a String) or uses
     *    AppLanguages or Fmt. Building a Msg or throwing a UserFacingException is allowed: the text stays
     *    deferred and getMessage() is English (plan B4's require()). "Can run" is computed per member:
     *    every member of OwnerCaptureUserService,
     *    then every method a reached member calls (Class.m(...), m(...) in the same file, and x.m(...)
     *    for any method m of that name among the loadable classes), every constructor it news, and the
     *    static and field initializers of every class it names.
     * So a shared class such as ScooterCommands may hold T.KEY ids and catalog accessors that only the app
     * calls (plan B4), as long as nothing the shell process calls reaches them. If a method the shell
     * process needs gains catalog text, keep that method's result an id or English and render it in the
     * app (ShizukuCaptureClient) instead.
     * Returns the problems; reached receives "Class#member" for every member found to run.
     */
    static List<String> shellProcess(Map<String, String> sources, Set<String> reached) {
        Map<String, String> code = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : sources.entrySet()) code.put(e.getKey().replace(".java", ""), L10nFiles.code(e.getValue()));
        List<String> problems = new ArrayList<>();
        // Every class the process can load, by name.
        Set<String> loadable = new TreeSet<>();
        Deque<String> queue = new ArrayDeque<>(Arrays.asList(SHELL_ENTRY));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (!code.containsKey(next) || !loadable.add(next)) continue;
            for (String other : code.keySet())
                if (!loadable.contains(other) && Pattern.compile("\\b" + other + "\\b").matcher(code.get(next)).find()) queue.add(other);
        }
        for (String clazz : loadable) {
            if (APP_RUNTIME.contains(clazz)) problems.add("the shell process can load " + clazz);
            for (String banned : APP_RUNTIME)
                if (!clazz.equals(banned) && Pattern.compile("\\b" + banned + "\\b").matcher(code.get(clazz)).find())
                    problems.add("the shell process can load " + clazz + ", which names " + banned);
        }
        // Every member it can run.
        Map<String, Map<String, String>> members = new LinkedHashMap<>();
        for (String clazz : loadable) if (!DEFERRED.contains(clazz)) members.put(clazz, members(code.get(clazz)));
        Deque<String> work = new ArrayDeque<>();
        for (String member : members.get(SHELL_ENTRY).keySet()) work.add(SHELL_ENTRY + "#" + member);
        while (!work.isEmpty()) {
            String next = work.poll();
            if (!reached.add(next)) continue;
            String clazz = next.substring(0, next.indexOf('#'));
            String body = members.get(clazz).get(next.substring(next.indexOf('#') + 1));
            int at = rendered(body);
            Matcher runtime = LOCALE_RUNTIME.matcher(body);
            String use = at >= 0 ? body.substring(at, Math.min(body.length(), at + 24)).trim() : runtime.find() ? runtime.group() : null;
            if (use != null) problems.add("the shell process runs " + next + ", which uses " + use
                    + "; keep text there an id, a Msg or English, and render it in the app");
            work.addAll(calls(clazz, body, members));
        }
        return problems;
    }

    /** A file's members: method name (overloads, nested classes and constructors merged by name) to bodies; "<init>" for initializers and fields. */
    private static Map<String, String> members(String code) {
        Map<String, StringBuilder> out = new LinkedHashMap<>();
        out.put("<init>", new StringBuilder());
        Deque<Boolean> typeBlocks = new ArrayDeque<>();
        StringBuilder statement = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            boolean inType = Boolean.TRUE.equals(typeBlocks.peek());
            if (c == '{') {
                String header = statement.toString();
                boolean type = header.matches("(?s).*\\b(class|interface|enum)\\b.*") && !header.contains("->")
                        || header.matches("(?s).*\\bnew\\s+[\\w.<>, ]+\\([^;]*\\)\\s*");
                if (inType && !type) {
                    // A method, constructor, initializer or lambda field: its whole body is one member.
                    int end = closingBrace(code, i);
                    Matcher method = Pattern.compile("(\\w+)\\s*\\([^)]*\\)[^()]*$").matcher(header);
                    String member = !header.contains("->") && !header.contains("=") && method.find() ? method.group(1) : "<init>";
                    out.computeIfAbsent(member, k -> new StringBuilder()).append(header).append(code, i, end).append('\n');
                    statement.setLength(0);
                    i = end - 1;
                    continue;
                }
                if (inType) out.get("<init>").append(header).append('\n');
                typeBlocks.push(type);
                statement.setLength(0);
            } else if (c == '}') {
                typeBlocks.poll();
                statement.setLength(0);
            } else if (c == ';') {
                if (inType) out.get("<init>").append(statement).append(";\n");
                statement.setLength(0);
            } else {
                statement.append(c);
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> e : out.entrySet()) result.put(e.getKey(), e.getValue().toString());
        return result;
    }

    private static int closingBrace(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i + 1;
        }
        return code.length();
    }

    /** The members a body can run next. */
    private static List<String> calls(String clazz, String body, Map<String, Map<String, String>> members) {
        List<String> out = new ArrayList<>();
        for (String other : members.keySet())
            if (other.equals(clazz) || Pattern.compile("\\b" + other + "\\b").matcher(body).find()) out.add(other + "#<init>");
        Matcher qualified = Pattern.compile("\\b([A-Z]\\w*)\\s*(?:\\.|::)\\s*([a-zA-Z_]\\w*)\\s*(\\()?").matcher(body);
        while (qualified.find()) {
            boolean call = qualified.group(3) != null || body.startsWith("::", qualified.start(2) - 2);
            if (!call) continue;
            if (members.containsKey(qualified.group(1))) add(out, members, qualified.group(1), qualified.group(2));
            else anywhere(out, members, qualified.group(2));
        }
        Matcher created = Pattern.compile("\\bnew\\s+(?:[\\w]+\\.)*([A-Z]\\w*)\\s*(?:<[^>]*>)?\\s*\\(").matcher(body);
        while (created.find()) anywhere(out, members, created.group(1));
        Matcher plain = Pattern.compile("(?<![\\w.])([a-z_]\\w*)\\s*\\(").matcher(body);
        while (plain.find()) if (!KEYWORDS.contains(plain.group(1))) add(out, members, clazz, plain.group(1));
        Matcher dotted = Pattern.compile("(?<![A-Z]\\w{0,80})\\.\\s*([a-z_]\\w*)\\s*\\(").matcher(body);
        while (dotted.find()) anywhere(out, members, dotted.group(1));
        return out;
    }

    private static void add(List<String> out, Map<String, Map<String, String>> members, String clazz, String member) {
        if (members.get(clazz).containsKey(member)) out.add(clazz + "#" + member);
    }

    private static void anywhere(List<String> out, Map<String, Map<String, String>> members, String member) {
        for (String clazz : members.keySet()) add(out, members, clazz, member);
    }

    /** The reach finds a catalog call the shell process runs, and ignores ids and accessors it never calls. */
    private static void shellSelfTest() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(SHELL_ENTRY + ".java", "final class " + SHELL_ENTRY + " { void run() { Helper.used(); new Parser().parse(); } }");
        sources.put("Parser.java", "final class Parser { void parse() { Helper.quiet(); } }");
        String helper = "final class Helper {\n static final int LABEL = T.COMMON_CANCEL;\n static void used() { inner(); }\n"
                + " static void quiet() {}\n static String label() { return T.appName(); }\n private static void inner() { %s }\n}";
        sources.put("Helper.java", String.format(helper, ""));
        List<String> clean = shellProcess(sources, new TreeSet<>());
        check(clean.isEmpty(), "self-test: T ids and accessors the shell process never calls are allowed: " + clean);
        sources.put("Helper.java", String.format(helper, "String s = T.commonOk();"));
        List<String> called = shellProcess(sources, new TreeSet<>());
        check(called.size() == 1 && called.get(0).contains("Helper#inner"), "self-test: a T call the shell process runs is found: " + called);
        sources.put("Parser.java", "final class Parser { void parse() { Helper.quiet(); } Msg later() { return M.commonOk(); } }");
        sources.put("Helper.java", String.format(helper, "") + "\nfinal class Elsewhere { void f() { AppLanguage.wrap(null); } }");
        List<String> runtime = shellProcess(sources, new TreeSet<>());
        check(runtime.size() == 1 && runtime.get(0).contains("Helper, which names AppLanguage"),
                "self-test: a loadable class naming AppLanguage is found, an uncalled Msg method is not: " + runtime);
        // The real sources: ScooterCommands may hold T ids and app-only accessors (plan B4) ...
        Map<String, String> real = new LinkedHashMap<>(L10nFiles.appSources());
        String commands = real.get("ScooterCommands.java");
        int body = commands.lastIndexOf('}');
        real.put("ScooterCommands.java", commands.substring(0, body)
                + "    static final int L10N_PROBE = T.COMMON_CANCEL;\n    static String groupLabelFor(String group) { return T.appName(); }\n}\n");
        real.put("ScooterCommands.java", real.get("ScooterCommands.java").replace(
                "throw new IllegalArgumentException(message);", "throw new UserFacingException(M.commonCancel());"));
        check(real.get("ScooterCommands.java").contains("new UserFacingException("), "self-test: B4's require() is planted");
        List<String> shared = shellProcess(real, new TreeSet<>());
        check(shared.isEmpty(), "self-test: app-only catalog code and B4's UserFacingException require() in ScooterCommands are allowed: " + shared);
        // ... but a catalog call in Protocol.fromHex, which OwnerIdentityParser runs in the shell process, is not.
        String protocol = real.get("Protocol.java");
        int fromHex = protocol.indexOf('{', protocol.indexOf("static byte[] fromHex("));
        real.put("Protocol.java", protocol.substring(0, fromHex + 1) + " T.appName(); " + protocol.substring(fromHex + 1));
        List<String> parsed = shellProcess(real, new TreeSet<>());
        check(parsed.size() == 1 && parsed.get(0).contains("Protocol#fromHex"), "self-test: a catalog call the shell process reaches is found: " + parsed);
    }

    private static String where(String name, String code, int index) { return name + ":" + L10nFiles.line(code, index); }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
