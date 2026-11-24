package com.jjricks.volt;

import java.util.Map;

/**
 * 2.0.5 item 5: a scooter switched off but still answering. Every guard of the rule and every false positive the
 * investigation listed: an on scooter parked, the first seconds after connecting, charging, other models, the simulator,
 * a stale status, a phone in deep sleep. Synthetic times only.
 */
public final class ScooterPowerStateTest {
    private static int checks;
    private static final long READY = 1_000_000;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) {
        offWhenSilent();
        parkedOnScooterStaysLive();
        graceAfterConnecting();
        firstPushClears();
        charging();
        otherModels();
        simulator();
        staleStatusAndNotReady();
        deepSleep();
        onlyPushesCount();
        usableWhileOff();
        diagnostics();
        System.out.println("ScooterPowerStateTest: " + checks + " assertions passed");
    }

    /** ready, armed, fresh status, not charging. */
    private static ScooterPowerState.Result judge(ScooterPowerState s, long now) { return s.verdict(now, false, true, true, true, false); }

    private static ScooterPowerState started() { ScooterPowerState s = new ScooterPowerState(); s.start(READY); return s; }

    /** The owner's 23 November state: answers every poll, pushes nothing. */
    private static void offWhenSilent() {
        ScooterPowerState s = started();
        check(judge(s, READY + ScooterPowerState.GRACE_MS).off(), "No push in the first 10 s: off");
        check(judge(s, READY + 25 * 60_000).off(), "Still off 25 minutes later");
        check(judge(s, READY + ScooterPowerState.GRACE_MS).why == ScooterPowerState.Why.SILENT, "Why: silent");
        // A scooter that pushed, then went quiet (switched off while the link stayed).
        s = started(); s.frame(0x92, READY + 1000); s.frame(0x90, READY + 1500);
        check(!judge(s, READY + 1500 + ScooterPowerState.SILENCE_MS - 1).off(), "Not a millisecond early");
        check(judge(s, READY + 1500 + ScooterPowerState.SILENCE_MS).off(), "Ten seconds after the last push: off");
    }

    /** An on scooter that isn't moving keeps pushing (frame ages 0.1 to 2.7 s in the owner's log). */
    private static void parkedOnScooterStaysLive() {
        ScooterPowerState s = started();
        for (long at = READY + 900; at < READY + 600_000; at += 2700) {
            s.frame(at % 2 == 0 ? 0x90 : 0x92, at);
            ScooterPowerState.Result r = judge(s, at + 2699);
            check(r.state == ScooterPowerState.State.LIVE && !r.off(), "Parked and on, pushing every 2.7 s: live at " + (at - READY));
        }
    }

    /** No verdict before the first push can be expected (it came within 3 s on every on connection). */
    private static void graceAfterConnecting() {
        ScooterPowerState s = started();
        for (long at = READY; at < READY + ScooterPowerState.GRACE_MS; at += 500) {
            ScooterPowerState.Result r = judge(s, at);
            check(r.state == ScooterPowerState.State.WAITING && !r.off(), "Waiting during the grace at " + (at - READY));
        }
        s.frame(0x92, READY + 3000);
        check(judge(s, READY + 3000).state == ScooterPowerState.State.LIVE, "The first push within the grace: live");
        check(!judge(s, READY + 12_000).off(), "Within ten seconds of that push: still live after the grace");
    }

    private static void firstPushClears() {
        ScooterPowerState s = started();
        check(judge(s, READY + 60_000).off(), "Off a minute in");
        s.frame(0x90, READY + 60_001);
        check(judge(s, READY + 60_001).state == ScooterPowerState.State.LIVE, "The first push clears it at once");
        check(s.pushed() && s.lastPush() == READY + 60_001, "The push is remembered");
    }

    private static void charging() {
        ScooterPowerState s = started();
        ScooterPowerState.Result r = s.verdict(READY + 60_000, false, true, true, true, true);
        check(!r.off() && r.why == ScooterPowerState.Why.CHARGING, "Never off while charging");
        check(ScooterPowerState.charging(1d, 0d), "Charging state 1 is charging");
        check(ScooterPowerState.charging(2d, null), "Any other non-zero charging state counts too (the safe side)");
        check(ScooterPowerState.charging(0d, -10.09), "A charging current counts (either sign)");
        check(ScooterPowerState.charging(null, 0.2) && ScooterPowerState.charging(null, -0.2), "The threshold is inclusive");
        check(!ScooterPowerState.charging(0d, 0d), "The owner's off state: 0 and 0 A is not charging");
        check(!ScooterPowerState.charging(0d, 0.1) && !ScooterPowerState.charging(null, null), "Noise and unknown are not charging");
    }

    /** A model that never pushes (the parked-tick path) is never judged; a scooter that pushed before is. */
    private static void otherModels() {
        ScooterPowerState s = started();
        ScooterPowerState.Result r = s.verdict(READY + 60_000, false, true, false, true, false);
        check(!r.off() && r.why == ScooterPowerState.Why.NOT_ARMED, "Not armed: never off");
        check(ScooterPowerState.armed(true, false), "Every UT5 MAX is armed");
        check(ScooterPowerState.armed(false, true), "Another model that pushed on an earlier connection is armed");
        check(!ScooterPowerState.armed(false, false), "Another model that never pushed is not");
        // A GT3 Max-style scooter pushing 0x91 is live like the UT5 MAX's 0x92.
        s = started(); s.frame(0x91, READY + 1000);
        check(s.verdict(READY + 5000, false, true, false, true, false).state == ScooterPowerState.State.LIVE, "0x91 pushes count");
    }

    private static void simulator() {
        ScooterPowerState s = started();
        ScooterPowerState.Result r = s.verdict(READY + 60_000, true, true, true, true, false);
        check(!r.off() && r.why == ScooterPowerState.Why.SIMULATION, "The simulator is never off (its hook is off by default)");
    }

    private static void staleStatusAndNotReady() {
        ScooterPowerState s = started();
        ScooterPowerState.Result r = s.verdict(READY + 60_000, false, true, true, false, false);
        check(!r.off() && r.why == ScooterPowerState.Why.STATUS_STALE, "A stale status is the loss watchdog's, never off");
        r = s.verdict(READY + 60_000, false, false, true, true, false);
        check(!r.off() && r.why == ScooterPowerState.Why.NOT_READY, "Not ready: not judged");
        ScooterPowerState never = new ScooterPowerState();
        check(!never.verdict(READY, false, true, true, true, false).off(), "Never started: not judged");
        s.reset();
        check(!judge(s, READY + 60_000).off() && !s.pushed(), "Reset on close forgets the session");
    }

    /**
     * The caller passes awake time (uptimeMillis), which stops in deep sleep: 30 minutes asleep right after a push is
     * not silence. Only awake time past the push counts.
     */
    private static void deepSleep() {
        ScooterPowerState s = started();
        s.frame(0x92, READY + 2000);
        long awakeAfterSleep = READY + 2000 + 4000; // 4 s awake, however long the phone slept
        check(!judge(s, awakeAfterSleep).off(), "A phone's sleep never counts as the scooter's silence");
    }

    private static void onlyPushesCount() {
        ScooterPowerState s = started();
        for (int cmd : new int[]{0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x79, 0x7B, 0x30, 0x51})
            check(!s.frame(cmd, READY + 1000), "A reply is not a push: 0x" + Integer.toHexString(cmd));
        check(judge(s, READY + ScooterPowerState.GRACE_MS).off(), "Answered polls alone still read as off");
        ScooterPowerState before = new ScooterPowerState();
        check(!before.frame(0x92, READY), "A push before the link is ready isn't counted");
    }

    private static void usableWhileOff() {
        for (String key : new String[]{"charge_limit", "timed_charging", "charge_start", "clock_sync"})
            check(ScooterPowerState.usableWhileOff(key), "Charging setting stays usable: " + key);
        for (String key : new String[]{"lock", "cruise_control", "headlight", "auto_sensor", "range_algorithm", "maximum_speed", "speed_limit",
                "start_speed", "energy_recovery", "traction_control", "turn_sound", "display_units", "pin", "proximity_key", OwnerRelease.KEY, "", null})
            check(!ScooterPowerState.usableWhileOff(key), "Refused while off: " + key);
    }

    @SuppressWarnings("unchecked")
    private static void diagnostics() {
        ScooterPowerState s = started();
        s.frame(0x90, READY + 1000); s.frame(0x90, READY + 2000); s.frame(0x92, READY + 2500);
        ScooterPowerState.Result r = judge(s, READY + 20_000);
        Map<String, Object> m = s.diagnostics(READY + 20_000, r, true);
        Map<String, Object> counts = (Map<String, Object>) m.get("counts"), ages = (Map<String, Object>) m.get("last_age_ms");
        check(Long.valueOf(2).equals(counts.get("0x90")) && Long.valueOf(0).equals(counts.get("0x91")) && Long.valueOf(1).equals(counts.get("0x92")), "Counts per push");
        check(Long.valueOf(18_000).equals(ages.get("0x90")) && ages.get("0x91") == null && Long.valueOf(17_500).equals(ages.get("0x92")), "Last age per push, null when none");
        check("off".equals(m.get("verdict")) && "silent".equals(m.get("why")) && Boolean.TRUE.equals(m.get("armed")), "The verdict and why");
        check(Long.valueOf(20_000).equals(m.get("ready_age_ms")), "Time since ready");
    }
}
