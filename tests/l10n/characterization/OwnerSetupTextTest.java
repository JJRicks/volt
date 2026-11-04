package com.jjricks.volt;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import rikka.shizuku.Shizuku;

/**
 * G5 characterization for B6 (owner setup and Shizuku), committed before the batch changes anything.
 *
 * ShizukuSetupActivity needs Android, so a plain JVM test cannot draw it; G2 (LedgerTest) proves its
 * new templates, plural and choice render the original concatenations, and G7's my-scooters steps
 * census its screens. What this pins is the English the screens show that comes from pure code:
 *   - every message OwnerIdentityParser.parse refuses a setup file with, and that each refusal is an
 *     IllegalArgumentException (the import screens catch exactly that);
 *   - that OwnerCaptureEngine.freshOwner passes the parser's conflict on and drops other refusals;
 *   - every status line ShizukuCaptureClient sets that a test without a phone can reach, byte for byte,
 *     through the same test doubles ShizukuCaptureClientTest uses (the Shizuku stub always answers, so
 *     the "not running" lines and the permission refusal are not reachable here).
 */
public final class OwnerSetupTextTest {
    private static int checks;
    private static final String CONFLICT = "This file contains conflicting owner identities or roles. "
            + "Capture one NAVEE account connection or import one private setup backup.";

    public static void main(String[] args) throws Exception {
        parserRefusals();
        engineConflicts();
        clientStatusLines();
        System.out.println("OwnerSetupTextTest: " + checks + " checks passed");
    }

    // OwnerIdentityParser

    private static void parserRefusals() {
        refused(null, "Setup file is empty or too large.");
        refused("", "Setup file is empty or too large.");
        refused("   \n ", "Setup file is empty or too large.");
        StringBuilder big = new StringBuilder();
        while (big.length() <= 1048576) big.append("0123456789abcdef");
        refused(big.toString(), "Setup file is empty or too large.");
        refused("identity: zz", "Need 12 hex characters");
        refused("{\"owner_identity\": \"0102\"}", "Need 12 hex characters");
        refused("hello", "No owner identity in file");
        refused("Send authData: 55 aa 00", "No owner identity in file");
        refused("identity: 010203040506\nrole: 2", "Unsupported owner role");
        refused("identity: 010203040506\nidentity: 0A0B0C0D0E0F", CONFLICT);
        refused("identity: 010203040506\nrole: 0\nrole: 1", CONFLICT);
        refused("identity: 010203040506\nrole: 0\nShared-user option: ON", CONFLICT);
        refused("Send authData: " + frame("010203040506", 0) + "\nSend authData: " + frame("0A0B0C0D0E0F", 0), CONFLICT);
        refused("Send authData: " + frame("010203040506", 0) + "\nSend authData: " + frame("010203040506", 1), CONFLICT);
        OwnerIdentityParser.Result one = OwnerIdentityParser.parse("Send authData: " + frame("010203040506", 1));
        check("010203040506".equals(one.identity) && one.role == 1, "a single checksum-valid frame still parses");
    }

    private static void refused(String input, String message) {
        try {
            OwnerIdentityParser.parse(input);
            throw new AssertionError("parser accepted " + shortened(input));
        } catch (IllegalArgumentException refused) {
            check(message.equals(refused.getMessage()), "parser refusal of " + shortened(input) + ": " + refused.getMessage());
        }
    }

    private static String shortened(String input) {
        return input == null ? "null" : input.length() > 60 ? input.substring(0, 60) + "..." : input;
    }

    /** A checksum-valid owner-auth frame: 55 aa 00 30 09 00 role id(6) 00 sum fe fd. */
    static String frame(String identity, int role) {
        int[] p = new int[17];
        p[0] = 0x55; p[1] = 0xaa; p[2] = 0; p[3] = 0x30; p[4] = 9; p[5] = 0; p[6] = role;
        for (int i = 0; i < 6; i++) p[7 + i] = Integer.parseInt(identity.substring(i * 2, i * 2 + 2), 16);
        p[13] = 0;
        int sum = 0;
        for (int i = 0; i < 14; i++) sum += p[i];
        p[14] = sum & 255; p[15] = 0xfe; p[16] = 0xfd;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 17; i++) out.append(i == 0 ? "" : " ").append(String.format("%02x", p[i]));
        return out.toString();
    }

    // OwnerCaptureEngine

    private static void engineConflicts() {
        String since = "1800000000.000";
        String rows = "1800000001.000 Send authData: " + frame("010203040506", 0) + "\n"
                + "1800000002.000 Send authData: " + frame("0A0B0C0D0E0F", 0) + "\n";
        try {
            OwnerCaptureEngine.freshOwner(rows, since);
            throw new AssertionError("engine accepted two owners in one read");
        } catch (IllegalArgumentException conflict) {
            check(CONFLICT.equals(conflict.getMessage()), "engine passes the parser's conflict on: " + conflict.getMessage());
        }
        check(OwnerCaptureEngine.freshOwner("1800000001.000 nothing here\n", since) == null, "no frame is nothing found");
        OwnerIdentityParser.Result owner = OwnerCaptureEngine.freshOwner("1800000001.000 Send authData: " + frame("010203040506", 1) + "\n", since);
        check(owner != null && "010203040506".equals(owner.identity) && owner.role == 1, "one fresh frame is the owner");
    }

    // ShizukuCaptureClient

    private static void clientStatusLines() throws Exception {
        completed(OwnerCaptureEngine.TIMED_OUT, true, null, "Nothing found in 90 s");
        completed(OwnerCaptureEngine.CONFLICT, true, null, "Several scooters. Connect one.");
        completed(OwnerCaptureEngine.BUSY, true, null, "Another capture is running");
        completed(OwnerCaptureEngine.UNREADABLE, true, "010203040506", "Volt couldn't read NAVEE's log");
        completed(OwnerCaptureEngine.CANCELLED, true, null, "Stopped");
        completed(OwnerCaptureEngine.FAILED, true, null, "Capture failed");
        completed(OwnerCaptureEngine.OK, false, "010203040506", "Logging cleanup unconfirmed");
        completed(OwnerCaptureEngine.OK, true, "010203040506", "");

        // A clean result whose journal cannot be cleared.
        Fixture stuck = new Fixture();
        stuck.client.start(false);
        Remote remote = stuck.connect();
        stuck.preferences.failCommits = true;
        remote.callback.completed(remote.token, OwnerCaptureEngine.OK, true, "010203040506", 0);
        Handler.drain();
        status(stuck, "Restore logging again", "clean result whose journal stays");

        Fixture early = new Fixture();
        early.client.start(false);
        early.client.cancel();
        status(early, "Stopped", "cancel before the helper answers");

        Fixture late = new Fixture();
        late.client.start(false);
        late.connect();
        late.client.cancel();
        status(late, "Restoring NAVEE…", "cancel after the helper started");

        Fixture slow = new Fixture();
        slow.client.start(false);
        Handler.advanceBy(15000);
        status(slow, "Helper didn't start", "helper that never connects");

        Fixture dropped = new Fixture();
        dropped.client.start(false);
        dropped.binding().onServiceDisconnected(null);
        status(dropped, "Shizuku stopped", "helper lost before dispatch");

        Fixture lost = new Fixture();
        lost.client.start(false);
        Remote started = lost.connect();
        check(started.callback.prepareCleanup(started.token, ""), "capture journals before reading");
        lost.binding().onServiceDisconnected(null);
        status(lost, "Shizuku lost. Restore logging.", "helper lost with cleanup pending");

        Fixture unknown = new Fixture();
        unknown.preferences.edit().putBoolean("pending", true).putString("token", "not-a-token").commit();
        unknown.client.start(true);
        status(unknown, "Cleanup unknown. Use computer recovery.", "recovery without a valid record");

        Fixture refused = new Fixture();
        refused.preferences.failCommits = true;
        refused.client.start(false);
        status(refused, "Couldn't start. Nothing changed.", "start whose reservation is not saved");

        Fixture idle = new Fixture();
        check("".equals(idle.client.unavailableReason()), "available Shizuku has no reason");
        idle.client.requestPermission();
        status(idle, "", "permission request shows no words of its own");
    }

    private static void completed(int outcome, boolean clean, String identity, String expected) throws Exception {
        Fixture f = new Fixture();
        f.client.start(false);
        Remote remote = f.connect();
        remote.callback.completed(remote.token, outcome, clean, identity, 0);
        Handler.drain();
        status(f, expected, "completed outcome " + outcome + (clean ? "" : ", unclean"));
    }

    private static void status(Fixture f, String expected, String context) {
        check(expected.equals(f.client.message), context + ": " + f.client.message);
    }

    private static final class Fixture {
        final Prefs preferences = new Prefs();
        final ShizukuCaptureClient client;
        Fixture() throws Exception {
            Handler.reset(); Shizuku.reset();
            Constructor<ShizukuCaptureClient> constructor = ShizukuCaptureClient.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            client = constructor.newInstance(new Context() {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
            });
        }
        ServiceConnection binding() { return Shizuku.bindings.get(Shizuku.bindings.size() - 1); }
        Remote connect() throws Exception {
            Remote remote = new Remote();
            binding().onServiceConnected((ComponentName) null, remote);
            return remote;
        }
    }

    private static final class Remote extends IOwnerCaptureService.Stub {
        String token;
        IOwnerCaptureCallback callback;
        @Override public void linkToDeath(IBinder.DeathRecipient recipient, int flags) { }
        @Override public void start(String session, IOwnerCaptureCallback cb) { token = session; callback = cb; }
        @Override public void recover(String session, String original, IOwnerCaptureCallback cb) { start(session, cb); }
        @Override public void cancel(String session) { }
        @Override public void destroy() { }
    }

    private static final class Prefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        boolean failCommits;
        public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key); return value == null ? fallback : (Boolean) value;
        }
        public String getString(String key, String fallback) {
            Object value = values.get(key); return value == null ? fallback : (String) value;
        }
        public Editor edit() {
            return new Editor() {
                final Map<String, Object> writes = new HashMap<>();
                boolean clear;
                public Editor putBoolean(String key, boolean value) { writes.put(key, value); return this; }
                public Editor putString(String key, String value) { writes.put(key, value); return this; }
                public Editor remove(String key) { writes.put(key, null); return this; }
                public Editor clear() { clear = true; return this; }
                public boolean commit() {
                    if (failCommits) return false;
                    if (clear) values.clear();
                    for (Map.Entry<String, Object> write : writes.entrySet()) {
                        if (write.getValue() == null) values.remove(write.getKey());
                        else values.put(write.getKey(), write.getValue());
                    }
                    return true;
                }
            };
        }
    }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
