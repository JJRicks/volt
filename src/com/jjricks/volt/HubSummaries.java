package com.jjricks.volt;

/**
 * The state behind each scooter subject's one-line summary on the Scooter tab's index (hub layout plan, PR 3).
 * Pure and text-free: it decides which summary applies from the scooter's values; HubSubjects turns that into
 * HubText's words. Every method returns null when the state isn't known, and the row then shows no summary.
 */
final class HubSummaries {
    private HubSummaries() { }

    /** Lights & sound: Automatic lights, else the Tail light setting. */
    enum Lights { AUTO, ON, OFF }

    /**
     * tailLight and autoSensor are the scooter's 0/1 values (null when unread). tailLight is feature key {@code headlight},
     * which drives the tail light (OEM tailIsOn), not the front lamp. autoOffered: the model offers Automatic lights as an
     * ordinary row. Automatic lights win; while the model offers them and their value is unread there is no summary (they
     * may be in charge), and the tail light alone is the basis only on a model with no Automatic lights setting.
     */
    static Lights lights(boolean autoOffered, Integer tailLight, Integer autoSensor) {
        if (autoOffered) {
            Boolean auto = toggle(autoSensor);
            if (auto == null) return null;
            if (auto) return Lights.AUTO;
        }
        Boolean tail = toggle(tailLight);
        if (tail == null) return null;
        return tail ? Lights.ON : Lights.OFF;
    }

    /**
     * A switch's value as its row reads it: 1 on, 0 off, null for unread or any other value. A value the model doesn't
     * define is unknown, as {@link #known} makes it for the charge limit, never "off".
     */
    static Boolean toggle(Integer value) {
        return value == null || (value != 0 && value != 1) ? null : value == 1;
    }

    /**
     * A reading's number only while it is live (ScooterService.displayReadings: fresh by the reading's own rule, the one
     * Home's status line uses for the drive mode), else null: a last-seen or expired value is unknown here.
     */
    static Double live(Double number, boolean live) {
        return !live || number == null || !Double.isFinite(number) ? null : number;
    }

    /** Battery & charging: the charge limit where the model has one, else the battery level; and whether it charges. */
    static final class Battery {
        /** A percent: the charge limit when {@link #limit}, else the battery level. */
        final int percent;
        final boolean limit, charging;
        Battery(int percent, boolean limit, boolean charging) { this.percent = percent; this.limit = limit; this.charging = charging; }
    }

    /**
     * limitOffered: the model has a charge limit (charge_limit); limit is its value in percent, level the battery's from a
     * live reading (null when there is none: a last-seen percentage is never shown beside a live charging state).
     * charging comes only from a live reading (null when there is none): without it there is no summary, since HubText
     * has no wording for a level or limit alone and "not charging" would be a guess. Null too when the number the
     * summary needs is unknown or out of range.
     */
    static Battery battery(boolean limitOffered, Integer limit, Integer level, Boolean charging) {
        if (charging == null) return null;
        Integer percent = limitOffered ? limit : level;
        if (percent == null || percent < 0 || percent > 100) return null;
        return new Battery(percent, limitOffered, charging);
    }

    /**
     * A setting's value as its row reads it, or null when the row shows it as unknown: a value outside the variant's
     * settings, such as the OEM charge limit's 0 ("Default (80%)", a sentinel Volt treats as unknown). Without a variant
     * (a profile that has never connected: catalogue rows, unavailable) the value passes as it is.
     */
    static Integer known(FeatureVariant variant, Integer value) {
        if (value == null || variant == null) return value;
        return variant.allows(value) || variant.shows(value) ? value : null;
    }

    /**
     * Controls: the drive mode's name as the riding screen shows it, or null when it is unknown there: no live reading
     * (HubSubjects passes the code through {@link #live}, so a stale one is no reading), or a code this model's names
     * don't cover (Telemetry marks it "(raw)").
     */
    static String driveMode(String label) {
        if (label == null || label.isEmpty() || Telemetry.rawReading(null, label) || label.equals(Telemetry.drivingModeLabel(0, null))) return null;
        return label;
    }

    /**
     * Controls: whether cruise control is on (0/1), null when unread or neither 0 nor 1 ({@link #toggle}). The drive
     * mode's name comes with it as data.
     */
    static Boolean cruise(Integer cruiseControl) {
        return toggle(cruiseControl);
    }

    /** Security: both the handlebar PIN and proximity unlock must be known, or there is no summary. */
    static final class Security {
        final boolean pin, proximity;
        Security(boolean pin, boolean proximity) { this.pin = pin; this.proximity = proximity; }
    }

    static Security security(Boolean pin, Boolean proximity) {
        return pin == null || proximity == null ? null : new Security(pin, proximity);
    }

    /** Service: maintenance reminders, from MaintenanceView.Summary's counts. OFF: reminders are off (the row's "Off"). */
    enum Service { DUE, SOON, CLEAR, OFF }

    /** on: reminders are switched on for this scooter; due and soon count the reminders in each state. */
    static Service service(boolean on, int due, int soon) {
        if (!on) return Service.OFF;
        return due > 0 ? Service.DUE : soon > 0 ? Service.SOON : Service.CLEAR;
    }
}
