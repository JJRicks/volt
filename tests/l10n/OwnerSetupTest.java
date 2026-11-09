package com.jjricks.volt;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B6's own suite (owner setup and Shizuku). The capture client's status lines and the setup refusals keep
 * their English in the code (tests, logs and the Shizuku shell process read it), and the screens show
 * catalog text in its place. This checks that pairing for every value:
 *   - every ShizukuStatus: its English is its key's English, so the field and the screen agree in English,
 *     and with another language installed each status shows its own key;
 *   - text that is no status ("" and anything else) passes through ShizukuStatus.display unchanged;
 *   - every String constant of ShizukuCaptureClient has its ShizukuStatus, and nothing but "", those
 *     constants, the setup screen's NO_BROWSER (shown through its own key) or unavailableReason() is
 *     assigned to message, so no status line can bypass the catalog unseen;
 *   - SetupException keeps its English getMessage() whatever language is installed, and shows its Msg;
 *   - every OwnerIdentityParser refusal shows its own key through SetupException.shown, and only conflicts
 *     are ConflictingIdentityExceptions.
 * Runs from the repository root (it reads two sources).
 */
public final class OwnerSetupTest {
    private static int checks;

    public static void main(String[] args) throws IOException {
        try {
            statuses();
            assignments();
            setupExceptions();
            parserRefusals();
        } finally {
            T.install(null);
        }
        System.out.println("OwnerSetupTest: " + checks + " checks passed");
    }

    /** Answers every id with its key, as a language would answer with its own text. */
    private static T.Source keys() {
        return new T.Source() {
            @Override public String string(int id) { return T.key(id); }
            @Override public String plural(int id, long quantity) { return T.key(id); }
            @Override public String language() { return "es"; }
        };
    }

    private static void statuses() {
        Set<String> english = new HashSet<>(), keys = new HashSet<>();
        for (ShizukuStatus status : ShizukuStatus.values()) {
            T.install(null);
            check(status.english.equals(T.render(status.text)), status + ": English differs from its key's: " + T.render(status.text));
            check(status.english.equals(ShizukuStatus.display(status.english)), status + ": shown English differs");
            check(status.english.equals(status.msg().english()), status + ": Msg English differs");
            check(ShizukuStatus.of(status.english) == status, status + ": not found by its English");
            check(english.add(status.english), status + ": English shared with another status");
            String key = T.key(status.text);
            check(key.startsWith("owner_status_") && keys.add(key), status + ": key " + key);
            T.install(keys());
            check(key.equals(ShizukuStatus.display(status.english)), status + ": shows " + ShizukuStatus.display(status.english));
            check(key.equals(status.msg().render()), status + ": Msg renders " + status.msg().render());
            check(status.english.equals(status.msg().english()), status + ": Msg English follows the language");
        }
        for (String other : new String[]{"", "Something else", "stopped"}) {
            check(other.equals(ShizukuStatus.display(other)), "passes through: " + other);
            check(ShizukuStatus.of(other) == null, "no status for: " + other);
        }
        check(ShizukuStatus.display(null) == null, "null passes through");
        T.install(null);
    }

    private static final Pattern ASSIGNMENT = Pattern.compile("\\bmessage\\s*=(?!=)([^;]*);");
    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern CONSTANT = Pattern.compile("(?<![\\w.])([A-Z][A-Z0-9_]+)\\b");
    private static final Pattern NO_BROWSER = Pattern.compile("static final String NO_BROWSER = (\"[^\"]*\");");

    private static void assignments() throws IOException {
        // Every String constant of the client is a status line with a ShizukuStatus.
        int constants = 0;
        for (Field field : ShizukuCaptureClient.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != String.class || !Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) continue;
            constants++;
            field.setAccessible(true);
            try {
                ShizukuStatus status = ShizukuStatus.of((String) field.get(null));
                check(status != null && status.name().equals(field.getName()), field.getName() + " has its ShizukuStatus");
            } catch (IllegalAccessException e) { throw new AssertionError(e); }
        }
        check(constants == ShizukuStatus.values().length, "one ShizukuStatus per client constant: " + constants);
        // Only "", those constants, the setup screen's NO_BROWSER or unavailableReason() are assigned to message.
        int found = 0;
        for (String file : new String[]{"ShizukuCaptureClient.java", "ShizukuSetupActivity.java"}) {
            String code = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt", file)), StandardCharsets.UTF_8);
            Matcher assignment = ASSIGNMENT.matcher(code);
            while (assignment.find()) {
                found++;
                String value = assignment.group(1);
                Matcher literal = LITERAL.matcher(value);
                while (literal.find()) check("\"\"".equals(literal.group()), file + ": status line assigned as a literal: " + value.trim());
                Matcher constant = CONSTANT.matcher(value);
                while (constant.find()) {
                    String name = constant.group(1);
                    check(name.equals("NO_BROWSER") && file.startsWith("ShizukuSetup") || isStatus(name), file + ": message = " + value.trim());
                }
            }
            if (file.startsWith("ShizukuSetup")) {
                Matcher browser = NO_BROWSER.matcher(code);
                check(browser.find(), "the setup screen declares NO_BROWSER");
                check(browser.group(1).equals("\"" + T.render(T.OWNER_STATUS_NO_BROWSER) + "\""), "NO_BROWSER is owner_status_no_browser's English");
                check(code.contains("NO_BROWSER.equals(message) ? T.ownerStatusNoBrowser() : ShizukuStatus.display(message)"),
                        "the setup screen shows NO_BROWSER through its key");
            }
        }
        check(found >= 20, "found the status assignments: " + found);
    }

    private static boolean isStatus(String name) {
        for (ShizukuStatus status : ShizukuStatus.values()) if (status.name().equals(name)) return true;
        return false;
    }

    private static void setupExceptions() {
        for (Msg msg : new Msg[]{M.ownerSetupImportFailed(), M.ownerSetupEnterIdentity()}) {
            T.install(keys());
            SetupException refused = new SetupException(msg);
            check(refused instanceof IllegalArgumentException, "a SetupException is an IllegalArgumentException");
            check(msg.english().equals(refused.getMessage()), "getMessage stays English: " + refused.getMessage());
            check(refused.msg() == msg && T.key(msg.id()).equals(SetupException.shown(refused).toString()), "shows its Msg");
            T.install(null);
            check(T.render(msg.id()).equals(refused.getMessage()), "English getMessage is the key's English");
        }
        check("Import failed. Pick file again.".equals(new SetupException(M.ownerSetupImportFailed()).getMessage()), "import refusal English");
        check("Enter a 12-character owner key.".equals(new SetupException(M.ownerSetupEnterIdentity()).getMessage()), "identity refusal English");
        check("Owner import profile already exists".equals(SetupException.shown(new IllegalStateException("Owner import profile already exists")).toString()),
                "other errors pass through");
        check("".equals(SetupException.shown(new IllegalArgumentException()).toString()), "no message shows nothing");
    }

    private static void parserRefusals() {
        refusal("", OwnerIdentityParser.EMPTY_OR_TOO_LARGE, M.ownerSetupEmptyOrLarge(), "owner_setup_empty_or_large", false);
        refusal("identity: zz", OwnerIdentityParser.NEED_HEX, M.ownerSetupNeedHex(), "owner_setup_need_hex", false);
        refusal("hello", OwnerIdentityParser.NO_IDENTITY, M.ownerSetupNoIdentity(), "owner_setup_no_identity", false);
        refusal("identity: 010203040506\nrole: 2", OwnerIdentityParser.UNSUPPORTED_ROLE, M.ownerSetupUnsupportedRole(), "owner_setup_unsupported_role", false);
        refusal("identity: 010203040506\nidentity: 0A0B0C0D0E0F", OwnerIdentityParser.CONFLICTING, M.ownerSetupConflicting(), "owner_setup_conflicting", true);
        refusal("identity: 010203040506\nrole: 0\nrole: 1", OwnerIdentityParser.CONFLICTING, M.ownerSetupConflicting(), "owner_setup_conflicting", true);
    }

    /**
     * english: the parser's getMessage(), which stays as it was (logs and the Shizuku shell read it); shown: the
     * catalog text the screen shows, whose English the owner-approved D3 terms changed (l10n/english-changes.tsv).
     */
    private static void refusal(String input, String english, Msg shownMsg, String key, boolean conflict) {
        for (boolean other : new boolean[]{false, true}) {
            T.install(other ? keys() : null);
            try {
                OwnerIdentityParser.parse(input);
                throw new AssertionError("parser accepted " + input);
            } catch (IllegalArgumentException refused) {
                check(english.equals(refused.getMessage()), "refusal stays English: " + refused.getMessage());
                check(conflict == (refused instanceof OwnerIdentityParser.ConflictingIdentityException), "conflict type for " + input);
                String shown = SetupException.shown(refused).toString();
                check((other ? key : shownMsg.english()).equals(shown), "refusal of " + input + " shows " + shown);
            }
        }
        T.install(null);
    }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
