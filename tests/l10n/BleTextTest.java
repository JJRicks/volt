package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B7's own checks (the connection controller). BleController is Android-only, so its display text is
 * reached through what a plain JVM can run: ConnectionText (the notices), LockTapQueue, ProximityFollowUp
 * (which still builds a dropped connection's English notice) and BleController's source.
 *
 * - The ble_drop_* templates print exactly ProximityFollowUp.dropNotice's English, for every reason.
 * - ConnectionText.notice turns that English into the catalog message that prints it, so message()
 *   (its English) is what it was, while the shown text follows the installed language.
 * - The shared ids and the result tokens BleController keeps as English equal the catalog English that
 *   displays them.
 * - LockTapQueue: takeFailure() stays English; takeFailureMessage() and failureKind() follow the source.
 */
public final class BleTextTest {
    private static int checks;

    /** A source that wraps every English text, so a rendered message shows it came from the catalog. */
    private static final T.Source MARKED = new T.Source() {
        @Override public String string(int id) { return "⟦" + EnglishText.INSTANCE.string(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + EnglishText.INSTANCE.plural(id, quantity) + "⟧"; }
        @Override public String language() { return "en"; }
    };

    public static void main(String[] args) throws Exception {
        try {
            drops();
            sharedIds();
            results();
            lockQueue();
        } finally {
            T.install(null);
        }
        System.out.println("BleTextTest: " + checks + " checks passed (drop templates, shared ids, result tokens, lock tap reasons)");
    }

    private static Msg[] reasons() {
        return new Msg[]{M.bleFailedConnection(), M.bleFailedTimedOut(), M.bleBluetoothOff(), M.bleFailedConnectionLost(),
                M.bleFailedUnsupportedScooter(), M.bleFailedSend(), M.bleFailedOwnerCheck(), M.bleFailedUnsupportedFirmware(),
                M.bleOwnerRejected(), M.bleFailedUnrecognised(), M.bleFailedNotSaved(), M.bleFailedOtherSaved("Ollie's UT5"),
                M.bleFailedSignalLost(), M.bleFailedNotConfirmed()};
    }

    /** The dropNotice forms, as (pending, key, sent, guided, reconnecting, nativeDrop, retrying). */
    private static final Object[][] FORMS = {
            {false, null, false, false, false, false, false}, {false, null, false, false, false, true, false},
            {false, null, false, false, false, false, true}, {true, "lock", false, false, false, false, true},
            {true, "lock", true, false, false, false, false}, {true, "proximity_key", false, false, true, false, true},
            {true, "proximity_key", true, true, true, false, true}, {true, "proximity_key", true, false, false, false, false},
            {true, "proximity_key", true, false, true, false, true}, {true, "proximity_key", true, false, true, true, true}};

    private static void drops() {
        for (Msg reason : reasons()) {
            for (Object[] f : FORMS) {
                String english = ProximityFollowUp.dropNotice(reason.english(), (Boolean) f[0], (String) f[1], (Boolean) f[2],
                        (Boolean) f[3], (Boolean) f[4], (Boolean) f[5], (Boolean) f[6]);
                Msg shown = ConnectionText.drop(reason, english);
                check(shown != null, "a drop template prints " + english);
                equal(english, shown.english(), "the template's English is dropNotice's");
                CharSequence notice = ConnectionText.notice(reason, english);
                check(notice instanceof Msg, "the notice is shown from the catalog: " + english);
                equal(english, ConnectionText.english(notice), "message() keeps " + english);
                T.install(MARKED);
                check(!notice.toString().equals(english) && notice.toString().contains("⟦"), "the shown notice follows the source: " + notice);
                equal(english, ConnectionText.english(notice), "and its English stays " + english);
                T.install(null);
                equal(english, notice.toString(), "in English it renders " + english);
            }
        }
        // Reasons that still reach a notice as text: pump()'s refused write and the shared ids.
        for (String english : new String[]{"Send failed", "Send failed · retrying", "Send failed · change cancelled", "Bluetooth off · retrying",
                "Owner identity rejected", "No scooter found", "Nearby devices permission needed", "No scooter set up",
                "Choose a whole hour within 48 hours"}) {
            CharSequence notice = ConnectionText.notice(null, english);
            check(notice instanceof Msg, "text with a known English is shown from the catalog: " + english);
            equal(english, ConnectionText.english(notice), "and keeps its English: " + english);
        }
        // Anything else (CapabilityResolver's reasons, exception text) is shown as the English it is.
        for (String english : new String[]{"Stop the scooter first, then try again", "Connection failed (133)", "", "Signal lost · something"}) {
            CharSequence notice = ConnectionText.notice(null, english);
            check(notice instanceof String && english.equals(notice), "unknown text stays as it is: " + english);
        }
        Msg own = M.bleStatusConnected();
        check(ConnectionText.notice(M.bleFailedSend(), own) == own, "a message is shown as it is");
        equal(null, ConnectionText.notice(null, null), "no text stays none");
        equal(null, ConnectionText.english(null), "no English for none");
    }

    private static void sharedIds() throws Exception {
        String ble = source();
        String[][] ids = {{"NEARBY_NEEDED", "ble_nearby_needed"}, {"BLUETOOTH_OFF", "ble_bluetooth_off"}, {"NO_PROFILE", "common_no_scooter"},
                {"NOTHING_FOUND", "ble_nothing_found"}, {"OWNER_REJECTED", "ble_owner_rejected"}, {"LIMITED_MODEL", "ble_limited_model"},
                {"WHOLE_HOUR", "ble_whole_hour"}};
        for (String[] id : ids) equal(constant(ble, id[0]), english(id[1]), "BleController." + id[0] + " is the English of " + id[1]);
    }

    private static void results() throws Exception {
        String ble = source();
        String[][] tokens = {{"RESULT_CONFIRMED", "ble_result_confirmed"}, {"RESULT_SETTING_CONFIRMED", "ble_result_setting_confirmed"},
                {"RESULT_CLOCK_ACCEPTED", "ble_result_clock_accepted"}, {"RESULT_ACCEPTED_UNREADABLE", "ble_result_accepted_unreadable"},
                {"RESULT_NOT_CONFIRMED", "ble_result_not_confirmed"}, {"RESULT_CANCELLED", "ble_result_cancelled"},
                {"RESULT_GUIDED_TEST", "ble_result_guided_test"}};
        for (String[] token : tokens) equal(constant(ble, token[0]), english(token[1]), "BleController." + token[0] + " is the English of " + token[1]);
        // REJECTED keeps the scooter's code: "Rejected (" + code + ")".
        String start = constant(ble, "RESULT_REJECTED");
        for (int code : new int[]{1, 7, 255}) equal(start + code + ")", M.bleResultRejected(String.valueOf(code)).english(), "a rejection with code " + code);
        // ProximityFollowUp's own "Cancelled before sending" is the same token.
        equal(constant(ble, "RESULT_CANCELLED"), ProximityFollowUp.closedResult("lock", false, false, false, false), "a closed, unsent change's token");
    }

    private static void lockQueue() {
        LockTapQueue q = new LockTapQueue();
        q.request(false, true, 0);
        q.step(10, false, false, false, LockTapQueue.Gate.OPEN);
        equal(LockTapQueue.Failure.CONNECTION_LOST, q.failureKind(), "a lost connection's kind");
        equal(LockTapQueue.Failure.CONNECTION_LOST, q.failureKind(), "the kind does not take the reason");
        T.install(MARKED);
        equal("Connection lost before the lock changed", q.takeFailure(), "takeFailure() stays English in any language");
        equal(null, q.failureKind(), "taken: nothing to say");

        q = new LockTapQueue();
        q.request(false, true, 0);
        q.step(10, true, false, false, LockTapQueue.Gate.OPEN);
        q.sent();
        q.step(20, true, false, false, LockTapQueue.Gate.OPEN);
        equal(LockTapQueue.Failure.NOT_CONFIRMED, q.failureKind(), "an unconfirmed change's kind");
        CharSequence shown = q.takeFailureMessage();
        equal("⟦The scooter didn't confirm the lock change⟧", shown.toString(), "the shown reason follows the source");
        equal("The scooter didn't confirm the lock change", ConnectionText.english(shown), "and keeps its English");
        equal(null, q.takeFailure(), "takeFailureMessage() takes the reason too");
        T.install(null);

        q = new LockTapQueue();
        q.request(true, false, 0);
        q.step(LockTapQueue.HOLD_MS + 1, true, true, false, LockTapQueue.Gate.WAIT);
        equal(LockTapQueue.Failure.NOT_READY, q.failureKind(), "an expired hold's kind");
        equal("The scooter wasn't ready to change the lock; try again", q.takeFailureMessage().toString(), "in English it renders the English");
        equal(null, q.takeFailureMessage(), "and is taken once");

        q = new LockTapQueue();
        q.request(false, true, 0);
        q.reject("Stop the scooter first");
        equal(LockTapQueue.Failure.REFUSED, q.failureKind(), "a hard refusal's kind");
        equal("Stop the scooter first", q.takeFailureMessage(), "a refusal's reason is shown as given");
        equal(null, q.failureKind(), "nothing waiting: no kind");
    }

    private static String source() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/BleController.java")), StandardCharsets.UTF_8);
    }

    /** The value of a String constant declared NAME="..." in source (a plain literal, no escapes). */
    private static String constant(String source, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"\\\\]*)\"").matcher(source);
        check(m.find(), "BleController declares " + name);
        String value = m.group(1);
        check(!m.find(), name + " is declared once");
        return value;
    }

    private static String english(String key) {
        for (int id = 0; id < T.size(); id++) if (T.key(id).equals(key)) return EnglishText.INSTANCE.string(id);
        throw new AssertionError("no catalog key " + key);
    }

    private static void equal(Object expected, Object actual, String what) {
        boolean same = expected == null ? actual == null : actual != null && expected.toString().equals(actual.toString());
        check(same, what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }
}
