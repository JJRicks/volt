package com.jjricks.volt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The subject rows of the Scooter tab's index (hub layout plan, PR 3): Controls, Battery & charging, Lights & sound,
 * Security and Service, each with a live one-line summary. HubSummaries decides which summary applies; the words come
 * from HubText. Ride and Data join the list when Home becomes the hub (PR 11).
 */
final class HubSubjects {
    private HubSubjects() { }

    /** The Scooter tab's subjects, in Home's order. */
    static final String[] SCOOTER = {HubPages.BATTERY, HubPages.CONTROLS, HubPages.LIGHTS, HubPages.SECURITY, HubPages.SERVICE};

    static List<HubListScreen.Row> scooterIndex(ScreenHost host) {
        ArrayList<HubListScreen.Row> rows = new ArrayList<>();
        for (String subject : SCOOTER) {
            Msg title = HubText.title(subject);
            rows.add(new HubListScreen.Row(subject, HubPages.icon(subject), title,
                    () -> summary(host.service(), subject),
                    subject.equals(HubPages.SERVICE) ? () -> serviceDue(host.service()) : null,
                    null, () -> host.open(subject)));
        }
        return rows;
    }

    /**
     * Service's rows (hub layout plan, PR 4): maintenance reminders (with the dot while a check is due), diagnostics and
     * connection activity (a link: Data is their one home, so it opens Live Stats on its Diagnostics section), the
     * compatibility test, issue reports, and every experimental control. diagnostics opens Data's Diagnostics.
     */
    static List<HubListScreen.Row> service(ScreenHost host, Runnable diagnostics) {
        ArrayList<HubListScreen.Row> rows = new ArrayList<>();
        String page = HubPages.SERVICE_MAINTENANCE;
        rows.add(new HubListScreen.Row(page, "gear", HubText.title(page),
                () -> MaintenanceScreen.rowValue(MaintenanceScreen.summary(host.service())),
                () -> serviceDue(host.service()), null, () -> host.open(page)));
        rows.add(new HubListScreen.Row(DIAGNOSTICS, "analytics", HubText.diagnosticsRow(), null, null, null, diagnostics));
        for (String link : new String[] {HubPages.SERVICE_COMPATIBILITY, HubPages.SERVICE_REPORTS, HubPages.SERVICE_EXPERIMENTAL})
            rows.add(new HubListScreen.Row(link, icon(link), HubText.title(link), null, null, null, () -> host.open(link)));
        return rows;
    }

    /** The id of Service's diagnostics row (its view tag is "hub-row-" and this): a link, not a page. */
    static final String DIAGNOSTICS = "service.diagnostics";

    private static String icon(String page) {
        switch (page) {
            case HubPages.SERVICE_COMPATIBILITY: return "check";
            case HubPages.SERVICE_REPORTS: return "export";
            default: return "lab";
        }
    }

    private static boolean serviceDue(ScooterService service) {
        MaintenanceView.Summary summary = MaintenanceScreen.summary(service);
        return summary != null && summary.dot();
    }

    /** A subject's summary now, or null when its state isn't known. */
    static CharSequence summary(ScooterService service, String subject) {
        if (service == null) return null;
        BleController ble = service.ble;
        switch (subject) {
            case HubPages.BATTERY: {
                Map<String, TelemetryHistory.Reading> readings = service.displayReadings();
                TelemetryHistory.Reading level = readings.get("battery_percent"), state = readings.get("charging_state");
                // Charging only from a live reading: a last-seen or missing state is not "not charging".
                Boolean charging = state == null || !state.live || state.number == null ? null : state.number == 1;
                Integer percent = level == null || level.number == null || !Double.isFinite(level.number) ? null : (int) Math.round(level.number);
                boolean limitOffered = ordinary(ble, "charge_limit");
                Integer limit = limitOffered ? HubSummaries.known(ble.decision("charge_limit").variant, feature(service, "charge_limit")) : null;
                HubSummaries.Battery battery = HubSummaries.battery(limitOffered, limit, percent, charging);
                if (battery == null) return null;
                Formatted shown = Fmt.percent(Fmt.integerNumber(battery.percent));
                return battery.limit ? HubText.batteryLimit(shown, battery.charging) : HubText.batteryLevel(shown, battery.charging);
            }
            case HubPages.CONTROLS: {
                Boolean cruise = HubSummaries.cruise(ordinary(ble, "cruise_control") ? feature(service, "cruise_control") : null);
                Double code = ble != null && ble.ready() ? ble.telemetry.number("driving_mode") : null;
                if (cruise == null || code == null) return null;
                ScooterProfiles.Profile profile = service.profiles == null ? null : service.profiles.active();
                String mode = HubSummaries.driveMode(Telemetry.drivingModeLabel(ModelRegistry.driveModeLabelProduct(profile == null ? null : profile.savedDescriptor()), code));
                return mode == null ? null : HubText.controls(mode, cruise);
            }
            case HubPages.LIGHTS: {
                boolean autoOffered = ordinary(ble, "auto_sensor");
                HubSummaries.Lights lights = HubSummaries.lights(autoOffered, // "headlight" is the tail light
                        ordinary(ble, "headlight") ? feature(service, "headlight") : null, autoOffered ? feature(service, "auto_sensor") : null);
                if (lights == null) return null;
                return lights == HubSummaries.Lights.AUTO ? HubText.automaticLights() : HubText.tailLight(lights == HubSummaries.Lights.ON);
            }
            case HubPages.SECURITY: {
                ScooterCommands.PinState pin = ble == null || !ble.ready() ? null : ble.pinState();
                Boolean pinOn = pin == null || pin.status != 0 ? null : pin.configured && Boolean.TRUE.equals(pin.enabled);
                // As More › Security's row reads it (featureValue never takes a Lab rule for this sensitive control).
                Integer proximity = ble == null || !ble.ready() ? null : ble.featureValue("proximity_key");
                HubSummaries.Security security = HubSummaries.security(pinOn, proximity == null ? null : proximity == 1);
                return security == null ? null : HubText.security(security.pin, security.proximity);
            }
            case HubPages.SERVICE: {
                MaintenanceView.Summary summary = MaintenanceScreen.summary(service);
                HubSummaries.Service state = summary == null ? null : HubSummaries.service(summary.on, summary.due, summary.soon);
                if (state == null) return null;
                // Off, due or soon: the words its own row on the Service page shows.
                return state == HubSummaries.Service.CLEAR ? HubText.serviceClear() : MaintenanceScreen.rowValue(summary);
            }
            default: return null;
        }
    }

    /**
     * The model offers this control as an ordinary row (the check its Scooter page row makes), not only as a Lab try:
     * BleController.featureValue falls back to the Lab's rule, and a summary never states what only an experiment reads.
     */
    private static boolean ordinary(BleController ble, String key) {
        if (ble == null || !ble.controlOffered(key)) return false;
        FeatureVariant variant = ble.decision(key).variant;
        return variant == null || !variant.lab;
    }

    /** The scooter's value: live while connected, else the last one seen. Callers check ordinary() first. */
    private static Integer feature(ScooterService service, String key) {
        BleController ble = service.ble;
        Integer live = ble == null ? null : ble.featureValue(key);
        return live != null ? live : service.displayFeatures().get(key);
    }
}
