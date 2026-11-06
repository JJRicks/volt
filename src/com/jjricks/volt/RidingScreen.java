package com.jjricks.volt;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Riding mode, Focus frame (riding-mode plan PR 2, with the owner's answers: Focus first, Q10).
 * A full-screen view for a phone on the handlebars: speed huge at the top, then Range at this pace,
 * battery with its segments, and a bottom row of trip, ride time and battery temperature, beside a
 * tall power bar. Sideways or on a wide screen the speed sits beside the readings.
 *
 * It only reads. No view here sends a scooter command, locks, unlocks, changes a setting or starts
 * a recording (Q12 A); there is no lock control and no lock state (Q16). Only the top bar takes
 * taps (back, 56 dp); the gauge area ignores them, so a bump or a glove changes nothing.
 */
final class RidingScreen implements AppScreen {
    /** Label size before the cap; labels grow with the text size up to RidingFocus.labelCap, numbers stay fixed. */
    private static final float LABEL_SP = 15, TOUCH_DP = 56;
    private final float labelCap;
    private final ScreenHost host;
    private final Ui ui;
    private final ScooterService service;
    private final SharedPreferences preferences;
    private final RidingSession session;
    private final RidingConfig config;
    private final RidingLayout layout = new RidingLayout();
    private final Runnable leave;
    private final FrameLayout view;
    private final LinearLayout frame;
    private final View dot;
    private final LinearLayout statusPill;
    /** A phone-width window: the warning chip shows its short rung so the status pill keeps room. */
    private final boolean narrow;
    private final TextView status, warning, speedLast, wattsCaption;
    private final Cell big, highlight, battery;
    private final List<Cell> bottom = new ArrayList<>();
    private final Segments segments;
    private final PowerBar bar;
    private final NumberView watts;
    /** Labels, units and dividers: the static chrome RidingBurnIn dims on a long ride. */
    private final List<View> chrome = new ArrayList<>();
    private long powerSampleMs = -1;
    private boolean closed;
    /** Brightness and the rotation lock while riding mode is open (its slider sits below). */
    private final RidingDisplay display;

    RidingScreen(ScreenHost host, RidingSession session, Runnable leave) {
        this.host = host;
        this.session = session;
        this.leave = leave;
        ui = host.ui();
        service = host.service();
        Context context = host.activity();
        preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        config = new RidingConfig(preferences, session.profileId);
        RidingLayout saved = config.layout();
        // This PR draws the Focus frame only (Halo and Trace come later): its choices, whichever frame is saved.
        for (RidingLayout.Spot spot : RidingLayout.frameDef(RidingLayout.FOCUS).spots)
            layout.setContent(RidingLayout.FOCUS, spot.id, saved.content(RidingLayout.FOCUS, spot.id));
        boolean large = RidingFocus.largeText(ui.textScale * context.getResources().getConfiguration().fontScale);
        android.content.res.Configuration configuration = context.getResources().getConfiguration();
        boolean side = RidingFocus.sideBySide(configuration.screenWidthDp, configuration.screenHeightDp, large);
        labelCap = RidingFocus.labelCap(side, configuration.screenHeightDp);

        view = new FrameLayout(context);
        view.setBackgroundColor(ui.bg);
        view.setContentDescription(T.ridingScreenDescription());
        int drift = ui.dp(RidingBurnIn.AMPLITUDE_DP);
        frame = new LinearLayout(context);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setPadding(ui.dp(side ? 20 : 16) + drift, ui.dp(8) + drift, ui.dp(side ? 20 : 16) + drift, ui.dp(12) + drift);
        view.addView(frame, new FrameLayout.LayoutParams(-1, -1));

        if (service != null && service.simulating()) {
            // The data is synthetic: say so here too, as Home's banner does.
            TextView banner = ui.text(T.mainSimulationBanner(), 12, true);
            banner.setTextColor(ui.onAccent);
            banner.setGravity(Gravity.CENTER);
            banner.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4));
            banner.setBackground(ui.shape(ui.accent, 10, 0));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.bottomMargin = ui.dp(6);
            frame.addView(banner, p);
        }

        // Top bar: the back disc and the status pill, the only things that take taps.
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout back = new FrameLayout(context);
        back.setBackground(disc());
        back.setClickable(true);
        back.setFocusable(true);
        back.setContentDescription(T.ridingBack());
        back.setOnClickListener(v -> { if (!closed) leave.run(); });
        back.addView(ui.icon("back", 26, ui.text), new FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER));
        top.addView(back, new LinearLayout.LayoutParams(ui.dp(TOUCH_DP), ui.dp(TOUCH_DP)));
        narrow = configuration.screenWidthDp < 600;
        LinearLayout pill = statusPill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        pill.setPadding(ui.dp(18), 0, ui.dp(18), 0);
        pill.setBackground(ui.shape(ui.dark ? ui.surface : ui.raised, TOUCH_DP / 2, 0));
        dot = new View(context);
        LinearLayout.LayoutParams dotSize = new LinearLayout.LayoutParams(ui.dp(10), ui.dp(10));
        dotSize.setMarginEnd(ui.dp(10));
        pill.addView(dot, dotSize);
        status = ui.text("", labelSp(17), true);
        status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ui.fitStatusLine(status);
        pill.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pillSize = new LinearLayout.LayoutParams(0, ui.dp(TOUCH_DP), 1);
        pillSize.setMarginStart(ui.dp(12));
        top.addView(topControl(layout.content(RidingLayout.FOCUS, "top"), pill), pillSize);
        warning = ui.text("", labelSp(15), true);
        warning.setTextColor(ui.onAccent);
        warning.setGravity(Gravity.CENTER);
        warning.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        warning.setBackground(ui.shape(ui.accent, TOUCH_DP / 2, 0));
        ui.fitStatusLine(warning);
        warning.setVisibility(View.GONE);
        LinearLayout.LayoutParams warningSize = new LinearLayout.LayoutParams(-2, ui.dp(TOUCH_DP));
        warningSize.setMarginStart(ui.dp(10));
        top.addView(warning, warningSize);
        LinearLayout.LayoutParams topSize = new LinearLayout.LayoutParams(-1, -2);
        topSize.bottomMargin = ui.dp(side ? 10 : 14);
        frame.addView(top, topSize);

        // The cells.
        // The unit sits under the speed, except in a short side-by-side window, where it goes beside so the number keeps its height.
        big = new Cell(layout.content(RidingLayout.FOCUS, "big"), 200, !(side && configuration.screenHeightDp < RidingFocus.SHORT_HEIGHT_DP));
        speedLast = ui.text("", labelSp(LABEL_SP), false);
        speedLast.setTextColor(ui.dim);
        speedLast.setMaxLines(1);
        speedLast.setVisibility(View.INVISIBLE);
        big.addView(speedLast, new LinearLayout.LayoutParams(-1, -2));
        highlight = new Cell(layout.content(RidingLayout.FOCUS, "highlight"), 84, false);
        battery = new Cell(layout.content(RidingLayout.FOCUS, RidingFocus.BATTERY_ROW), 84, false);
        segments = new Segments();
        battery.addBeside(segments);
        for (String spot : RidingFocus.bottomRow(large))
            bottom.add(new Cell(layout.content(RidingLayout.FOCUS, spot), 60, false));
        watts = new NumberView(64, false);
        wattsCaption = ui.text("", labelSp(LABEL_SP), false);
        wattsCaption.setTextColor(ui.muted);
        wattsCaption.setMaxLines(1);
        wattsCaption.setEllipsize(TextUtils.TruncateAt.END);
        chrome.add(wattsCaption);
        LinearLayout power = column();
        power.addView(watts, new LinearLayout.LayoutParams(-1, 0, 1));
        power.addView(wattsCaption, new LinearLayout.LayoutParams(-1, -2));
        bar = new PowerBar();

        LinearLayout body = row();
        body.addView(bar, new LinearLayout.LayoutParams(ui.dp(side ? 40 : 44), -1));
        LinearLayout bottomRow = row();
        for (int i = 0; i < bottom.size(); i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) p.setMarginStart(ui.dp(14));
            bottomRow.addView(bottom.get(i), p);
        }
        if (side) {
            // Speed and the power readout share the left column, so each reading on the right keeps a large number.
            LinearLayout speed = column();
            speed.addView(big, new LinearLayout.LayoutParams(-1, 0, 2.3f));
            divider(speed);
            speed.addView(power, new LinearLayout.LayoutParams(-1, 0, 1f));
            LinearLayout.LayoutParams speedSize = new LinearLayout.LayoutParams(0, -1, 1.1f);
            speedSize.setMarginStart(ui.dp(18));
            body.addView(speed, speedSize);
            LinearLayout readings = column();
            readings.addView(highlight, new LinearLayout.LayoutParams(-1, 0, 1.1f));
            divider(readings);
            readings.addView(battery, new LinearLayout.LayoutParams(-1, 0, 1f));
            divider(readings);
            readings.addView(bottomRow, new LinearLayout.LayoutParams(-1, 0, 1f));
            LinearLayout.LayoutParams readingsSize = new LinearLayout.LayoutParams(0, -1, 1);
            readingsSize.setMarginStart(ui.dp(24));
            body.addView(readings, readingsSize);
        } else {
            LinearLayout readings = column();
            readings.addView(big, new LinearLayout.LayoutParams(-1, 0, 3.4f));
            divider(readings);
            readings.addView(highlight, new LinearLayout.LayoutParams(-1, 0, 1.5f));
            divider(readings);
            readings.addView(battery, new LinearLayout.LayoutParams(-1, 0, 1.35f));
            divider(readings);
            readings.addView(bottomRow, new LinearLayout.LayoutParams(-1, 0, 1.25f));
            divider(readings);
            readings.addView(power, new LinearLayout.LayoutParams(-1, 0, 1.3f));
            LinearLayout.LayoutParams readingsSize = new LinearLayout.LayoutParams(0, -1, 1);
            readingsSize.setMarginStart(ui.dp(16));
            body.addView(readings, readingsSize);
        }
        frame.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        display = new RidingDisplay(host.activity(), ui, preferences);
        frame.addView(display.controls(), RidingDisplay.slot(ui));
        // The big watt number sits on the bar's zero line, so the eye always finds it in the same place.
        body.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> bar.post(this::alignZero));
        refresh();
    }

    @Override public View view() { return view; }
    @Override public void close() {
        closed = true;
        display.detach();
    }

    /**
     * The top spot. Record is Focus's default (owner): the shared recording control it needs plugs in
     * here. Until then Record and Status both show the status pill: drive mode and connection, never
     * the lock, and a tap does nothing, so riding mode never starts or stops a recording itself.
     */
    private View topControl(String content, View statusPill) {
        return statusPill;
    }

    private void alignZero() {
        if (closed || !watts.isLaidOut() || !bar.isLaidOut()) return;
        int[] a = new int[2], b = new int[2];
        watts.getLocationInWindow(a);
        bar.getLocationInWindow(b);
        bar.setZeroY(a[1] - b[1] + watts.baselineY());
    }

    @Override
    public void refresh() {
        if (closed) return;
        long now = SystemClock.elapsedRealtime();
        String units = units();
        BleController ble = service == null ? null : service.ble;
        Double recordingKm = null;
        if (ble != null) {
            RideStore.Summary ride = service.isRecording() ? service.rides.activeSummary() : null;
            session.values.setRecordingStart(ride == null ? null : ride.startMonotonicMs);
            recordingKm = ride == null ? null : ride.distanceKm;
            Double charging = ble.telemetry.number("charging_state");
            session.feed(ble.telemetry.metrics(), now, charging != null && charging > 0, units, true);
            if (session.values.takeScaleChanged()) config.save(session.values.scale());
        }
        if (session.takeBandDrop()) Haptics.tick(view);
        showStatus(ble, now);
        big.show(now, units, recordingKm);
        highlight.show(now, units, recordingKm);
        battery.show(now, units, recordingKm);
        for (Cell cell : bottom) cell.show(now, units, recordingKm);
        showSpeedLast(now, units);
        showPower(now);
        // OLED care: the whole layout drifts slowly and the static chrome dims on a long ride.
        float[] offset = RidingBurnIn.offsetDp(now - session.openedMs);
        frame.setTranslationX(ui.dp(offset[0]));
        frame.setTranslationY(ui.dp(offset[1]));
        float alpha = RidingBurnIn.chromeAlpha(now - session.openedMs);
        for (View v : chrome) v.setAlpha(alpha);
    }

    private void showStatus(BleController ble, long now) {
        boolean ready = ble != null && ble.ready();
        BleController.State state = ble == null ? null : ble.state();
        boolean reconnecting = ble != null && !ready && (ble.connected() || ble.recoverableConnectionLoss()
                || state == BleController.State.CONNECTING || state == BleController.State.AUTHENTICATING
                || state == BleController.State.SCANNING || state == BleController.State.WAITING);
        RidingFocus.Link link = RidingFocus.link(ready, reconnecting);
        int color = link == RidingFocus.Link.CONNECTED ? ui.ok : ui.orange;
        dot.setBackground(oval(color));
        status.setTextColor(link == RidingFocus.Link.CONNECTED ? ui.text : ui.orange);
        if (link == RidingFocus.Link.CONNECTED) {
            String mode = mode(ble);
            // The green dot alone is the last rung: the pill's icon (the narrow-label ladder).
            if (mode != null) ui.statusRungs(status, T.ridingStatusConnectedMode(mode), T.ridingStatusConnectedModeShort(), "");
            else ui.statusRungs(status, T.ridingStatusConnectedModeShort(), "");
            statusPill.setContentDescription(mode != null ? T.ridingStatusConnectedMode(mode) : T.ridingStatusConnectedModeShort());
        } else if (link == RidingFocus.Link.RECONNECTING) {
            ui.statusRungs(status, T.ridingStatusReconnecting());
            statusPill.setContentDescription(T.ridingStatusReconnecting());
        } else {
            long last = session.lastReadingMs(now);
            if (last >= 0) {
                String ago = clock(now - last);
                ui.statusRungs(status, T.ridingStatusNotConnectedSeen(ago), T.ridingStatusNotConnectedSeenShort(ago),
                        T.ridingStatusNotConnectedSeenCompact());
                statusPill.setContentDescription(T.ridingStatusNotConnectedSeen(ago));
            } else {
                ui.statusRungs(status, T.ridingStatusNotConnectedSeenCompact());
                statusPill.setContentDescription(T.ridingStatusNotConnectedSeenCompact());
            }
        }
        RidingValues.Reading code = session.values.reading("warning_code", now);
        long warningCode = code == null ? 0 : Math.round(code.value);
        if (warningCode > 0) {
            String number = Fmt.integer(warningCode);
            warning.setVisibility(View.VISIBLE);
            // On a phone the chip yields room to a Reconnecting or Not connected pill: the red chip and its number stay.
            if (narrow && link != RidingFocus.Link.CONNECTED) ui.statusRungs(warning, number);
            else if (narrow) ui.statusRungs(warning, T.ridingWarningCodeShort(number));
            else ui.statusRungs(warning, T.ridingWarningCode(number), T.ridingWarningCodeShort(number));
            warning.setContentDescription(T.ridingWarningSpoken(number));
            warning.setAlpha(code.stale ? .6f : 1);
        } else warning.setVisibility(View.GONE);
    }

    /** The drive mode word as the scooter shows it ("ECO", "SPORT"), or null when unknown or raw. */
    private String mode(BleController ble) {
        RidingValues.Reading r = session.values.reading("driving_mode", SystemClock.elapsedRealtime());
        if (r == null || r.stale || ble == null) return null;
        String label = Telemetry.drivingModeLabel(ModelRegistry.driveModeLabelProduct(ble.descriptor()), r.value);
        return label == null || Telemetry.rawReading(null, label) || label.equals(Telemetry.drivingModeLabel(0, null)) ? null : label;
    }

    private void showSpeedLast(long now, String units) {
        if (!RidingLayout.SPEED.equals(big.content)) { speedLast.setVisibility(View.GONE); return; }
        RidingValues.Speed speed = session.values.speed(now);
        if (speed.kmh == null && speed.lastKmh != null) {
            speedLast.setText(T.ridingSpeedLast(T.fmtQuantity(Fmt.decimal(Locale.US, speedIn(speed.lastKmh, units), 1), speedUnit(units).symbol())));
            speedLast.setVisibility(View.VISIBLE);
        } else speedLast.setVisibility(View.INVISIBLE);
    }

    private void showPower(long now) {
        RidingValues.Power p = session.values.power(now);
        RidingValues.Reading sample = session.values.reading(RidingValues.POWER_KEY, now);
        RidingValues.PowerScale scale = session.values.scale();
        if (p.watts == null) {
            watts.set("", T.ridingNoValue(), "", "", ui.dim, ui.dim);
            wattsCaption.setText(T.ridingPowerDrawPeakShort());
            bar.set(null, p.direction, scale, p.peakDrawW, 0, now);
            return;
        }
        boolean regen = p.direction == RidingValues.Direction.REGEN && p.watts < 0;
        boolean unknown = p.direction == RidingValues.Direction.UNKNOWN;
        int color = regen ? ui.ok : unknown ? ui.muted : ui.text;
        Formatted shown = Fmt.quantity(Fmt.integerNumber(Math.round(p.watts)), Fmt.unit(UnitCode.WATT));
        watts.set(shown, color, regen ? ui.ok : ui.muted);
        double peak = regen ? p.peakRegenW : p.peakDrawW;
        String peakText = T.fmtQuantity(Fmt.integer(Math.round(Math.abs(peak))), UnitCode.WATT.symbol());
        String caption = unknown ? T.ridingPowerUnknown() : Math.abs(peak) < 1 ? null
                : regen ? T.ridingPowerRegenPeak(peakText) : T.ridingPowerDrawPeak(peakText);
        String brief = regen ? T.ridingPowerRegenPeakShort() : T.ridingPowerDrawPeakShort();
        fit(wattsCaption, caption == null ? brief : caption, brief);
        if (sample != null && sample.updatedMs != powerSampleMs) {
            powerSampleMs = sample.updatedMs;
            bar.set(p.watts, p.direction, scale, p.peakDrawW, sample.intervalMs, now);
        } else bar.set(p.watts, p.direction, scale, p.peakDrawW, -1, now);
    }

    /** A caption's ladder: the full text while it fits, else the brief one; never a broken word. */
    private void fit(TextView view, String full, String brief) {
        int room = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        String pick = room <= 0 || view.getPaint().measureText(full) <= room ? full : brief;
        if (TileRung.needsSet(pick, view.getText())) view.setText(pick);
        if (room <= 0) view.post(() -> { if (!closed && view.getWidth() > 0) fit(view, full, brief); });
    }

    // What each content shows.

    /** A reading as a cell shows it: its label's ladder (and icon), its number in parts, and its colours. */
    private static final class Shown {
        String[] rungs = new String[0];
        String icon;
        CharSequence prefix = "", main = "", fraction = "", suffix = "";
        int color, unitColor;
        String spoken;
        RidingValues.Band band;
        double percent = Double.NaN;
    }

    private Shown shown(String content, long now, String units, Double recordingKm) {
        Shown s = new Shown();
        s.color = ui.text;
        s.unitColor = ui.muted;
        RidingValues v = session.values;
        switch (content) {
            case RidingLayout.SPEED: {
                s.rungs = new String[]{T.ridingLabelSpeed()};
                RidingValues.Speed speed = v.speed(now);
                UnitCode unit = speedUnit(units);
                s.suffix = unit.symbol();
                if (speed.kmh == null) { none(s); break; }
                double value = speedIn(speed.kmh, units);
                // Tenths only where the scooter sends them (0x92) or the simulator does; never invented.
                boolean tenths = speed.tenths || (service != null && service.simulating());
                if (tenths) {
                    long[] parts = RidingFocus.tenths(value);
                    s.main = Fmt.integer(parts[0]);
                    s.fraction = decimalSeparator() + Fmt.integer(parts[1]);
                } else s.main = Fmt.integer(Math.round(value));
                s.spoken = T.fmtQuantity(Fmt.decimal(Locale.US, value, tenths ? 1 : 0), unit.symbol());
                if (speed.stale) dim(s);
                break;
            }
            case RidingLayout.RANGE_AT_PACE: case RidingLayout.RANGE_SCOOTER: case RidingLayout.RANGE_VOLT: {
                RidingValues.Reading scooter = v.reading(RidingValues.SCOOTER_RANGE_KEY, now);
                RidingFocus.Range range = RidingFocus.range(content, session.range(), scooter == null ? null : scooter.value,
                        scooter == null || scooter.stale, units);
                s.icon = "route";
                if (range.kind == RidingFocus.RangeKind.SCOOTER)
                    s.rungs = new String[]{T.ridingLabelRangeScooter(), T.ridingLabelRangeScooterShort()};
                else if (range.kind == RidingFocus.RangeKind.VOLT)
                    s.rungs = new String[]{T.ridingLabelRangeVolt(), T.ridingLabelRangeVoltShort()};
                else s.rungs = new String[]{T.ridingLabelRangeAtPace(), T.ridingLabelRangeAtPaceShort()};
                if (range.kind == RidingFocus.RangeKind.ALMOST_EMPTY) {
                    s.main = T.ridingRangeAlmostEmpty();
                    s.color = ui.orange;
                    s.spoken = s.main.toString();
                    break;
                }
                if (range.value == null) { none(s); break; }
                Formatted number = Fmt.decimalNumber(Locale.US, range.value, range.decimals);
                Formatted quantity = Fmt.quantity(range.approx ? Fmt.compose(T.RIDING_APPROX, number) : number, Fmt.unit(distanceUnit(units)));
                split(s, quantity);
                if (range.amber) s.color = ui.orange;
                if (range.dim) dim(s);
                break;
            }
            case RidingLayout.BATTERY: {
                s.rungs = new String[]{T.ridingLabelBattery()};
                s.icon = "battery";
                RidingValues.Reading r = v.reading(RidingValues.BATTERY_KEY, now);
                if (r == null) { none(s); break; }
                split(s, Fmt.percent(Fmt.integerNumber(Math.round(r.value))));
                s.percent = r.value;
                s.band = RidingValues.band(r.value);
                // Amber below 30 %, red below 15 % (owner): the UT5 MAX limits power below 30 %.
                if (s.band == RidingValues.Band.AMBER) s.color = ui.orange;
                else if (s.band == RidingValues.Band.RED) s.color = ui.danger;
                if (r.stale) dim(s);
                break;
            }
            case RidingLayout.TRIP: {
                RidingValues.Trip trip = v.trip(recordingKm, now);
                s.rungs = new String[]{trip.fromRecording ? T.ridingLabelRideDistance() : T.ridingLabelTrip()};
                s.icon = "route";
                if (trip.km == null) { none(s); break; }
                split(s, Fmt.quantity(Fmt.decimalNumber(Locale.US, RidingFocus.distance(trip.km, units), 1), Fmt.unit(distanceUnit(units))));
                RidingValues.Reading r = v.reading("trip_km", now);
                if (!trip.fromRecording && (r == null || r.stale)) dim(s);
                break;
            }
            case RidingLayout.RIDE_TIME: case RidingLayout.RECORDING_TIME: {
                boolean ride = content.equals(RidingLayout.RIDE_TIME);
                s.rungs = ride ? new String[]{T.ridingLabelRideTime(), T.ridingLabelRideTimeShort()}
                        : new String[]{T.ridingLabelRecordingTime(), T.ridingLabelRecordingTimeShort()};
                s.icon = "clock";
                Long ms = ride ? v.rideTimeMs(now) : v.recordingTimeMs(now);
                if (ms == null) { none(s); break; }
                s.main = clock(ms);
                s.spoken = s.main.toString();
                RidingValues.Reading r = v.reading(RidingValues.TRIP_MINUTES_KEY, now);
                if (ride && (r == null || r.stale)) dim(s);
                break;
            }
            case RidingLayout.AVERAGE_SPEED: case RidingLayout.MAX_SPEED: {
                boolean average = content.equals(RidingLayout.AVERAGE_SPEED);
                s.rungs = average ? new String[]{T.ridingLabelAverageSpeed(), T.ridingLabelAverageSpeedShort()}
                        : new String[]{T.ridingLabelMaxSpeed(), T.ridingLabelMaxSpeedShort()};
                s.icon = "speed";
                RidingValues.Reading r = v.reading(average ? "average_speed_kmh" : "maximum_speed_kmh", now);
                if (r == null) { none(s); break; }
                split(s, Fmt.quantity(Fmt.decimalNumber(Locale.US, speedIn(r.value, units), 1), Fmt.unit(speedUnit(units))));
                if (r.stale) dim(s);
                break;
            }
            case RidingLayout.EFFICIENCY: {
                String per = T.ridingUnitWhPer(distanceUnit(units).symbol());
                s.rungs = new String[]{T.ridingLabelEfficiency(), per};
                RidingValues.Efficiency e = v.efficiency(now);
                if (e.whPerKm == null) { none(s); break; }
                double value = "km".equals(units) ? e.whPerKm : e.whPerKm * RidingFocus.KM_PER_MI;
                split(s, Fmt.quantity(Fmt.integerNumber(Math.round(value)), Fmt.unitText(per)));
                break;
            }
            case RidingLayout.RIDE_ENERGY: {
                s.rungs = new String[]{T.ridingLabelRideEnergy(), T.ridingLabelRideEnergyShort()};
                s.icon = "energy";
                split(s, Fmt.quantity(Fmt.integerNumber(Math.round(v.rideEnergyWh())), Fmt.unit(UnitCode.WATT_HOUR)));
                break;
            }
            case RidingLayout.VOLTAGE: case RidingLayout.CURRENT: case RidingLayout.ODOMETER: {
                boolean volts = content.equals(RidingLayout.VOLTAGE), odometer = content.equals(RidingLayout.ODOMETER);
                s.rungs = volts ? new String[]{T.ridingLabelVoltage()} : odometer
                        ? new String[]{T.ridingLabelOdometer(), T.ridingLabelOdometerShort()} : new String[]{T.ridingLabelCurrent()};
                RidingValues.Reading r = v.reading(volts ? "voltage_v" : odometer ? "odometer_km" : "current_a", now);
                if (r == null) { none(s); break; }
                double value = odometer ? RidingFocus.distance(r.value, units) : r.value;
                split(s, Fmt.quantity(Fmt.decimalNumber(Locale.US, value, 1),
                        Fmt.unit(volts ? UnitCode.VOLT : odometer ? distanceUnit(units) : UnitCode.AMP)));
                if (r.stale) dim(s);
                break;
            }
            case RidingLayout.BATTERY_TEMPERATURE: {
                s.rungs = new String[]{T.ridingLabelBatteryTemperature(), T.ridingLabelBatteryTemperatureShort()};
                s.icon = "temperature";
                RidingValues.Reading r = v.reading("temperature_c", now);
                if (r == null) { none(s); break; }
                // No preference reads as Celsius, as everywhere else.
                Formatted temperature = DisplayTemperature.quantity(r.value, preferences.getString("temperature_unit", null), 0);
                if (temperature == null) { none(s); break; }
                split(s, temperature);
                if (r.stale) dim(s);
                break;
            }
            case RidingLayout.DRIVE_MODE: {
                s.rungs = new String[]{T.ridingLabelDriveMode(), T.ridingLabelDriveModeShort()};
                String mode = mode(service == null ? null : service.ble);
                if (mode == null) { none(s); break; }
                s.main = mode;
                s.spoken = mode;
                break;
            }
            case RidingLayout.CLOCK: {
                s.rungs = new String[]{T.ridingLabelClock()};
                s.icon = "clock";
                s.main = DisplayTime.clock(host.activity(), preferences, System.currentTimeMillis());
                s.spoken = s.main.toString();
                break;
            }
            case RidingLayout.POWER: {
                s.rungs = new String[]{T.ridingLabelPower()};
                s.icon = "power";
                RidingValues.Power p = v.power(now);
                if (p.watts == null) { none(s); break; }
                split(s, Fmt.quantity(Fmt.integerNumber(Math.round(p.watts)), Fmt.unit(UnitCode.WATT)));
                if (p.direction == RidingValues.Direction.REGEN && p.watts < 0) s.color = ui.ok;
                break;
            }
            default: none(s); break;
        }
        return s;
    }

    private void none(Shown s) {
        s.prefix = "";
        s.main = T.ridingNoValue();
        s.fraction = "";
        s.color = ui.dim;
        s.unitColor = ui.dim;
        s.spoken = null;
    }

    private void dim(Shown s) { s.color = ui.dim; s.unitColor = ui.dim; }

    /** A formatted quantity in its parts: the number large, the text before and after it small. */
    private static void split(Shown s, Formatted f) {
        String text = f.toString();
        if (f.numbers() == 0) { s.main = text; s.spoken = text; return; }
        int start = f.numberStart(f.numbers() - 1), end = f.numberEnd(f.numbers() - 1);
        s.prefix = text.substring(0, start);
        s.main = text.substring(start, end);
        s.suffix = text.substring(end);
        s.spoken = text;
    }

    private String units() { return preferences.getString("units", "mi"); }
    private static double speedIn(double kmh, String units) { return RidingFocus.distance(kmh, units); }
    private static UnitCode speedUnit(String units) { return "km".equals(units) ? UnitCode.KMH : UnitCode.MPH; }
    private static UnitCode distanceUnit(String units) { return "km".equals(units) ? UnitCode.KM : UnitCode.MI; }
    private static String decimalSeparator() {
        return String.valueOf(DecimalFormatSymbols.getInstance(Fmt.locale(Locale.US)).getDecimalSeparator());
    }

    private static String clock(long ms) { return RidingFocus.clockText(ms); }

    private float labelSp(float sp) { return sp * Math.min(ui.textScale, labelCap) / ui.textScale; }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(host.activity());
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(host.activity());
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }
    private void divider(LinearLayout parent) {
        View line = new View(host.activity());
        line.setBackgroundColor(ui.outline);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1)));
        p.topMargin = ui.dp(6);
        p.bottomMargin = ui.dp(8);
        parent.addView(line, p);
        chrome.add(line);
    }
    private GradientDrawable disc() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(ui.dark ? ui.surface : ui.raised);
        return d;
    }
    private static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    // Views.

    /** One reading: its laddered label over its big number; the battery row adds its segments beside the number. */
    private final class Cell extends LinearLayout {
        final String content;
        final Label label;
        final NumberView number;
        private LinearLayout line;
        Cell(String content, float maxSp, boolean unitBelow) {
            super(host.activity());
            this.content = content;
            setOrientation(VERTICAL);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setFocusable(true);
            label = new Label();
            addView(label, new LayoutParams(-1, -2));
            number = new NumberView(maxSp, unitBelow);
            LayoutParams p = new LayoutParams(-1, 0, 1);
            p.topMargin = ui.dp(2);
            addView(number, p);
        }
        void addBeside(View extra) {
            removeView(number);
            line = new LinearLayout(host.activity());
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(number, new LayoutParams(0, -1, 1));
            LayoutParams e = new LayoutParams(0, ui.dp(34), 1.15f);
            e.setMarginStart(ui.dp(14));
            line.addView(extra, e);
            LayoutParams p = new LayoutParams(-1, 0, 1);
            p.topMargin = ui.dp(2);
            addView(line, p);
        }
        void show(long now, String units, Double recordingKm) {
            Shown s = shown(content, now, units, recordingKm);
            label.set(s.icon, s.rungs);
            number.set(s.prefix, s.main, s.fraction, s.suffix, s.color, s.unitColor);
            String name = s.rungs.length == 0 ? "" : s.rungs[0];
            setContentDescription(s.spoken == null ? T.ridingReadingSpokenNone(name) : T.ridingReadingSpoken(name, s.spoken));
            if (this == battery) segments.set(s.percent, s.band, s.color == ui.dim);
        }
    }

    /**
     * A reading's name on the narrow-label ladder: the first rung that fits at full size, then its icon
     * alone when one is named, never a smaller or broken word.
     */
    private final class Label extends TextView {
        private String[] rungs = new String[0];
        private String icon;
        Label() {
            super(host.activity());
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, labelSp(LABEL_SP) * ui.textScale);
            setTypeface(ui.medium());
            setTextColor(ui.muted);
            setIncludeFontPadding(false);
            setMaxLines(1);
            setEllipsize(TextUtils.TruncateAt.END);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            chrome.add(this);
        }
        void set(String icon, String... rungs) {
            if (java.util.Arrays.equals(this.rungs, rungs) && TextUtils.equals(this.icon, icon)) return;
            this.rungs = rungs;
            this.icon = icon;
            fit();
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (w != ow) post(this::fit);
        }
        private void fit() {
            if (rungs.length == 0) { setText(""); return; }
            int room = getWidth() - getPaddingLeft() - getPaddingRight();
            if (room <= 0) { show(rungs[0], false); return; }
            float[] widths = new float[rungs.length];
            for (int i = 0; i < rungs.length; i++) widths[i] = getPaint().measureText(rungs[i]);
            int pick = TileRung.first(widths, room);
            if (pick < 0 && icon != null) show("", true);
            else show(rungs[pick < 0 ? rungs.length - 1 : pick], false);
        }
        private void show(String text, boolean iconOnly) {
            if (TileRung.needsSet(text, getText())) setText(text);
            Drawable glyph = iconOnly ? glyph(icon, ui.muted, Math.round(getTextSize() * 1.2f)) : null;
            setCompoundDrawables(glyph, null, null, null);
        }
    }

    private Drawable glyph(String key, int color, int size) {
        Drawable d = new Drawable() {
            @Override public void draw(Canvas canvas) {
                Rect b = getBounds();
                ui.drawIcon(canvas, key, color, b.exactCenterX(), b.exactCenterY(), Math.min(b.width(), b.height()));
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(ColorFilter filter) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
        d.setBounds(0, 0, size, size);
        return d;
    }

    /**
     * A big number drawn to fit its box at a fixed largest size: the number in Saira bold, an optional
     * tenths part and the text around it (a unit, "≈", "%") smaller, on one baseline; or, for speed,
     * the unit under the number. Labels are separate views; nothing here is a tap target.
     */
    private final class NumberView extends View {
        private final float maxPx;
        private final boolean unitBelow;
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private CharSequence prefix = "", main = "", fraction = "", suffix = "";
        private int color, unitColor;
        private float baseline;
        NumberView(float maxSp, boolean unitBelow) {
            super(host.activity());
            maxPx = ui.dp(maxSp);
            this.unitBelow = unitBelow;
            big.setTypeface(ui.bold());
            small.setTypeface(ui.semibold());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void set(Formatted f, int color, int unitColor) {
            Shown s = new Shown();
            split(s, f);
            set(s.prefix, s.main, "", s.suffix, color, unitColor);
        }
        void set(CharSequence prefix, CharSequence main, CharSequence fraction, CharSequence suffix, int color, int unitColor) {
            if (TextUtils.equals(this.prefix, prefix) && TextUtils.equals(this.main, main) && TextUtils.equals(this.fraction, fraction)
                    && TextUtils.equals(this.suffix, suffix) && this.color == color && this.unitColor == unitColor) return;
            this.prefix = prefix; this.main = main; this.fraction = fraction; this.suffix = suffix;
            this.color = color; this.unitColor = unitColor;
            invalidate();
        }
        /** The number's baseline in this view, once drawn: where the power bar puts its zero line. */
        float baselineY() { return baseline > 0 ? baseline - capHeight(big) / 2 : getHeight() / 2f; }
        private float capHeight(Paint p) {
            Rect r = new Rect();
            String eight = Fmt.integer(8);
            p.getTextBounds(eight, 0, eight.length(), r);
            return r.height();
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || main.length() == 0) return;
            // The no-reading dash is a mark, not a number: half size, so it never reads as a bar.
            float size = TextUtils.equals(main, T.ridingNoValue()) ? maxPx * .5f : maxPx;
            float fractionShare = .46f, unitShare = unitBelow ? .30f : .42f;
            for (int pass = 0; pass < 2; pass++) {
                big.setTextSize(size);
                small.setTextSize(size * unitShare);
                float width = small.measureText(prefix, 0, prefix.length()) + big.measureText(main, 0, main.length())
                        + measureFraction(size * fractionShare) + (unitBelow ? 0 : gap() + small.measureText(suffix, 0, suffix.length()));
                if (unitBelow) width = Math.max(width, small.measureText(suffix, 0, suffix.length()));
                float cap = capHeight(big), unitCap = capHeight(small);
                float height = cap + (unitBelow && suffix.length() > 0 ? unitCap * 1.9f : 0) + ui.dp(4);
                float scale = Math.min(1, Math.min((w - ui.dp(2)) / Math.max(1, width), h / Math.max(1, height)));
                if (scale >= .999f) break;
                size *= scale;
            }
            big.setTextSize(size);
            small.setTextSize(size * unitShare);
            float cap = capHeight(big);
            baseline = cap + ui.dp(2);
            float x = 0;
            small.setColor(unitColor);
            if (prefix.length() > 0) { canvas.drawText(prefix, 0, prefix.length(), x, baseline, small); x += small.measureText(prefix, 0, prefix.length()); }
            big.setColor(color);
            canvas.drawText(main, 0, main.length(), x, baseline, big);
            x += big.measureText(main, 0, main.length());
            if (fraction.length() > 0) {
                Paint f = new Paint(big);
                f.setTextSize(size * fractionShare);
                canvas.drawText(fraction, 0, fraction.length(), x, baseline, f);
                x += f.measureText(fraction, 0, fraction.length());
            }
            if (suffix.length() > 0) {
                if (unitBelow) canvas.drawText(suffix, 0, suffix.length(), 0, baseline + capHeight(small) * 1.9f, small);
                else canvas.drawText(suffix, 0, suffix.length(), x + gap(), baseline, small);
            }
        }
        /** A unit symbol set straight after the number (speed's "mph") gets a thin space; formatted text brings its own. */
        private float gap() {
            return suffix.length() > 0 && !Character.isWhitespace(suffix.charAt(0)) && Character.isLetter(suffix.charAt(0))
                    ? small.getTextSize() * .3f : 0;
        }
        private float measureFraction(float size) {
            if (fraction.length() == 0) return 0;
            Paint f = new Paint(big);
            f.setTextSize(size);
            return f.measureText(fraction, 0, fraction.length());
        }
    }

    /** The battery row's ten segments, green, amber or red by band; dim when the reading is stale. */
    private final class Segments extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int filled;
        private int color;
        Segments() {
            super(host.activity());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void set(double percent, RidingValues.Band band, boolean stale) {
            int next = RidingFocus.segments(percent);
            int tint = band == RidingValues.Band.RED ? ui.danger : band == RidingValues.Band.AMBER ? ui.orange : ui.ok;
            if (stale) tint = ui.dim;
            if (next == filled && tint == color) return;
            filled = next;
            color = tint;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float gap = ui.dp(5), w = getWidth(), h = getHeight();
            float each = (w - gap * (RidingFocus.SEGMENTS - 1)) / RidingFocus.SEGMENTS;
            if (each <= 0) return;
            float r = Math.min(ui.dp(6), each / 2);
            for (int i = 0; i < RidingFocus.SEGMENTS; i++) {
                paint.setColor(i < filled ? color : ui.dark ? ui.raised : ui.outline);
                float left = i * (each + gap);
                canvas.drawRoundRect(new RectF(left, 0, left + each, h), r, r, paint);
            }
        }
    }

    /**
     * The tall power bar on the left edge. Its zero line sits on the big watt number. Draw fills up in
     * red, regen down in green only when the scooter confirmed the direction (0x79), and a reading
     * with an unknown direction grows up from zero in grey. A stale reading leaves an outline at zero.
     * A white tick marks this ride's highest draw. Each new reading glides in (GaugeSmoother), tuned
     * to the reading's interval, and the bar never runs ahead of the latest real reading.
     */
    private final class PowerBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GaugeSmoother smoother = new GaugeSmoother();
        private float zeroY = -1;
        private boolean present;
        private RidingValues.Direction direction = RidingValues.Direction.UNKNOWN;
        private double drawScale = RidingValues.PowerScale.DRAW_START_W, regenScale = RidingValues.PowerScale.REGEN_START_W, peak;
        PowerBar() {
            super(host.activity());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void setZeroY(float y) {
            if (Math.abs(y - zeroY) < .5f) return;
            zeroY = y;
            invalidate();
        }
        /** intervalMs is the reading's interval for a new reading, or -1 when nothing new arrived. */
        void set(Double watts, RidingValues.Direction direction, RidingValues.PowerScale scale, double peakDraw, long intervalMs, long now) {
            present = watts != null;
            this.direction = direction;
            drawScale = scale.drawW;
            regenScale = scale.regenW;
            peak = peakDraw;
            if (watts == null) smoother.snap(0, now);
            else if (intervalMs >= 0 || !smoother.started()) smoother.setTarget(watts, Math.max(1, intervalMs), now);
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float zero = zeroY > 0 && zeroY < h ? zeroY : h * .89f;
            float radius = w / 2;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(ui.dark ? ui.surface : ui.raised);
            canvas.drawRoundRect(new RectF(0, 0, w, h), radius, radius, paint);
            // Scale ticks above the zero line, one below it.
            paint.setColor(ui.outline);
            float tick = Math.max(1, ui.dp(1.5f));
            for (int i = 1; i < 8; i++) {
                float y = zero - i * (zero - radius) / 8;
                canvas.drawRect(w * .22f, y - tick / 2, w * .78f, y + tick / 2, paint);
            }
            canvas.drawRect(w * .22f, zero + (h - zero) / 2 - tick / 2, w * .78f, zero + (h - zero) / 2 + tick / 2, paint);
            long now = SystemClock.elapsedRealtime();
            double value = smoother.started() ? smoother.value(now) : 0;
            float inset = ui.dp(3);
            if (!present) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(1.5f));
                paint.setColor(ui.dim);
                canvas.drawRoundRect(new RectF(inset, zero - ui.dp(6), w - inset, zero + ui.dp(6)), ui.dp(6), ui.dp(6), paint);
                paint.setStyle(Paint.Style.FILL);
            } else if (value >= 0 || direction != RidingValues.Direction.REGEN) {
                double magnitude = Math.abs(value);
                float top = (float) (zero - (zero - inset) * Math.min(1, magnitude / Math.max(1, drawScale)));
                if (direction == RidingValues.Direction.UNKNOWN) paint.setColor(ui.muted);
                else paint.setShader(new LinearGradient(0, zero, 0, inset, ui.accentDeep, lighter(ui.accent), Shader.TileMode.CLAMP));
                if (zero - top > 1) canvas.drawRoundRect(new RectF(inset, top, w - inset, zero), radius - inset, radius - inset, paint);
                paint.setShader(null);
            } else {
                float bottom = (float) (zero + (h - inset - zero) * Math.min(1, -value / Math.max(1, regenScale)));
                paint.setColor(ui.ok);
                if (bottom - zero > 1) canvas.drawRoundRect(new RectF(inset, zero, w - inset, bottom), radius - inset, radius - inset, paint);
            }
            // The zero line, and the peak-draw tick.
            paint.setColor(ui.muted);
            canvas.drawRect(0, zero - tick / 2, w, zero + tick / 2, paint);
            if (peak > 0) {
                float y = (float) (zero - (zero - inset) * Math.min(1, peak / Math.max(1, drawScale)));
                paint.setColor(ui.text);
                canvas.drawRoundRect(new RectF(-ui.dp(1), y - ui.dp(1.5f), w + ui.dp(1), y + ui.dp(1.5f)), ui.dp(1.5f), ui.dp(1.5f), paint);
            }
            if (present && !smoother.settled(now, 1)) postInvalidateOnAnimation();
        }
        private int lighter(int color) {
            int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
            return 0xff000000 | (Math.min(255, r + 40) << 16) | (Math.min(255, g + 70) << 8) | Math.min(255, b + 70);
        }
    }

    // Home's RIDE button.

    /**
     * Home's red RIDE button, left of the edit pencil (owner Q11 B: the speedometer icon, since the tab
     * bar's centre disc already shows the scooter). On a tight header it steps down to the icon alone in
     * the same red disc; the word never shrinks.
     */
    static View entry(ScreenHost host, Runnable open) {
        Ui ui = host.ui();
        Context context = host.activity();
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(ui.shape(ui.accent, 100, 0));
        View icon = ui.icon("speed", 20, ui.onAccent);
        pill.addView(icon, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        TextView word = ui.text(T.ridingRideButton(), 14, true);
        word.setTextColor(ui.onAccent);
        word.setLetterSpacing(.06f);
        word.setMaxLines(1);
        LinearLayout.LayoutParams wordSpace = new LinearLayout.LayoutParams(-2, -2);
        wordSpace.setMarginStart(ui.dp(8));
        pill.addView(word, wordSpace);
        FrameLayout button = new FrameLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                // The word stays while the labelled pill takes at most a third of the header row.
                float labelled = ui.dp(20 + 8 + 32) + word.getPaint().measureText(word.getText().toString());
                float header = getResources().getDisplayMetrics().widthPixels;
                if (ui.wideLayout()) header -= ui.dp(92);
                boolean fits = RidingFocus.rideLabelFits(labelled, header - ui.dp(36));
                word.setVisibility(fits ? VISIBLE : GONE);
                pill.setPadding(fits ? ui.dp(14) : 0, 0, fits ? ui.dp(18) : 0, 0);
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) pill.getLayoutParams();
                p.width = fits ? -2 : ui.dp(40);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        button.setMinimumWidth(ui.dp(48));
        button.setMinimumHeight(ui.dp(48));
        button.addView(pill, new FrameLayout.LayoutParams(-2, ui.dp(40), Gravity.CENTER));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(T.ridingRideDescription());
        button.setOnClickListener(v -> open.run());
        ui.pressFeedback(button);
        return button;
    }
}
