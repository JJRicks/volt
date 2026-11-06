package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The plain connection notice out of sight (Background connection on, no live card, alert or recording showing):
 * with status asked for every 15 s it must stay "Connected" with the scooter's name and battery while the link is up,
 * and show the Bluetooth message once the link is really gone. Drives StatusCadence.linked() (what BleController.linked()
 * uses) with a fake monotonic clock, and checks ScooterService picks the notice from linked(). Listed in
 * tests/l10n/connection-notice-background.sources.
 */
public final class ConnectionNoticeBackgroundTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long S = 1000L, T0 = 1_000_000L, STEP = 250L, REPLY = 300L;
    private static final String MESSAGE = "Bluetooth message";

    public static void main(String[] args) throws Exception {
        source();
        backgroundStaysConnected();
        foregroundUnchanged();
        linkDown();
        silentScooter();
        System.out.println("ConnectionNoticeBackgroundTest: " + checks + " checks passed");
    }

    /** ScooterService's title and text, for the plain notice: connected ? Connected / name and battery : Volt / the BLE message. */
    private static final class Notice {
        final String title, text;
        Notice(boolean connected) {
            title = connected ? T.serviceNoticeConnected() : "Volt";
            text = connected ? T.serviceNoticeScooterBattery("UT5 MAX", Fmt.integer(99)) : MESSAGE;
        }
        boolean connected() { return title.equals(T.serviceNoticeConnected()) && !text.equals(MESSAGE); }
    }
    /** BleController.ready()'s age check before #269, copied: statusMs>0&&now()-statusMs<=7000. */
    private static boolean readyBefore(boolean up, long statusMs, long now) { return up && statusMs > 0 && now - statusMs <= 7000; }
    /** Volt's last 2 s read 1 s before the owner left at T0, then the reply to each 15 s poll from T0 + 15 s up to lastPoll. */
    private static long backgroundStatus(long t, long lastPoll) {
        long last = T0 - S;
        for (long p = T0 + StatusCadence.POLL_SLOW_MS; p <= lastPoll && p + REPLY <= t; p += StatusCadence.POLL_SLOW_MS) last = p + REPLY;
        return last;
    }

    private static void source() throws Exception {
        String s = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/ScooterService.java")), StandardCharsets.UTF_8);
        check(count(s, ": ble.linked() ? T.serviceNoticeConnected() : \"Volt\";") == 1, "the notice's title follows linked()");
        check(count(s, ": ble.linked() ? connectedText() : ble.displayMessage();") == 1, "the notice's text follows linked()");
        check(!s.contains("ble.ready() ? T.serviceNoticeConnected()") && !s.contains("ble.ready() ? connectedText()"), "no notice path is left on ready()");
    }
    private static int count(String s, String part) { int n = 0; for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) n++; return n; }

    /** The device rhythm: status every 15 s out of sight. Connected at every step; the 7 s window flipped it about half the time. */
    private static void backgroundStaysConnected() {
        int flips = 0, steps = 0;
        boolean last = true;
        for (long t = T0; t <= T0 + 10 * 60 * S; t += STEP) {
            long status = backgroundStatus(t, Long.MAX_VALUE);
            Notice n = new Notice(StatusCadence.linked(true, status, t, false));
            check(n.connected() && "UT5 MAX · 99%".equals(n.text), "the notice says Connected at " + (t - T0) + " ms: " + n.title + " / " + n.text);
            boolean old = readyBefore(true, status, t);
            if (old != last) flips++;
            last = old;steps++;
        }
        check(flips >= 70, "the 7 s window switched the notice on every 15 s poll: " + flips + " switches in " + steps + " steps");
    }

    /** In sight or recording linked() is ready(): every status age and link state gives the same notice. */
    private static void foregroundUnchanged() {
        for (boolean up : new boolean[]{false, true})
            for (long age = 0; age <= 40 * S; age += 50)
                check(new Notice(StatusCadence.linked(up, T0, T0 + age, true)).connected() == readyBefore(up, T0, T0 + age), "fast cadence matches ready() at age " + age);
    }

    /** The link goes down out of sight: BleController clears the GATT client and statusMs, so the message shows at once, as before. */
    private static void linkDown() {
        long downAt = T0 + 2 * 60 * S + 7 * S;
        long status = backgroundStatus(downAt - STEP, Long.MAX_VALUE);
        check(new Notice(StatusCadence.linked(true, status, downAt - STEP, false)).connected(), "Connected just before the drop");
        Notice n = new Notice(StatusCadence.linked(false, 0, downAt, false));
        check(!n.connected() && "Volt".equals(n.title) && MESSAGE.equals(n.text), "the drop shows Volt and the Bluetooth message at once");
        check(new Notice(readyBefore(false, 0, downAt)).title.equals(n.title), "the same moment ready() switched it");
    }

    /** The link stays up but the scooter stops answering: Connected through the background silence limit, then the message. */
    private static void silentScooter() {
        long lastPoll = T0 + 3 * StatusCadence.POLL_SLOW_MS, lastStatus = lastPoll + REPLY, shownAt = -1, failedAt = -1;
        for (long t = T0; t <= lastStatus + 60 * S && shownAt < 0; t += STEP) {
            long status = backgroundStatus(t, lastPoll);
            // BleController.poll(): a tick past the silence limit fails the link (signal lost).
            if (failedAt < 0 && t > T0 && (t - T0) % StatusCadence.POLL_SLOW_MS == 0 && t - status > StatusCadence.silenceMs(false)) failedAt = t;
            if (!new Notice(StatusCadence.linked(failedAt < 0, failedAt < 0 ? status : 0, t, false)).connected()) shownAt = t;
        }
        check(shownAt - lastStatus > StatusCadence.SILENCE_SLOW_MS && shownAt - lastStatus <= StatusCadence.SILENCE_SLOW_MS + STEP,
                "no answer: Connected until the background silence limit, then the message (" + (shownAt - lastStatus) + " ms)");
        check(failedAt < 0 || failedAt >= shownAt, "the message shows no later than poll() ends the link");
    }
}
