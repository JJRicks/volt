package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Clean Home's four quick actions (hub layout plan 1.9): which ids, their defaults and their storage. Pure Java.
 *
 * <p>Clean stores its four in {@code home_quick_v1_<profile id>} (a comma-joined list of ids, a scooter
 * preference) and never reads or writes the widget dashboard. Besides every Quick access catalog id, Clean
 * offers three ids of its own, {@link #RIDING} (open riding mode), {@link #RECORD} (start or stop recording)
 * and {@link #CHARGE_ALERTS} (the Battery alerts sheet). They are deliberately kept out of QuickAccessActions'
 * catalog: a Widgets dashboard, a saved slot or a dashboard transfer that held one would be refused by an older
 * Volt ("Invalid shortcut").
 *
 * <p>The defaults follow what the connected scooter supports (owner's answer 11 on #383): Ride, Record ride, then
 * Charge done by where scheduled charging exists, else charge alerts where the model has them, then one control
 * the scooter offers ({@link #CONTROL_ORDER}). There is no drive mode action: a scooter's drive mode is changed on
 * the scooter, never from the app. No lights action by default. The defaults are computed while the key is absent
 * (or unreadable) and written only when the owner edits the four; an owner's stored four are kept as stored.
 */
final class HomeQuickActions {
    /** Opens riding mode (MainActivity.openRiding). Clean only. */
    static final String RIDING = "riding";
    /** Starts or stops recording a ride (RideRecordingApp's one toggle). Clean only. */
    static final String RECORD = "record";
    /** Opens the Battery alerts sheet (MainActivity.showChargeAlerts). Clean only, and only where alerts are offered. */
    static final String CHARGE_ALERTS = "charge_alerts";
    /** Ui icon keys for the Clean-only actions. */
    static final String ICON_RIDING = "scooter", ICON_RECORD = "record", ICON_CHARGE_ALERTS = "bolt";
    /** Per-scooter key prefix; the profile id follows. */
    static final String KEY_PREFIX = "home_quick_v1_";
    static final int COUNT = 4;

    /**
     * The controls the fourth default is picked from, in this order: the first the scooter offers wins (feature
     * keys). Cruise control, walk assist (hill_start_assist on other models), traction control, power assist
     * (parking_assist on other models), then energy recovery, hill descent control and overspeed control.
     * Maximum speed is not here: it was the plan's stand-in for "Drive mode", which the scooter alone changes.
     */
    static final List<String> CONTROL_ORDER = Collections.unmodifiableList(Arrays.asList(
            "cruise_control", "hill_start_assist", "traction_control", "parking_assist",
            "energy_recovery", "hill_descent_control", "overspeed_control"));

    private static final String CHARGE_DONE_BY = "charge_done_by", CONTROLS = "controls", RIDES = "rides";

    private HomeQuickActions() {}

    /** The stored key for a scooter profile. */
    static String key(String profileId) { return KEY_PREFIX + profileId; }

    /**
     * The Quick access entry Clean shows for an id: the catalog's, or for Clean's own an entry outside the catalog
     * (QuickAccessActions.local) named by HubText, grouped under Ride (Charge alerts under Battery & charging).
     * Null for an id Clean can't show.
     */
    static QuickAccessActions.Entry entry(String id) {
        QuickAccessActions.Entry entry = QuickAccessActions.find(id);
        if (entry != null || !cleanOnly(id)) return entry;
        if (CHARGE_ALERTS.equals(id)) return QuickAccessActions.local(id, HubText.quickActionLabel(id),
                HubText.quickActionShortLabel(id), ICON_CHARGE_ALERTS, HubText.title(HubPages.BATTERY));
        return QuickAccessActions.local(id, HubText.quickActionLabel(id), HubText.quickActionShortLabel(id),
                RIDING.equals(id) ? ICON_RIDING : ICON_RECORD, HubText.title(HubPages.RIDE));
    }

    /** Clean's own, as entries for the shortcut picker (listed before the catalog); Charge alerts only where offered. */
    static List<QuickAccessActions.Entry> ownEntries(boolean chargeAlertsOffered) {
        ArrayList<QuickAccessActions.Entry> entries = new ArrayList<>();
        entries.add(entry(RIDING));
        entries.add(entry(RECORD));
        if (chargeAlertsOffered) entries.add(entry(CHARGE_ALERTS));
        return Collections.unmodifiableList(entries);
    }

    /** Whether Clean can show this id: one of its own, or any Quick access catalog id. */
    static boolean known(String id) {
        return id != null && (cleanOnly(id) || QuickAccessActions.find(id) != null);
    }

    /** Whether an id is one of Clean's own, which Widgets never stores. */
    static boolean cleanOnly(String id) { return RIDING.equals(id) || RECORD.equals(id) || CHARGE_ALERTS.equals(id); }

    /**
     * Clean's default four for the connected scooter (owner's answer 11): Ride, Record ride, then Charge done by
     * where scheduled charging exists, else Charge alerts where the model has them, then the first control of
     * {@link #CONTROL_ORDER} the scooter offers. Where the third has nothing, a second offered control takes it;
     * still short, Rides and then the Settings shortcut fill in.
     *
     * @param chargeDoneOffered scheduled charging exists (timed_charging and charge_start offered), as the Battery
     *     page's Charge done by row decides
     * @param chargeAlertsOffered the model has Volt's charge alerts (ScooterService.chargeAlertsOffered)
     * @param controlOffered a feature key the scooter offers as an ordinary control (the Controls page's gates:
     *     CapabilityResolver's known encoding, never Lab); null offers none
     */
    static List<String> defaults(boolean chargeDoneOffered, boolean chargeAlertsOffered, Predicate<String> controlOffered) {
        ArrayList<String> ids = new ArrayList<>();
        ids.add(RIDING);
        ids.add(RECORD);
        if (chargeDoneOffered) ids.add(CHARGE_DONE_BY);
        else if (chargeAlertsOffered) ids.add(CHARGE_ALERTS);
        if (controlOffered != null) for (String key : CONTROL_ORDER) {
            if (ids.size() >= COUNT) break;
            String id = QuickAccessActions.FEATURE_PREFIX + key;
            if (known(id) && !ids.contains(id) && controlOffered.test(key)) ids.add(id);
        }
        for (String id : new String[]{RIDES, CONTROLS}) if (ids.size() < COUNT && !ids.contains(id)) ids.add(id);
        return Collections.unmodifiableList(ids);
    }

    /**
     * Exactly four distinct ids Clean can show: the stored ones in order (unknown and repeated ids dropped, at most
     * four), topped up from the defaults, then from Quick access's defaults. A null list (nothing stored) is the defaults.
     */
    static List<String> normalize(List<String> ids, List<String> defaults) {
        ArrayList<String> result = new ArrayList<>();
        if (ids != null) for (String id : ids) {
            if (result.size() >= COUNT) break;
            if (known(id) && !result.contains(id)) result.add(id);
        }
        if (defaults != null) fill(result, defaults);
        fill(result, QuickAccessActions.defaultIds());
        return Collections.unmodifiableList(result);
    }

    /** The stored value as ids, or null when nothing (or only blanks) is stored. */
    static List<String> parse(String stored) {
        if (stored == null || stored.trim().isEmpty()) return null;
        ArrayList<String> ids = new ArrayList<>();
        for (String part : stored.split(",")) { String id = part.trim(); if (!id.isEmpty()) ids.add(id); }
        return ids.isEmpty() ? null : ids;
    }

    /** The value to store for an owner's four (ids never hold a comma). */
    static String encode(List<String> ids) {
        StringBuilder text = new StringBuilder();
        for (String id : ids) {
            if (id == null || id.isEmpty() || id.indexOf(',') >= 0) continue;
            if (text.length() > 0) text.append(',');
            text.append(id);
        }
        return text.toString();
    }

    /** The four to show from the stored value and this scooter's defaults. */
    static List<String> load(String stored, List<String> defaults) {
        return normalize(parse(stored), defaults);
    }

    private static void fill(List<String> result, List<String> from) {
        for (String id : from) {
            if (result.size() >= COUNT) return;
            if (known(id) && !result.contains(id)) result.add(id);
        }
    }
}
