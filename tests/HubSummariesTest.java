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
        require(HubSummaries.lights(0, 1) == HubSummaries.Lights.AUTO, "Automatic lights win");
        require(HubSummaries.lights(null, 1) == HubSummaries.Lights.AUTO, "Automatic with the headlight unread");
        require(HubSummaries.lights(1, 0) == HubSummaries.Lights.ON && HubSummaries.lights(1, null) == HubSummaries.Lights.ON, "On");
        require(HubSummaries.lights(0, 0) == HubSummaries.Lights.OFF, "Off");
        require(HubSummaries.lights(null, 0) == null && HubSummaries.lights(null, null) == null, "Unknown shows nothing");
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
    }

    private static void controls() {
        require(Boolean.TRUE.equals(HubSummaries.cruise(1)) && Boolean.FALSE.equals(HubSummaries.cruise(0)), "Cruise on and off");
        require(HubSummaries.cruise(null) == null, "Unread cruise");
    }

    private static void security() {
        HubSummaries.Security s = HubSummaries.security(true, false);
        require(s != null && s.pin && !s.proximity, "Both known");
        require(HubSummaries.security(null, true) == null && HubSummaries.security(false, null) == null, "Either unknown shows nothing");
    }

    private static void service() {
        require(HubSummaries.service(false, 3, 1) == null, "Reminders off: nothing");
        require(HubSummaries.service(true, 1, 2) == HubSummaries.Service.DUE, "Due wins");
        require(HubSummaries.service(true, 0, 2) == HubSummaries.Service.SOON, "Soon");
        require(HubSummaries.service(true, 0, 0) == HubSummaries.Service.CLEAR, "Clear");
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
