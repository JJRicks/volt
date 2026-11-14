package com.jjricks.volt;

/** Riding mode's battery alert banner (RidingAlert): when it shows, what it says, and leaving with it up. No Android. */
public final class RidingAlertTest {
    private static int assertions;
    private static final long T0 = 5_000_000L;

    public static void main(String[] args) {
        silent();
        sounding();
        replaced();
        seen();
        placement();
        titles();
        leaving();
        System.out.println("RidingAlertTest: " + assertions + " assertions passed");
    }

    /** Without sound the banner hides by itself after about ten seconds in view. */
    private static void silent() {
        check(RidingAlert.SILENT_MS == 10_000L, "ten seconds");
        check(at(0, false).shown(false), "shows at once");
        check(at(9_999, false).shown(false), "still at 9.999 s in view");
        check(!at(10_000, false).shown(false), "gone at 10 s in view");
        check(!at(600_000, false).shown(false), "never again later");
        check(at(0, false).silentLeft() == 10_000L && at(4_000, false).silentLeft() == 6_000L && at(10_000, false).silentLeft() == 0, "its timer");
        // An alarm sounding (a newer alert's) always keeps the banner with its Stop sound.
        check(at(60_000, false).shown(true), "a sounding alarm is never left without its Stop");
    }

    /** With sound it stays until Stop sound or the alarm's own timeout ends it, then hides for good. */
    private static void sounding() {
        check(at(0, true).shown(true), "shows while sounding");
        check(at(59_000, true).shown(true), "past ten seconds while it sounds");
        check(!at(2_000, true).shown(false), "the sound ended: gone, even inside the silent ten seconds");
        check(at(0, true).silentLeft() == 0, "no silent timer for a sounding alert");
    }

    /** Two alerts in a row: the newer one (its own id) replaces the older; a close meant for the older is told apart. */
    private static void replaced() {
        RidingAlert older = new RidingAlert(ChargeAlertPolicy.Event.CHARGE, 80, 3, 0, false, "");
        RidingAlert newer = new RidingAlert(ChargeAlertPolicy.Event.FULL, 100, 4, 0, false, "");
        check(older.id != newer.id, "each alert its own number");
        // The service starts a new Seen per alert: the newer one's ten seconds count from its own start.
        RidingAlert.Seen first = new RidingAlert.Seen(), second = new RidingAlert.Seen();
        first.tick(T0, true);
        first.tick(T0 + 9_000, true);
        second.tick(T0 + 9_000, true);
        check(second.tick(T0 + 12_000, true) == 3_000L, "a fresh clock for the newer alert");
    }

    /** Time under the riding settings sheet, a dialog or another window doesn't count toward the silent ten seconds. */
    private static void seen() {
        RidingAlert.Seen clock = new RidingAlert.Seen();
        check(clock.tick(T0, false) == 0, "fired under the sheet: no time yet");
        check(clock.tick(T0 + 30_000, false) == 0, "thirty seconds under the sheet: still none");
        check(clock.tick(T0 + 30_500, true) == 0, "the sheet closed: its time starts now");
        check(clock.tick(T0 + 34_500, true) == 4_000L, "four seconds in view");
        check(clock.tick(T0 + 35_000, false) == 4_000L, "covered again: held");
        check(clock.tick(T0 + 50_000, true) == 4_000L, "back in view: nothing added for the covered time");
        check(clock.tick(T0 + 56_000, true) == 10_000L, "ten seconds in view in all");
        check(!new RidingAlert(ChargeAlertPolicy.Event.LOW, 30, 1, clock.tick(T0 + 56_000, true), false, "").shown(false), "then gone");
        // A clock read twice at the same moment, or out of order, never runs backwards.
        check(clock.tick(T0 + 55_000, true) == 10_000L, "never backwards");
    }

    /** Over the top bar only connected and with room for its short title and button; otherwise its own row. */
    private static void placement() {
        check(RidingAlert.inline(true, 600, 500), "connected, room: over the top bar");
        check(RidingAlert.inline(true, 500, 500), "exactly the room it needs");
        check(!RidingAlert.inline(true, 499, 500), "too little room (a warning chip, large text): its own row");
        check(!RidingAlert.inline(false, 2000, 500), "not connected or reconnecting: its own row, the pill stays");
    }

    private static RidingAlert at(long seenMs, boolean sounded) {
        return new RidingAlert(ChargeAlertPolicy.Event.LOW, 30, 1, seenMs, sounded, "My scooter is at 29%.");
    }

    private static void titles() {
        RidingAlert low = new RidingAlert(ChargeAlertPolicy.Event.LOW, 30, 1, T0, false, "My scooter is at 29%.");
        same(low.titles(), new String[]{"Battery below 30%", "Below 30%"}, "low");
        check("Battery below 30%. My scooter is at 29%.".equals(low.spoken()), "spoken: " + low.spoken());
        same(new RidingAlert(ChargeAlertPolicy.Event.CHARGE, 80, 1, T0, false, "").titles(), new String[]{"Charged to 80%", "Charged"}, "charge");
        same(new RidingAlert(ChargeAlertPolicy.Event.FULL, 100, 1, T0, false, "").titles(), new String[]{"Fully charged", "Full"}, "full");
        // Never fires in sight; its notice title stands in.
        same(new RidingAlert(ChargeAlertPolicy.Event.PAUSED, 0, 1, T0, false, "").titles(), new String[]{"Charge alert paused"}, "paused");
        same(RidingAlert.stopLabels(), new String[]{"Stop sound", "Stop"}, "stop");
        for (ChargeAlertPolicy.Event e : ChargeAlertPolicy.Event.values()) {
            String[] rungs = new RidingAlert(e, 50, 1, T0, false, "").titles();
            for (int i = 1; i < rungs.length; i++) check(rungs[i].length() < rungs[i - 1].length(), e + ": each rung shorter");
        }
        check(new RidingAlert(ChargeAlertPolicy.Event.LOW, 30, 1, T0, false, null).text.isEmpty(), "no sentence is empty, not null");
    }

    /** Leaving riding mode: counted as shown, the alarm keeps its timeout with the notice's Stop, or stops when no notice can show. */
    private static void leaving() {
        check(RidingAlert.leave(true, true, true) == RidingAlert.Leave.NOTICE, "sounding with notices: the notice carries Stop");
        check(RidingAlert.leave(true, true, false) == RidingAlert.Leave.STOP, "sounding with notifications off: nothing else could stop it");
        check(RidingAlert.leave(true, false, true) == RidingAlert.Leave.NOTHING, "silent: nothing to do");
        check(RidingAlert.leave(true, false, false) == RidingAlert.Leave.NOTHING, "silent, notices off: nothing to do");
        check(RidingAlert.leave(false, true, true) == RidingAlert.Leave.NOTHING, "no banner: another alarm is left alone");
        check(RidingAlert.leave(false, false, false) == RidingAlert.Leave.NOTHING, "nothing at all");
    }

    private static void same(String[] got, String[] want, String what) {
        check(java.util.Arrays.equals(got, want), what + ": " + java.util.Arrays.toString(got));
    }

    private static void check(boolean ok, String what) {
        assertions++;
        if (!ok) throw new AssertionError(what);
    }
}
