package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The overview card's two small readings (owner, Nov 19 2026: "the Odometer and Drive Mode texts on both the clean and
 * widget homes ... could be set to any particular data parameter individually"): which readings a slot can hold, the
 * defaults and the storage. Pure Java.
 *
 * <p>Each scooter keeps its own two, as it keeps its quick actions (HomeQuickActions) and its widget dashboards: the
 * first in {@code home_reading_1_v1_<profile id>}, the second in {@code home_reading_2_v1_<profile id>} in app_settings,
 * each a Telemetry reading key. Absent, unreadable or unknown means that slot's default, Odometer then Drive mode, so
 * nothing changes for anyone who never picks; they are written only when the owner picks. Clean's battery card always
 * shows them; a Widgets "Scooter overview" card shows them while its own reading list is still the stock pair
 * (followsHome), so a card whose readings were picked in its own Readings sheet keeps them.
 *
 * <p>The choices are Telemetry's own catalogue (the one the widget dashboard's reading pickers list), narrowed to the
 * live ride and battery readings that fit a small slot: no raw or undecoded fields, not the card's own charge and range,
 * and none whose value is a long text (the stored trips list, the high temperature exposure's run of units, the
 * manufacturing date). Drive mode is a Settings reading in that catalogue; it is listed with the ride readings.
 */
final class HomeReadings {
    static final String FIRST_PREFIX = "home_reading_1_v1_", SECOND_PREFIX = "home_reading_2_v1_";
    static final String ODOMETER = "odometer_km", DRIVE_MODE = "driving_mode";
    /** The two slots' defaults, in order: what the card showed before the choice existed. */
    static final List<String> DEFAULTS = Collections.unmodifiableList(Arrays.asList(ODOMETER, DRIVE_MODE));
    static final int COUNT = 2;
    /** Ride and battery readings left out: the card's own two, and those whose value is a long text. */
    private static final Set<String> LEFT_OUT = new HashSet<>(Arrays.asList(
            "battery_percent", "remaining_range_km", "stored_trips", "high_temperature_duration", "battery_manufactured"));
    private static final List<String> CATALOG;
    static {
        ArrayList<String> ride = new ArrayList<>(), battery = new ArrayList<>();
        for (Telemetry.Metric metric : new Telemetry().metrics()) {
            if (LEFT_OUT.contains(metric.key) || Telemetry.rawReading(metric.label, null)) continue;
            if (Telemetry.GROUP_RIDE.equals(metric.group)) {
                ride.add(metric.key);
                // Drive mode beside the odometer: the card's two defaults stay next to each other in the list.
                if (ODOMETER.equals(metric.key)) ride.add(DRIVE_MODE);
            } else if (Telemetry.GROUP_BATTERY.equals(metric.group)) battery.add(metric.key);
        }
        if (!ride.contains(DRIVE_MODE)) ride.add(DRIVE_MODE);
        ArrayList<String> all = new ArrayList<>(ride);
        all.addAll(battery);
        CATALOG = Collections.unmodifiableList(all);
    }

    private HomeReadings() { }

    /** Every reading a slot can hold: the ride readings (Drive mode among them), then the battery readings. */
    static List<String> catalog() { return CATALOG; }

    /** Whether a slot can hold this reading key. */
    static boolean offered(String key) { return key != null && CATALOG.contains(key); }

    /** The group a reading is listed under in the picker (Telemetry's group ids): Drive mode with the ride readings. */
    static String group(String key) {
        if (DRIVE_MODE.equals(key)) return Telemetry.GROUP_RIDE;
        for (Telemetry.Metric metric : new Telemetry().metrics()) if (metric.key.equals(key)) return metric.group;
        return null;
    }

    /** The stored key of a slot (0 or 1) for a scooter profile. */
    static String key(int slot, String profileId) { return (slot == 0 ? FIRST_PREFIX : SECOND_PREFIX) + profileId; }

    /**
     * The two to show from what is stored: each slot's stored reading where a slot can hold it, else its default. Never
     * the same reading twice: a second slot that repeats the first (a hand-edited or damaged backup) shows its default, or
     * the first slot's default where that is the one the first slot shows.
     */
    static List<String> resolve(String first, String second) {
        String one = offered(first) ? first : DEFAULTS.get(0);
        String two = offered(second) && !second.equals(one) ? second : DEFAULTS.get(1);
        if (two.equals(one)) two = DEFAULTS.get(0);
        return Collections.unmodifiableList(Arrays.asList(one, two));
    }

    /** A scooter's two from its stored values (stored reads a key, null when absent or unreadable); no scooter: the defaults. */
    static List<String> load(Function<String, String> stored, String profileId) {
        if (profileId == null || profileId.isEmpty() || stored == null) return DEFAULTS;
        return resolve(stored.apply(key(0, profileId)), stored.apply(key(1, profileId)));
    }

    /**
     * The two after picking reading for slot (0 or 1) of current: a reading the other slot shows changes places with
     * this slot's, so the card never shows one reading twice. An unknown reading or slot changes nothing.
     */
    static List<String> choose(List<String> current, int slot, String reading) {
        List<String> now = current == null || current.size() != COUNT ? DEFAULTS : resolve(current.get(0), current.get(1));
        if (slot < 0 || slot >= COUNT || !offered(reading)) return now;
        ArrayList<String> next = new ArrayList<>(now);
        int other = 1 - slot;
        if (reading.equals(next.get(other))) next.set(other, next.get(slot));
        next.set(slot, reading);
        return Collections.unmodifiableList(next);
    }

    /**
     * Whether a Widgets overview card follows the Home style choice: its own reading list is still the stock pair
     * (Odometer, then Drive mode), which every new or default card has. A list picked in the card's own Readings sheet
     * (anything else) is kept as picked.
     */
    static boolean followsHome(List<String> widgetKeys) { return DEFAULTS.equals(widgetKeys); }

    /**
     * Whether picking {@code picked} in a Widgets card's own Readings sheet puts Home style's two ({@code home}) back to
     * the stock pair. Only an overview card ({@code overviewCard}) follows Home style: there the stock pair means "follow
     * Home style" (followsHome), so a card given Odometer then Drive mode would show Home style's other two instead of
     * what was picked. Home style's two go back to Odometer and Drive mode, so the card shows the pick, and so do Clean
     * and every other card that follows Home style. Any other card (a Scooter card, whose own default list is the same
     * pair, Reading tiles, a reading list) never touches Home style's two. Nothing changes while they already are the
     * stock pair.
     */
    static boolean resetsHome(boolean overviewCard, List<String> picked, List<String> home) {
        return overviewCard && followsHome(picked) && !DEFAULTS.equals(home);
    }
}
