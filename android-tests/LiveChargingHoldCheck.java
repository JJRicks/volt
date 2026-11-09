package com.jjricks.volt;

/**
 * What the hardware sweep saw of the live charging hold on leaving Volt, and what follows from it. Android-free, unit
 * tested in tests/l10n/LiveChargingHoldCheckTest. ChargeLiveHold.active() is only the hold's internal state: the service
 * also needs notifications (chargeLiveHold(), which is what WorkReasons sees), so an armed hold without notifications
 * holds nothing and the ordinary 10 s grace is correct. Whether the hold should have armed is judged from Conditions read
 * immediately before leaving: once Volt is out of sight the link check is the looser background one, so a scooter can read
 * as connected afterwards that ChargeLiveHold.leave did not see as connected.
 */
public final class LiveChargingHoldCheck {
    private LiveChargingHoldCheck() {}

    /** What ChargeLiveHold.leave decides from, read on the main thread just before the service is told Volt left. */
    public static final class Conditions {
        public boolean enabled, connected, scheduled, stoppedForCharge;
        /** The raw charging reading as the service sees it (fresh, selected scooter), null if none. */
        public Boolean charging;

        /** ChargeLiveHold.leave's rule; LiveChargingHoldCheckTest keeps the two the same. */
        public boolean armable() { return enabled && connected && !stoppedForCharge && (Boolean.TRUE.equals(charging) || scheduled); }
    }

    public static final class Observed {
        /** ChargeLiveHold.active(), the service's chargeLiveHold(), and chargeLiveAllowed(), after leaving. */
        public boolean armed, effective, notificationsAllowed;
        public Conditions before = new Conditions();
        public boolean workAllowed;
        public long graceDeadlineMs, nowMs;
    }

    /** A failure the sweep must report, or null when the app did what it should. */
    public static String problem(Observed o) {
        if (o.before.armable() && !o.armed) return "The live charging hold did not arm although its conditions were met on leaving";
        if (o.effective && !o.armed) return "The service reported an effective live charging hold that was never armed";
        if (o.armed && !o.effective && o.notificationsAllowed) return "The live charging hold armed with notifications allowed but is not in effect";
        if (o.effective && o.graceDeadlineMs != 0) return "The live charging hold did not keep the connection in place of the 10 s grace";
        if (o.effective && !o.workAllowed) return "The live charging hold left hidden work disallowed";
        if (o.armed && !o.effective && !o.notificationsAllowed && o.graceDeadlineMs <= o.nowMs)
            return "With notifications blocked the hold cannot keep the connection, but the ordinary 10 s grace was not scheduled";
        return null;
    }

    /** Observed conditions for the sweep's evidence, never a guess at why the hold did not arm. */
    public static String evidence(Observed o) {
        if (o.effective) return "Hold effective on leaving (" + facts(o) + "): kept the connection instead of the 10 s grace; Live charging off for the grace checks, then restored";
        if (o.armed) return "Armed but not effective (" + facts(o) + "): notifications are blocked, so nothing holds and the ordinary 10 s grace applies";
        return "Not armed (" + facts(o) + "): the grace checks ran with no hold involved";
    }

    private static String facts(Observed o) {
        Conditions c = o.before;
        return "before leaving: Live charging " + (c.enabled ? "on" : "off")
                + ", notifications " + (o.notificationsAllowed ? "allowed" : "blocked")
                + ", scooter " + (c.connected ? "connected" : "not connected")
                + ", charging reading " + (c.charging == null ? "unavailable" : c.charging ? "1" : "0")
                + ", Volt schedule waiting " + (c.scheduled ? "yes" : "no")
                + ", stopped for this charge " + (c.stoppedForCharge ? "yes" : "no");
    }
}
