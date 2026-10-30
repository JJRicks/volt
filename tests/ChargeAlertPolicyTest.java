package com.jjricks.volt;

import com.jjricks.volt.ChargeAlertPolicy.Event;
import com.jjricks.volt.ChargeAlertPolicy.Link;
import com.jjricks.volt.ChargeAlertPolicy.Settings;
import com.jjricks.volt.ChargeAlertPolicy.State;

public final class ChargeAlertPolicyTest {
    private static int checks;
    private static final long MONO = 5_000_000L, WALL = 1_800_000_000_000L, SEC = 1000L, MIN = 60_000L;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    /** A clock the steps advance together; readings are fresh at the step's time unless aged. */
    private static final class Rig {
        final ChargeAlertPolicy policy;
        long mono = MONO, wall = WALL;
        boolean offered = true, canNotify = true;
        Rig(Settings settings) { this(settings, null); }
        Rig(Settings settings, State state) { policy = new ChargeAlertPolicy(settings, state); }
        Event at(long after, int charging, int percent) { return read(after, Link.CONNECTED, (double) charging, (double) percent, 0); }
        Event read(long after, Link link, Double charging, Double percent, long age) {
            mono += after; wall += after;
            return policy.step(mono, wall, offered, canNotify, link, charging, mono - age, percent, mono - age);
        }
    }
    private static Settings charge(int at) { return new Settings(true, at, false, ChargeAlertPolicy.LOW_DEFAULT, true, false); }
    private static Settings low(int below) { return new Settings(false, ChargeAlertPolicy.CHARGE_DEFAULT, true, below, true, false); }

    public static void main(String[] args) {
        settings();
        crossing();
        full();
        sessions();
        reconnects();
        stale();
        lowBattery();
        holds();
        gating();
        wording();
        System.out.println("ChargeAlertPolicy: " + checks + " checks passed");
    }

    private static void settings() {
        Settings off = Settings.decode(null);
        check(!off.charge && !off.low && !off.notify && !off.alarm && off.chargeAt == 80 && off.lowBelow == 20, "Nothing is on by default: no alert, no notification, no alarm");
        check(Settings.decode("garbage;at=x").equals(Settings.OFF), "Unreadable settings are everything off");
        Settings s = new Settings(true, 90, true, 15, false, true);
        check(Settings.decode(s.encode()).equals(s), "Settings survive encode and decode");
        check(new Settings(true, 83, true, 1, false, false).chargeAt == 85 && new Settings(true, 140, true, 1, false, false).chargeAt == 100
                && new Settings(true, 83, true, 1, false, false).lowBelow == 5 && new Settings(true, 20, true, 70, false, false).chargeAt == 50
                && new Settings(true, 20, true, 70, false, false).lowBelow == 50, "Levels snap to 5-point steps within 50–100 and 5–50");
        check(!s.delivers(false) || s.alarm, "The alarm delivers without notifications");
        check(!new Settings(true, 80, false, 20, true, false).delivers(false) && new Settings(true, 80, false, 20, true, false).delivers(true),
                "Notify me delivers only when Android shows notifications");
        check(!new Settings(true, 80, true, 20, false, false).delivers(true), "An alert with neither delivery reaches nobody");
        State st = new State(); st.session = true; st.startPct = 40; st.bestPct = 62; st.firedAt = 0; st.seenWall = WALL; st.lowLatched = true;
        State back = State.decode(st.encode());
        check(back.session && back.startPct == 40 && back.bestPct == 62 && back.seenWall == WALL && back.lowLatched, "Session state survives encode and decode");
        check(!State.decode("session=1;start=70;best=60;seen=1").session && !State.decode("session=1;start=1;best=2").session,
                "Inconsistent or partial saved sessions are dropped rather than trusted");
    }

    private static void crossing() {
        Rig r = new Rig(charge(80));
        check(r.at(0, 1, 40) == Event.NONE && r.policy.watching(), "Charging below the level starts a watched session");
        check(r.at(10 * SEC, 1, 79) == Event.NONE, "Below the level, nothing fires");
        check(r.at(10 * SEC, 1, 80) == Event.CHARGE && !r.policy.watching(), "Reaching the level fires once and ends the watch");
        check(r.at(10 * SEC, 1, 81) == Event.NONE && r.at(10 * SEC, 1, 90) == Event.NONE, "It never fires twice in one session");
        check(r.policy.takeDirty() && !r.policy.takeDirty(), "The fired session is marked for saving once");

        r = new Rig(charge(80));
        check(r.at(0, 1, 80) == Event.NONE && r.at(10 * SEC, 1, 85) == Event.NONE && !r.policy.watching(), "A charge first seen at the level never fires or holds");
        r = new Rig(charge(80));
        check(r.at(0, 1, 92) == Event.NONE && !r.policy.watching(), "A charge first seen above the level never fires");

        r = new Rig(charge(80));
        r.at(0, 1, 70);
        r.policy.settings(charge(60));
        check(r.at(10 * SEC, 1, 71) == Event.NONE, "Lowering the level below the battery counts as reached, without an alert");
        r.policy.settings(charge(75));
        check(r.at(10 * SEC, 1, 74) == Event.NONE && r.at(10 * SEC, 1, 75) == Event.CHARGE, "Raising the level again fires on reaching it");
        r = new Rig(Settings.OFF);
        r.at(0, 1, 50);
        r.policy.settings(charge(80));
        check(r.at(10 * SEC, 1, 80) == Event.CHARGE, "Turning the alert on mid-charge below the level fires on reaching it");
        r = new Rig(Settings.OFF);
        r.at(0, 1, 50); r.at(10 * SEC, 1, 85);
        r.policy.settings(charge(80));
        check(r.at(10 * SEC, 1, 86) == Event.NONE, "Turning the alert on above the level never fires at once");

        r = new Rig(new Settings(true, 80, false, 20, false, false));
        check(r.at(0, 1, 70) == Event.NONE && !r.policy.watching() && r.at(10 * SEC, 1, 80) == Event.NONE, "Without a delivery, nothing is armed or watched");
        r = new Rig(charge(80)); r.canNotify = false;
        check(r.at(0, 1, 70) == Event.NONE && !r.policy.watching() && r.at(10 * SEC, 1, 80) == Event.NONE, "Notify me with notifications off arms nothing");
        r = new Rig(new Settings(true, 80, false, 20, false, true)); r.canNotify = false;
        check(r.at(0, 1, 70) == Event.NONE && r.policy.watching() && r.at(10 * SEC, 1, 80) == Event.CHARGE, "The alarm alone still alerts with notifications off");
        r = new Rig(charge(80));
        r.at(0, 1, 70); r.policy.skip();
        check(!r.policy.watching() && r.at(10 * SEC, 1, 75) == Event.NONE && !r.policy.watching() && r.at(10 * SEC, 1, 80) == Event.NONE,
                "Stop watching skips the rest of this charge");
        check(r.at(4 * MIN, 0, 80) == Event.NONE && r.at(10 * SEC, 0, 80) == Event.NONE, "Unplugging ends the skipped session");
        r.at(3 * MIN + SEC, 0, 60);
        check(r.at(10 * SEC, 1, 61) == Event.NONE && r.policy.watching() && r.at(10 * SEC, 1, 80) == Event.CHARGE, "The next charge alerts as usual after a skip");
    }

    private static void full() {
        Rig r = new Rig(charge(100));
        r.at(0, 1, 90);
        check(r.at(10 * SEC, 1, 99) == Event.NONE && r.policy.watching(), "99% while charging is not yet full");
        check(r.at(10 * SEC, 1, 100) == Event.FULL && !r.policy.watching(), "Reaching 100% while charging fires full");
        check(r.at(10 * SEC, 0, 100) == Event.NONE, "Full fires once, even when charging then stops");

        r = new Rig(charge(100));
        r.at(0, 1, 95);
        check(r.at(10 * SEC, 0, 99) == Event.FULL, "Charging that stops at 99% counts as full");
        r = new Rig(charge(100));
        r.at(0, 1, 95);
        check(r.at(10 * SEC, 0, 98) == Event.NONE && r.policy.watching(), "A charger that stops below 99% is a pause, still watched");
        check(r.at(MIN, 1, 98) == Event.NONE && r.at(10 * SEC, 1, 100) == Event.FULL, "The same session fires when charging resumes to full");

        r = new Rig(charge(100));
        check(r.at(0, 1, 99) == Event.NONE && !r.policy.watching() && r.at(10 * SEC, 1, 100) == Event.NONE && r.at(10 * SEC, 0, 100) == Event.NONE,
                "A top-up first seen at 99% or above never fires full");
        r = new Rig(charge(100));
        check(r.at(0, 0, 100) == Event.NONE, "A full scooter that isn't charging, with no session, fires nothing");
        r = new Rig(charge(90));
        r.at(0, 1, 80);
        check(r.at(10 * SEC, 0, 99) == Event.NONE, "Stopping at 99% counts only for the full alert");
    }

    private static void sessions() {
        Rig r = new Rig(charge(80));
        r.at(0, 1, 60); r.at(10 * SEC, 1, 80);
        check(r.at(10 * SEC, 0, 80) == Event.NONE && r.at(ChargeAlertPolicy.STOP_MS, 0, 80) == Event.NONE && r.policy.state().session,
                "Charging off for exactly the stop window keeps the session");
        r.at(SEC, 0, 80);
        check(!r.policy.state().session, "Charging off for longer than the stop window ends the session");
        check(r.at(10 * SEC, 1, 80) == Event.NONE, "Plugging in again at the level starts a session that doesn't fire");
        r.at(4 * MIN, 0, 80); r.at(10 * SEC, 0, 80);
        r.at(MIN, 0, 55);
        check(r.at(10 * SEC, 1, 56) == Event.NONE && r.policy.watching() && r.at(10 * SEC, 1, 80) == Event.CHARGE, "After a ride, the next charge re-arms and fires");

        r = new Rig(charge(80));
        r.at(0, 1, 60); r.at(10 * SEC, 1, 80);
        check(r.at(10 * SEC, 0, 79) == Event.NONE && r.policy.state().session, "One point of noise doesn't end a session");
        r.at(10 * SEC, 0, 78);
        check(!r.policy.state().session, "A two-point drop ends the session at once");
        r = new Rig(charge(80));
        check(r.at(0, 1, 70) == Event.NONE && r.at(10 * SEC, 1, 82) == Event.CHARGE, "A level passed between readings still fires");
        check(r.at(10 * SEC, 1, 72) == Event.NONE && r.policy.state().startPct == 72 && r.policy.watching(), "A drop while charging is a new charge from the new level");
        check(r.at(10 * SEC, 1, 80) == Event.CHARGE, "The new charge fires on reaching the level");
    }

    private static void reconnects() {
        Rig r = new Rig(charge(80));
        r.at(0, 1, 50); r.at(10 * SEC, 1, 80);
        String saved = r.policy.state().encode();
        // Volt closed and reopened mid-charge: the saved session already fired.
        Rig again = new Rig(charge(80), State.decode(saved)); again.mono = r.mono + 5 * MIN; again.wall = r.wall + 5 * MIN;
        check(again.at(0, 1, 84) == Event.NONE && !again.policy.watching() && again.at(10 * SEC, 1, 90) == Event.NONE,
                "Reconnecting mid-charge after the alert doesn't fire it again or hold");
        // A session that hadn't fired yet carries on after a restart and fires late if the level passed meanwhile.
        r = new Rig(charge(80));
        r.at(0, 1, 50);
        Rig late = new Rig(charge(80), State.decode(r.policy.state().encode())); late.mono = r.mono + 30 * MIN; late.wall = r.wall + 30 * MIN;
        check(late.at(0, 1, 83) == Event.CHARGE, "A charge seen starting below the level fires on reconnect after passing it");
        // Seen once long ago: can't tell that charge from this one, so it is a new session first seen at its level.
        r = new Rig(charge(80));
        r.at(0, 1, 50);
        Rig old = new Rig(charge(80), State.decode(r.policy.state().encode())); old.mono = r.mono + 13 * 60 * MIN; old.wall = r.wall + 13 * 60 * MIN;
        check(old.at(0, 1, 83) == Event.NONE && old.policy.state().startPct == 83, "A session unseen for longer than a charge lasts is not continued");
        Rig back = new Rig(charge(80), State.decode(r.policy.state().encode())); back.wall = r.wall - 10 * MIN;
        check(back.at(0, 1, 83) == Event.NONE && back.policy.state().startPct == 83, "A phone clock that jumped back drops the saved session");
    }

    private static void stale() {
        Rig r = new Rig(charge(80));
        r.at(0, 1, 50);
        check(r.read(10 * SEC, Link.CONNECTED, 1d, 85d, ChargeAlertPolicy.FRESH_MS + 1) == Event.NONE, "A stale reading at the level never fires");
        check(r.policy.percent() == 50 && !r.policy.percentFresh(), "A stale reading isn't taken as the last level");
        check(r.read(10 * SEC, Link.CONNECTED, 1d, 85d, ChargeAlertPolicy.FRESH_MS) == Event.CHARGE, "A reading exactly at the freshness bound counts");
        r = new Rig(charge(80));
        r.at(0, 1, 50);
        check(r.read(10 * SEC, Link.RECONNECTING, 1d, 85d, 0) == Event.NONE && r.read(10 * SEC, Link.LOST, 1d, 85d, 0) == Event.PAUSED,
                "Readings without a ready connection are ignored: no alert, only the pause");
        check(r.read(10 * SEC, Link.CONNECTED, 1d, 85d, -SEC) == Event.NONE, "A reading from the future is ignored");
        check(r.read(10 * SEC, Link.CONNECTED, 1d, Double.NaN, 0) == Event.NONE && r.read(10 * SEC, Link.CONNECTED, 2d, 85d, 0) == Event.NONE
                && r.read(10 * SEC, Link.CONNECTED, 1d, 140d, 0) == Event.NONE, "Unknown states and impossible levels are ignored");
        check(r.read(10 * SEC, Link.CONNECTED, null, 85d, 0) == Event.NONE, "A level without a fresh charging state decides nothing");
        check(r.at(10 * SEC, 1, 85) == Event.CHARGE, "The next fresh pair fires");
    }

    private static void lowBattery() {
        Rig r = new Rig(low(20));
        check(r.at(0, 0, 25) == Event.NONE && r.at(10 * SEC, 0, 20) == Event.NONE, "At the level is not below it");
        check(r.at(10 * SEC, 0, 19) == Event.LOW, "Dropping below the level while not charging fires");
        check(r.at(10 * SEC, 0, 18) == Event.NONE && r.at(10 * SEC, 0, 21) == Event.NONE && r.at(10 * SEC, 0, 19) == Event.NONE,
                "Hovering around the level fires once");
        check(r.at(10 * SEC, 0, 25) == Event.NONE && r.policy.state().lowLatched, "Exactly the level plus five doesn't re-arm");
        r.at(10 * SEC, 1, 26);
        check(!r.policy.state().lowLatched, "Above the level plus five re-arms");
        check(r.at(10 * SEC, 1, 19) == Event.NONE, "Below the level while charging never fires");
        check(r.at(10 * SEC, 0, 19) == Event.LOW, "Re-armed, it fires on the next drop below");
        check(!r.policy.watching(), "The low alert never asks for a background hold");
        State saved = State.decode(r.policy.state().encode());
        Rig again = new Rig(low(20), saved);
        check(again.at(0, 0, 17) == Event.NONE, "A reconnect while still low doesn't fire again");
        r = new Rig(Settings.OFF);
        r.at(0, 0, 15);
        r.policy.settings(low(20));
        check(r.at(10 * SEC, 0, 14) == Event.NONE, "Turning the alert on while already low doesn't fire");
        r.policy.settings(low(10));
        check(r.at(10 * SEC, 0, 12) == Event.NONE && r.at(10 * SEC, 0, 9) == Event.LOW, "A lower level fires on dropping below it");
        r = new Rig(new Settings(false, 80, true, 20, false, false));
        check(r.at(0, 0, 25) == Event.NONE && r.at(10 * SEC, 0, 10) == Event.NONE, "Without a delivery the low alert stays silent");
    }

    private static void holds() {
        Rig r = new Rig(charge(80));
        r.at(0, 1, 50);
        long started = r.mono;
        check(r.policy.watching() && r.policy.watchDeadline() == started + ChargeAlertPolicy.HOLD_MS, "Watching is bounded by the hold limit");
        check(r.read(10 * SEC, Link.RECONNECTING, null, null, 0) == Event.NONE && r.policy.watching(), "A brief reconnect keeps watching");
        check(r.read(ChargeAlertPolicy.RECONNECT_MS, Link.RECONNECTING, null, null, 0) == Event.NONE && r.policy.watching(), "Watching lasts the reconnect window");
        check(r.read(SEC, Link.RECONNECTING, null, null, 0) == Event.PAUSED && !r.policy.watching(), "A reconnect past its window pauses, once");
        check(r.read(10 * SEC, Link.LOST, null, null, 0) == Event.NONE, "The pause is reported once");
        check(r.at(10 * SEC, 1, 60) == Event.NONE && r.policy.watching(), "Reconnecting resumes the watch");
        check(r.read(10 * SEC, Link.LOST, null, null, 0) == Event.PAUSED && !r.policy.watching(), "A lost connection pauses at once");
        check(r.policy.percent() == 60 && !r.policy.percentFresh(), "The last level stays, marked not fresh");

        r = new Rig(charge(80));
        r.at(0, 1, 50);
        r.at(ChargeAlertPolicy.HOLD_MS - 10 * SEC, 1, 70);
        check(r.policy.watching(), "Still watching just inside the hold limit");
        check(r.at(10 * SEC, 1, 71) == Event.TIME_LIMIT && !r.policy.watching(), "The hold limit ends the watch and says so");
        check(r.at(10 * SEC, 1, 72) == Event.NONE && !r.policy.watching(), "A timed-out watch doesn't restart by itself");
        check(r.at(10 * SEC, 1, 80) == Event.CHARGE, "The alert still fires while Volt happens to be connected");
        r = new Rig(charge(80));
        r.at(0, 1, 50); r.at(ChargeAlertPolicy.HOLD_MS, 1, 70);
        r.policy.resume();
        check(r.at(10 * SEC, 1, 71) == Event.NONE && r.policy.watching(), "Opening Volt lets a timed-out watch hold again");

        r = new Rig(charge(80));
        r.at(0, 1, 50);
        r.at(10 * SEC, 0, 52);
        check(r.policy.watching(), "A charger pause keeps watching for the stop window");
        r.at(ChargeAlertPolicy.STOP_MS + SEC, 0, 52);
        check(!r.policy.watching() && !r.policy.state().session, "Charging stopped for longer ends the watch");
        r = new Rig(charge(80));
        r.at(0, 1, 50); r.read(10 * SEC, Link.LOST, null, null, 0);
        r.at(10 * SEC, 1, 51);
        check(r.policy.watching(), "A pause clears when the scooter is back");
        r.policy.settings(r.policy.settings().delivery(false, false));
        check(!r.policy.watching() && r.policy.watchDeadline() == 0, "Turning both deliveries off ends the watch at once");
        r = new Rig(charge(80));
        r.at(0, 1, 50); r.policy.settings(Settings.OFF);
        check(!r.policy.watching(), "Turning the alert off ends the watch at once");
        r = new Rig(charge(80));
        r.at(0, 1, 50); r.policy.idle();
        check(!r.policy.watching() && r.at(10 * SEC, 1, 51) == Event.NONE && r.policy.watching(), "An idle pause lifts at the next evaluation, without a pause notice");
    }

    private static void gating() {
        DeviceDescriptor ut5 = DeviceDescriptor.of("X2538-SYNTHETIC", "", 0, null, false, DeviceDescriptor.Transport.STANDARD, 0);
        DeviceDescriptor gt3 = DeviceDescriptor.of("Q2345ZZ9ES7777777", "24182", 1, null, false, DeviceDescriptor.Transport.STANDARD, 0);
        DeviceDescriptor mapped = DeviceDescriptor.of("", "24431", 1, null, false, DeviceDescriptor.Transport.STANDARD, 0);
        DeviceDescriptor never = DeviceDescriptor.of("", "", 0, null, false, DeviceDescriptor.Transport.STANDARD, 0);
        check(!ChargeAlertPolicy.offered(ut5), "The UT5 MAX has a charge limit: no charge alerts");
        check(!ChargeAlertPolicy.offered(mapped), "A model with the official app's charge limit gets none");
        check(ChargeAlertPolicy.offered(gt3), "The GT3 Max has no charge limit: alerts are offered");
        check(!ChargeAlertPolicy.offered(never) && !ChargeAlertPolicy.offered(null), "A scooter that never connected has no model evidence yet");
        check(!ChargeAlertPolicy.offered(DeviceDescriptor.simulated("", 0)) && !ChargeAlertPolicy.offered(DeviceDescriptor.simulated("24431", 0))
                && ChargeAlertPolicy.offered(DeviceDescriptor.simulated("39991", 0)), "Simulated models follow the same rule");
        for (DeviceDescriptor d : new DeviceDescriptor[]{ut5, mapped, gt3, never})
            check(ChargeAlertPolicy.offered(d) == !(CapabilityResolver.decide("charge_limit", d, StatusFields.NONE, null).support == CapabilityResolver.Support.KNOWN_ENCODING
                    || d.pidSource() == DeviceDescriptor.PidSource.NONE), "Offered exactly where Volt hides the charge limit row: " + d.rulePid());
        Rig r = new Rig(new Settings(true, 80, true, 20, true, true)); r.offered = false;
        check(r.at(0, 1, 50) == Event.NONE && !r.policy.watching() && r.at(10 * SEC, 1, 80) == Event.NONE && r.at(10 * SEC, 0, 10) == Event.NONE,
                "Where the charge limit is offered, nothing ever fires or holds");
    }

    private static void wording() {
        check(ChargeAlertPolicy.watchTitle(80).equals("Charging to 80%") && ChargeAlertPolicy.watchTitle(100).equals("Charging to full"), "Watch titles");
        check(ChargeAlertPolicy.watchText(42, true, 80).equals("42% now · alert at 80%"), "Live watch text");
        check(ChargeAlertPolicy.watchText(42, false, 80).equals("Last seen 42% · alert at 80%"), "Stale watch text says last seen");
        check(ChargeAlertPolicy.watchText(97, true, 100).equals("97% now · alert when full") && ChargeAlertPolicy.watchText(null, false, 100).equals("Alert when full")
                && ChargeAlertPolicy.watchText(null, false, 80).equals("Alert at 80%"), "Full and unknown watch text");
        check(ChargeAlertPolicy.watchProgress(42, 80) == 42 && ChargeAlertPolicy.watchProgress(85, 80) == 80 && ChargeAlertPolicy.watchProgress(null, 80) == -1
                && ChargeAlertPolicy.watchProgress(-3, 80) == 0, "Progress runs 0 to the target, hidden with no reading");
        check(ChargeAlertPolicy.alertTitle(Event.CHARGE, 80).equals("Charged to 80%") && ChargeAlertPolicy.alertTitle(Event.FULL, 100).equals("Fully charged")
                && ChargeAlertPolicy.alertTitle(Event.LOW, 20).equals("Battery low") && ChargeAlertPolicy.alertTitle(Event.PAUSED, 80).equals("Charge alert paused")
                && ChargeAlertPolicy.alertTitle(Event.TIME_LIMIT, 80).equals("Charge alert paused"), "Alert titles");
        check(ChargeAlertPolicy.alertText(Event.CHARGE, "GT3 Max", 81).equals("GT3 Max is at 81%.")
                && ChargeAlertPolicy.alertText(Event.PAUSED, "GT3 Max", 0).equals("Volt lost the connection to GT3 Max. Open Volt to keep watching this charge.")
                && ChargeAlertPolicy.alertText(Event.TIME_LIMIT, "GT3 Max", 0).equals("Volt stopped watching after 12 hours. Open Volt to keep watching this charge."), "Alert texts");
        check(ChargeAlertPolicy.levelText(100).equals("Full") && ChargeAlertPolicy.levelText(85).equals("85%"), "Level labels");
    }
}
