package com.jjricks.volt;

/** Riding mode's battery alert banner (RidingAlert): when it shows, what it says, and leaving with it up. No Android. */
public final class RidingAlertTest {
    private static int assertions;
    private static final long T0 = 5_000_000L;

    public static void main(String[] args) {
        silent();
        sounding();
        replaced();
        titles();
        leaving();
        System.out.println("RidingAlertTest: " + assertions + " assertions passed");
    }

    /** Without sound the banner hides by itself after about ten seconds. */
    private static void silent() {
        RidingAlert a = new RidingAlert(ChargeAlertPolicy.Event.LOW, 30, 1, T0, false, "My scooter is at 29%.");
        check(RidingAlert.SILENT_MS == 10_000L, "ten seconds");
        check(a.shown(T0, false), "shows at once");
        check(a.shown(T0 + 9_999, false), "still at 9.999 s");
        check(!a.shown(T0 + 10_000, false), "gone at 10 s");
        check(!a.shown(T0 + 600_000, false), "never again later");
        check(a.silentLeft(T0) == 10_000L && a.silentLeft(T0 + 4_000) == 6_000L && a.silentLeft(T0 + 10_000) == 0, "its timer");
        // An alarm sounding (a newer alert's) always keeps the banner with its Stop sound.
        check(a.shown(T0 + 60_000, true), "a sounding alarm is never left without its Stop");
    }

    /** With sound it stays until Stop sound or the alarm's own timeout ends it, then hides for good. */
    private static void sounding() {
        RidingAlert a = new RidingAlert(ChargeAlertPolicy.Event.LOW, 30, 2, T0, true, "");
        check(a.shown(T0, true), "shows while sounding");
        check(a.shown(T0 + 59_000, true), "past ten seconds while it sounds");
        check(!a.shown(T0 + 2_000, false), "the sound ended: gone, even inside the silent ten seconds");
        check(a.silentLeft(T0) == 0, "no silent timer for a sounding alert");
    }

    /** Two alerts in a row: the newer one (its own id) replaces the older; a close meant for the older is told apart. */
    private static void replaced() {
        RidingAlert older = new RidingAlert(ChargeAlertPolicy.Event.CHARGE, 80, 3, T0, false, "");
        RidingAlert newer = new RidingAlert(ChargeAlertPolicy.Event.FULL, 100, 4, T0 + 3_000, false, "");
        check(older.id != newer.id, "each alert its own number");
        check(newer.shown(T0 + 12_000, false), "the newer one's ten seconds count from its own start");
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
