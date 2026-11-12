package com.jjricks.volt;

/**
 * The state behind each scooter subject's one-line summary on the Scooter tab's index (hub layout plan, PR 3) and on
 * Clean Home (PR 8).
 * Pure and text-free: it decides which summary applies from the scooter's values; HubSubjects turns that into
 * HubText's words. Every method returns null when the state isn't known, and the row then shows no summary.
 */
final class HubSummaries {
    private HubSummaries() { }

    /** Lights & sound: Automatic lights, else the Tail light setting. */
    enum Lights { AUTO, ON, OFF }

    /**
     * tailLight and autoSensor are the scooter's 0/1 values (null when unread). tailLight is feature key {@code headlight},
     * which drives the tail light (OEM tailIsOn), not the front lamp. Automatic lights win.
     */
    static Lights lights(Integer tailLight, Integer autoSensor) {
        if (Integer.valueOf(1).equals(autoSensor)) return Lights.AUTO;
        if (tailLight == null) return null;
        return tailLight == 1 ? Lights.ON : Lights.OFF;
    }

    /** Battery & charging: the charge limit where the model has one, else the battery level; and whether it charges. */
    static final class Battery {
        /** A percent: the charge limit when {@link #limit}, else the battery level. */
        final int percent;
        final boolean limit, charging;
        Battery(int percent, boolean limit, boolean charging) { this.percent = percent; this.limit = limit; this.charging = charging; }
    }

    /**
     * limitOffered: the model has a charge limit (charge_limit); limit is its value in percent, level the battery's;
     * charging is the scooter's live charging state. Null when the number the summary needs is unknown or out of range,
     * or when the charging state is unknown: the line says "charging" or "not charging", so without a live report it
     * would guess. Missing telemetry stays unavailable (Clean Home and the Scooter tab's index share this rule).
     */
    static Battery battery(boolean limitOffered, Integer limit, Integer level, Boolean charging) {
        if (charging == null) return null;
        Integer percent = limitOffered ? limit : level;
        if (percent == null || percent < 0 || percent > 100) return null;
        return new Battery(percent, limitOffered, charging);
    }

    /** Controls: whether cruise control is on (0/1), null when unread. The drive mode's name comes with it as data. */
    static Boolean cruise(Integer cruiseControl) {
        return cruiseControl == null ? null : cruiseControl == 1;
    }

    /** Security: both the handlebar PIN and proximity unlock must be known, or there is no summary. */
    static final class Security {
        final boolean pin, proximity;
        Security(boolean pin, boolean proximity) { this.pin = pin; this.proximity = proximity; }
    }

    static Security security(Boolean pin, Boolean proximity) {
        return pin == null || proximity == null ? null : new Security(pin, proximity);
    }

    /** Service: maintenance reminders, from MaintenanceView.Summary's counts. */
    enum Service { DUE, SOON, CLEAR }

    /** on: reminders are switched on for this scooter; due and soon count the reminders in each state. Null when off. */
    static Service service(boolean on, int due, int soon) {
        if (!on) return null;
        return due > 0 ? Service.DUE : soon > 0 ? Service.SOON : Service.CLEAR;
    }
}
