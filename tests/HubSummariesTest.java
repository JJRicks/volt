package com.jjricks.volt;

/** HubSummaries: which summary each scooter subject shows, from the scooter's values (hub layout plan, PR 3). */
public final class HubSummariesTest {
    private static int checks;

    public static void main(String[] args) {
        lights();
        battery();
        batteryCard();
        batteryFacts();
        controls();
        security();
        service();
        ride();
        System.out.println("HubSummaries: " + checks + " checks passed");
    }

    private static void lights() {
        // A model that offers Automatic lights (the UT5 MAX): they win, and while they are unread there is no summary.
        require(HubSummaries.lights(true, 0, 1) == HubSummaries.Lights.AUTO, "Automatic lights win");
        require(HubSummaries.lights(true, null, 1) == HubSummaries.Lights.AUTO, "Automatic with the tail light unread");
        require(HubSummaries.lights(true, 1, 0) == HubSummaries.Lights.ON, "Tail light on");
        require(HubSummaries.lights(true, 1, null) == null, "Automatic lights unread: they may be in charge, so nothing");
        require(HubSummaries.lights(true, 0, null) == null, "Automatic lights unread with the tail light off: nothing");
        require(HubSummaries.lights(true, 0, 0) == HubSummaries.Lights.OFF, "Tail light off");
        require(HubSummaries.lights(true, null, 0) == null && HubSummaries.lights(true, null, null) == null, "Unknown shows nothing");
        // A model with no Automatic lights setting: the tail light alone is the basis.
        require(HubSummaries.lights(false, 1, null) == HubSummaries.Lights.ON && HubSummaries.lights(false, 0, null) == HubSummaries.Lights.OFF,
                "No Automatic lights setting: the tail light");
        require(HubSummaries.lights(false, 1, 1) == HubSummaries.Lights.ON, "A value for a setting the model doesn't offer is ignored");
        require(HubSummaries.lights(false, null, null) == null && HubSummaries.lights(false, null, 1) == null, "Unread tail light shows nothing");
        // Only 0 and 1 are known: any other value is unknown, never "Tail light off".
        require(HubSummaries.lights(false, 2, null) == null && HubSummaries.lights(true, 2, 0) == null, "A tail light of 2 shows nothing");
        require(HubSummaries.lights(true, 1, 2) == null && HubSummaries.lights(true, 0, -1) == null, "Automatic lights of 2 or -1 show nothing");
        require(HubSummaries.lights(false, 1, 2) == HubSummaries.Lights.ON, "A value for a setting the model doesn't offer is still ignored");
        require(Boolean.TRUE.equals(HubSummaries.toggle(1)) && Boolean.FALSE.equals(HubSummaries.toggle(0)), "A switch reads 1 and 0");
        require(HubSummaries.toggle(null) == null && HubSummaries.toggle(2) == null && HubSummaries.toggle(-1) == null
                && HubSummaries.toggle(255) == null, "Unread and other values are unknown");
    }

    private static void battery() {
        HubSummaries.Battery limit = HubSummaries.battery(true, 80, 55, false);
        require(limit != null && limit.limit && limit.percent == 80 && !limit.charging, "A model with a limit shows the limit");
        HubSummaries.Battery charging = HubSummaries.battery(true, 90, null, true);
        require(charging != null && charging.charging && charging.percent == 90, "The limit needs no level");
        HubSummaries.Battery level = HubSummaries.battery(false, null, 64, true);
        require(level != null && !level.limit && level.percent == 64 && level.charging, "No limit: the level");
        require(HubSummaries.battery(true, null, 64, false) == null, "An unread limit shows nothing");
        require(HubSummaries.battery(false, 80, null, false) == null, "An unread level shows nothing");
        require(HubSummaries.battery(false, null, 101, false) == null && HubSummaries.battery(false, null, -1, false) == null, "Out of range");
        require(HubSummaries.battery(false, null, 0, false) != null && HubSummaries.battery(false, null, 100, false) != null, "The ends");
        // No live charging reading (disconnected, or the state unread): never "not charging", so no summary.
        require(HubSummaries.battery(true, 80, 55, null) == null && HubSummaries.battery(false, null, 72, null) == null,
                "Charging unknown shows nothing");
        // The level only from a live reading: an expired 60% beside a live "Charging" shows nothing (no "Charging · 60%").
        require(HubSummaries.battery(false, null, liveLevel(60.0, false), true) == null, "An expired level shows nothing");
        HubSummaries.Battery fresh = HubSummaries.battery(false, null, liveLevel(59.6, true), true);
        require(fresh != null && fresh.percent == 60 && fresh.charging, "A live level shows");
        require(HubSummaries.battery(true, 80, liveLevel(60.0, false), true) != null, "With a limit the level isn't shown, so its age doesn't matter");
        require(HubSummaries.live(64.0, true) == 64.0 && HubSummaries.live(64.0, false) == null && HubSummaries.live(null, true) == null
                && HubSummaries.live(Double.NaN, true) == null && HubSummaries.live(Double.POSITIVE_INFINITY, true) == null, "Live numbers only");
        // The OEM charge limit's 0 is "Default (80%)", which the Battery page reads as unknown: never "Limit 0%".
        FeatureVariant oem = ModelRegistry.OEM_CHARGE_LIMIT;
        require(HubSummaries.known(oem, 0) == null, "The OEM sentinel is unknown");
        require(HubSummaries.battery(true, HubSummaries.known(oem, 0), 55, false) == null, "No Limit 0%");
        require(Integer.valueOf(80).equals(HubSummaries.known(oem, 80)) && Integer.valueOf(100).equals(HubSummaries.known(oem, 100)), "Offered limits pass");
        require(HubSummaries.known(oem, 83) == null && HubSummaries.known(oem, null) == null, "Other values and unread are unknown");
        require(Integer.valueOf(0).equals(HubSummaries.known(null, 0)), "No variant: the value as it is");
    }

    /** Battery & charging's summary card, under the level line (HubBatterySummary; #387 review, finding 4). */
    private static void batteryCard() {
        // Charging without a known limit: a plain "Charging", never the level again ("Charging · 78%" under "78% · ...").
        require(HubSummaries.card(true, true, null) == HubSummaries.CardState.CHARGING, "No limit: plain Charging");
        require(HubSummaries.card(true, true, 90) == HubSummaries.CardState.CHARGING_TO_LIMIT, "A limit: Charging to it");
        require(HubSummaries.card(true, true, 101) == HubSummaries.CardState.CHARGING && HubSummaries.card(true, true, -1) == HubSummaries.CardState.CHARGING,
                "An out-of-range limit is no limit");
        require(HubSummaries.card(false, true, 90) == HubSummaries.CardState.NOT_CHARGING
                && HubSummaries.card(false, true, null) == HubSummaries.CardState.NOT_CHARGING, "Not charging");
        // Charging only from a live state, and only beside a live level (the #404 rule): otherwise the line hides.
        require(HubSummaries.card(null, true, 90) == null && HubSummaries.card(null, true, null) == null, "Charging unknown: no line");
        require(HubSummaries.card(true, false, 90) == null && HubSummaries.card(true, false, null) == null
                && HubSummaries.card(false, false, null) == null, "A last-seen level: no charging line beside it");
    }

    /** Battery & charging's summary card, right column: health and pack temperature only where the model reports them live. */
    private static void batteryFacts() {
        require(Integer.valueOf(97).equals(HubSummaries.health(97.0, true)) && Integer.valueOf(97).equals(HubSummaries.health(96.6, true)),
                "Live health, in whole percent");
        require(Integer.valueOf(100).equals(HubSummaries.health(100.0, true)) && Integer.valueOf(1).equals(HubSummaries.health(1.0, true)), "1-100 shows");
        require(HubSummaries.health(97.0, false) == null, "A last-seen health is not stated as now");
        require(HubSummaries.health(null, true) == null && HubSummaries.health(null, false) == null,
                "A model that doesn't report health: no line");
        require(HubSummaries.health(0.0, true) == null && HubSummaries.health(101.0, true) == null && HubSummaries.health(-3.0, true) == null
                && HubSummaries.health(Double.NaN, true) == null && HubSummaries.health(Double.POSITIVE_INFINITY, true) == null,
                "Out-of-range or no number: no line");
        require(Double.valueOf(23).equals(HubSummaries.packTemperature(23.0, true)) && Double.valueOf(-5).equals(HubSummaries.packTemperature(-5.0, true)),
                "Live pack temperature, below zero too");
        require(Double.valueOf(-40).equals(HubSummaries.packTemperature(-40.0, true)) && Double.valueOf(100).equals(HubSummaries.packTemperature(100.0, true)),
                "The ends of the range show");
        require(HubSummaries.packTemperature(23.0, false) == null, "A last-seen temperature is not stated as now");
        require(HubSummaries.packTemperature(null, true) == null, "A model that doesn't report it: no line");
        require(HubSummaries.packTemperature(-41.0, true) == null && HubSummaries.packTemperature(101.0, true) == null
                && HubSummaries.packTemperature(Double.NaN, true) == null, "Not a battery temperature: no line");
    }

    private static void controls() {
        require(Boolean.TRUE.equals(HubSummaries.cruise(1)) && Boolean.FALSE.equals(HubSummaries.cruise(0)), "Cruise on and off");
        require(HubSummaries.cruise(null) == null, "Unread cruise");
        require(HubSummaries.cruise(2) == null && HubSummaries.cruise(-1) == null, "Cruise of 2 is unknown, never \"cruise off\"");
        // The OEM cruise variant defines 0 and 1 only: known() drops anything else before cruise() reads it.
        FeatureVariant oemCruise = ModelRegistry.OEM_CRUISE;
        require(HubSummaries.known(oemCruise, 2) == null && HubSummaries.cruise(HubSummaries.known(oemCruise, 2)) == null, "OEM cruise 2");
        require(Boolean.TRUE.equals(HubSummaries.cruise(HubSummaries.known(oemCruise, 1))), "OEM cruise on");
        require(HubSummaries.known(ModelRegistry.OEM_TAIL, 3) == null && HubSummaries.known(ModelRegistry.OEM_AUTO, 3) == null, "OEM light switches");
        // The drive mode as the riding screen names it; a code this model's names don't cover is unknown.
        require("SPORT".equals(HubSummaries.driveMode(Telemetry.drivingModeLabel(2538, 3.0))), "A named mode");
        String raw = Telemetry.drivingModeLabel(0, 7.0);
        require(raw.endsWith("(raw)") && HubSummaries.driveMode(raw) == null, "A raw code shows nothing: " + raw);
        require(HubSummaries.driveMode(Telemetry.drivingModeLabel(2538, null)) == null && HubSummaries.driveMode(null) == null
                && HubSummaries.driveMode("") == null, "No reading");
        // A stale drive-mode reading is no reading (displayReadings' freshness rule, as Home's status line uses): the index
        // never says SPORT from an old report.
        require(HubSummaries.driveMode(Telemetry.drivingModeLabel(2538, HubSummaries.live(3.0, false))) == null, "A stale mode shows nothing");
        require("SPORT".equals(HubSummaries.driveMode(Telemetry.drivingModeLabel(2538, HubSummaries.live(3.0, true)))), "A live mode");
    }

    private static void security() {
        HubSummaries.Security s = HubSummaries.security(true, false);
        require(s != null && s.pin && !s.proximity, "Both known");
        require(HubSummaries.security(null, true) == null && HubSummaries.security(false, null) == null, "Either unknown shows nothing");
    }

    private static void service() {
        require(HubSummaries.service(false, 3, 1) == HubSummaries.Service.OFF && HubSummaries.service(false, 0, 0) == HubSummaries.Service.OFF,
                "Reminders off: the row's Off");
        require(HubSummaries.service(true, 1, 2) == HubSummaries.Service.DUE, "Due wins");
        require(HubSummaries.service(true, 0, 2) == HubSummaries.Service.SOON, "Soon");
        require(HubSummaries.service(true, 0, 0) == HubSummaries.Service.CLEAR, "Clear");
    }

    /** The level HubSubjects passes: a live reading's percent, rounded, else null. */
    private static Integer liveLevel(double number, boolean live) {
        Double shown = HubSummaries.live(number, live);
        return shown == null ? null : (int) Math.round(shown);
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static void ride() {
        // While a ride records, the row shows the recording, not the ride before it (or "No rides yet" on a first ride).
        require(HubSummaries.rideLine(true) == HubSummaries.RideLine.RECORDING, "Recording: the running recording");
        require(HubSummaries.rideLine(false) == HubSummaries.RideLine.LAST_RIDE, "Not recording: the newest finished ride");
        long every = 30_000;
        // No look at the finished rides while recording, whatever else holds.
        require(!HubSummaries.rescanRides(true, false, false, every * 2, every), "Recording, nothing known: no look");
        require(!HubSummaries.rescanRides(true, true, true, every * 2, every), "Recording again: no look");
        // A recording that just ended is looked for at once, even within the interval.
        require(HubSummaries.rescanRides(false, true, true, 0, every), "Just ended: look at once");
        // Nothing known yet: look on every refresh.
        require(HubSummaries.rescanRides(false, false, false, 0, every), "Unknown: look");
        // Otherwise at the interval.
        require(!HubSummaries.rescanRides(false, false, true, every - 1, every), "Known, within the interval: no look");
        require(HubSummaries.rescanRides(false, false, true, every, every), "Known, the interval passed: look");
    }
}
