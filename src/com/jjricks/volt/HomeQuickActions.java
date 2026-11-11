package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Clean Home's four quick actions (hub layout plan 1.9): which ids, their defaults and their storage. Pure Java.
 *
 * <p>Clean stores its four in {@code home_quick_v1_<profile id>} (a comma-joined list of ids, a scooter
 * preference) and never reads or writes the widget dashboard. Besides every Quick access catalog id, Clean
 * offers two ids of its own, {@link #RIDING} (open riding mode) and {@link #RECORD} (start or stop recording).
 * They are deliberately kept out of QuickAccessActions' catalog: a Widgets dashboard, a saved slot or a
 * dashboard transfer that held one would be refused by an older Volt ("Invalid shortcut").
 *
 * <p>The defaults (Joel 3) are Ride, Record ride, Charge done by and Drive mode, with no lights action. They are
 * computed while the key is absent and written only when the owner edits the four.
 */
final class HomeQuickActions {
    /** Opens riding mode (MainActivity.openRiding). Clean only. */
    static final String RIDING = "riding";
    /** Starts or stops recording a ride (RideRecordingApp's one toggle). Clean only. */
    static final String RECORD = "record";
    /** Ui icon keys for the two Clean-only actions. */
    static final String ICON_RIDING = "scooter", ICON_RECORD = "record";
    /** Per-scooter key prefix; the profile id follows. */
    static final String KEY_PREFIX = "home_quick_v1_";
    static final int COUNT = 4;

    private static final String MAXIMUM_SPEED = QuickAccessActions.FEATURE_PREFIX + "maximum_speed";
    private static final String TIMED_CHARGING = QuickAccessActions.FEATURE_PREFIX + "timed_charging";
    private static final String CHARGE_DONE_BY = "charge_done_by", CONTROLS = "controls", RIDES = "rides";

    private HomeQuickActions() {}

    /** The stored key for a scooter profile. */
    static String key(String profileId) { return KEY_PREFIX + profileId; }

    /** Whether Clean can show this id: one of its own two, or any Quick access catalog id. */
    static boolean known(String id) {
        return id != null && (id.equals(RIDING) || id.equals(RECORD) || QuickAccessActions.find(id) != null);
    }

    /** Whether an id is one of Clean's own two, which Widgets never stores. */
    static boolean cleanOnly(String id) { return RIDING.equals(id) || RECORD.equals(id); }

    /**
     * Clean's default four: Ride, Record ride, Charge done by (or, where the model can't offer it, Timed charging,
     * else Rides), and Drive mode: the model's maximum speed control when its setting tiles hold it, else its first
     * setting tile not already listed, else the Settings shortcut.
     *
     * @param modelTiles the model's setting tile ids (ModelTiles.settingTiles), or null
     */
    static List<String> defaults(boolean chargeDoneOffered, boolean timedChargingOffered, List<String> modelTiles) {
        ArrayList<String> ids = new ArrayList<>();
        ids.add(RIDING);
        ids.add(RECORD);
        ids.add(chargeDoneOffered ? CHARGE_DONE_BY : timedChargingOffered ? TIMED_CHARGING : RIDES);
        List<String> tiles = modelTiles == null ? Collections.<String>emptyList() : modelTiles;
        String mode = null;
        if (tiles.contains(MAXIMUM_SPEED) && known(MAXIMUM_SPEED)) mode = MAXIMUM_SPEED;
        else for (String tile : tiles) if (known(tile) && !ids.contains(tile)) { mode = tile; break; }
        ids.add(mode == null || ids.contains(mode) ? CONTROLS : mode);
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
