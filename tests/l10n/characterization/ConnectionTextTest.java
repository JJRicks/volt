package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * G5 characterization for B7 (connection controller), pinned before the batch: the English the
 * connection layer shows and logs, byte for byte, where a plain JVM can reach it.
 *
 * - LockTapQueue: the three reasons a lock tap is put back, a hard refusal passed through as it is,
 *   and that a reason is taken once.
 * - ProximityFollowUp.dropNotice: every form a dropped connection's notice takes, for every reason
 *   BleController passes it (the batch shows these through whole catalog templates).
 * - BleController's shared English ids (compared by MainActivity, OnboardingScreen and the
 *   validations), read from its source: they stay ids, so their declarations must not change.
 */
public final class ConnectionTextTest {
    private static int checks;

    /** Every reason BleController hands to failed(...) (and so to dropNotice) before the batch. */
    static final String[] DROP_REASONS = {
            "Connection failed", "Timed out", "Bluetooth off", "Connection lost", "Unsupported scooter", "Send failed",
            "Owner check failed", "Unsupported firmware", "Owner identity rejected", "Unrecognised scooter",
            "Not the saved scooter", "This is Ollie's UT5. Select it in My scooters, or scan for your new scooter.",
            "Signal lost", "Change not confirmed"};

    public static void main(String[] args) throws Exception {
        lockQueue();
        dropNotices();
        sharedIds();
        System.out.println("ConnectionTextTest: " + checks + " checks passed (lock tap reasons, drop notices, shared connection ids)");
    }

    private static void lockQueue() {
        LockTapQueue q = new LockTapQueue();
        check(q.request(false, true, 0), "a tap toward locked is taken");
        equal(LockTapQueue.Action.REVERT, q.step(10, false, false, false, LockTapQueue.Gate.OPEN), "a lost connection puts it back");
        equal("Connection lost before the lock changed", q.takeFailure(), "lost connection reason");
        equal(null, q.takeFailure(), "a reason is taken once");

        q = new LockTapQueue();
        check(q.request(false, true, 0), "a second tap is taken");
        equal(LockTapQueue.Action.SEND, q.step(10, true, false, false, LockTapQueue.Gate.OPEN), "an open gate sends");
        q.sent();
        equal(LockTapQueue.Action.REVERT, q.step(20, true, false, false, LockTapQueue.Gate.OPEN), "no confirmation puts it back");
        equal("The scooter didn't confirm the lock change", q.takeFailure(), "unconfirmed reason");

        q = new LockTapQueue();
        check(q.request(true, false, 0), "a tap toward unlocked is taken");
        equal(LockTapQueue.Action.NONE, q.step(100, true, true, false, LockTapQueue.Gate.WAIT), "a short hold waits");
        equal(LockTapQueue.Action.REVERT, q.step(LockTapQueue.HOLD_MS + 1, true, true, false, LockTapQueue.Gate.WAIT), "an expired hold puts it back");
        equal("The scooter wasn't ready to change the lock; try again", q.takeFailure(), "not ready reason");

        q = new LockTapQueue();
        check(q.request(false, true, 0), "a tap before a refusal is taken");
        equal(LockTapQueue.Action.REVERT, q.reject("Stop the scooter first"), "a hard refusal puts it back");
        equal("Stop the scooter first", q.takeFailure(), "the gate's own reason passes through unchanged");
        equal(null, q.takeFailure(), "and is taken once");
        equal(LockTapQueue.Action.NONE, q.reject("Stop the scooter first"), "nothing waiting: nothing to refuse");
        equal(null, q.takeFailure(), "and nothing to say");
    }

    private static void dropNotices() {
        for (String reason : DROP_REASONS) {
            equal(reason, ProximityFollowUp.dropNotice(reason, false, null, false, false, false, false, false), "bare: " + reason);
            equal(reason, ProximityFollowUp.dropNotice(reason, false, null, false, false, false, true, false), "bare native: " + reason);
            equal(reason + " · retrying", ProximityFollowUp.dropNotice(reason, false, null, false, false, false, false, true), "retrying: " + reason);
            equal(reason + " · change cancelled", ProximityFollowUp.dropNotice(reason, true, "lock", false, false, false, false, true), "unsent lock: " + reason);
            equal(reason + " · change cancelled", ProximityFollowUp.dropNotice(reason, true, "lock", true, false, false, false, false), "sent lock: " + reason);
            equal(reason + " · change cancelled", ProximityFollowUp.dropNotice(reason, true, "proximity_key", false, false, true, false, true), "unsent proximity: " + reason);
            equal(reason + " · change cancelled", ProximityFollowUp.dropNotice(reason, true, "proximity_key", true, true, true, false, true), "guided proximity: " + reason);
            equal(reason + " · change not confirmed", ProximityFollowUp.dropNotice(reason, true, "proximity_key", true, false, false, false, false), "unchecked proximity: " + reason);
            equal(reason + " · reconnecting to confirm", ProximityFollowUp.dropNotice(reason, true, "proximity_key", true, false, true, false, true), "rechecked proximity: " + reason);
            equal("Scooter may have restarted Bluetooth · reconnecting to confirm",
                    ProximityFollowUp.dropNotice(reason, true, "proximity_key", true, false, true, true, true), "native recheck: " + reason);
        }
    }

    private static void sharedIds() throws Exception {
        String ble = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/BleController.java")), StandardCharsets.UTF_8);
        contains(ble, "public static final String NEARBY_NEEDED=\"Nearby devices permission needed\",BLUETOOTH_OFF=\"Bluetooth off\",NO_PROFILE=\"No scooter set up\",");
        contains(ble, "NOTHING_FOUND=\"No scooter found\",OWNER_REJECTED=\"Owner identity rejected\",LIMITED_MODEL=\"Limited controls on this model\";");
        contains(ble, "static final String WHOLE_HOUR=\"Choose a whole hour within 48 hours\";");
    }

    private static void contains(String source, String text) {
        check(source.contains(text), "BleController still declares " + text);
    }

    private static void equal(Object expected, Object actual, String what) {
        check(expected == null ? actual == null : expected.equals(actual), what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }
}
