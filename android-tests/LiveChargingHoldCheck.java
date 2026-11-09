package com.jjricks.volt;

/**
 * What the hardware sweep saw of the live charging hold on leaving Volt, and what follows from it. Android-free, unit
 * tested in tests/l10n/LiveChargingHoldCheckTest. ChargeLiveHold.active() is only the hold's internal state: the service
 * also needs notifications (chargeLiveHold(), which is what WorkReasons sees), so an armed hold without notifications
 * holds nothing and the ordinary 10 s grace is correct.
 */
final class LiveChargingHoldCheck {
    private LiveChargingHoldCheck() {}

    static final class Observed {
        /** ChargeLiveHold.active(), the service's chargeLiveHold(), and chargeLiveAllowed(). */
        boolean armed, effective, notificationsAllowed;
        boolean enabled, connected, scheduled, stoppedForCharge;
        /** The raw charging reading as the service sees it (fresh, selected scooter), null if none. */
        Boolean charging;
        boolean workAllowed;
        long graceDeadlineMs, nowMs;
    }

    /** A failure the sweep must report, or null when the app did what it should. */
    static String problem(Observed o) {
        if (o.effective && !o.armed) return "The service reported an effective live charging hold that was never armed";
        if (o.effective && o.graceDeadlineMs != 0) return "The live charging hold did not keep the connection in place of the 10 s grace";
        if (o.effective && !o.workAllowed) return "The live charging hold left hidden work disallowed";
        if (o.armed && !o.effective && !o.notificationsAllowed && o.graceDeadlineMs <= o.nowMs)
            return "With notifications blocked the hold cannot keep the connection, but the ordinary 10 s grace was not scheduled";
        return null;
    }

    /** Observed conditions for the sweep's evidence, never a guess at why the hold did not arm. */
    static String evidence(Observed o) {
        if (o.effective) return "Hold effective on leaving (" + facts(o) + "): kept the connection instead of the 10 s grace; Live charging off for the grace checks, then restored";
        if (o.armed) return "Armed but not effective (" + facts(o) + "): notifications are blocked, so nothing holds and the ordinary 10 s grace applies";
        return "Not armed (" + facts(o) + "): the grace checks ran with no hold involved";
    }

    private static String facts(Observed o) {
        return "Live charging " + (o.enabled ? "on" : "off")
                + ", notifications " + (o.notificationsAllowed ? "allowed" : "blocked")
                + ", scooter " + (o.connected ? "connected" : "not connected")
                + ", charging reading " + (o.charging == null ? "unavailable" : o.charging ? "1" : "0")
                + ", Volt schedule waiting " + (o.scheduled ? "yes" : "no")
                + ", stopped for this charge " + (o.stoppedForCharge ? "yes" : "no");
    }
}
