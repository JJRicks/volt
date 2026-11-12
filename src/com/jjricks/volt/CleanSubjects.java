package com.jjricks.volt;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Clean Home's seven subjects (hub layout plan, PR 8), in Home's order: each row has the subject's icon, name and a
 * live one-line summary, and opens the subject's page (ScreenHost.open; until the Hub replaces the tabs, a page opens
 * on the tab that holds it today). Service carries an orange dot while a maintenance check is due. A summary whose
 * state isn't known shows no line. Words from HubText; values formatted as the rest of Volt formats them.
 */
final class CleanSubjects {
    /** View tag of a subject's row: "home-subject-" and its page id. */
    static String tag(String subject) { return "home-subject-" + subject; }

    private final ScreenHost host;
    private final Ui ui;
    private final SharedPreferences preferences;
    private final LinearLayout view;
    private final List<String> ids = new ArrayList<>();
    private final List<TextView> names = new ArrayList<>(), values = new ArrayList<>();
    private final List<View> dots = new ArrayList<>();
    private boolean closed;
    /** Ride's line and when it was worked out: listing the rides folder waits at least RIDE_EVERY_MS between refreshes. */
    private CharSequence rideLine;
    private long rideAt = Long.MIN_VALUE;
    private static final long RIDE_EVERY_MS = 30_000;

    CleanSubjects(ScreenHost host) {
        this.host = host;
        ui = host.ui();
        preferences = host.activity().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        view = ui.groupCard();
        for (String subject : HubPages.SUBJECTS) {
            if (!ids.isEmpty()) ui.divider(view);
            view.addView(line(subject));
        }
    }

    View view() { return view; }

    private View line(String subject) {
        LinearLayout line = new LinearLayout(host.activity());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setBaselineAligned(false);
        line.setMinimumHeight(ui.dp(60));
        line.setPadding(0, ui.dp(8), 0, ui.dp(8));
        line.setTag(tag(subject));
        LinearLayout words = new LinearLayout(host.activity());
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = ui.text(HubText.title(subject), 15, false);
        name.setMaxLines(2);
        ui.decorate(name, HubPages.icon(subject));
        words.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView value = ui.caption("");
        value.setMaxLines(1);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setPaddingRelative(ui.dp(42), ui.dp(3), 0, 0);
        value.setVisibility(View.GONE);
        words.addView(value, new LinearLayout.LayoutParams(-1, -2));
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        View dot = new View(host.activity());
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(ui.orange);
        dot.setBackground(disc);
        dot.setVisibility(View.GONE);
        LinearLayout.LayoutParams dotSize = new LinearLayout.LayoutParams(ui.dp(10), ui.dp(10));
        dotSize.setMarginStart(ui.dp(8));
        line.addView(dot, dotSize);
        LinearLayout.LayoutParams chevronSize = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
        chevronSize.setMarginStart(ui.dp(8));
        line.addView(ui.icon("chevron", 15, ui.muted), chevronSize);
        line.setClickable(true);
        line.setFocusable(true);
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        line.setOnClickListener(v -> { if (!closed) host.open(subject); });
        ids.add(subject);
        names.add(name);
        values.add(value);
        dots.add(dot);
        return line;
    }

    void refresh() {
        if (closed) return;
        ScooterService service = host.service();
        for (int i = 0; i < ids.size(); i++) {
            String subject = ids.get(i);
            CharSequence summary = summary(service, subject);
            TextView value = values.get(i);
            if (summary == null || summary.length() == 0) { value.setText(""); value.setVisibility(View.GONE); }
            else {
                if (!TextUtils.equals(summary.toString(), value.getText())) value.setText(summary);
                value.setVisibility(View.VISIBLE);
            }
            boolean due = subject.equals(HubPages.SERVICE) && serviceDue(service);
            dots.get(i).setVisibility(due ? View.VISIBLE : View.GONE);
            // TalkBack reads the name and the summary; while a check is due, the name says so, as the tab's dot does.
            names.get(i).setContentDescription(due ? HubText.dueDescription(HubText.title(subject)).render() : null);
        }
    }

    void close() { closed = true; }

    private static boolean serviceDue(ScooterService service) {
        MaintenanceView.Summary summary = MaintenanceScreen.summary(service);
        return summary != null && summary.dot();
    }

    /** A subject's line now, or null when its state isn't known. */
    private CharSequence summary(ScooterService service, String subject) {
        if (subject.equals(HubPages.DATA)) return HubText.dataSummary().render();
        if (service == null) return null;
        BleController ble = service.ble;
        switch (subject) {
            case HubPages.RIDE: {
                long now = android.os.SystemClock.elapsedRealtime();
                if (rideLine == null || now - rideAt >= RIDE_EVERY_MS) { rideLine = ride(service); rideAt = now; }
                return rideLine;
            }
            case HubPages.BATTERY: {
                Map<String, TelemetryHistory.Reading> readings = service.displayReadings();
                TelemetryHistory.Reading level = readings.get("battery_percent"), state = readings.get("charging_state");
                boolean charging = state != null && state.live && state.number != null && state.number == 1;
                Integer limit = ble != null && ble.controlOffered("charge_limit") ? feature(service, "charge_limit") : null;
                if (limit != null && limit > 0 && limit <= 100)
                    return HubText.batteryLimit(Fmt.percent(Fmt.integerNumber(limit)), charging).render();
                if (level == null || level.number == null || !Double.isFinite(level.number)) return null;
                long percent = Math.round(Math.max(0, Math.min(100, level.number)));
                return HubText.batteryLevel(Fmt.percent(Fmt.integerNumber(percent)), charging).render();
            }
            case HubPages.CONTROLS: {
                Integer cruise = feature(service, "cruise_control");
                Double code = ble != null && ble.ready() ? ble.telemetry.number("driving_mode") : null;
                if (cruise == null || code == null) return null;
                ScooterProfiles.Profile profile = service.profiles == null ? null : service.profiles.active();
                String mode = Telemetry.drivingModeLabel(ModelRegistry.driveModeLabelProduct(profile == null ? null : profile.savedDescriptor()), code);
                return HubText.controls(mode, cruise == 1).render();
            }
            case HubPages.LIGHTS: {
                Integer automatic = feature(service, "auto_sensor"), headlight = feature(service, "headlight");
                if (automatic != null && automatic == 1) return HubText.headlightAuto().render();
                return headlight == null ? null : HubText.headlight(headlight == 1).render();
            }
            case HubPages.SECURITY: {
                if (ble == null || !ble.ready()) return null;
                ScooterCommands.PinState pin = ble.pinState();
                Integer proximity = ble.featureValue("proximity_key");
                if (pin == null || pin.status != 0 || proximity == null) return null;
                return HubText.security(pin.configured && Boolean.TRUE.equals(pin.enabled), proximity == 1).render();
            }
            case HubPages.SERVICE: {
                MaintenanceView.Summary summary = MaintenanceScreen.summary(service);
                if (summary == null || !summary.on) return null;
                return summary.due > 0 || summary.soon > 0 ? MaintenanceScreen.rowValue(summary) : HubText.serviceClear().render();
            }
            default: return null;
        }
    }

    /**
     * Ride: the newest finished ride's distance and when it ended (today, yesterday or its date), or "No rides yet".
     * Only rides already in memory (RideStore.listCached); with nothing cached yet the line waits for a later refresh.
     */
    private CharSequence ride(ScooterService service) {
        if (service.rides == null) return null;
        List<RideStore.Summary> rides = service.rides.listCached();
        if (rides == null) return null;
        RideStore.Summary last = null;
        for (RideStore.Summary ride : rides)
            if (!ride.active && (last == null || ride.shownEndWallMs > last.shownEndWallMs)) last = ride;
        if (last == null) return HubText.rideNone().render();
        if (last.distanceKm == null || !Double.isFinite(last.distanceKm)) return null;
        String units = preferences.getString("units", "mi");
        String distance = DisplayUnits.metricText(String.format(Locale.US, "%.1f km", last.distanceKm), units);
        Calendar then = Calendar.getInstance(), day = Calendar.getInstance();
        then.setTimeInMillis(last.shownEndWallMs);
        if (sameDay(then, day)) return HubText.rideLastToday(distance).render();
        day.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(then, day)) return HubText.rideLastYesterday(distance).render();
        Locale locale = Fmt.locale(Locale.getDefault());
        String date = new java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
                .format(new java.util.Date(last.shownEndWallMs));
        return HubText.rideLastOn(distance, date).render();
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** The scooter's value: live while connected, else the last one seen. */
    private static Integer feature(ScooterService service, String key) {
        BleController ble = service.ble;
        Integer live = ble == null ? null : ble.featureValue(key);
        return live != null ? live : service.displayFeatures().get(key);
    }
}
