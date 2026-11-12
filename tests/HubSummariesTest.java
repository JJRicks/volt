package com.jjricks.volt;

/** HubSummaries: which summary each scooter subject shows, from the scooter's values (hub layout plan, PR 3). */
public final class HubSummariesTest {
    private static int checks;

    public static void main(String[] args) {
        lights();
        battery();
        controls();
        security();
        service();
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
        // The OEM charge limit's 0 is "Default (80%)", which the Battery page reads as unknown: never "Limit 0%".
        FeatureVariant oem = ModelRegistry.OEM_CHARGE_LIMIT;
        require(HubSummaries.known(oem, 0) == null, "The OEM sentinel is unknown");
        require(HubSummaries.battery(true, HubSummaries.known(oem, 0), 55, false) == null, "No Limit 0%");
        require(Integer.valueOf(80).equals(HubSummaries.known(oem, 80)) && Integer.valueOf(100).equals(HubSummaries.known(oem, 100)), "Offered limits pass");
        require(HubSummaries.known(oem, 83) == null && HubSummaries.known(oem, null) == null, "Other values and unread are unknown");
        require(Integer.valueOf(0).equals(HubSummaries.known(null, 0)), "No variant: the value as it is");
    }

    private static void controls() {
        require(Boolean.TRUE.equals(HubSummaries.cruise(1)) && Boolean.FALSE.equals(HubSummaries.cruise(0)), "Cruise on and off");
        require(HubSummaries.cruise(null) == null, "Unread cruise");
        // The drive mode as the riding screen names it; a code this model's names don't cover is unknown.
        require("SPORT".equals(HubSummaries.driveMode(Telemetry.drivingModeLabel(2538, 3.0))), "A named mode");
        String raw = Telemetry.drivingModeLabel(0, 7.0);
        require(raw.endsWith("(raw)") && HubSummaries.driveMode(raw) == null, "A raw code shows nothing: " + raw);
        require(HubSummaries.driveMode(Telemetry.drivingModeLabel(2538, null)) == null && HubSummaries.driveMode(null) == null
                && HubSummaries.driveMode("") == null, "No reading");
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

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
