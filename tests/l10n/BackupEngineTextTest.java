package com.jjricks.volt;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * B10's own suite: the backup engine's text in another language.
 *   - With English installed, each review message reads exactly as its English line.
 *   - Under a source that answers every id with its marked key, the messages switch and the English
 *     lines (what logs, tests and approval checks read) do not; a plan reviewed in one language is
 *     still the same plan in another, so a language change never voids an approval.
 *   - BackupException keeps English in getMessage() whatever is installed, shows the catalog text in
 *     msg(), and carries CHANGED and NO_SPACE as codes; a failure that is not an IOException is wrapped
 *     in one, and an IOException passes through as it was.
 *   - Each category label's catalog text is LABELS' English.
 */
public final class BackupEngineTextTest {
    private static int checks;
    private static final String A = BackupTestStore.A, B = BackupTestStore.B, C = BackupArchiveTest.C;

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static Set<String> set(String... values) { return new LinkedHashSet<>(Arrays.asList(values)); }
    private interface Action { void run() throws Exception; }

    private static IOException refusal(Action action) throws Exception {
        try { action.run(); } catch (IOException refused) { return refused; }
        throw new AssertionError("expected a refusal");
    }

    /** Every id answered with its key in marks, so a moved text is told from English. */
    private static final T.Source KEYS = new T.Source() {
        public String string(int id) { return T.isPlural(id) ? null : "‹" + T.key(id) + "›"; }
        public String plural(int id, long quantity) { return T.isPlural(id) ? "‹" + T.key(id) + "#" + quantity + "›" : null; }
        public String language() { return "de"; }
    };

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("volt-backup-text-").toFile();
        try {
            T.install(null);
            BackupTestStore source = BackupTestStore.rich(new File(root, "source"));
            byte[] complete = source.export(BackupTestStore.all());
            reviews(root, complete);
            refusals(root, complete);
            labels();
            System.out.println("BackupEngineTextTest: " + checks + " checks passed (review messages and lines, approvals across languages, BackupException codes and English, category labels)");
        } finally {
            T.install(null);
            AppDataArchive.deleteTree(root);
        }
    }

    private static void reviews(File root, byte[] complete) throws Exception {
        BackupTestStore phone = BackupArchiveTest.phone(new File(root, "review"));
        Map<String, String> chosen = new LinkedHashMap<>(); chosen.put(A, C); chosen.put(B, BackupArchive.SKIP);
        BackupArchive.Prepared prepared = phone.stage(complete);
        BackupArchive.Plan english = BackupArchive.plan(phone, prepared, BackupTestStore.all(), false, chosen);
        check(english.messages.size() == english.lines.size(), "one message per line");
        List<String> shown = new ArrayList<>(); for (CharSequence message : english.messages) shown.add(message.toString());
        check(shown.equals(english.lines), "in English the messages read as the lines: " + shown);
        int moved = 0; for (CharSequence message : english.messages) if (message instanceof Msg) moved++;
        check(moved >= 10, "the review's moved lines are messages: " + moved);

        T.install(KEYS);
        try {
            List<String> foreign = new ArrayList<>(); for (CharSequence message : english.messages) foreign.add(message.toString());
            check(foreign.contains("‹backup_engine_plan_scooters›") && foreign.contains("‹backup_engine_plan_scooters_skipped#1›")
                    && foreign.contains("‹backup_engine_plan_rides›") && foreign.contains("‹backup_engine_plan_charges›")
                    && foreign.contains("‹backup_engine_plan_history_adds#1›") && foreign.contains("‹backup_engine_plan_online_map›"),
                    "the messages switch with the installed text: " + foreign);
            check(foreign.contains("Dashboards: replaces layouts for Phone UT5"), "a line not moved yet stays English: " + foreign);
            BackupArchive.Plan again = BackupArchive.plan(phone, prepared, BackupTestStore.all(), false, chosen);
            check(again.lines.equals(english.lines), "the English lines do not follow the installed text: " + again.lines);
            check(again.same(english) && english.same(again), "a review approved in one language is the same plan in another");
        } finally { T.install(null); }
        BackupArchive.discard(phone, prepared);
    }

    private static void refusals(File root, byte[] complete) throws Exception {
        T.install(KEYS);
        try {
            BackupTestStore phone = BackupArchiveTest.phone(new File(root, "refusals"));
            IOException unknown = refusal(() -> BackupArchive.stage(phone, new ByteArrayInputStream("not a backup".getBytes(StandardCharsets.UTF_8)), null));
            check(unknown instanceof BackupException && "Choose a Volt backup file.".equals(unknown.getMessage()), "getMessage() stays English: " + unknown.getMessage());
            check(((BackupException) unknown).code() == BackupException.Code.FAILED, "an ordinary refusal is FAILED");
            check("‹backup_engine_choose_volt_backup_file›".equals(((BackupException) unknown).msg().toString()), "msg() follows the installed text");
            IOException json = refusal(() -> BackupJson.parse("{\"a\":1,\"a\":2}", 8));
            check(json instanceof BackupException && "Duplicate JSON key.".equals(json.getMessage()), "a parser error keeps its own English");

            BackupArchive.Prepared prepared = phone.stage(complete);
            BackupArchive.Plan plan = phone.plan(prepared, set(BackupRegistry.PREFERENCES), false, null);
            phone.usable = 0;
            IOException space = refusal(() -> BackupTransaction.apply(phone, plan));
            check(space instanceof BackupException && ((BackupException) space).code() == BackupException.Code.NO_SPACE && "Not enough free storage".equals(space.getMessage()),
                    "no free space is NO_SPACE, with today's English");
            phone.usable = Long.MAX_VALUE;
            phone.put("app_settings", "dashboard_columns", 3);
            IOException changed = refusal(() -> BackupTransaction.apply(phone, plan));
            check(changed instanceof BackupException && ((BackupException) changed).code() == BackupException.Code.CHANGED && "Saved data changed. Review again.".equals(changed.getMessage()),
                    "changed data is CHANGED, with today's English");
            BackupArchive.discard(phone, prepared);

            BackupTestStore failing = BackupArchiveTest.phone(new File(root, "failing"));
            BackupArchive.Prepared staged = failing.stage(complete);
            BackupArchive.Plan approved = failing.plan(staged, set(BackupRegistry.PREFERENCES), false, null);
            BackupArchive.Storage throwing = failAt(failing, "preferences", new IllegalStateException("injected"));
            IOException wrapped = refusal(() -> BackupTransaction.apply(throwing, approved));
            check(wrapped instanceof BackupException && "The restore failed; your data was kept.".equals(wrapped.getMessage())
                    && wrapped.getCause() instanceof IllegalStateException && ((BackupException) wrapped).code() == BackupException.Code.FAILED,
                    "a failure that is not an IOException is wrapped, with English getMessage(): " + wrapped);
            check(!BackupTransaction.needsRecovery(failing), "the wrapped failure rolled back");
            BackupArchive.Plan retry = failing.plan(failing.stage(complete), set(BackupRegistry.PREFERENCES), false, null);
            IOException plain = new IOException("plain");
            IOException passed = refusal(() -> BackupTransaction.apply(failAt(failing, "preferences", plain), retry));
            check(passed == plain, "an IOException passes through unwrapped");
        } finally { T.install(null); }
    }

    /** The store, with one transaction step failing with the given exception. */
    private static BackupArchive.Storage failAt(BackupTestStore store, String boundary, Exception failure) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("step") && boundary.equals(args[0])) throw failure;
            try { return method.invoke(store, args); } catch (InvocationTargetException thrown) { throw thrown.getCause(); }
        };
        return (BackupArchive.Storage) Proxy.newProxyInstance(BackupEngineTextTest.class.getClassLoader(), new Class<?>[]{BackupArchive.Storage.class}, handler);
    }

    private static void labels() {
        for (String category : BackupRegistry.CATEGORIES) {
            check(BackupRegistry.labelMsg(category).english().equals(BackupRegistry.label(category)), category + ": the catalog label is LABELS' English");
            check(BackupRegistry.known(category), category + " is a backup category");
        }
        check(BackupRegistry.labelMsg("unknown-id").english().equals("unknown-id") && BackupRegistry.label("unknown-id").equals("unknown-id"), "an unknown id is shown as it is");
        T.install(KEYS);
        try {
            check(BackupRegistry.labelMsg(BackupRegistry.RIDES).toString().equals("‹backup_engine_category_rides›"), "a label follows the installed text");
            check(BackupRegistry.label(BackupRegistry.RIDES).equals("Rides"), "LABELS stays English");
        } finally { T.install(null); }
    }
}
