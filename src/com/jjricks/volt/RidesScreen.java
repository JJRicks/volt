package com.jjricks.volt;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Persistent local sessions, honest energy interpretation and useful comparisons. */
public final class RidesScreen implements AppScreen {
    private static final int DIALOG = 0, PAGE = 1, SHEET = 2;
    /** Route thumbnails by ride id, journal length and time; an empty array means "no GPS route". */
    private static final Map<String, float[]> THUMBS = Collections.synchronizedMap(new LinkedHashMap<String, float[]>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) { return size() > 96; }
    });
    private static final float[] NO_ROUTE = new float[0];
    private static final int INLINE_MAP_DP = 300;
    /** The wide list pane's scroll position across rebuilds; the activity restores only the page scroller, which here never moves. */
    private static int wideListScroll;

    private final ScreenHost host;
    private final Ui ui;
    private final ScooterService service;
    private final SharedPreferences preferences;
    private final ScrollView scroll;
    /** Wide screens: the list on the left, the selected ride's details on the right; null when compact. */
    private final boolean wide;
    private final ScrollView root, detailScroll;
    private final LinearLayout detailPane;
    private final Map<String, View> historyRows = new HashMap<String, View>();
    private String selectedRide, detailKey = "";
    private final LinearLayout page, recordCard, activeCard, history;
    private final TextView recordButton, connectButton, endButton, activeTitle, activeElapsed, activeDistance,
            activeDistanceUnit, activePower, activeEnergy, activeBattery, activeBatteryUnit, activeQuality, historyCount;
    /** Live sensor state as tinted icons, chips that appear only when they apply, and the coverage figure. */
    private final GlyphView gpsIcon, barometerIcon, motionIcon;
    private final LinearLayout sensorIcons;
    private final TextView gapChip, recoveredChip, coverageText, disconnectedPill, reconnectButton;
    private Drawable recordFill; private ColorStateList recordInk;
    private final Row sensorsRow, compareRow;
    private final Ui.ChartView liveChart;
    private final ExecutorService phoneLoader = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "phone-ride-preview"); t.setDaemon(true); return t; });
    private final ExecutorService chartLoader = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "full-ride-chart"); t.setDaemon(true); return t; });
    private final ExecutorService thumbLoader = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ride-thumbnails"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; });
    /** Reads saved rides when the cache can't answer, so Rides never replays a journal on the UI thread. */
    private final ExecutorService rideListLoader = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, RIDE_LIST_THREAD); t.setDaemon(true); return t; });
    private static final String RIDE_LIST_THREAD = "ride_list";
    private boolean closed;
    /** historyLoaded: real rows (or the empty or unreadable state) are showing, not the loading skeleton. */
    private boolean historyLoaded, historyLoading, historyReload, comparisonLoading;
    private int historyGeneration;
    /** A finished background read, handed to the next refreshHistory() or chooseComparison(); null when none. */
    private List<RideStore.Summary> loadedHistory, loadedComparison;
    private IOException historyFailure, comparisonFailure;
    /** The wide pane's key whose chart preview is loading on rideListLoader, then its result; null when none. */
    private String detailWanted, detailLoadedKey;
    private List<RideStore.Point> detailLoaded;
    private final List<RouteMapView> maps = new ArrayList<RouteMapView>();
    private final Set<String> selected;
    private final Map<String, RouteThumb> thumbs = new HashMap<String, RouteThumb>();
    private final List<AlertDialog> dialogs = new ArrayList<AlertDialog>();
    private final List<Runnable> phoneRefreshers = new ArrayList<>();
    private int visibleHistory = 20, savedCount;
    private Boolean activeStyled;

    public RidesScreen(ScreenHost host) {
        this.host = host; ui = host.ui(); service = host.service(); preferences = service.settings;
        selected = new LinkedHashSet<String>(preferences.getStringSet("ride_comparison", new LinkedHashSet<String>()));
        chartKey = preferences.getString("ride_chart_key", "power_w");
        selectedRide = preferences.getString("ride_selected", "");
        // Same rule as the activity's side rail (Ui.wideLayout), so two panes appear exactly when the rail does.
        wide = ui.wideLayout();
        scroll = new MapTouchScroll(); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        page = vertical(); page.setPadding(wide ? 0 : ui.dp(18), ui.dp(6), wide ? 0 : ui.dp(18), ui.dp(24)); scroll.addView(page);

        // Live recording: red-tinted card with elapsed time, three stats, quality, chart and Notes / End.
        activeCard = vertical(); activeCard.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        LinearLayout.LayoutParams activeParams = new LinearLayout.LayoutParams(-1, -2); activeParams.bottomMargin = ui.dp(10);
        LinearLayout top = horizontal(); top.setGravity(Gravity.CENTER_VERTICAL);
        activeTitle = ui.text(T.ridesLiveRecording(), 14, true); activeTitle.setMaxLines(1); activeTitle.setEllipsize(TextUtils.TruncateAt.END);
        Drawable dot = new RecordingDot(); int dotSize = ui.dp(15); dot.setBounds(0, 0, dotSize, dotSize);
        activeTitle.setCompoundDrawablesRelative(dot, null, null, null); activeTitle.setCompoundDrawablePadding(ui.dp(8));
        top.addView(activeTitle, new LinearLayout.LayoutParams(0, -2, 1));
        disconnectedPill = pill(T.ridesLiveDisconnected(), ui.orange); disconnectedPill.setVisibility(View.GONE);
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(-2, -2); pillParams.leftMargin = ui.dp(8);
        top.addView(disconnectedPill, pillParams);
        activeElapsed = ui.text("0:00", 30, true);
        LinearLayout.LayoutParams elapsedParams = new LinearLayout.LayoutParams(-2, -2); elapsedParams.leftMargin = ui.dp(10);
        top.addView(activeElapsed, elapsedParams); activeCard.addView(top);
        LinearLayout stats = horizontal(); stats.setPadding(0, ui.dp(14), 0, ui.dp(8));
        TextView[] distance = stat(stats), power = stat(stats), energy = stat(stats), battery = stat(stats);
        activeDistance = distance[0]; activeDistanceUnit = distance[1]; activePower = power[0]; activeEnergy = energy[0];
        activeBattery = battery[0]; activeBatteryUnit = battery[1];
        power[1].setText(T.ridesLivePowerNow()); energy[1].setText(T.unitsWattHour()); activeBatteryUnit.setText(T.ridesLiveBattery());
        // The fourth stat carries a battery glyph beside its value.
        LinearLayout batteryValue = horizontal(); batteryValue.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout batteryColumn = (LinearLayout) activeBattery.getParent(); batteryColumn.removeView(activeBattery);
        batteryValue.addView(ui.icon("battery", 15, ui.muted), new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)));
        LinearLayout.LayoutParams batteryTextParams = new LinearLayout.LayoutParams(-2, -2); batteryTextParams.leftMargin = ui.dp(3);
        batteryValue.addView(activeBattery, batteryTextParams); batteryColumn.addView(batteryValue, 0);
        activeCard.addView(stats);
        // Sensor icons (green = live, orange = lost, grey = off), then chips that appear only when they apply.
        Flow quality = new Flow(); quality.setPadding(0, 0, 0, ui.dp(4));
        sensorIcons = horizontal(); sensorIcons.setGravity(Gravity.CENTER_VERTICAL); sensorIcons.setMinimumHeight(ui.dp(28));
        gpsIcon = new GlyphView("pin", ui.dim); barometerIcon = new GlyphView("wave", ui.dim); motionIcon = new GlyphView("motion", ui.dim);
        for (GlyphView icon : new GlyphView[]{gpsIcon, barometerIcon, motionIcon}) {
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)); iconParams.rightMargin = ui.dp(8);
            sensorIcons.addView(icon, iconParams);
        }
        sensorIcons.setFocusable(true); quality.addView(sensorIcons);
        gapChip = pill("", ui.orange); recoveredChip = pill(T.ridesRecoveredGap(), ui.orange);
        coverageText = ui.caption(""); coverageText.setGravity(Gravity.CENTER_VERTICAL); coverageText.setMinHeight(ui.dp(28));
        for (TextView chip : new TextView[]{gapChip, recoveredChip}) { chip.setMinHeight(ui.dp(28)); chip.setVisibility(View.GONE); quality.addView(chip); }
        quality.addView(coverageText); activeCard.addView(quality);
        activeQuality = ui.caption(""); activeQuality.setTextColor(ui.danger); activeQuality.setVisibility(View.GONE); activeCard.addView(activeQuality);
        String[] liveKeys = {"power_w", "speed_kmh", "voltage_v", "battery_percent"};
        SegmentedSelector liveChips = new SegmentedSelector(ui, T.ridesLiveChartTitle(), new String[]{T.ridesChartPower(), T.ridesChartSpeed(), T.ridesChartVoltage(), T.ridesChartBattery()}, choice -> {
            chartKey = liveKeys[choice]; preferences.edit().putString("ride_chart_key", chartKey).apply(); refreshActive();
        });
        int liveChoice = 0; for (int i = 0; i < liveKeys.length; i++) if (liveKeys[i].equals(chartKey)) liveChoice = i;
        liveChips.select(liveChoice, false); activeCard.addView(liveChips);
        liveChart = ui.new ChartView(); activeCard.addView(liveChart, new LinearLayout.LayoutParams(-1, ui.dp(180)));
        reconnectButton = ui.secondaryButton(T.ridesReconnect(), host::connect); iconize(reconnectButton, "bluetooth");
        reconnectButton.setContentDescription(T.ridesReconnectDescription()); reconnectButton.setVisibility(View.GONE);
        activeCard.addView(reconnectButton);
        TextView notes = ui.secondaryButton(T.ridesNotes(), this::recordingNotes); iconize(notes, "note");
        notes.setContentDescription(T.ridesNotesDescription());
        endButton = ui.button(T.ridesEnd(), this::toggleRecording); iconize(endButton, "stop");
        endButton.setContentDescription(T.ridesEndDescription());
        activeCard.addView(pair(notes, endButton));
        page.addView(activeCard, activeParams);

        // Record controls: the red record button, then direction and phone sensor rows.
        recordCard = ui.card(); recordCard.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), 0);
        recordButton = ui.button(T.ridesStartRecording(), this::toggleRecording); iconize(recordButton, "record");
        recordFill = recordButton.getBackground(); recordInk = recordButton.getTextColors();
        LinearLayout.LayoutParams recordParams = new LinearLayout.LayoutParams(-1, -2); recordParams.bottomMargin = ui.dp(4);
        recordCard.addView(recordButton, recordParams);
        connectButton = ui.secondaryButton(T.ridesConnectToRecord(), host::connect);
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(-1, -2); connectParams.topMargin = ui.dp(4); connectParams.bottomMargin = ui.dp(4);
        recordCard.addView(connectButton, connectParams);
        sensorsRow = navRow("sensors", M.ridesPhoneSensors(), "", "", this::phoneOptions); recordCard.addView(sensorsRow.view);
        page.addView(recordCard);

        LinearLayout comparison = ui.card(); comparison.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4));
        compareRow = navRow("compare", M.ridesCompareTwo(), "", "", this::chooseComparison);
        comparison.addView(compareRow.view);
        ui.divider(comparison);
        Row backups = navRow("download", M.ridesBackups(), "", "", this::rideBackups);
        backups.view.setTag("ride-backups-menu");comparison.addView(backups.view);page.addView(comparison);

        LinearLayout head = horizontal(); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ui.sectionLabel(T.ridesHistory()), new LinearLayout.LayoutParams(0, -2, 1));
        historyCount = ui.caption(""); historyCount.setGravity(Gravity.END); historyCount.setPadding(ui.dp(4), ui.dp(14), ui.dp(4), ui.dp(6));
        head.addView(historyCount); page.addView(head);
        history = vertical(); page.addView(history);
        if (wide) {
            detailScroll = new ScrollView(host.activity()); detailScroll.setFillViewport(true); detailScroll.setClipToPadding(false);
            detailPane = vertical(); detailPane.setPadding(0, ui.dp(6), 0, ui.dp(24)); detailScroll.addView(detailPane);
            Panes panes = new Panes(scroll, detailScroll); panes.setPadding(ui.dp(24), 0, ui.dp(24), 0);
            root = new PaneHost(); root.addView(panes);
        } else { detailScroll = null; detailPane = null; root = scroll; }
        refresh();
        if (wide) Motion.scrollBeforeDraw(scroll, wideListScroll);
    }
    /** A shortcut into Back up & restore with only Rides selected. */
    private void rideBackups() { host.dataBackup(null, BackupRegistry.RIDES); }
    private LinearLayout vertical() { LinearLayout result = new LinearLayout(host.activity()); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private LinearLayout horizontal() { LinearLayout result = new LinearLayout(host.activity()); result.setOrientation(LinearLayout.HORIZONTAL); return result; }
    @Override public View view() { return root; }
    @Override public void refresh() {
        boolean recording = service.isRecording(), ready = service.ble.ready(), saving = service.phoneSaving();
        String error = service.recordingError();
        boolean simulating = service.simulating();
        label(recordButton, simulating ? T.ridesSimulatingExit() : saving ? T.ridesSavingSensorsButton() : T.ridesStartRecording());
        // While simulating the button looks disabled, but a tap exits simulation.
        recordButton.setEnabled(simulating || (service.rides != null && !saving && ready));
        if (simulating) { recordButton.setBackground(ui.shape(ui.raised, 14, 0)); recordButton.setTextColor(ui.muted); }
        else if (recordButton.getBackground() != recordFill) { recordButton.setBackground(recordFill); recordButton.setTextColor(recordInk); }
        if (simulating) recordButton.setContentDescription(T.ridesSimulatingExitDescription());
        recordButton.setVisibility(recording ? View.GONE : View.VISIBLE);
        connectButton.setVisibility(!ready && !recording ? View.VISIBLE : View.GONE);
        recordCard.setPadding(recordCard.getPaddingLeft(), recording ? 0 : ui.dp(10), recordCard.getPaddingRight(), 0);
        label(endButton, error == null ? T.ridesEnd() : T.ridesRetrySave());
        endButton.setContentDescription(error == null ? T.ridesEndDescription() : T.ridesRetrySave());
        endButton.setEnabled(service.rides != null && !saving);
        updateSensorsRow();
        if ((activeCard.getVisibility() == View.VISIBLE) != recording && activeCard.getParent() instanceof ViewGroup)
            Motion.animateChanges((ViewGroup) activeCard.getParent());
        activeCard.setVisibility(recording ? View.VISIBLE : View.GONE); if (recording) refreshActive();
        refreshHistory(); updateComparisonActions();
        for (Runnable refresh : new ArrayList<>(phoneRefreshers)) refresh.run();
    }
    /** Buttons keep their spoken label in step with the visible one. */
    private static void label(TextView button, String text) {
        if (!text.contentEquals(button.getText())) button.setText(text);
        button.setContentDescription(text);
    }
    /** The record and end buttons; the checks and the start live in the shared RideRecordingControl. */
    private void toggleRecording() {
        RideRecordingApp.of(host).toggle();
    }
    /** Why a ride did not start or stop (RideRecordingControl), as the Rides tab has always said it. */
    static String recordingMessage(int outcome) {
        switch (outcome) {
            case RideRecordingControl.SIMULATING: return T.ridesToastExitSimulation();
            case RideRecordingControl.FINISHING_PREVIOUS: return T.ridesToastFinishingPrevious();
            case RideRecordingControl.NO_STORAGE: return T.ridesToastStorageUnavailable();
            case RideRecordingControl.NOT_CONNECTED: return T.ridesToastConnectFirst();
            case RideRecordingControl.NOT_STOPPED: return T.ridesToastCouldNotStop();
            default: return null;
        }
    }
    private String defaultRideName() { return defaultRideName(host); }
    static String defaultRideName(ScreenHost host) { return defaultRideName(host.service()); }
    /** A new ride's name: date, time and the scooter's fresh drive mode, when it is known. */
    static String defaultRideName(ScooterService service) {
        SharedPreferences preferences = service.settings;
        String mode = "";
        long now = android.os.SystemClock.elapsedRealtime();
        ScooterProfiles.Profile active = service.profiles.active(), bound = service.ble.profile();
        if (service.ble.ready() && !service.simulating() && active != null && bound != null && active.id.equals(bound.id)) {
            for (Telemetry.Metric reading : service.ble.telemetry.metrics()) {
                if (!reading.key.equals("driving_mode") || reading.numericValue == null
                        || reading.updatedMs <= 0 || reading.updatedMs > now || now - reading.updatedMs > 7000) continue;
                String label = Telemetry.drivingModeLabel(ModelRegistry.driveModeLabelProduct(service.ble.descriptor()), reading.numericValue);
                if (!label.equals("—")) mode = label.endsWith(" (raw)") ? "Mode " + label : label;
                break;
            }
        }
        return DisplayTime.rideName(service, preferences, System.currentTimeMillis(), mode);
    }
    private EditText input(String hint) {
        EditText input = new EditText(host.activity()); input.setSingleLine(true); input.setHint(hint);
        input.setTextColor(ui.text); input.setHintTextColor(ui.muted); input.setTextSize(16 * ui.textScale);
        input.setTypeface(ui.regular()); input.setBackground(ui.shape(ui.bg, 14, ui.outline));
        input.setMinHeight(ui.dp(52)); input.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14)); return input;
    }
    /** A dialog body with its own Saira title and muted explanation, for dialogs that carry in-body buttons. */
    private LinearLayout dialogBody(String title, String message) {
        LinearLayout box = vertical(); box.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(16));
        TextView heading = ui.text(title, 20, true); heading(heading); box.addView(heading);
        if (message != null) {
            TextView copy = ui.text(message, 13.5f, false); copy.setTextColor(ui.muted);
            copy.setPadding(0, ui.dp(10), 0, ui.dp(14)); box.addView(copy);
        } else heading.setPadding(0, 0, 0, ui.dp(14));
        return box;
    }
    private void recordingNotes() {
        if (service.rides == null) return;
        RideStore.Summary recording = service.rides.activeSummary(); if (recording == null) return;
        EditText notes = input(T.ridesNotesHint()); notes.setSingleLine(false); notes.setMinLines(3); notes.setMaxLines(8);
        notes.setGravity(Gravity.TOP | Gravity.START); notes.setText(recording.notes);
        LinearLayout box = dialogBody((recording.label.isEmpty() ? T.ridesNotesTitleUnnamed() : T.ridesNotesTitle(recording.label)), null);
        box.addView(notes);
        TextView cancel = ui.secondaryButton(T.commonCancel(), null), save = ui.button(T.ridesNotesSave(), null);
        LinearLayout buttons = pair(cancel, save); ((LinearLayout.LayoutParams) buttons.getLayoutParams()).topMargin = ui.dp(12);
        box.addView(buttons);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).create();
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            dialog.dismiss();
            if (!recording.id.equals(service.rides.activeId())) { host.toast(T.ridesToastNotesNotSaved()); return; }
            try { service.rides.setNotes(notes.getText().toString()); }
            catch (IOException e) { host.toast(T.ridesToastNotesFailed()); }
        });
        show(dialog, DIALOG, null);
    }
    private void updateSensorsRow() {
        boolean gps = preferences.getBoolean("phone_gps", true), motion = preferences.getBoolean("phone_motion", true);
        int on = (gps ? 2 : 0) + (motion ? 1 : 0);
        sensorsRow.update(sensorSummary(), on == 0 ? T.commonOff() : T.ridesSensorsOnCount(on, on));
    }
    /** "GPS · elevation · motion": elevation is attached to GPS fixes (barometer when present, else GPS altitude), so it needs GPS. */
    private String sensorSummary() {
        boolean gps = preferences.getBoolean("phone_gps", true), motion = preferences.getBoolean("phone_motion", true);
        List<String> on = new ArrayList<String>(); if (gps) { on.add(T.ridesSensorsGps()); on.add(T.ridesSensorsElevation()); } if (motion) on.add(gps ? T.ridesSensorsMotion() : T.ridesSensorsMotionStart());
        String sub = on.isEmpty() ? T.ridesSensorsScooterOnly() : TextUtils.join(" · ", on);
        return sub;
    }
    private void refreshActive() {
        if (service.rides == null) return;
        RideStore.Summary s = service.rides.activeSummary(); if (s == null) return;
        String error = service.recordingError();
        String name = s.label.isEmpty() ? T.ridesDefaultName() : s.label;
        activeTitle.setText((error == null ? T.ridesLiveTitle(name) : T.ridesLiveTitleAttention(name)));
        activeTitle.setTextColor(ui.danger);
        styleActiveCard(error != null);
        long elapsed = Math.max(s.elapsedMs, System.currentTimeMillis() - s.startWallMs);
        activeElapsed.setText(clock(elapsed)); activeElapsed.setContentDescription(T.ridesElapsedDescription(duration(elapsed)));
        String[] distance = s.distanceKm == null ? null : RideText.numberAndRest(distanceParts(s.distanceKm));
        activeDistance.setText(distance == null ? "—" : distance[0]);
        activeDistanceUnit.setText(distance == null ? ("km".equals(preferences.getString("units", "mi")) ? T.unitsKm() : T.unitsMi()) : distance[1]);
        List<RideStore.Point> trend = Collections.emptyList();
        try { trend = service.rides.trend(s.id, 600); } catch (IOException e) { host.toast(T.ridesToastChartFailed()); }
        RideStore.Point latest = trend.isEmpty() ? null : trend.get(trend.size() - 1);
        boolean fresh = latest != null && latest.powerW != null && Math.abs(System.currentTimeMillis() - latest.wallMs) <= 5000;
        activePower.setText(fresh ? String.format(Fmt.locale(Locale.US), "%.0f", latest.powerW) : "—");
        activeEnergy.setText(s.measuredThroughputWh == null ? "—" : String.format(Fmt.locale(Locale.US), "%.1f", s.measuredThroughputWh));
        // Energy below the 80% coverage needed for per-distance figures reads orange.
        activeEnergy.setTextColor(s.measuredThroughputWh != null && s.electricalCoverage < RideStore.MIN_EFFICIENCY_COVERAGE ? ui.orange : ui.text);
        activeBattery.setText(s.batteryDropPercent == null ? "—" : change(s.batteryDropPercent));
        describeStat(activeDistance, activeDistanceUnit); describeStat(activePower, null); describeStat(activeEnergy, null);
        describeStat(activeBattery, activeBatteryUnit);
        refreshSensorIcons();
        gapChip.setText(s.gapCount == 1 ? T.ridesGapOne() : T.ridesGaps(s.gapCount, s.gapCount)); gapChip.setContentDescription(gapChip.getText());
        gapChip.setVisibility(s.gapCount > 0 ? View.VISIBLE : View.GONE);
        recoveredChip.setVisibility(s.recovered ? View.VISIBLE : View.GONE);
        coverageText.setText(T.ridesCoverage(percent(s.electricalCoverage)));
        coverageText.setContentDescription(T.ridesCoverageDescription(percent(s.electricalCoverage), duration(s.electricalCoverageMs), duration(s.elapsedMs)));
        boolean disconnected = !service.ble.connected();
        disconnectedPill.setVisibility(disconnected ? View.VISIBLE : View.GONE);
        reconnectButton.setVisibility(disconnected ? View.VISIBLE : View.GONE);
        activeQuality.setText(error == null ? "" : error);
        activeQuality.setVisibility(error == null ? View.GONE : View.VISIBLE);
        liveChart.set(points(trend, chartKey), chartUnit(chartKey), chartColor(chartKey), T.ridesChartWaiting());
    }
    /** ScooterService.phoneStatus() in the display language, from its flags (status() stays the English id). */
    static String phoneStatusText(java.util.EnumSet<PhoneRideRecorder.StatusFlag> flags) {
        if (flags.contains(PhoneRideRecorder.StatusFlag.SAVING)) return T.ridesSavingSensors();
        if (flags.contains(PhoneRideRecorder.StatusFlag.STORAGE_ERROR)) return T.ridesSensorStatusStorage();
        if (flags.contains(PhoneRideRecorder.StatusFlag.IDLE)) return T.ridesSensorStatusIdle();
        String text = flags.contains(PhoneRideRecorder.StatusFlag.GPS_STARTING) ? T.ridesSensorStatusGpsStarting()
                : flags.contains(PhoneRideRecorder.StatusFlag.GPS_WAITING) ? T.ridesSensorStatusGpsWaiting()
                : flags.contains(PhoneRideRecorder.StatusFlag.GPS_LOCATION_OFF) ? T.ridesSensorStatusLocationOff()
                : flags.contains(PhoneRideRecorder.StatusFlag.GPS_UNAVAILABLE) ? T.ridesSensorStatusGpsUnavailable()
                : flags.contains(PhoneRideRecorder.StatusFlag.GPS_PERMISSION_UNAVAILABLE) ? T.ridesSensorStatusPermissionUnavailable()
                : flags.contains(PhoneRideRecorder.StatusFlag.GPS_NO_PERMISSION) ? T.ridesSensorStatusNoPermission()
                : flags.contains(PhoneRideRecorder.StatusFlag.GPS_RECORDING) ? T.ridesSensorStatusGpsRecording()
                : flags.contains(PhoneRideRecorder.StatusFlag.GPS_SIGNAL_LOST) ? T.ridesSensorStatusSignalLost()
                : flags.contains(PhoneRideRecorder.StatusFlag.GPS_SIGNAL_UNAVAILABLE) ? T.ridesSensorStatusSignalUnavailable()
                : T.ridesSensorStatusGpsOff();
        if (flags.contains(PhoneRideRecorder.StatusFlag.BAROMETER)) text = T.ridesSensorStatusBarometer(text);
        if (flags.contains(PhoneRideRecorder.StatusFlag.MOTION_EXPERIMENTAL)) text = T.ridesSensorStatusMotionExperimental(text);
        else if (flags.contains(PhoneRideRecorder.StatusFlag.MOTION_RAW)) text = T.ridesSensorStatusMotionRaw(text);
        else if (flags.contains(PhoneRideRecorder.StatusFlag.MOTION_UNAVAILABLE)) text = T.ridesSensorStatusMotionUnavailable(text);
        return text;
    }
    /** GPS, barometer and motion icons: green when live, orange when the signal is lost or missing, grey when off. */
    private void refreshSensorIcons() {
        java.util.EnumSet<PhoneRideRecorder.StatusFlag> flags = service.phoneStatusFlags();
        String live = service.phoneLiveReadings();
        boolean capturing = !flags.contains(PhoneRideRecorder.StatusFlag.SAVING) && !flags.contains(PhoneRideRecorder.StatusFlag.IDLE) && !flags.contains(PhoneRideRecorder.StatusFlag.STORAGE_ERROR);
        int gps = !capturing ? ui.dim : flags.contains(PhoneRideRecorder.StatusFlag.GPS_RECORDING) ? ui.ok
                : flags.contains(PhoneRideRecorder.StatusFlag.GPS_SIGNAL_LOST) || flags.contains(PhoneRideRecorder.StatusFlag.GPS_WAITING) ? ui.orange : ui.dim;
        int barometer = capturing && flags.contains(PhoneRideRecorder.StatusFlag.BAROMETER) ? ui.ok : ui.dim;
        boolean motionOn = capturing && (flags.contains(PhoneRideRecorder.StatusFlag.MOTION_EXPERIMENTAL) || flags.contains(PhoneRideRecorder.StatusFlag.MOTION_RAW));
        boolean samplesArriving = !live.isEmpty() && !live.startsWith("Waiting");
        int motion = !capturing ? ui.dim : flags.contains(PhoneRideRecorder.StatusFlag.MOTION_UNAVAILABLE) ? ui.orange
                : motionOn ? (samplesArriving ? ui.ok : ui.orange) : ui.dim;
        gpsIcon.setColor(gps); barometerIcon.setColor(barometer); motionIcon.setColor(motion);
        sensorIcons.setContentDescription(phoneStatusText(flags));
    }
    private void describeStat(TextView value, TextView unit) {
        View column = (View) value.getParent();
        TextView caption = unit != null ? unit : (TextView) ((ViewGroup) column).getChildAt(1);
        column.setContentDescription(("—".contentEquals(value.getText()) ? T.ridesNotMeasured() : value.getText()) + " " + caption.getText());
    }
    private void styleActiveCard(boolean error) {
        if (activeStyled != null && activeStyled == error) return; activeStyled = error;
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(ui.surface, ui.accent, ui.dark ? .14 : .07), ui.surface, ui.surface});
        fill.setCornerRadius(ui.dp(20)); fill.setStroke(ui.dp(1), Ui.withAlpha(error ? ui.danger : ui.accent, error ? 160 : 90));
        activeCard.setBackground(fill);
    }
    /** One of the three live stats: a large value over a muted unit. */
    private TextView[] stat(LinearLayout parent) {
        LinearLayout column = vertical();
        TextView value = ui.text("—", 21, true), unit = ui.caption(""); unit.setPadding(0, ui.dp(3), 0, 0);
        value.setMaxLines(1); unit.setMaxLines(1); unit.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(value); column.addView(unit);
        if (Build.VERSION.SDK_INT >= 28) column.setScreenReaderFocusable(true);
        parent.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
        return new TextView[]{value, unit};
    }
    /** A small rounded status pill tinted with one colour, e.g. Disconnected or Direction ?. */
    /** "Direction ?": also names a chart's power line while it plots a direction nobody measured. */
    static String directionUnconfirmedChip() { return T.ridesDirectionChipUnconfirmed(); }
    private TextView pill(String text, int color) {
        TextView pill = ui.text(text, 11.5f, false); pill.setTypeface(ui.semibold()); pill.setTextColor(color);
        pill.setGravity(Gravity.CENTER); pill.setMaxLines(1); pill.setPadding(ui.dp(9), ui.dp(3), ui.dp(9), ui.dp(3));
        pill.setBackground(ui.shape(Ui.withAlpha(color, ui.dark ? 46 : 36), 100, 0));
        return pill;
    }
    /** Makes a value explain itself on tap, e.g. a "—" that says why it is unavailable. */
    private void reasonOnTap(View view, String reason) {
        if (reason == null) return;
        view.setOnClickListener(v -> host.toast(reason)); view.setClickable(true);
        CharSequence spoken = view.getContentDescription();
        view.setContentDescription((spoken == null ? "" : spoken + ", ") + reason);
    }
    private void refreshHistory() {
        if (service.rides == null) { historyCount.setText(T.ridesStorageUnavailable()); if (wide) emptyDetail("unavailable"); return; }
        try {
            List<RideStore.Summary> summaries = historySummaries();
            if (summaries == null) return;
            List<RideStore.Summary> stopped = new ArrayList<RideStore.Summary>();
            StringBuilder signature = new StringBuilder();
            for (RideStore.Summary s : summaries) if (!s.active) { stopped.add(s); signature.append(s.id).append(':').append(s.sampleCount).append(':').append(s.label).append(';'); }
            signature.append('|').append(visibleHistory);
            savedCount = stopped.size();
            String chosen = wide ? chosenRide(stopped) : null;
            if (signature.toString().equals(historySignature)) {
                updateHistoryCount();
                if (wide) { styleHistoryRows(chosen); refreshDetail(chosen, stopped); }
                return;
            }
            historySignature = signature.toString(); history.removeAllViews(); thumbs.clear(); historyRows.clear();
            Set<String> available = new LinkedHashSet<String>(); for (RideStore.Summary s : stopped) available.add(s.id);
            if (selected.retainAll(available)) saveSelection();
            List<String> shown = new ArrayList<String>();
            if (!stopped.isEmpty()) {
                LinearLayout group = ui.groupCard();
                for (int i = 0; i < Math.min(visibleHistory, stopped.size()); i++) {
                    if (i > 0) ui.divider(group);
                    group.addView(historyRow(stopped.get(i), stopped.get(i).id.equals(chosen))); shown.add(stopped.get(i).id);
                }
                history.addView(group);
                if (stopped.size() > visibleHistory) history.addView(ui.secondaryButton(T.ridesShowMore(), () -> { visibleHistory += 20; refreshHistory(); }));
            } else {
                LinearLayout empty = ui.card(); empty.addView(ui.icon("route", 30, ui.muted), new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)));
                TextView copy = ui.text(T.ridesNoRidesYet(), 18, true); copy.setPadding(0, ui.dp(10), 0, ui.dp(2)); empty.addView(copy);
                history.addView(empty);
            }
            updateHistoryCount(); loadThumbnails(shown); measureStorage();
            if (wide) refreshDetail(chosen, stopped);
        } catch (IOException e) { historyCount.setText(T.ridesHistoryUnreadable()); if (wide) emptyDetail("unreadable"); }
    }
    /**
     * The list from the ride cache when every journal is in it, so a warm open draws at once. Otherwise null: skeleton
     * rows show while rideListLoader reads the rides, and refreshHistory() runs again with the result.
     */
    private List<RideStore.Summary> historySummaries() throws IOException {
        List<RideStore.Summary> summaries = loadedHistory; loadedHistory = null;
        IOException failure = historyFailure; historyFailure = null;
        if (summaries == null && failure == null) summaries = service.rides.listCached();
        if (summaries == null && failure == null) { loadHistory(); return null; }
        if (!historyLoaded) { history.removeAllViews(); historySignature = ""; historyLoaded = true; }
        if (failure != null) throw failure;
        return summaries;
    }
    private void loadHistory() {
        if (historyLoading) { historyReload = true; return; }
        if (!historyLoaded) showHistorySkeleton();
        historyLoading = true;
        final int generation = ++historyGeneration;
        final RideStore store = service.rides;
        final String remembered = wide ? selectedRide : null;
        try {
            rideListLoader.execute(() -> {
                List<RideStore.Summary> loaded = null; IOException failure = null;
                try { loaded = store.list(); } catch (IOException unreadable) { failure = unreadable; }
                // The wide pane's chart preview comes from the same worker, so the pane never replays on the UI thread.
                String chosen = loaded == null || remembered == null ? null : chosenRide(stoppedRides(loaded), remembered);
                if (chosen != null) {
                    try { store.trend(chosen, RideStore.MAX_TREND_POINTS); } catch (IOException | RuntimeException reportedByPane) { }
                }
                final List<RideStore.Summary> result = loaded; final IOException error = failure;
                host.activity().runOnUiThread(() -> {
                    if (closed || generation != historyGeneration) return;
                    boolean first = !historyLoaded;
                    historyLoading = false; loadedHistory = result; historyFailure = error;
                    refreshHistory(); updateComparisonActions();
                    // The first rows after a background load: put the wide list back where the last visit left it.
                    if (first && wide) Motion.scrollBeforeDraw(scroll, wideListScroll);
                    if (historyReload) { historyReload = false; refreshHistory(); }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException screenClosed) { historyLoading = false; }
    }
    private static List<RideStore.Summary> stoppedRides(List<RideStore.Summary> summaries) {
        List<RideStore.Summary> stopped = new ArrayList<RideStore.Summary>();
        for (RideStore.Summary s : summaries) if (!s.active) stopped.add(s);
        return stopped;
    }
    /** Three rows shaped like history rows, with no text; screen readers hear "Loading rides…". */
    private void showHistorySkeleton() {
        history.removeAllViews(); thumbs.clear(); historyRows.clear(); historySignature = "";
        historyCount.setText("");
        LinearLayout group = ui.groupCard();
        for (int i = 0; i < 3; i++) {
            if (i > 0) ui.divider(group);
            LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(ui.dp(64));
            row.setPadding(0, ui.dp(10), 0, ui.dp(10));
            row.addView(skeletonBlock(9), new LinearLayout.LayoutParams(ui.dp(52), ui.dp(40)));
            LinearLayout words = vertical();
            // The name bar follows the column's width, so it never reaches the distance on a narrow wide-layout list.
            LinearLayout.LayoutParams name = new LinearLayout.LayoutParams(-1, ui.dp(12)); name.rightMargin = ui.dp(i == 1 ? 72 : 36);
            words.addView(skeletonBlock(5), name);
            LinearLayout.LayoutParams when = new LinearLayout.LayoutParams(ui.dp(84), ui.dp(10)); when.topMargin = ui.dp(9);
            words.addView(skeletonBlock(5), when);
            LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1); wordsParams.leftMargin = ui.dp(12);
            row.addView(words, wordsParams);
            row.addView(skeletonBlock(5), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(12)));
            group.addView(row);
        }
        group.setFocusable(true); group.setContentDescription(T.ridesHistoryLoading());
        history.addView(group);
        if (wide) detailSkeleton();
    }
    private View skeletonBlock(float radius) {
        View block = new View(host.activity()); block.setBackground(ui.shape(ui.raised, radius, 0));
        block.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return block;
    }
    private void updateHistoryCount() {
        String count = savedCount == 0 ? T.ridesHistoryNone() : T.ridesHistoryCount(savedCount, savedCount);
        String storageText = storageSize(storedBytes);
        historyCount.setText(storageText.isEmpty() ? count : count + " · " + storageText);
    }
    /** History row: route thumbnail, name and start time, then distance and duration. */
    private View historyRow(RideStore.Summary s, boolean chosen) {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(ui.dp(64));
        row.setPadding(0, ui.dp(10), 0, ui.dp(10));
        if (wide) {
            // The selection tint runs nearly to the card's edges; the row keeps its content where it was.
            int bleed = ui.dp(8); row.setPadding(bleed, ui.dp(10), bleed, ui.dp(10));
            LinearLayout.LayoutParams bleedParams = new LinearLayout.LayoutParams(-1, -2);
            bleedParams.leftMargin = -bleed; bleedParams.rightMargin = -bleed; row.setLayoutParams(bleedParams);
        }
        RouteThumb thumb = new RouteThumb(); thumbs.put(s.id, thumb);
        row.addView(thumb, new LinearLayout.LayoutParams(ui.dp(52), ui.dp(40)));
        LinearLayout words = vertical();
        String label = s.label.isEmpty() ? T.ridesDefaultName() : s.label;
        TextView name = ui.text(label, 15, false); name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END); words.addView(name);
        TextView when = ui.caption(shortDate(s.startWallMs)); when.setPadding(0, ui.dp(4), 0, 0); when.setMaxLines(1); words.addView(when);
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1); wordsParams.leftMargin = ui.dp(12);
        row.addView(words, wordsParams);
        LinearLayout right = vertical(); right.setGravity(Gravity.END);
        TextView distance = ui.text(distance(s.distanceKm), 14.5f, s.distanceKm != null); distance.setGravity(Gravity.END);
        if (s.distanceKm == null) distance.setTextColor(ui.muted);
        TextView time = ui.caption(duration(s.shownElapsedMs)); time.setGravity(Gravity.END); time.setPadding(0, ui.dp(4), 0, 0);
        right.addView(distance); right.addView(time);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(-2, -2); rightParams.leftMargin = ui.dp(10);
        row.addView(right, rightParams);
        String spoken = label + ", " + longDate(s.startWallMs) + ", " + distance(s.distanceKm) + ", " + duration(s.shownElapsedMs);
        if (wide) {
            row.setClickable(true); row.setFocusable(true); row.setOnClickListener(v -> selectRide(s.id));
            row.setContentDescription(T.ridesHistoryRowWide(spoken));
            historyRows.put(s.id, row); styleHistoryRow(row, chosen);
        } else {
            rowTouch(row, () -> showDetails(s.id));
            row.setContentDescription(T.ridesHistoryRow(spoken));
        }
        return row;
    }
    /** Wide layout: the selected ride's row carries a red tint and reads as selected. */
    private void styleHistoryRow(View row, boolean chosen) {
        if (Boolean.valueOf(chosen).equals(row.getTag())) return; row.setTag(chosen);
        Drawable fill = chosen ? ui.shape(Ui.withAlpha(ui.accent, ui.dark ? 46 : 30), 14, 0) : null;
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), fill, ui.shape(Color.WHITE, 14, 0)));
        row.setSelected(chosen);
    }
    private void styleHistoryRows(String chosen) {
        for (Map.Entry<String, View> entry : historyRows.entrySet()) styleHistoryRow(entry.getValue(), entry.getKey().equals(chosen));
    }
    /** The remembered ride when it still exists, otherwise the newest saved ride. */
    private String chosenRide(List<RideStore.Summary> stopped) { return chosenRide(stopped, selectedRide); }
    private static String chosenRide(List<RideStore.Summary> stopped, String remembered) {
        for (RideStore.Summary s : stopped) if (s.id.equals(remembered)) return s.id;
        return stopped.isEmpty() ? null : stopped.get(0).id;
    }
    private void selectRide(String id) {
        if (!id.equals(selectedRide)) { selectedRide = id; preferences.edit().putString("ride_selected", id).apply(); }
        styleHistoryRows(id);
        boolean changed = !detailKey.startsWith(id + ":");
        refreshHistory();
        if (changed && detailScroll != null) {
            Motion.scrollBeforeDraw(detailScroll, 0);
            View title = detailPane.findViewWithTag("ride-title");
            if (title instanceof TextView) detailPane.announceForAccessibility(T.ridesShowing(((TextView) title).getText()));
        }
    }
    private void rowTouch(View row, Runnable action) {
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 12, 0)));
        row.setClickable(true); row.setFocusable(true); row.setOnClickListener(v -> action.run());
    }
    private void loadThumbnails(List<String> ids) {
        PhoneRideStore store = service.phoneRides; if (store == null) return;
        for (String id : ids) {
            final File file; try { file = store.rawCsv(id); } catch (RuntimeException invalid) { continue; }
            final String key = id + ":" + file.length() + ":" + file.lastModified();
            float[] cached = THUMBS.get(key);
            if (cached != null) { RouteThumb thumb = thumbs.get(id); if (thumb != null) thumb.setRoute(cached); continue; }
            try {
                thumbLoader.execute(() -> {
                    float[] route = readThumb(file); if (route == null) return;
                    THUMBS.put(key, route);
                    host.activity().runOnUiThread(() -> { if (closed) return; RouteThumb thumb = thumbs.get(id); if (thumb != null) thumb.setRoute(route); });
                });
            } catch (java.util.concurrent.RejectedExecutionException stopped) { return; }
        }
    }
    /** Reads only accepted GPS fixes from the phone journal, never more than 16 MB, and keeps a 64-point outline. */
    static float[] readThumb(File file) {
        if (!file.isFile() || file.length() > 16L * 1024 * 1024) return NO_ROUTE;
        ArrayList<double[]> fixes = new ArrayList<double[]>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) return null;
                if (!line.startsWith("GPS,")) continue;
                String[] v = line.split(",", -1); if (v.length < 6) continue;
                try {
                    double lat = Double.parseDouble(v[3]), lon = Double.parseDouble(v[4]), accuracy = Double.parseDouble(v[5]);
                    if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180 || !(accuracy >= 0 && accuracy <= 35)) continue;
                    fixes.add(new double[]{lat, lon});
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException | RuntimeException e) { return NO_ROUTE; }
        if (fixes.isEmpty()) return NO_ROUTE;
        int count = Math.min(64, fixes.size());
        double latitude = 0; for (double[] fix : fixes) latitude += fix[0]; latitude /= fixes.size();
        double cos = Math.max(.05, Math.cos(Math.toRadians(latitude)));
        double[] xs = new double[count], ys = new double[count];
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double[] fix = fixes.get(count <= 1 ? 0 : (int) ((long) i * (fixes.size() - 1) / (count - 1)));
            xs[i] = fix[1] * cos; ys[i] = -fix[0];
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
        }
        double span = Math.max(maxX - minX, maxY - minY);
        float[] result = new float[2 + 2 * count];
        if (span < 1e-7) { result[0] = 0; result[1] = 0; for (int i = 0; i < count; i++) { result[2 + 2 * i] = 0; result[3 + 2 * i] = 0; } return result; }
        result[0] = (float) ((maxX - minX) / span); result[1] = (float) ((maxY - minY) / span);
        for (int i = 0; i < count; i++) { result[2 + 2 * i] = (float) ((xs[i] - minX) / span); result[3 + 2 * i] = (float) ((ys[i] - minY) / span); }
        return result;
    }
    private static long bytes(File file, int depth) {
        if (file.isFile()) return file.length();
        File[] children = depth > 3 ? null : file.listFiles(); long total = 0;
        if (children != null) for (File child : children) total += bytes(child, depth + 1);
        return total;
    }
    private void updateComparisonActions() {
        if (!historyLoaded) { comparisonUnknown(); return; }
        boolean possible = savedCount >= 2;
        // Looks disabled until two rides exist, yet stays tappable so it can say why.
        compareRow.setEnabled(possible); compareRow.view.setEnabled(true);
        compareRow.update("", !possible ? savedCount + "/2" : selected.size() == 2 ? T.ridesCompareTwoSelectedValue() : "");
        if (!possible) compareRow.view.setContentDescription(T.ridesCompareTwoSavedCount(savedCount, savedCount));
        else if (selected.size() == 2) compareRow.view.setContentDescription(T.ridesCompareTwoSelected());
    }
    /** While the list loads the count is unknown: no count rather than "0/2", still tappable. */
    private void comparisonUnknown() {
        compareRow.setEnabled(false); compareRow.view.setEnabled(true); compareRow.update("", "");
    }
    private void saveSelection() { preferences.edit().putStringSet("ride_comparison", new LinkedHashSet<String>(selected)).apply(); }
    /** Pick exactly two saved rides; the choice persists like before. */
    private void chooseComparison() {
        if (service.rides == null) return;
        try {
            List<RideStore.Summary> summaries = comparisonSummaries();
            if (summaries == null) return;
            List<RideStore.Summary> stopped = new ArrayList<RideStore.Summary>();
            for (RideStore.Summary s : summaries) if (!s.active) stopped.add(s);
            if (stopped.size() < 2) { host.toast(T.ridesToastCompareNeedsTwo()); return; }
            LinkedHashSet<String> picked = new LinkedHashSet<String>();
            for (String id : selected) for (RideStore.Summary s : stopped) if (s.id.equals(id)) picked.add(id);
            // Title with a live n/2 count; once two are ticked the rest grey out instead of refusing with a toast.
            LinearLayout title = horizontal(); title.setGravity(Gravity.CENTER_VERTICAL); title.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(8));
            TextView heading = ui.text(T.ridesCompareTitle(), 20, true); heading(heading); title.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            TextView count = pill("", ui.accent); title.addView(count);
            LinearLayout list = vertical(); list.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(8));
            List<CheckBox> boxes = new ArrayList<CheckBox>();
            AlertDialog[] holder = new AlertDialog[1];
            Runnable sync = () -> {
                count.setText(picked.size() + OF_TWO); count.setContentDescription(T.ridesCompareChosenCount(picked.size(), picked.size()));
                for (int i = 0; i < boxes.size(); i++) boxes.get(i).setEnabled(picked.size() < 2 || boxes.get(i).isChecked());
                if (holder[0] != null && holder[0].getButton(AlertDialog.BUTTON_POSITIVE) != null) holder[0].getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(picked.size() == 2);
            };
            for (RideStore.Summary s : stopped) {
                CheckBox box = new CheckBox(host.activity());
                box.setText((s.label.isEmpty() ? T.ridesDefaultName() : s.label) + " · " + shortDate(s.startWallMs) + " · " + distance(s.distanceKm));
                box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * ui.textScale); box.setTypeface(ui.regular());
                box.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{ui.dim, ui.text}));
                box.setButtonTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{ui.dim, ui.accent, ui.muted}));
                box.setMinHeight(ui.dp(48)); box.setPadding(ui.dp(8), ui.dp(6), 0, ui.dp(6));
                box.setChecked(picked.contains(s.id));
                box.setOnCheckedChangeListener((button, isChecked) -> { if (isChecked) picked.add(s.id); else picked.remove(s.id); sync.run(); });
                boxes.add(box); list.addView(box);
            }
            ScrollView listScroll = new ScrollView(host.activity()); listScroll.addView(list);
            AlertDialog dialog = new AlertDialog.Builder(host.activity()).setCustomTitle(title).setView(listScroll)
                    .setNegativeButton(T.commonCancel(), null).setPositiveButton(T.ridesCompareAction(), (d, which) -> {
                        selected.clear(); selected.addAll(picked); saveSelection(); updateComparisonActions(); showComparison();
                    }).create();
            holder[0] = dialog; show(dialog, DIALOG, null); sync.run();
        } catch (IOException e) { host.toast(T.ridesToastSavedUnreadable()); }
    }
    /** The cached list, or null while rideListLoader reads it; chooseComparison() then runs again with the result. */
    private List<RideStore.Summary> comparisonSummaries() throws IOException {
        List<RideStore.Summary> summaries = loadedComparison; loadedComparison = null;
        IOException failure = comparisonFailure; comparisonFailure = null;
        if (failure != null) throw failure;
        if (summaries == null) summaries = service.rides.listCached();
        if (summaries != null || comparisonLoading) return summaries;
        comparisonLoading = true;
        final RideStore store = service.rides;
        try {
            rideListLoader.execute(() -> {
                List<RideStore.Summary> loaded = null; IOException error = null;
                try { loaded = store.list(); } catch (IOException unreadable) { error = unreadable; }
                final List<RideStore.Summary> result = loaded; final IOException failed = error;
                host.activity().runOnUiThread(() -> {
                    comparisonLoading = false;
                    if (closed || host.activity().isDestroyed()) return;
                    loadedComparison = result; comparisonFailure = failed; chooseComparison();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException screenClosed) { comparisonLoading = false; }
        return null;
    }
    private void showDetails(String id) {
        try {
            RideStore.Summary ride = service.rides.get(id); if (ride == null) { host.toast(T.ridesToastRecordingUnavailable()); return; }
            ScrollView scroller = new MapTouchScroll(); LinearLayout body = vertical();
            body.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(20)); scroller.addView(body);
            AlertDialog[] holder = new AlertDialog[1];
            buildOverview(body, ride, service.rides.trend(ride.id, RideStore.MAX_TREND_POINTS), () -> { if (holder[0] != null) holder[0].dismiss(); });
            AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(scroller).setPositiveButton(T.commonDone(), null).create();
            holder[0] = dialog; show(dialog, PAGE, null);
        } catch (IOException e) { host.toast(T.ridesToastRecordingUnreadable()); }
    }
    private void rideHeading(LinearLayout body, RideStore.Summary ride) {
        body.addView(ui.caption(longDate(ride.startWallMs)));
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = ui.text(nameOf(ride), 24, true); title.setMaxLines(3); title.setTag("ride-title"); heading(title);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView rename = ui.secondaryButton("", () -> renameRide(ride.id, title));
        iconize(rename, "edit");
        rename.setContentDescription(T.ridesRename()); row.addView(rename, new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)));
        row.setPadding(0, ui.dp(4), 0, ui.dp(12)); body.addView(row);
    }
    private void renameRide(String id, TextView title) {
        EditText name = input(T.ridesRenameHint()); name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)}); name.setText(title.getText()); name.setSelectAllOnFocus(true);
        LinearLayout box = dialogBody(T.ridesRename(), null); box.addView(name);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).setNegativeButton(T.commonCancel(), null).setPositiveButton(T.ridesRenameSave(), null).create();
        show(dialog, DIALOG, null); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String clean = name.getText().toString().trim(); if (clean.isEmpty()) { name.setError(T.ridesRenameEmpty()); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            phoneLoader.execute(() -> {
                try {
                    service.rides.rename(id, clean);
                    host.activity().runOnUiThread(() -> { if (closed || host.activity().isDestroyed()) return; title.setText(clean); historySignature = ""; dialog.dismiss(); refreshHistory(); });
                } catch (IOException | IllegalArgumentException e) {
                    host.activity().runOnUiThread(() -> { if (!closed && dialog.isShowing()) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); host.toast(T.ridesToastRenameFailed()); } });
                }
            });
        });
    }
    /** The same comprehensive overview on compact screens and in the unfolded detail pane. */
    private void buildOverview(LinearLayout body, RideStore.Summary ride, List<RideStore.Point> measured, Runnable afterDelete) throws IOException {
        rideHeading(body, ride);
        LinearLayout route = vertical(); body.addView(route);
        ChartPanel graph = new ChartPanel(ride.id, measured, null, false, ride.unsignedCurrent()); body.addView(graph);
        body.addView(ui.sectionLabel(T.ridesJourney()));
        if (ride.distanceKm != null) tileReadings(distanceParts(ride.distanceKm));
        if (ride.averageSpeedKmh != null) tileReadings(speedParts(ride.averageSpeedKmh));
        if (ride.maximumSpeedKmh != null) tileReadings(speedParts(ride.maximumSpeedKmh));
        List<View> journey = tiles(body, new String[]{"route", T.ridesTileDistance(), ride.odometerReset ? "—" : distance(ride.distanceKm), "clock", T.ridesTileDuration(), duration(ride.shownElapsedMs),
                "speed", T.ridesTileAvgSpeed(), speed(ride.averageSpeedKmh), "speed", T.ridesTileMaxSpeed(), speed(ride.maximumSpeedKmh)});
        if (ride.odometerReset) reasonOnTap(journey.get(0), T.ridesReasonOdometerReset());
        body.addView(ui.sectionLabel(T.ridesBatteryEnergy()));
        boolean batteryGain = ride.batteryDropPercent != null && ride.batteryDropPercent < 0;
        tileReadings(efficiencyParts(ride), quantityParts(ride.measuredThroughputWh, T.unitsWattHour(), 2));
        List<View> energy = tiles(body, new String[]{"battery", batteryGain ? T.ridesTileBatteryGain() : T.ridesTileBatteryDrop(), battery(ride), "route", T.ridesTilePerDistance(), efficiency(ride),
                "bolt", T.ridesTileThroughput(), quantity(ride.measuredThroughputWh, T.unitsWattHour(), 2), "bolt", T.ridesPowerCoverage(), percent(ride.electricalCoverage)});
        if (ride.batteryDropPercent != null && Double.isFinite(ride.batteryDropPercent))
            energy.get(0).setContentDescription((batteryGain ? T.ridesBatteryGainDescriptionCount(Math.round(Math.abs(ride.batteryDropPercent)), Math.round(Math.abs(ride.batteryDropPercent)))
                    : T.ridesBatteryDropDescriptionCount(Math.round(Math.abs(ride.batteryDropPercent)), Math.round(Math.abs(ride.batteryDropPercent)))));
        reasonOnTap(energy.get(1), efficiencyReason(ride));
        LinearLayout phone = vertical(); body.addView(phone);
        detailSections(body, ride.id, ride, measured, afterDelete);
        if (service.phoneRides != null) new PhoneOverview(ride.id, route, phone, graph);
    }
    /** A pending sensor save refreshes the already-open compact dialog as well as the wide pane. */
    private final class PhoneOverview implements Runnable {
        final String id; final LinearLayout route, phone; final ChartPanel graph; final FrameLayout placeholder;
        boolean loading, finished;
        PhoneOverview(String id, LinearLayout route, LinearLayout phone, ChartPanel graph) {
            this.id=id; this.route=route; this.phone=phone; this.graph=graph;
            placeholder=mapSkeleton();placeholder.setMinimumHeight(ui.dp(INLINE_MAP_DP));
            PhoneRideStore known=service.phoneRides;
            // A ride without phone files never reserves map space, so nothing flashes or jumps away.
            if(known!=null&&!service.phoneSaving()&&!known.rawCsv(id).isFile()&&!known.motionCsv(id).isFile()){finished=true;return;}
            route.addView(placeholder);
            phoneRefreshers.add(this);
            route.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { run(); }
                @Override public void onViewDetachedFromWindow(View v) { finished=true;phoneRefreshers.remove(PhoneOverview.this); }
            });
            placeholder.setContentDescription(service.phoneSaving()?T.ridesRouteFinishing():T.ridesRouteLoading());
            route.post(this);
        }
        @Override public void run() {
            if(closed||finished||loading||!route.isAttachedToWindow())return;
            if(service.phoneSaving()){placeholder.setContentDescription(T.ridesRouteFinishing());return;}
            PhoneRideStore store=service.phoneRides;
            if(store==null||(!store.rawCsv(id).isFile()&&!store.motionCsv(id).isFile())){finish();return;}
            loading=true;placeholder.setContentDescription(T.ridesRouteLoading());
            phoneLoader.execute(() -> {
            try {
                PhoneRideStore.Track track = store.load(id);
                host.activity().runOnUiThread(() -> {
                    if (closed || finished || host.activity().isDestroyed() || !route.isAttachedToWindow()) return;
                    finish();
                    graph.setPhone(track);
                    if(track.locationCount==0&&track.motionSampleCount==0)return;
                    PhoneRide map = phoneRide(track, route, phone, true);
                    if (!track.points.isEmpty()) route.addView(map.frame, 0, new LinearLayout.LayoutParams(-1, ui.dp(INLINE_MAP_DP)));
                    else { maps.remove(map.map); map.map.close(); }
                    route.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                        @Override public void onViewAttachedToWindow(View v) { }
                        @Override public void onViewDetachedFromWindow(View v) { maps.remove(map.map); map.map.close(); }
                    });
                });
            } catch (IOException failure) {
                host.activity().runOnUiThread(() -> { if (!closed && !finished && route.isAttachedToWindow()) {finish();routeNotice(route, T.ridesRouteUnavailable(), () -> exportSheet(id));} });
            }
            });
        }
        /** The map or notice that replaces the placeholder fades up in place; anything below glides (the chart's own controls simply update). */
        private void finish(){finished=true;phoneRefreshers.remove(this);if(route.getParent() instanceof ViewGroup)Motion.animateChanges((ViewGroup)route.getParent(),graph);route.removeView(placeholder);}
    }
    /** Map-shaped loading placeholder: a faint dashed route and a spinner instead of a sentence. */
    private FrameLayout mapSkeleton() {
        FrameLayout frame = new FrameLayout(host.activity()); frame.setBackground(ui.shape(ui.surface, 20, 0));
        View road = new View(host.activity()) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(2)); paint.setColor(ui.outline);
                paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{ui.dp(8), ui.dp(6)}, 0));
                float w = getWidth(), h = getHeight(); Path path = new Path();
                path.moveTo(w * .12f, h * .78f); path.cubicTo(w * .28f, h * .30f, w * .46f, h * .82f, w * .60f, h * .42f);
                path.cubicTo(w * .70f, h * .16f, w * .82f, h * .30f, w * .88f, h * .22f);
                canvas.drawPath(path, paint);
            }
        };
        road.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(road, new FrameLayout.LayoutParams(-1, -1));
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(host.activity());
        spinner.setIndeterminateTintList(ColorStateList.valueOf(ui.accent)); spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(spinner, new FrameLayout.LayoutParams(ui.dp(32), ui.dp(32), Gravity.CENTER));
        frame.setFocusable(true);
        return frame;
    }
    private final class ChartPanel extends LinearLayout {
        final String rideId;
        final List<RideStore.Point> measured; PhoneRideStore.Track phone;
        final RideChartView chart; final Flow choices; final Set<String> chosen;
        final android.widget.HorizontalScrollView chartScroll;
        final LinearLayout inspection; String inspectionSignature = "";
        final boolean full, inferRegen; List<RideChartData.Series> data;
        final TextView loadStatus; AlertDialog expandedDialog; java.util.concurrent.Future<?> fullLoad;
        /** A waiting "load once saving finishes" step; cancelled with the load so it can't reload later. */
        Runnable fullLoadRetry;
        int loadGeneration; boolean fullDataLoaded;
        int overlayLimit = ui.dp(112); ScrollView overlayScroll;
        ChartPanel(String rideId, List<RideStore.Point> measured, PhoneRideStore.Track phone, boolean full, boolean inferRegen) {
            super(host.activity()); this.rideId = rideId; this.measured = measured; this.phone = phone; this.full = full;
            this.inferRegen = inferRegen;
            setOrientation(VERTICAL); setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); setBackground(ui.shape(ui.surface, 20, 0));
            LinearLayout head = horizontal(); head.setGravity(Gravity.CENTER_VERTICAL); head.addView(ui.text(T.ridesReadingsTitle(), 18, true), new LinearLayout.LayoutParams(0, -2, 1));
            loadStatus = ui.text(T.ridesLoading(), 12, false); loadStatus.setMinHeight(ui.dp(48)); loadStatus.setGravity(Gravity.CENTER); loadStatus.setPadding(ui.dp(6), 0, ui.dp(6), 0);
            if (full) head.addView(loadStatus);
            if (!full) { TextView expand = ui.secondaryButton(T.ridesExpand(), this::expand); iconize(expand, "fit"); head.addView(expand, new LinearLayout.LayoutParams(-2, -2)); }
            addView(head); choices = new Flow(); choices.setPadding(0, ui.dp(8), 0, ui.dp(10));
            if (full) {
                // The series choices share this bounded region so enlarged text never
                // takes another unbounded block out of the fullscreen plot.
                ScrollView overlays = new ScrollView(host.activity()) {
                    @Override protected void onMeasure(int width, int height) { super.onMeasure(width, MeasureSpec.makeMeasureSpec(overlayLimit, MeasureSpec.AT_MOST)); }
                };
                overlayScroll = overlays;
                LinearLayout controls = vertical(); controls.addView(choices);
                overlays.setVerticalFadingEdgeEnabled(true); overlays.setFadingEdgeLength(ui.dp(16)); overlays.addView(controls); addView(overlays, new LinearLayout.LayoutParams(-1, overlayLimit));
            } else addView(choices);
            chosen = new LinkedHashSet<>(preferences.getStringSet("ride_chart_series", RideChartData.defaults()));
            chart = new RideChartView(ui);
            chartScroll = new android.widget.HorizontalScrollView(host.activity()); chartScroll.setFillViewport(true); chartScroll.addView(chart, new android.widget.HorizontalScrollView.LayoutParams(-1, full ? -1 : ui.dp(280)));
            addView(chartScroll, new LinearLayout.LayoutParams(-1, full ? 0 : ui.dp(280), full ? 1 : 0));
            inspection = horizontal(); inspection.setGravity(Gravity.CENTER_VERTICAL);
            // Reserve the same height before/after a touch so scrubbing never moves the plot.
            inspection.setMinimumHeight(Math.max(ui.dp(68), (int)ui.sp(56) + ui.dp(12)));
            android.widget.HorizontalScrollView readings = new android.widget.HorizontalScrollView(host.activity());
            readings.setHorizontalScrollBarEnabled(false); readings.setHorizontalFadingEdgeEnabled(true); readings.setFadingEdgeLength(ui.dp(16));
            readings.addView(inspection, new android.widget.HorizontalScrollView.LayoutParams(-2, -2)); addView(readings);
            chart.setInspectionListener(this::inspect);
            LinearLayout zoom = horizontal(); zoom.setGravity(Gravity.CENTER); zoom.setBaselineAligned(false); zoom.setPadding(0, ui.dp(6), 0, ui.dp(8));
            TextView[] buttons = {ui.secondaryButton("−", () -> chart.zoomBy(.5)), ui.secondaryButton(T.ridesChartFit(), chart::reset), ui.secondaryButton("+", () -> chart.zoomBy(2))};
            buttons[0].setContentDescription(T.ridesChartZoomOut()); buttons[1].setContentDescription(T.ridesChartFitDescription()); buttons[2].setContentDescription(T.ridesChartZoomIn());
            for (TextView button : buttons) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMargins(ui.dp(3), 0, ui.dp(3), 0); zoom.addView(button, p); } addView(zoom);
            update();
        }
        @Override protected void onMeasure(int width, int height) {
            overlayLimit = ui.dp(112);
            if (overlayScroll != null) {
                overlayScroll.getLayoutParams().height = overlayLimit;
                overlayScroll.forceLayout();
            }
            super.onMeasure(width, height);
            if (full && chartScroll != null && chartScroll.getMeasuredHeight() < ui.dp(200)) {
                // Reserve graph room using actual available height, not a device-width
                // breakpoint. The nested MATCH_PARENT chart can still report its old
                // height until its scroll container finishes measuring. Use the current
                // container height; overlay choices remain reachable by scrolling.
                overlayLimit = Math.max(ui.dp(48), overlayLimit - (ui.dp(200) - chartScroll.getMeasuredHeight()));
                overlayScroll.getLayoutParams().height = overlayLimit;
                overlayScroll.forceLayout();
                super.onMeasure(width, height);
            }
        }
        void inspect(double time, List<RideChartData.Series> selected) {
            StringBuilder signature = new StringBuilder(Double.isFinite(time) ? "selected" : "idle");
            for (RideChartData.Series s : selected) { RideChartData.Value value = s.sampleAt(time); signature.append('|').append(s.key).append(':').append(s.unit).append(':').append(value == null ? "gap" : value.time + ":" + value.value); }
            if (signature.toString().equals(inspectionSignature)) return;
            inspectionSignature = signature.toString(); inspection.removeAllViews();
            // The chart itself says "Choose readings above"; the cursor starts on the last sample otherwise.
            if (!Double.isFinite(time) || selected.isEmpty()) return;
            for (RideChartData.Series s : selected) {
                RideChartData.Value value = s.sampleAt(time);
                LinearLayout cell = vertical(); cell.setMinimumWidth(ui.dp(124)); cell.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
                TextView label = ui.text((s.index % 3 == 0 ? MARK_SOLID : s.index % 3 == 1 ? MARK_DASH : MARK_DOT) + s.notedLabel(), 12, false);
                label.setSingleLine(true); cell.addView(label);
                TextView reading = ui.text(RideChartView.sampleValue(s, value), 17, true); reading.setSingleLine(true); cell.addView(reading);
                boolean available = value != null && value.value != null && Double.isFinite(value.value);
                TextView stamp = ui.caption(available ? T.mapsChartCursorAt(RideChartView.sampleTime(value.time)) : "—"); stamp.setSingleLine(true); cell.addView(stamp);
                cell.setContentDescription(T.ridesChartCellDescription(s.notedLabel(), (available ? reading.getText() + ", " + stamp.getText() : T.ridesChartNoSample())));
                inspection.addView(cell);
            }
        }
        void setPhone(PhoneRideStore.Track track) { phone = track; update(); }
        void update() {
            applyData(RideChartData.build(measured, phone, !"km".equals(preferences.getString("units", "mi")), false, inferRegen));
        }
        void applyData(List<RideChartData.Series> prepared) {
            data = prepared;
            choices.removeAllViews();
            for (RideChartData.Series series : data) {
                series.directionNote = series.directionInferred ? directionUnconfirmedChip() : null;
                CheckBox toggle = new CheckBox(host.activity()); toggle.setButtonDrawable(null); toggle.setMinHeight(ui.dp(48));
                // Energy / distance exists only above walking pace; the chip says where, and the chart hatches the rest.
                String limit = "wh_per_distance".equals(series.key) ? (miles() ? T.ridesChartChipFromMph() : T.ridesChartChipFromKmh()) : "";
                toggle.setText((series.index % 3 == 0 ? MARK_SOLID : series.index % 3 == 1 ? MARK_DASH : MARK_DOT) + series.notedLabel() + limit + (series.available() ? "" : T.ridesChartChipNoData()));
                toggle.setTextSize(12 * ui.textScale); toggle.setTypeface(ui.semibold()); toggle.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
                toggle.setChecked(chosen.contains(series.key)); toggle.setContentDescription(WH_PER_DISTANCE.equals(series.key) ? (miles() ? (series.available() ? T.ridesChartSeriesOnlyAtMph(series.displayLabel()) : T.ridesChartSeriesOnlyAtMphNoSamples(series.displayLabel())) : (series.available() ? T.ridesChartSeriesOnlyAtKmh(series.displayLabel()) : T.ridesChartSeriesOnlyAtKmhNoSamples(series.displayLabel()))) : (series.available() ? T.ridesChartSeries(series.notedLabel()) : T.ridesChartSeriesNoSamples(series.displayLabel())));
                Runnable style = () -> { toggle.setTextColor(toggle.isChecked() ? ui.text : ui.muted); toggle.setBackground(ui.shape(toggle.isChecked() ? Ui.withAlpha(series.color, 50) : ui.raised, 12, toggle.isChecked() ? series.color : ui.outline)); };
                style.run(); toggle.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) chosen.add(series.key); else chosen.remove(series.key);
                    preferences.edit().putStringSet("ride_chart_series", new LinkedHashSet<>(chosen)).apply(); style.run(); chart.set(data, chosen);
                }); choices.addView(toggle);
            }
            chart.set(data, chosen);
        }
        void expand() {
            if (closed || !isAttachedToWindow() || (expandedDialog != null && expandedDialog.isShowing())) return;
            ChartPanel expanded = new ChartPanel(rideId, measured, phone, true, inferRegen);
            expanded.setMinimumHeight(Math.max(ui.dp(400), host.activity().getWindow().getDecorView().getHeight() - ui.dp(110)));
            AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(expanded).setPositiveButton(T.commonDone(), null).create();
            expandedDialog = dialog;
            show(dialog, PAGE, () -> { cancelFullLoad(); expandedDialog = null; if (!closed && isAttachedToWindow()) { chosen.clear(); chosen.addAll(preferences.getStringSet("ride_chart_series", RideChartData.defaults())); update(); } });
            dialog.getWindow().setLayout(-1, -1);
            expanded.loadStatus.setOnClickListener(v -> loadFull(expanded, dialog));
            loadFull(expanded, dialog);
        }
        void loadFull(ChartPanel expanded, AlertDialog dialog) {
            if (closed || !isAttachedToWindow() || !dialog.isShowing()) return;
            cancelFullLoad(); final int generation = loadGeneration;
            expanded.loadStatus.setText(T.ridesLoading()); expanded.loadStatus.setEnabled(false);
            if (service.phoneSaving()) {
                // The pill reads Saving… while the sensor files finish, then loads by itself.
                expanded.loadStatus.setText(T.ridesSaving()); expanded.loadStatus.setContentDescription(T.ridesSavingSensors());
                Runnable retry = new Runnable() { @Override public void run() {
                    if (!dialog.isShowing()) { phoneRefreshers.remove(this); return; }
                    if (!service.phoneSaving()) { phoneRefreshers.remove(this); loadFull(expanded, dialog); }
                } };
                fullLoadRetry = retry; phoneRefreshers.add(retry); return;
            }
            final boolean miles = !"km".equals(preferences.getString("units", "mi"));
            final RideStore rideStore = service.rides; final PhoneRideStore phoneStore = service.phoneRides;
            fullLoad = chartLoader.submit(() -> {
                try {
                    List<RideStore.Point> all = rideStore.fullTrend(rideId);
                    PhoneRideStore.Track track = phoneStore == null ? null : phoneStore.loadSnapshot(rideId);
                    List<RideChartData.Series> prepared = RideChartData.build(all, track, miles, true, inferRegen);
                    host.activity().runOnUiThread(() -> {
                        if (!currentLoad(generation, expanded, dialog)) return;
                        fullLoad = null; expanded.fullDataLoaded = true;
                        // The preview sharpens into the full journal rather than swapping in one frame.
                        Motion.page(expanded, 0, () -> {
                            expanded.applyData(prepared);
                            expanded.loadStatus.setText(T.ridesAllSamples()); expanded.loadStatus.setContentDescription(T.ridesAllSamplesDescription());
                        });
                    });
                } catch (java.io.InterruptedIOException | java.util.concurrent.CancellationException canceled) {
                    // Closing, replacing the ride, or leaving Rides cancels without a late UI update.
                } catch (IOException | RuntimeException | OutOfMemoryError failure) {
                    // Executor Futures otherwise swallow these failures and leave Loading
                    // disabled forever. Memory exhaustion gets best-effort recovery;
                    // other fatal Error types are not blanket-caught.
                    // The pill already says Preview · Retry; only low memory needs a word (Export still has the full journal).
                    String message = failure instanceof RideStore.DetailMemoryException || failure instanceof OutOfMemoryError
                            ? T.ridesToastLowMemory() : null;
                    host.activity().runOnUiThread(() -> { if (currentLoad(generation, expanded, dialog)) { fullLoad = null; fullLoadFailed(expanded, message); } });
                }
            });
        }
        boolean currentLoad(int generation, ChartPanel expanded, AlertDialog dialog) {
            return !closed && generation == loadGeneration && expandedDialog == dialog && dialog.isShowing()
                    && isAttachedToWindow() && expanded.isAttachedToWindow() && !host.activity().isDestroyed();
        }
        void fullLoadFailed(ChartPanel expanded, String message) {
            expanded.loadStatus.setText(T.ridesPreviewRetry()); expanded.loadStatus.setEnabled(true);
            expanded.loadStatus.setContentDescription(T.ridesPreviewRetryDescription()); if (message != null) host.toast(message);
        }
        void cancelFullLoad() {
            loadGeneration++; if (fullLoad != null) { fullLoad.cancel(true); fullLoad = null; }
            if (fullLoadRetry != null) { phoneRefreshers.remove(fullLoadRetry); fullLoadRetry = null; }
        }
        @Override protected void onDetachedFromWindow() {
            cancelFullLoad(); if (expandedDialog != null) expandedDialog.dismiss(); super.onDetachedFromWindow();
        }
    }
    /** Energy, quality, sensor history, notes, exports and Delete: shared by the details page and the wide detail pane. */
    private void detailSections(LinearLayout body, String id, RideStore.Summary s, List<RideStore.Point> measured, Runnable afterDelete) {
        // Section label with a Direction ✓ / ? chip that gives its reason on tap.
        LinearLayout energyHead = horizontal(); energyHead.setGravity(Gravity.CENTER_VERTICAL);
        energyHead.addView(ui.sectionLabel(T.ridesElectricalEnergy()));
        TextView direction = pill(s.directionConfirmed ? T.ridesDirectionChipConfirmed() : directionUnconfirmedChip(), s.directionConfirmed ? ui.ok : ui.orange);
        // A 48dp touch target around a compact pill.
        direction.setBackground(new InsetDrawable(direction.getBackground(), 0, ui.dp(9), 0, ui.dp(9))); direction.setMinHeight(ui.dp(48));
        reasonOnTap(direction, s.directionConfirmed ? T.ridesDirectionConfirmed() : T.ridesDirectionUnconfirmed());
        LinearLayout.LayoutParams directionParams = new LinearLayout.LayoutParams(-2, -2); directionParams.leftMargin = ui.dp(4); directionParams.topMargin = ui.dp(4);
        energyHead.addView(direction, directionParams); body.addView(energyHead);
        LinearLayout energy = ui.groupCard();
        boolean partialDirection = s.confirmedDirectionCoverageMs > 0 && !s.directionConfirmed;
        LinearLayout draw = valueRow(energy, partialDirection ? T.ridesDrawShort() : T.ridesDraw(), quantity(s.drawWh, "Wh", 2));
        LinearLayout input = valueRow(energy, partialDirection ? T.ridesInputShort() : T.ridesInput(), quantity(s.returnedWh, "Wh", 2));
        if (partialDirection) {
            partialGlyph(draw, T.ridesDrawPartial()); partialGlyph(input, T.ridesInputPartial());
        }
        valueRow(energy, T.ridesNetEnergy(), quantity(s.netConsumptionWh, "Wh", 2));
        reasonOnTap(valueRow(energy, s.netWhPerKm == null ? T.ridesThroughputPerDistance() : T.ridesNetPerDistance(), efficiency(s)), efficiencyReason(s));
        body.addView(energy);
        if (partialDirection) directionSplit(body, s.confirmedDirectionCoverageMs, s.unconfirmedDirectionCoverageMs);

        body.addView(ui.sectionLabel(T.ridesMeasurementQuality()));
        LinearLayout quality = ui.groupCard();
        valueRow(quality, T.ridesPowerCoverage(), percent(s.electricalCoverage));
        valueRow(quality, T.ridesObservedRecorded(), duration(s.electricalCoverageMs) + " / " + duration(s.elapsedMs));
        valueRow(quality, T.ridesUnobservedPower(), duration(s.skippedElectricalMs));
        valueRow(quality, T.ridesSamplesGaps(), String.format(Fmt.locale(Locale.US), "%,d / %,d", s.sampleCount, s.gapCount));
        valueRow(quality, T.ridesMovingStationary(), duration(s.movingMs) + " / " + duration(s.stationaryMs));
        valueRow(quality, T.ridesMovingThroughput(), quantity(s.movingThroughputWh, "Wh", 2));
        valueRow(quality, T.ridesStationaryThroughput(), quantity(s.stationaryThroughputWh, "Wh", 2));
        valueRow(quality, T.ridesUnknownMotion(), quantity(s.unknownMotionThroughputWh, "Wh", 2));
        valueRow(quality, T.ridesVoltageMinMax(), T.fmtDual(measured(quantity(s.minimumVoltageV, "V", 2)), measured(quantity(s.maximumVoltageV, "V", 2))));
        valueRow(quality, T.ridesPeakPower(), quantity(s.peakAbsolutePowerW, "W", 0));
        valueRow(quality, T.ridesTemperature(), T.fmtDual(measured(temperature(s.minimumTemperatureC)), measured(temperature(s.maximumTemperatureC))));
        body.addView(quality);
        // A min / max pair whose minimum is missing reads quiet, as the other unmeasured rows do.
        if (s.minimumVoltageV == null) quiet(quality, 2);
        if (s.minimumTemperatureC == null) quiet(quality, 0);
        if (s.recovered) {
            TextView recovered = pill(T.ridesRecoveredGap(), ui.orange); recovered.setMinHeight(ui.dp(28));
            LinearLayout.LayoutParams recoveredParams = new LinearLayout.LayoutParams(-2, -2); recoveredParams.leftMargin = ui.dp(4); recoveredParams.bottomMargin = ui.dp(10);
            body.addView(recovered, recoveredParams);
        }
        if (s.automaticStop || s.noMotionStop) {
            TextView automatic = pill(T.ridesSavedAutomatically(), ui.blue); automatic.setMinHeight(ui.dp(28));
            automatic.setContentDescription(RideAutoStopText.description(s));
            LinearLayout.LayoutParams automaticParams = new LinearLayout.LayoutParams(-2, -2); automaticParams.leftMargin = ui.dp(4); automaticParams.bottomMargin = ui.dp(10);
            body.addView(automatic, automaticParams);
        }

        if (!s.notes.isEmpty()) { body.addView(ui.sectionLabel(T.ridesNotesSection())); LinearLayout notes = ui.card(); notes.addView(ui.text(s.notes, 14, false)); body.addView(notes); }

        // One button for every file this ride can export (RideExportFiles); the sheet says which apply.
        TextView export = ui.secondaryButton(T.ridesExportData(), () -> exportSheet(id)); iconize(export, EXPORT_ICON);
        ladder(export, T.ridesExportData(), T.ridesExportDataShort());
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(-1, -2); exportParams.topMargin = ui.dp(10); exportParams.bottomMargin = ui.dp(6);
        body.addView(export, exportParams);
        TextView delete = ui.dangerButton(T.ridesDeleteRide(), () -> confirmDelete(id, s.label, afterDelete));
        iconize(delete, "trash"); body.addView(delete);
        // While the sensor files finish, Delete is disabled and says so.
        Runnable deleteState = () -> { boolean saving = service.phoneSaving(); label(delete, saving ? T.ridesSaving() : T.ridesDeleteRide()); delete.setEnabled(!saving); };
        deleteState.run(); phoneRefreshers.add(deleteState);
        delete.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { if (!phoneRefreshers.contains(deleteState)) phoneRefreshers.add(deleteState); deleteState.run(); }
            @Override public void onViewDetachedFromWindow(View v) { phoneRefreshers.remove(deleteState); }
        });
    }
    /** Why per-distance energy shows "—", or null when it has a value. */
    private static String efficiencyReason(RideStore.Summary s) {
        if ((s.netWhPerKm == null ? s.throughputWhPerKm : s.netWhPerKm) != null) return null;
        if (s.odometerReset) return T.ridesReasonOdometerReset();
        if (s.distanceKm == null) return T.ridesReasonNoOdometer();
        if (s.distanceKm + 1e-9 < RideStore.MIN_EFFICIENCY_DISTANCE_KM) return T.ridesReasonTooShort();
        if (s.electricalCoverage < RideStore.MIN_EFFICIENCY_COVERAGE) return T.ridesReasonLowCoverage();
        return T.ridesNotMeasured();
    }
    /** Marks a Draw / Input row as covering only the confirmed-direction part of the ride. */
    private void partialGlyph(LinearLayout row, String spoken) {
        TextView label = (TextView) row.getChildAt(0);
        Drawable half = new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas canvas) {
                RectF box = new RectF(getBounds()); box.inset(ui.dp(1), ui.dp(1));
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(1.5f)); paint.setColor(ui.orange); canvas.drawOval(box, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawArc(box, 90, 180, true, paint);
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(ColorFilter filter) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
        int size = ui.dp(12); half.setBounds(0, 0, size, size);
        label.setCompoundDrawablesRelative(null, null, half, null); label.setCompoundDrawablePadding(ui.dp(6));
        row.setContentDescription(spoken + ", " + ((TextView) row.getChildAt(1)).getText());
    }
    /** A two-part bar: confirmed-direction time against time that remains absolute throughput. */
    private void directionSplit(LinearLayout parent, long confirmedMs, long unconfirmedMs) {
        LinearLayout box = vertical(); box.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(12));
        LinearLayout bar = horizontal(); bar.setClipToOutline(true); bar.setBackground(ui.shape(ui.raised, 4, 0));
        View confirmed = new View(host.activity()); confirmed.setBackground(ui.shape(ui.accent, 4, 0));
        bar.addView(confirmed, new LinearLayout.LayoutParams(0, -1, Math.max(1, confirmedMs)));
        bar.addView(new View(host.activity()), new LinearLayout.LayoutParams(0, -1, Math.max(1, unconfirmedMs)));
        box.addView(bar, new LinearLayout.LayoutParams(-1, ui.dp(8)));
        LinearLayout labels = horizontal(); labels.setPadding(0, ui.dp(5), 0, 0);
        labels.addView(ui.caption(T.ridesDirectionConfirmedFor(duration(confirmedMs))), new LinearLayout.LayoutParams(0, -2, 1));
        TextView rest = ui.caption(T.ridesDirectionUnconfirmedFor(duration(unconfirmedMs))); rest.setGravity(Gravity.END); labels.addView(rest);
        box.addView(labels);
        box.setContentDescription(T.ridesDirectionSplitDescription(duration(confirmedMs), duration(unconfirmedMs)));
        box.setFocusable(true);
        parent.addView(box);
    }
    /** Wide layout: rebuilds the right pane only when the selection or that ride's saved data changes. */
    private void refreshDetail(String id, List<RideStore.Summary> stopped) {
        RideStore.Summary chosen = null;
        for (RideStore.Summary s : stopped) if (s.id.equals(id)) chosen = s;
        if (chosen == null) { emptyDetail(stopped.isEmpty() ? "none" : "missing"); return; }
        String key = chosen.id + ":" + chosen.sampleCount + ":" + chosen.label + ":" + service.phoneSaving() + ":" + (service.phoneRides != null);
        if (key.equals(detailKey)) return;
        boolean sameRide = detailKey.startsWith(chosen.id + ":");
        int keep = sameRide ? detailScroll.getScrollY() : 0;
        final RideStore.Summary ride = chosen;
        List<RideStore.Point> measured = detailPreview(key, ride, sameRide);
        if (measured == null) return;
        Runnable change = () -> { clearDetail(key); buildDetail(ride, measured); Motion.scrollBeforeDraw(detailScroll, keep); };
        if (sameRide) change.run(); else Motion.replace(detailScroll, change);
    }
    /**
     * The ride's chart preview from the cache, or null while rideListLoader reads it (past the preview cap, or a ride
     * that just changed); refreshHistory() then runs again and the pane fills. Another pane content cancels the load.
     */
    private List<RideStore.Point> detailPreview(String key, RideStore.Summary ride, boolean sameRide) {
        List<RideStore.Point> measured = key.equals(detailLoadedKey) ? detailLoaded : null;
        detailLoadedKey = null; detailLoaded = null;
        if (measured == null) measured = service.rides.trendCached(ride.id);
        if (measured != null) { detailWanted = null; return measured; }
        if (key.equals(detailWanted)) return null;
        if (!sameRide) detailSkeleton();
        detailWanted = key;
        final RideStore store = service.rides;
        try {
            rideListLoader.execute(() -> {
                List<RideStore.Point> loaded;
                try { loaded = store.trend(ride.id, RideStore.MAX_TREND_POINTS); } catch (IOException | RuntimeException unreadable) { loaded = null; }
                final List<RideStore.Point> result = loaded;
                host.activity().runOnUiThread(() -> {
                    if (closed || !key.equals(detailWanted)) return;
                    detailWanted = null;
                    if (result == null) { clearDetail(key); note(detailPane, T.ridesToastRecordingUnreadable()); return; }
                    detailLoadedKey = key; detailLoaded = result; refreshHistory();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException screenClosed) { detailWanted = null; }
        return null;
    }
    private void clearDetail(String key) {
        detailKey = key; detailPane.removeAllViews();
        detailWanted = null;
    }
    /** Wide pane while its ride loads: the map-shaped placeholder alone; the list's skeleton speaks for both. */
    private void detailSkeleton() {
        if (DETAIL_LOADING.equals(detailKey)) return;
        clearDetail(DETAIL_LOADING);
        FrameLayout placeholder = mapSkeleton(); placeholder.setFocusable(false);
        placeholder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        detailPane.addView(placeholder, new LinearLayout.LayoutParams(-1, ui.dp(INLINE_MAP_DP)));
    }
    private static final String DETAIL_LOADING = "loading";
    /** Right pane with no ride to show: a short note instead of an empty column. */
    private void emptyDetail(String reason) {
        if (reason.equals(detailKey)) return;
        clearDetail(reason);
        LinearLayout empty = ui.card(); empty.setGravity(Gravity.CENTER_HORIZONTAL); empty.setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(28));
        empty.addView(ui.icon("route", 30, ui.muted), new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)));
        boolean none = reason.equals("none") || reason.equals("missing");
        TextView title = ui.text(none ? T.ridesEmptyNone() : reason.equals("unavailable") ? T.ridesStorageUnavailable() : T.ridesEmptyUnreadable(), 18, true);
        title.setGravity(Gravity.CENTER); title.setPadding(0, ui.dp(10), 0, 0); heading(title); empty.addView(title);
        detailPane.addView(empty);
    }
    /** Wide layout: the selected ride inline: route map first, then stat tiles and the rest of the details page. */
    private void buildDetail(RideStore.Summary ride, List<RideStore.Point> measured) {
        try { buildOverview(detailPane, ride, measured, null); }
        catch (IOException e) { note(detailPane, T.ridesToastRecordingUnreadable()); }
    }
    private void routeNotice(LinearLayout parent, String text, Runnable files) {
        LinearLayout card = ui.card(); card.addView(ui.caption(text));
        if (files != null) {
            TextView button = ui.secondaryButton(T.ridesPhoneFiles(), files); iconize(button, EXPORT_ICON);
            button.setContentDescription(T.ridesPhoneFilesDescription()); card.addView(button);
        }
        parent.addView(card);
    }
    private String battery(RideStore.Summary s) {
        return s.batteryDropPercent == null || !Double.isFinite(s.batteryDropPercent) ? T.ridesNotMeasured()
                : T.fmtPercent(Fmt.integer(Math.round(Math.abs(s.batteryDropPercent))));
    }
    private void note(LinearLayout parent, String text) {
        TextView caption = ui.caption(text); caption.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(10)); parent.addView(caption);
    }

    private void phoneOptions() { phoneOptions(null); }
    private void phoneOptions(Runnable dismissed) {
        LinearLayout content = vertical(); content.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(20));
        // While recording, a Next ride tag says the changes apply to the next recording.
        View close = sheetHeader(content, T.ridesPhoneSensors(), service.isRecording() ? T.ridesNextRide() : null);
        LinearLayout sensors = ui.groupCard();
        switchRow(sensors, "pin", T.ridesGpsRoute(), T.ridesGpsRouteSub(), "phone_gps", true);
        ui.divider(sensors);
        android.hardware.SensorManager sensorManager = host.activity().getSystemService(android.hardware.SensorManager.class);
        boolean barometer = sensorManager != null && sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_PRESSURE) != null;
        infoRow(sensors, "wave", T.ridesBarometer(), null, barometer ? T.ridesBarometerAuto() : T.ridesBarometerGps());
        ui.divider(sensors);
        switchRow(sensors, "motion", T.ridesMotionSensors(), null, "phone_motion", true);
        labBadge((LinearLayout) ((ViewGroup) sensors.getChildAt(sensors.getChildCount() - 1)).getChildAt(1));
        content.addView(sensors);
        ScrollView scroller = new ScrollView(host.activity()); scroller.addView(content); insetBottom(scroller);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(scroller).create();
        close.setOnClickListener(v -> dialog.dismiss());
        show(dialog, SHEET, () -> { if (!closed) { refresh(); if (dismissed != null) dismissed.run(); } });
    }
    /** The orange LAB badge More uses, placed after a row or section title. */
    private TextView labBadge() {
        TextView badge = ui.text(T.ridesLabBadge(), 10.5f, true); badge.setTypeface(ui.bold()); badge.setLetterSpacing(0.08f); badge.setTextColor(ui.orange);
        badge.setPadding(ui.dp(7), ui.dp(2), ui.dp(7), ui.dp(2)); badge.setMaxLines(1);
        badge.setBackground(ui.shape(Color.TRANSPARENT, 6, Ui.withAlpha(ui.orange, 120))); badge.setContentDescription(T.ridesLabDescription());
        return badge;
    }
    /** Replaces a row's title with the same title followed by the LAB badge. */
    private void labBadge(LinearLayout words) {
        TextView heading = (TextView) words.getChildAt(0); words.removeView(heading);
        LinearLayout line = horizontal(); line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(heading); LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2); badgeParams.leftMargin = ui.dp(8);
        line.addView(labBadge(), badgeParams); words.addView(line, 0);
    }
    /** Keeps a bottom sheet's last row clear of a gesture or button navigation bar. */
    private static void insetBottom(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
    }
    private static String shortSourceName(MapSource source) {
        int split = source.name.indexOf(" · "); return split > 0 ? source.name.substring(0, split) : source.name;
    }
    /** Sheet grab handle, title and a round close button; the caller wires the close action. */
    private View sheetHeader(LinearLayout content, String title, String tag) {
        View grab = new View(host.activity()); grab.setBackground(ui.shape(ui.outline, 2, 0));
        LinearLayout.LayoutParams grabParams = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4)); grabParams.gravity = Gravity.CENTER_HORIZONTAL;
        grabParams.topMargin = ui.dp(10); grabParams.bottomMargin = ui.dp(6); content.addView(grab, grabParams);
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(ui.dp(4), 0, 0, ui.dp(8));
        TextView heading = ui.text(title, 22, true); heading(heading);
        if (tag == null) row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        else {
            row.addView(heading);
            TextView chip = pill(tag, ui.blue); LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2); chipParams.leftMargin = ui.dp(10);
            chip.setContentDescription(T.ridesChangesNextRide());
            row.addView(chip, chipParams); row.addView(new View(host.activity()), new LinearLayout.LayoutParams(0, 1, 1));
        }
        FrameLayout close = new FrameLayout(host.activity());
        GradientDrawable disc = new GradientDrawable(); disc.setShape(GradientDrawable.OVAL); disc.setColor(ui.raised);
        close.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 40)), new InsetDrawable(disc, ui.dp(4)), null));
        close.addView(ui.icon("close", 16, ui.text), new FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER));
        close.setContentDescription(T.commonClose()); close.setFocusable(true); close.setClickable(true);
        row.addView(close, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        content.addView(row);
        return close;
    }
    private void switchRow(LinearLayout group, String glyph, String title, String sub, String key, boolean fallback) {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(ui.dp(60)); row.setPadding(0, ui.dp(10), 0, ui.dp(10));
        row.addView(glyphSquare(glyph, ui.accent), squareParams());
        row.addView(words(title, sub), new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Haptics.FeedbackSwitch(host.activity()); toggle.setShowText(false); toggle.setTextOn(""); toggle.setTextOff("");
        toggle.setChecked(preferences.getBoolean(key, fallback)); toggle.setMinimumWidth(ui.dp(56)); toggle.setMinimumHeight(ui.dp(48));
        ui.styleSwitch(toggle); toggle.setContentDescription(sub == null ? title : title + ", " + sub);
        if (sub == null && "phone_motion".equals(key)) toggle.setContentDescription(T.ridesSwitchExperimental(title));
        toggle.setOnCheckedChangeListener((button, on) -> { preferences.edit().putBoolean(key, on).apply(); if (on && AutoStartPolicy.PHONE_GPS_KEY.equals(key)) offerAutoRecordLocation(); });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-2, -2); toggleParams.leftMargin = ui.dp(12);
        row.addView(toggle, toggleParams);
        row.setOnClickListener(v -> toggle.performClick()); row.setFocusable(false);
        group.addView(row);
    }
    /** GPS route turned on with auto-record already on: the next automatic ride would otherwise have no route and no way to ask. */
    private void offerAutoRecordLocation() {
        if (AutoStartPolicy.asksLocationOnGpsOn(preferences.getBoolean(AutoStartPolicy.AUTO_RECORD_KEY, false), service.simulating(), service.ridingNow())) host.requestAutoRecordLocation(null);
    }
    private void infoRow(LinearLayout group, String glyph, String title, String sub, String value) {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(ui.dp(60)); row.setPadding(0, ui.dp(10), 0, ui.dp(10));
        row.addView(glyphSquare(glyph, ui.accent), squareParams());
        row.addView(words(title, sub), new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = ui.text(value, 14, false); amount.setTextColor(ui.muted);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-2, -2); valueParams.leftMargin = ui.dp(12);
        row.addView(amount, valueParams);
        row.setContentDescription(title + ", " + value + (sub == null ? "" : ", " + sub));
        if (Build.VERSION.SDK_INT >= 28) row.setScreenReaderFocusable(true);
        group.addView(row);
    }
    private LinearLayout words(String title, String sub) {
        LinearLayout words = vertical();
        words.addView(ui.text(title, 15, false));
        if (sub != null && !sub.isEmpty()) { TextView detail = ui.caption(sub); detail.setPadding(0, ui.dp(3), 0, 0); words.addView(detail); }
        return words;
    }
    private LinearLayout.LayoutParams squareParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)); params.rightMargin = ui.dp(12); return params;
    }
    private void openWeb(String url) {
        try { host.activity().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException e) { host.toast(T.ridesToastNoBrowser()); }
    }
    private void presentPhoneRide(PhoneRideStore.Track track) {
        String rideName = T.ridesPhoneSensors();
        try { RideStore.Summary summary = service.rides == null ? null : service.rides.get(track.id); if (summary != null && !summary.label.isEmpty()) rideName = summary.label; }
        catch (IOException | RuntimeException ignored) { }
        ScrollView scroller = new MapTouchScroll(); LinearLayout outer = vertical(); scroller.addView(outer);
        LinearLayout content = vertical(); content.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(16)); outer.addView(content);
        PhoneRide ride = phoneRide(track, content, content, false);
        outer.addView(ride.frame, 0, new LinearLayout.LayoutParams(-1, ui.dp(340)));
        RouteMapView map = ride.map;
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setCustomTitle(titleView(rideName, T.ridesRoute())).setView(scroller).setPositiveButton(T.commonDone(), null).create();
        show(dialog, PAGE, () -> { maps.remove(map); map.close(); });
    }
    /** The inline and expanded maps own separate tile work, but share the same recorded route. */
    private static final class PhoneRide {
        final FrameLayout frame; final RouteMapView map; final SegmentedSelector colors;
        final LegendView legend; final PhoneRideStore.Track track;
        AlertDialog expandedDialog;
        PhoneRide(FrameLayout frame, RouteMapView map, SegmentedSelector colors, LegendView legend, PhoneRideStore.Track track) {
            this.frame = frame; this.map = map; this.colors = colors; this.legend = legend; this.track = track;
        }
    }
    private PhoneRide phoneRide(PhoneRideStore.Track track, LinearLayout top, LinearLayout bottom, boolean inline) {
        PhoneRide ride = mapPanel(track, top, inline, false);
        bottom.addView(ui.sectionLabel(T.ridesPhoneRoute()));
        int dropped = track.rejectedLocationCount + track.rejectedElevationCount;
        TextView fixes = ui.caption(track.locationCount == 0 ? T.ridesGpsNoFixes() : T.ridesGpsFixes(track.locationCount, Fmt.groupedInteger(Locale.US, track.locationCount)) + (dropped > 0 ? T.ridesGpsDropped(dropped, Fmt.groupedInteger(Locale.US, dropped)) : ""));
        fixes.setContentDescription(track.statusText()); fixes.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(10)); bottom.addView(fixes);
        if (track.distanceMeters != null) tileReadings(distanceParts(track.distanceMeters / 1000));
        tileReadings(ascentParts(track.ascentMeters, track.descentMeters));
        // Grade and ascent are smoothed estimates: ≈ marks them.
        tiles(bottom, new String[]{"route", T.ridesTileGpsDistance(), distance(track.distanceMeters == null ? null : track.distanceMeters / 1000),
                "hill", T.ridesTileAscentDescent(), (track.ascentMeters == null ? "" : "≈") + height(track.ascentMeters) + " / " + height(track.descentMeters)});
        LinearLayout accelerationHead = horizontal(); accelerationHead.setGravity(Gravity.CENTER_VERTICAL);
        accelerationHead.addView(ui.sectionLabel(T.ridesAcceleration()));
        LinearLayout.LayoutParams labParams = new LinearLayout.LayoutParams(-2, -2); labParams.leftMargin = ui.dp(4); labParams.topMargin = ui.dp(8);
        accelerationHead.addView(labBadge(), labParams); bottom.addView(accelerationHead);
        LinearLayout movement = ui.groupCard();
        valueRow(movement, T.ridesGpsSpeedChange(), acceleration(track.minGpsAccelerationMps2) + " / " + acceleration(track.maxGpsAccelerationMps2));
        valueRow(movement, T.ridesScooterSpeedChange(), acceleration(track.minScooterAccelerationMps2) + " / " + acceleration(track.maxScooterAccelerationMps2));
        valueRow(movement, T.ridesLinearPeak(), acceleration(track.maxLinearAccelerationMps2));
        bottom.addView(movement);
        note(bottom, T.ridesMotionSamples(track.motionSampleCount, Fmt.groupedInteger(Locale.US, track.motionSampleCount)));
        return ride;
    }
    private static final String[] ROUTE_METRICS = {"power", "speed", "grade", "accel", "elevation"};
    /** Shared controls keep power, speed, grade, acceleration, selection and basemap attribution in both views. */
    private PhoneRide mapPanel(PhoneRideStore.Track track, LinearLayout top, boolean inline, boolean full) {
        // Readings appear only after a route hit, and leave no empty row when inspection is dismissed.
        Flow selection = new Flow(); selection.setPadding(0, ui.dp(2), 0, ui.dp(10)); selection.setVisibility(View.GONE);
        selection.setContentDescription(T.ridesRouteSelectionDescription());
        MapSource source = MapSource.load(preferences);
        RouteMapView map = new RouteMapView(ui, track, source.url, p -> pointReadout(selection, p));
        if (full) map.setMinimumHeight(0);
        maps.add(map);
        FrameLayout mapFrame = new FrameLayout(host.activity());
        if (inline) { mapFrame.setBackground(ui.shape(ui.surface, 20, 0)); mapFrame.setClipToOutline(true); }
        mapFrame.addView(map, new FrameLayout.LayoutParams(-1, -1));
        int plate = ui.dark ? ui.bg : ui.text, ink = ui.dark ? ui.text : ui.surface;
        TextView attribution = ui.text(source.displayAttribution(), 10.5f, false); attribution.setTextColor(ink); attribution.setMaxLines(3);
        attribution.setBackground(ui.shape(Ui.withAlpha(plate, 215), 7, 0)); attribution.setPadding(ui.dp(7), ui.dp(4), ui.dp(7), ui.dp(4));
        attribution.setOnClickListener(v -> openWeb(source.attributionUrl));
        FrameLayout.LayoutParams creditParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        creditParams.setMargins(ui.dp(8), 0, ui.dp(64), ui.dp(8)); mapFrame.addView(attribution, creditParams);
        attribution.setVisibility(View.GONE);
        if (!track.points.isEmpty()) top.addView(MapPreferences.basemapControl(host, enabled -> {
            map.setStreets(enabled);
            attribution.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }));
        TextView mapStatus = ui.caption(""); mapStatus.setTextColor(ui.orange);
        mapStatus.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), 0); top.addView(mapStatus);
        map.setStatusListener(message -> { mapStatus.setText(message); mapStatus.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE); });
        LegendView legend = new LegendView(); legend.show(track, "power");
        SegmentedSelector colors = new SegmentedSelector(ui, T.ridesRouteColor(), new String[]{T.ridesRouteColorPower(), T.ridesRouteColorSpeed(), T.ridesRouteColorGrade(), T.ridesRouteColorAccel(), T.ridesRouteColorElevation()}, choice -> {
            String key = ROUTE_METRICS[choice]; map.setMetric(key);
            legend.show(track, key);
        }); colors.select(0, false);
        // Five choices stay visible on cover screens and with large text: one row when it fits, otherwise wrapped.
        colors.useCompactPadding(); colors.setWrapChoices(true);
        if (!track.points.isEmpty()) {
            LinearLayout.LayoutParams colorParams = new LinearLayout.LayoutParams(-1, -2); colorParams.topMargin = ui.dp(6);
            top.addView(colors, colorParams); top.addView(legend); top.addView(selection);
        }
        PhoneRide ride = new PhoneRide(mapFrame, map, colors, legend, track);
        LinearLayout controls = vertical();
        controls.addView(mapButton("plus", T.ridesMapZoomIn(), () -> map.zoomBy(1)));
        controls.addView(mapButton("minus", T.ridesMapZoomOut(), () -> map.zoomBy(-1)));
        controls.addView(full ? mapButton("center", T.ridesMapFit(), map::fitRoute)
                : mapButton("fit", T.ridesMapExpand(), () -> expandMap(ride)));
        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        controlParams.setMargins(0, 0, ui.dp(6), ui.dp(6)); mapFrame.addView(controls, controlParams);
        if (!full) {
            FrameLayout.LayoutParams fitParams = new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.TOP | Gravity.END);
            fitParams.setMargins(0, ui.dp(6), ui.dp(6), 0);
            mapFrame.addView(mapButton("center", T.ridesMapFit(), map::fitRoute), fitParams);
        }
        mapFrame.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                if (ride.expandedDialog != null) ride.expandedDialog.dismiss();
            }
        });
        return ride;
    }
    private String mapLegend(PhoneRideStore.Track track, String metric) {
        return "grade".equals(metric) ? T.ridesLegendGrade()
                : "accel".equals(metric) ? T.ridesLegendAccel()
                : "elevation".equals(metric) ? elevationLegend(track)
                : "speed".equals(metric) ? speedLegend(RouteSpeedScale.of(track.points))
                : T.ridesLegendPower(RouteSpeedScale.wattsLabel(RouteSpeedScale.powerTopWatts(track.points)));
    }
    private void syncMapControls(PhoneRide ride) {
        String metric = ride.map.metric(); int selected = 0;
        String[] keys = ROUTE_METRICS;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(metric)) selected = i;
        ride.colors.select(selected, false); ride.legend.show(ride.track, metric);
    }
    private void expandMap(PhoneRide source) {
        if (closed || !source.frame.isAttachedToWindow() || (source.expandedDialog != null && source.expandedDialog.isShowing())) return;
        LinearLayout controls = vertical(); controls.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(8));
        ScrollView controlScroll = new ScrollView(host.activity()); controlScroll.addView(controls);
        controlScroll.setContentDescription(T.ridesMapControlsDescription());
        LinearLayout body = new MapTouchColumn() {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                // The map keeps most of the display even with large text or a landscape window.
                int available = MeasureSpec.getSize(heightSpec);
                ViewGroup.LayoutParams params = controlScroll.getLayoutParams();
                params.height = Math.min(ui.dp(180), Math.max(ui.dp(64), available / 3));
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        body.setOrientation(LinearLayout.VERTICAL); body.setBackgroundColor(ui.bg);
        LinearLayout heading = horizontal(); heading.setGravity(Gravity.CENTER_VERTICAL); heading.setPadding(ui.dp(16), ui.dp(4), ui.dp(6), ui.dp(4));
        heading.addView(ui.text(T.ridesRoute(), 20, true), new LinearLayout.LayoutParams(0, -2, 1));
        PhoneRide expanded = mapPanel(source.track, controls, false, true);
        expanded.map.copyViewStateFrom(source.map); syncMapControls(expanded);
        body.addView(heading); body.addView(expanded.frame, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(controlScroll, new LinearLayout.LayoutParams(-1, ui.dp(180)));
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).create();
        dialog.setView(body, 0, 0, 0, 0);
        source.expandedDialog = dialog;
        heading.addView(mapButton("close", T.ridesMapCloseExpanded(), dialog::dismiss));
        show(dialog, PAGE, () -> {
            source.expandedDialog = null;
            if (!closed && source.frame.isAttachedToWindow()) { source.map.copyViewStateFrom(expanded.map); syncMapControls(source); }
            maps.remove(expanded.map); expanded.map.close();
        });
    }
    private String elevationLegend(PhoneRideStore.Track track) {
        Double low = null, high = null; for (PhoneRideStore.Point point : track.points) if (point.relativeAltitudeM != null) {
            low = low == null ? point.relativeAltitudeM : Math.min(low, point.relativeAltitudeM);
            high = high == null ? point.relativeAltitudeM : Math.max(high, point.relativeAltitudeM);
        }
        return T.ridesLegendElevation(height(low), height(high));
    }
    private String speedLegend(RouteSpeedScale scale) {
        if (scale.source == null) return T.ridesLegendSpeedNone();
        return (RouteSpeedScale.SCOOTER.equals(scale.source) ? T.ridesLegendSpeedScooter(RouteSpeedScale.label(scale.topKmh, preferences.getString("units", "mi")))
                : T.ridesLegendSpeedGps(RouteSpeedScale.label(scale.topKmh, preferences.getString("units", "mi"))));
    }
    private View mapButton(String glyph, String description, Runnable action) {
        FrameLayout button = new FrameLayout(host.activity());
        int plate = ui.dark ? ui.bg : ui.text, ink = ui.dark ? ui.text : ui.surface;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 70)),
                new InsetDrawable(ui.shape(Ui.withAlpha(plate, 235), 11, ui.dark ? ui.outline : 0), ui.dp(4)), null));
        button.addView(new GlyphView(glyph, ink), new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
        button.setContentDescription(description); button.setClickable(true); button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        return button;
    }
    /** Route point readings as label-over-value cells; "—" where a reading was not measured. */
    private void pointReadout(Flow readout, PhoneRideStore.Point p) {
        readout.removeAllViews(); readout.setVisibility(p == null ? View.GONE : View.VISIBLE);
        if (p == null) return;
        String gps = p.speedMps == null ? "—" : speed(p.speedMps * 3.6);
        String[] cells = {T.ridesPointTime(), duration(p.elapsedMs), T.ridesPointGps(), gps, T.ridesPointScooter(), p.scooterSpeedKmh == null ? "—" : speed(p.scooterSpeedKmh), T.ridesPointPower(), dash(quantity(p.powerW, "W", 0)), T.ridesPointBattery(), dash(quantity(p.batteryPercent, "%", 0)),
                T.ridesPointGrade(), p.gradePercent == null ? "—" : String.format(Fmt.locale(Locale.US), "≈%+.1f%%", p.gradePercent),
                T.ridesPointGpsAccel(), accel(p.gpsAccelerationMps2), T.ridesPointScooterAccel(), accel(p.scooterAccelerationMps2)};
        for (int i = 0; i < cells.length; i += 2) {
            LinearLayout cell = vertical(); cell.setMinimumWidth(ui.dp(96)); cell.setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4));
            TextView label = ui.caption(cells[i]); label.setSingleLine(true); cell.addView(label);
            TextView value = ui.text(cells[i + 1], 15, true); value.setSingleLine(true); value.setPadding(0, ui.dp(3), 0, 0);
            if ("—".equals(cells[i + 1])) { value.setTextColor(ui.muted); value.setTypeface(ui.regular()); }
            cell.addView(value);
            String spoken = cells[i] + ", " + ("—".equals(cells[i + 1]) ? T.ridesPointNotMeasured() : cells[i + 1]);
            if (i == 2 && p.speedMps != null) spoken = T.ridesPointGpsAccuracy(cells[i], cells[i + 1], height(p.accuracyM));
            cell.setContentDescription(spoken); cell.setFocusable(true);
            readout.addView(cell);
        }
    }
    private static String accel(Double value) { return value == null ? "—" : T.fmtQuantity(Fmt.decimal(Locale.US, value, 2), T.ridesUnitAcceleration()); }
    private String acceleration(Double value) { return value == null ? "—" : T.ridesAccelerationWithG(Fmt.decimal(Locale.US, value, 2), T.ridesUnitAcceleration(), Fmt.decimal(Locale.US, value / 9.80665, 2), T.ridesUnitGravity()); }
    private String height(Double value) {
        if (value == null) return "—"; String units = preferences.getString("units", "mi");
        return units.equals("km") ? quantity(value, "m", 0) : units.equals("dual") ? quantity(value * 3.280839895, "ft", 0) + " / " + quantity(value, "m", 0) : quantity(value * 3.280839895, "ft", 0);
    }
    /** True while the Export ride data sheet reads the ride's phone recording, so a double tap opens one sheet. */
    private boolean exportLoading;
    /**
     * Export ride data: reads what the ride has off the main thread (its analysis, the phone files and, when there
     * are any, the phone recording), then opens the sheet.
     */
    private void exportSheet(String id) {
        if (exportLoading || service.rides == null) return;
        exportLoading = true;
        final RideStore rides = service.rides; final PhoneRideStore store = service.phoneRides; final boolean saving = service.phoneSaving();
        try {
            phoneLoader.execute(() -> {
                RideStore.Summary summary;
                try { summary = rides.get(id); } catch (IOException | RuntimeException unreadable) { summary = null; }
                RideExportFiles.Phone phone = null;
                if (store != null) {
                    boolean journal = store.rawCsv(id).isFile(), motion = store.motionCsv(id).isFile(), unreadable = false;
                    PhoneRideStore.Track track = null;
                    if (!saving && (journal || motion)) {
                        try { track = store.load(id); } catch (IOException | RuntimeException failure) { unreadable = true; }
                    }
                    phone = new RideExportFiles.Phone(saving, journal, motion, track, unreadable);
                }
                final RideStore.Summary ride = summary; final RideExportFiles.Phone recorded = phone;
                host.activity().runOnUiThread(() -> {
                    exportLoading = false;
                    if (closed || host.activity().isDestroyed()) return;
                    if (ride == null) { host.toast(T.ridesToastRecordingUnavailable()); return; }
                    showExportSheet(id, ride, recorded);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException screenClosed) { exportLoading = false; }
    }
    /** The sheet: a checkbox per file the ride has (greyed out with a reason when it cannot be made now), a hint and Export. */
    private void showExportSheet(String id, RideStore.Summary summary, RideExportFiles.Phone phone) {
        LinearLayout content = vertical(); content.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(20));
        View close = sheetHeader(content, T.ridesExportData(), null);
        Set<RideExportFiles.Format> remembered = RideExportFiles.parse(preferences.getString(RideExportFiles.CHOICE, null));
        Set<RideExportFiles.Format> initial = RideExportFiles.initial(remembered, phone);
        Map<RideExportFiles.Format, CheckBox> boxes = new EnumMap<>(RideExportFiles.Format.class);
        // Made once: what the sheet shows is what gets saved, and every format of this ride shares it.
        String stem = RideExportFiles.stem(id, summary);
        LinearLayout group = null; int section = -1;
        for (RideExportFiles.Format format : RideExportFiles.listed(phone)) {
            if (group == null || format.section() != section) {
                if (group != null) content.addView(group);
                // Everything has a card of its own at the top, with no heading; the scooter's and the phone's files follow under theirs.
                if (format.section() > 0) content.addView(ui.sectionLabel(format.phone ? T.ridesExportSectionPhone() : T.ridesExportSectionScooter()));
                group = ui.groupCard(); section = format.section();
            }
            RideExportFiles.Unavailable why = RideExportFiles.why(format, phone);
            boolean offered = why == RideExportFiles.Unavailable.NONE;
            boxes.put(format, exportRow(group, exportLabel(format), offered ? RideExportFiles.shownName(format, stem) : exportReason(why), offered, initial.contains(format)));
        }
        if (group != null) content.addView(group);
        TextView hint = ui.caption(T.ridesExportHintNone()); hint.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(4)); content.addView(hint);
        AlertDialog[] holder = new AlertDialog[1];
        TextView action = ui.button(T.ridesExportAction(), () -> {
            Set<RideExportFiles.Format> chosen = EnumSet.noneOf(RideExportFiles.Format.class);
            for (Map.Entry<RideExportFiles.Format, CheckBox> e : boxes.entrySet()) if (e.getValue().isEnabled() && e.getValue().isChecked()) chosen.add(e.getKey());
            if (chosen.isEmpty()) return;
            preferences.edit().putString(RideExportFiles.CHOICE, RideExportFiles.format(RideExportFiles.remember(remembered, phone, chosen))).apply();
            if (holder[0] != null) holder[0].dismiss();
            if (chosen.size() == 1) saveSingle(chosen.iterator().next(), id, stem, summary, phone == null ? null : phone.track);
            else saveSeveral(chosen, id, stem, summary, phone == null ? null : phone.track);
        });
        content.addView(action);
        Runnable sync = () -> {
            int count = 0;
            for (CheckBox box : boxes.values()) if (box.isEnabled() && box.isChecked()) count++;
            action.setEnabled(count > 0);
            hint.setText(count == 0 ? T.ridesExportHintNone() : count == 1 ? T.ridesExportHintOne() : T.ridesExportHintSeveral());
        };
        for (CheckBox box : boxes.values()) box.setOnCheckedChangeListener((v, on) -> sync.run());
        sync.run();
        ScrollView scroller = new ScrollView(host.activity()); scroller.addView(content); insetBottom(scroller);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(scroller).create();
        holder[0] = dialog;
        close.setOnClickListener(v -> dialog.dismiss());
        show(dialog, SHEET, null);
    }
    /** One 48 dp checkbox with the file's name (or why it is greyed out) under it. The name is the file's own, never translated. */
    private CheckBox exportRow(LinearLayout group, String label, String detail, boolean enabled, boolean checked) {
        if (group.getChildCount() > 0) ui.divider(group);
        ColorStateList words = new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{ui.muted, ui.text});
        CheckBox box = new CheckBox(host.activity()); box.setText(label); box.setTextColor(words); box.setTypeface(ui.regular());
        box.setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.sp(15)); box.setMinHeight(ui.dp(48));
        box.setButtonTintList(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{ui.dim, ui.accent}));
        box.setEnabled(enabled); box.setChecked(enabled && checked);
        group.addView(box, new LinearLayout.LayoutParams(-1, -2));
        TextView under = ui.caption(detail); under.setTextColor(ui.muted);
        // Lines up with the checkbox's label: the box's own button is about 32 dp wide.
        under.setPadding(ui.dp(32), 0, 0, ui.dp(8)); group.addView(under);
        if (!enabled) box.setContentDescription(T.ridesExportUnavailableDescription(label, detail));
        return box;
    }
    private static String exportLabel(RideExportFiles.Format format) {
        switch (format) {
            case EVERYTHING_JSON: return T.ridesExportEverythingJson();
            case SENSOR_CSV: return T.ridesExportSensorCsv();
            case ANALYSIS_JSON: return T.ridesExportAnalysisJson();
            case ROUTE_CSV: return T.ridesExportRouteCsv();
            case GPX: return T.ridesExportGpx();
            case PHONE_RAW: return T.ridesExportPhoneRaw();
            case MOTION_RAW: return T.ridesExportMotionRaw();
            default: return T.ridesExportCombinedJson();
        }
    }
    private static String exportReason(RideExportFiles.Unavailable why) {
        switch (why) {
            case SAVING: return T.ridesExportReasonSaving();
            case NO_FIXES: return T.ridesExportReasonNoFixes();
            case UNREADABLE: return T.ridesExportReasonUnreadable();
            default: return T.ridesExportReasonNotRecorded();
        }
    }
    /** One file: Android's Save dialog, where the rider can rename it, exactly as each old export button did. */
    private void saveSingle(RideExportFiles.Format format, String id, String stem, RideStore.Summary summary, PhoneRideStore.Track track) {
        if (format == RideExportFiles.Format.EVERYTHING_JSON) saveLater(format, id, stem, summary, track);
        else saveSingle(format, stem, () -> RideExportFiles.payload(format, id, service.rides, summary, service.phoneRides, track, host.activity().getCacheDir(), AppVersion.name(host.activity())));
    }
    /** One export's content, made now or handed over from a background thread. */
    private interface Made { RideExportFiles.Payload get() throws IOException; }
    private void saveSingle(RideExportFiles.Format format, String stem, Made made) {
        try { saveOne(format, stem, made.get()); }
        catch (RideExportFiles.Missing missing) { host.toast(T.ridesToastNoPhoneData()); }
        catch (IOException failure) { host.toast(format == RideExportFiles.Format.SENSOR_CSV ? T.ridesToastExportCsvFailed() : T.ridesToastExportFailed()); }
    }
    /**
     * Several files: each is written into a staging folder in the cache off the main thread, then
     * RideExportFolderActivity asks for one folder with Android's folder picker and copies them there.
     */
    private void saveSeveral(Set<RideExportFiles.Format> chosen, String id, String stem, RideStore.Summary summary, PhoneRideStore.Track track) {
        final RideStore rides = service.rides; final PhoneRideStore store = service.phoneRides;
        final File cache = host.activity().getCacheDir(); final String version = AppVersion.name(host.activity());
        final List<RideExportFiles.Format> formats = new ArrayList<>(chosen);
        try {
            phoneLoader.execute(() -> {
                File staging = null; boolean missing = false;
                try {
                    staging = RideExportFiles.newStaging(cache, System.currentTimeMillis());
                    for (RideExportFiles.Format format : formats) {
                        RideExportFiles.Payload payload = RideExportFiles.payload(format, id, rides, summary, store, track, cache, version);
                        try { RideExportFiles.stage(staging, format, stem, payload); } finally { payload.discard(); }
                    }
                } catch (IOException | RuntimeException failure) {
                    missing = failure instanceof RideExportFiles.Missing;
                    RideExportFiles.remove(staging); staging = null;
                }
                final File ready = staging; final boolean noPhoneData = missing;
                host.activity().runOnUiThread(() -> {
                    if (ready == null) { host.toast(noPhoneData ? T.ridesToastNoPhoneData() : T.ridesToastExportFailed()); return; }
                    if (host.activity().isDestroyed()) { RideExportFiles.remove(ready); return; }
                    host.activity().startActivity(RideExportFolderActivity.intent(host.activity(), ready, stem, formats));
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException screenClosed) { }
    }
    /**
     * One file that takes a moment to make (Everything on a long ride): written into the cache off the main thread,
     * then handed to Android's Save dialog like any other file.
     */
    private void saveLater(RideExportFiles.Format format, String id, String stem, RideStore.Summary summary, PhoneRideStore.Track track) {
        final RideStore rides = service.rides; final PhoneRideStore store = service.phoneRides;
        final File cache = host.activity().getCacheDir(); final String version = AppVersion.name(host.activity());
        try {
            phoneLoader.execute(() -> {
                RideExportFiles.Payload made = null; IOException failed = null;
                try { made = RideExportFiles.payload(format, id, rides, summary, store, track, cache, version); }
                catch (IOException failure) { failed = failure; }
                catch (RuntimeException failure) { failed = new IOException(failure); }
                final RideExportFiles.Payload ready = made; final IOException failure = failed;
                host.activity().runOnUiThread(() -> {
                    if (host.activity().isDestroyed()) { if (ready != null) ready.discard(); return; }
                    saveSingle(format, stem, () -> { if (failure != null) throw failure; return ready; });
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException screenClosed) { }
    }
    /**
     * Hands one export to Android's Save dialog: text through saveText, a file through saveFile, as each button always
     * did. A file the payload owns (Everything) is discarded once the host has copied it, or could not.
     */
    private void saveOne(RideExportFiles.Format format, String stem, RideExportFiles.Payload payload) {
        String name = RideExportFiles.fileName(format, stem);
        if (payload.text != null) host.saveText(name, format.mime, payload.text);
        else if (payload.owned != null) host.saveFile(name, format.mime, payload.file, payload::discard);
        else host.saveFile(name, format.mime, payload.file);
    }
    /**
     * The narrow-label ladder on a one-line button with a start icon: the full label while it fits, then the short
     * one, then the icon alone. Text is never shrunk or broken; screen readers always hear the full label.
     */
    private void ladder(TextView button, String full, String brief) {
        button.setMaxLines(1); button.setContentDescription(full);
        button.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            Drawable icon = button.getCompoundDrawablesRelative()[0];
            float room = (right - left) - button.getPaddingLeft() - button.getPaddingRight()
                    - (icon == null ? 0 : icon.getBounds().width() + button.getCompoundDrawablePadding());
            int rung = TileRung.first(new float[]{button.getPaint().measureText(full), button.getPaint().measureText(brief)}, room);
            // null: the icon alone (setText(null) leaves the button empty).
            CharSequence pick = rung == 0 ? full : rung == 1 ? brief : null;
            boolean change = pick == null ? button.getText().length() > 0 : TileRung.needsSet(pick, button.getText());
            if (change) button.post(() -> button.setText(pick));
        });
    }

    private void confirmDelete(String id, String label, Runnable deleted) {
        // Delete is disabled while sensor files are saving; these guards only cover a race.
        if (service.phoneSaving()) return;
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setCustomTitle(titleView(null, (label.isEmpty() ? T.ridesDeleteTitleUnnamed() : T.ridesDeleteTitle(label))))
                .setMessage(T.ridesDeleteMessage())
                .setNegativeButton(T.ridesDeleteKeep(), null).setPositiveButton(T.ridesDeleteAction(), (d, which) -> {
                    if (service.phoneSaving()) { host.toast(T.ridesToastSavingTryAgain()); return; }
                    try { if (service.phoneRides != null) service.phoneRides.delete(id); service.rides.delete(id); service.invalidateCapacityEstimate(); selected.remove(id); saveSelection(); historySignature = ""; refresh(); host.toast(T.ridesToastDeleted()); if (deleted != null) deleted.run(); }
                    catch (IOException | IllegalStateException e) { host.toast(T.ridesToastDeleteFailed()); }
                }).create();
        show(dialog, DIALOG, null);
        TextView delete = dialog.getButton(AlertDialog.BUTTON_POSITIVE); if (delete != null) delete.setTextColor(ui.danger);
    }
    private void showComparison() {
        if (selected.size() != 2 || service.rides == null) return;
        try {
            List<RideStore.Summary> chosen = new ArrayList<RideStore.Summary>();
            for (String id : selected) { RideStore.Summary summary = service.rides.get(id); if (summary != null) chosen.add(summary); }
            if (chosen.size() != 2) { host.toast(T.ridesToastSelectTwo()); return; }
            RideStore.Summary a = chosen.get(0), b = chosen.get(1);
            ScrollView scroller = new ScrollView(host.activity()); LinearLayout content = vertical(); content.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(20)); scroller.addView(content);
            LinearLayout names = horizontal(); names.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(8));
            names.addView(new View(host.activity()), new LinearLayout.LayoutParams(0, 1, 1.2f));
            names.addView(compareName(a), new LinearLayout.LayoutParams(0, -2, 1)); names.addView(compareName(b), new LinearLayout.LayoutParams(0, -2, 1));
            content.addView(names);
            LinearLayout group = ui.groupCard(); content.addView(group);
            compareLine(group, T.ridesTileDistance(), distance(a.distanceKm), distance(b.distanceKm));
            compareLine(group, T.ridesTileDuration(), duration(a.shownElapsedMs), duration(b.shownElapsedMs));
            compareLine(group, T.ridesTileAvgSpeed(), speed(a.averageSpeedKmh), speed(b.averageSpeedKmh));
            compareLine(group, T.ridesCompareBatteryChange(), change(a.batteryDropPercent), change(b.batteryDropPercent));
            compareLine(group, T.ridesCompareMeasuredThroughput(), quantity(a.measuredThroughputWh, "Wh", 2), quantity(b.measuredThroughputWh, "Wh", 2));
            compareLine(group, T.ridesNetEnergy(), quantity(a.netConsumptionWh, "Wh", 2), quantity(b.netConsumptionWh, "Wh", 2));
            compareLine(group, T.ridesCompareEnergyPerDistance(), efficiency(a), efficiency(b), efficiencyReason(a), efficiencyReason(b));
            compareLine(group, T.ridesPowerCoverage(), percent(a.electricalCoverage), percent(b.electricalCoverage));
            compareLine(group, T.ridesCompareGaps(), Long.toString(a.shownGapCount), Long.toString(b.shownGapCount));
            compareLine(group, T.ridesCompareMinVoltage(), quantity(a.minimumVoltageV, "V", 2), quantity(b.minimumVoltageV, "V", 2));
            compareLine(group, T.ridesStationaryThroughput(), quantity(a.stationaryThroughputWh, "Wh", 2), quantity(b.stationaryThroughputWh, "Wh", 2));
            if (service.phoneRides != null) {
                TextView first = ui.secondaryButton(T.ridesViewFirst(), () -> showDetails(a.id)), second = ui.secondaryButton(T.ridesViewSecond(), () -> showDetails(b.id));
                first.setContentDescription(T.ridesViewRouteOf(nameOf(a))); second.setContentDescription(T.ridesViewRouteOf(nameOf(b)));
                content.addView(pair(first, second));
            }
            if (service.phoneRides != null && !service.phoneSaving()) phoneLoader.execute(() -> {
                try {
                    PhoneRideStore.Track pa = service.phoneRides.load(a.id), pb = service.phoneRides.load(b.id);
                    host.activity().runOnUiThread(() -> { if (closed) return;
                        if (group.getParent() instanceof ViewGroup) Motion.animateChanges((ViewGroup) group.getParent());
                        compareLine(group, T.ridesTileGpsDistance(), distance(pa.distanceMeters == null ? null : pa.distanceMeters / 1000), distance(pb.distanceMeters == null ? null : pb.distanceMeters / 1000));
                        compareLine(group, T.ridesCompareAscent(), approx(pa.ascentMeters), approx(pb.ascentMeters));
                        compareLine(group, T.ridesCompareDescent(), approx(pa.descentMeters), approx(pb.descentMeters));
                    });
                } catch (IOException ignored) { }
            });
            show(new AlertDialog.Builder(host.activity()).setCustomTitle(titleView(T.ridesCompareSheetRides(), T.ridesCompareSheetTitle())).setView(scroller).setPositiveButton(T.commonDone(), null).create(), PAGE, null);
        } catch (IOException e) { host.toast(T.ridesToastCompareFailed()); }
    }
    private static String nameOf(RideStore.Summary s) { return s.label.isEmpty() ? T.ridesDefaultName() : s.label; }
    private View compareName(RideStore.Summary s) {
        LinearLayout column = vertical(); column.setGravity(Gravity.END);
        TextView name = ui.text(nameOf(s), 14, true); name.setGravity(Gravity.END); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        TextView date = ui.caption(new SimpleDateFormat(datePattern(RIDE_DATE), Fmt.locale(Locale.getDefault())).format(new Date(s.startWallMs))); date.setGravity(Gravity.END);
        column.addView(name); column.addView(date); column.setPadding(ui.dp(6), 0, 0, 0);
        return column;
    }
    private static final String OF_TWO = "/2";
    private String approx(Double meters) { return meters == null ? "—" : "≈" + height(meters); }
    private void compareLine(LinearLayout group, String title, String first, String second) { compareLine(group, title, measured(first), measured(second), null, null); }
    /** A value at the edge where it is shown: null means not measured. */
    private static String measured(String value) { return value == null ? T.ridesNotMeasured() : value; }
    /** A compare row; a "—" with a reason explains itself on tap. */
    private void compareLine(LinearLayout group, String title, String first, String second, String firstReason, String secondReason) {
        if (group.getChildCount() > 0) ui.divider(group);
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(ui.dp(44)); row.setPadding(0, ui.dp(10), 0, ui.dp(10));
        TextView label = ui.text(title, 13.5f, false);
        TextView a = ui.text(first, 13.5f, true); a.setGravity(Gravity.END); a.setPadding(ui.dp(6), 0, 0, 0);
        TextView b = ui.text(second, 13.5f, true); b.setGravity(Gravity.END); b.setTextColor(ui.muted); b.setPadding(ui.dp(6), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1.2f)); row.addView(a, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        if ("—".equals(first)) a.setTextColor(ui.muted);
        if ("—".equals(second)) b.setTextColor(ui.muted);
        String spokenFirst = firstReason == null ? first : first + " (" + firstReason + ")", spokenSecond = secondReason == null ? second : second + " (" + secondReason + ")";
        row.setContentDescription(T.ridesCompareLineDescription(title, spokenFirst, spokenSecond));
        if (Build.VERSION.SDK_INT >= 28) row.setScreenReaderFocusable(true);
        if (firstReason != null || secondReason != null) {
            String reason = firstReason != null && firstReason.equals(secondReason) ? firstReason
                    : firstReason == null ? T.ridesCompareReasonSecond(secondReason) : secondReason == null ? T.ridesCompareReasonFirst(firstReason)
                    : T.ridesCompareReasonBoth(firstReason, secondReason);
            row.setOnClickListener(v -> host.toast(reason)); row.setClickable(true);
        }
        group.addView(row);
    }
    /** A divided label / value row inside a group card; the value is bold and right-aligned. */
    private LinearLayout shownValueRow(LinearLayout group, String title, String value) {
        if (group.getChildCount() > 0 && !(group.getChildAt(group.getChildCount() - 1) instanceof TextView)) ui.divider(group);
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(ui.dp(48)); row.setPadding(0, ui.dp(11), 0, ui.dp(11));
        TextView label = ui.text(title, 14, false);
        TextView amount = ui.text(value, 14, true); amount.setGravity(Gravity.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(0, -2, 1); valueParams.leftMargin = ui.dp(10);
        row.addView(amount, valueParams);
        row.setContentDescription(title + ", " + value);
        if (Build.VERSION.SDK_INT >= 28) row.setScreenReaderFocusable(true);
        group.addView(row);
        return row;
    }
    /**
     * Two-column grid of small stat tiles from icon / label / value triples. Each call site names its
     * tiles' icons: a label is display text in the shown language, so it never picks one.
     */
    private final Map<String, Formatted> tileParts = new HashMap<String, Formatted>();
    private String historySignature = "", chartKey;
    /** What the saved rides take on disk; shown in the current language when the count is drawn. */
    private long storedBytes;
    private List<View> tiles(LinearLayout parent, String[] cells) {
        List<View> made = new ArrayList<View>();
        LinearLayout grid = vertical(); grid.setPadding(0, 0, 0, ui.dp(4));
        for (int i = 0; i < cells.length; i += 6) {
            LinearLayout row = horizontal(); row.setBaselineAligned(false);
            for (int j = 0; j < 6 && i + j < cells.length; j += 3) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
                if (j == 0) params.rightMargin = ui.dp(4); else params.leftMargin = ui.dp(4);
                View tile = tile(cells[i + j], cells[i + j + 1], cells[i + j + 2] == null ? T.ridesNotMeasured() : cells[i + j + 2]); made.add(tile); row.addView(tile, params);
            }
            if (row.getChildCount() == 1) row.addView(new View(host.activity()), new LinearLayout.LayoutParams(0, 1, 1));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2); rowParams.bottomMargin = ui.dp(8);
            grid.addView(row, rowParams);
        }
        parent.addView(grid);
        tileParts.clear();
        return made;
    }
    private LinearLayout pair(TextView first, TextView second) {
        LinearLayout row = horizontal();
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1); a.rightMargin = ui.dp(5);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, -2, 1); b.leftMargin = ui.dp(5);
        for (TextView button : new TextView[]{first, second}) {
            // Half-width buttons keep one line: tighter side padding, ellipsis only at very large text sizes.
            button.setMaxLines(1); button.setEllipsize(TextUtils.TruncateAt.END);
            button.setPadding(ui.dp(10), button.getPaddingTop(), ui.dp(10), button.getPaddingBottom());
        }
        row.addView(first, a); row.addView(second, b);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = ui.dp(10); params.bottomMargin = ui.dp(6);
        row.setLayoutParams(params);
        return row;
    }
    /** Dialog title: an optional muted line above a bold Saira title. */
    private View titleView(String overline, String title) {
        LinearLayout box = vertical(); box.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(8));
        if (overline != null && !overline.isEmpty()) {
            TextView over = ui.text(overline, 13, false); over.setTextColor(ui.muted); over.setMaxLines(1); over.setEllipsize(TextUtils.TruncateAt.END);
            over.setPadding(0, 0, 0, ui.dp(4)); box.addView(over);
        }
        TextView heading = ui.text(title, overline == null ? 20 : 24, true); heading.setMaxLines(2); heading.setEllipsize(TextUtils.TruncateAt.END);
        heading(heading); box.addView(heading);
        return box;
    }
    private static void heading(TextView view) { if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true); }

    private final class Row {
        final LinearLayout view; final TextView title, sub, value; final GlyphView glyph; boolean chevron;
        Row(LinearLayout view, TextView title, TextView sub, TextView value, GlyphView glyph) { this.view = view; this.title = title; this.sub = sub; this.value = value; this.glyph = glyph; }
        void update(String subText, String valueText) {
            if (subText != null) { sub.setText(subText); sub.setVisibility(subText.isEmpty() ? View.GONE : View.VISIBLE); }
            if (valueText != null) { value.setText(valueText); value.setVisibility(valueText.isEmpty() ? View.GONE : View.VISIBLE); }
            String spoken = title.getText().toString();
            if (sub.getVisibility() == View.VISIBLE) spoken += ", " + sub.getText();
            if (value.getVisibility() == View.VISIBLE) spoken += ", " + value.getText();
            view.setContentDescription(spoken);
        }
        void setEnabled(boolean enabled) {
            view.setEnabled(enabled); title.setTextColor(enabled ? ui.text : ui.muted); glyph.setColor(enabled ? ui.accent : ui.dim);
        }
        void addChevron() {
            if (chevron) return; chevron = true;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)); params.leftMargin = ui.dp(8);
            view.addView(ui.icon("chevron", 15, ui.muted), params);
        }
    }
    /** Navigation row: icon square, title with muted sub-text, an optional short value and a chevron. */
    private Row navRow(String glyphKey, CharSequence title, String sub, String value, Runnable action) {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(ui.dp(56)); row.setPadding(0, ui.dp(10), 0, ui.dp(10));
        FrameLayout square = glyphSquare(glyphKey, ui.accent); row.addView(square, squareParams());
        LinearLayout words = vertical();
        TextView heading = ui.text(title, 15, false); words.addView(heading);
        TextView detail = ui.caption(""); detail.setPadding(0, ui.dp(3), 0, 0); words.addView(detail);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = ui.text("", 14, false); amount.setTextColor(ui.muted); amount.setGravity(Gravity.END); amount.setMaxLines(2); amount.setMaxWidth(ui.dp(140));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-2, -2); valueParams.leftMargin = ui.dp(10);
        row.addView(amount, valueParams);
        Row result = new Row(row, heading, detail, amount, (GlyphView) square.getChildAt(0));
        result.update(sub == null ? "" : sub, value == null ? "" : value);
        if (action != null) { rowTouch(row, action); result.addChevron(); }
        return result;
    }
    private FrameLayout glyphSquare(String key, int color) {
        FrameLayout box = new FrameLayout(host.activity());
        box.setBackground(ui.shape(ui.dark ? ui.bg : ui.raised, 10, 0));
        box.addView(new GlyphView(key, color), new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return box;
    }
    /** Adds an icon that sits right beside the centred label, in the label's own colour. */
    private void iconize(TextView button, String key) {
        Drawable icon = new CenteredIcon(button, key); int size = ui.dp(17); icon.setBounds(0, 0, size, size);
        button.setCompoundDrawablesRelative(icon, null, null, null); button.setCompoundDrawablePadding(ui.dp(8));
    }

    /** Local line icons for this tab; anything else falls back to the shared Ui set. */
    private void glyph(Canvas canvas, String key, int color, float cx, float cy, float size) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.save(); canvas.translate(cx - size / 2, cy - size / 2); canvas.scale(size / 24f, size / 24f);
        Path p = new Path(); boolean drawn = true;
        switch (key) {
            case "record": canvas.drawCircle(12, 12, 8.5f, paint); paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 12, 4.5f, paint); break;
            case "stop": paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(new RectF(5.5f, 5.5f, 18.5f, 18.5f), 2.5f, 2.5f, paint); break;
            case "note":
                p.moveTo(6, 3); p.lineTo(14, 3); p.lineTo(19, 8); p.lineTo(19, 21); p.lineTo(6, 21); p.close();
                p.moveTo(14, 3); p.lineTo(14, 8); p.lineTo(19, 8); p.moveTo(9, 12.5f); p.lineTo(16, 12.5f); p.moveTo(9, 16.5f); p.lineTo(14, 16.5f);
                canvas.drawPath(p, paint); break;
            case "compare":
                p.moveTo(8, 20); p.lineTo(8, 4); p.moveTo(4, 8); p.lineTo(8, 4); p.lineTo(12, 8);
                p.moveTo(16, 4); p.lineTo(16, 20); p.moveTo(12, 16); p.lineTo(16, 20); p.lineTo(20, 16); canvas.drawPath(p, paint); break;
            case "pin":
                p.moveTo(12, 21.5f); p.cubicTo(8, 17, 5, 13.5f, 5, 9.5f); p.arcTo(new RectF(5, 2.5f, 19, 16.5f), 180, 180); p.cubicTo(19, 13.5f, 16, 17, 12, 21.5f); p.close();
                canvas.drawPath(p, paint); canvas.drawCircle(12, 9.5f, 2.6f, paint); break;
            case "layers":
                p.moveTo(12, 3); p.lineTo(21, 8); p.lineTo(12, 13); p.lineTo(3, 8); p.close();
                p.moveTo(3, 12.5f); p.lineTo(12, 17.5f); p.lineTo(21, 12.5f); p.moveTo(3, 16.5f); p.lineTo(12, 21.5f); p.lineTo(21, 16.5f);
                canvas.drawPath(p, paint); break;
            case "motion":
                canvas.drawRoundRect(new RectF(8, 3, 16, 21), 2, 2, paint); canvas.drawLine(4, 8, 4, 16, paint); canvas.drawLine(20, 8, 20, 16, paint); break;
            case "map":
                p.moveTo(3, 6); p.lineTo(9, 4); p.lineTo(15, 6); p.lineTo(21, 4); p.lineTo(21, 18); p.lineTo(15, 20); p.lineTo(9, 18); p.lineTo(3, 20); p.close();
                p.moveTo(9, 4); p.lineTo(9, 18); p.moveTo(15, 6); p.lineTo(15, 20); canvas.drawPath(p, paint); break;
            case "fit":
                p.moveTo(4, 9); p.lineTo(4, 4); p.lineTo(9, 4); p.moveTo(15, 4); p.lineTo(20, 4); p.lineTo(20, 9);
                p.moveTo(20, 15); p.lineTo(20, 20); p.lineTo(15, 20); p.moveTo(9, 20); p.lineTo(4, 20); p.lineTo(4, 15); canvas.drawPath(p, paint); break;
            case "center":
                canvas.drawCircle(12, 12, 6, paint); canvas.drawCircle(12, 12, 2, paint);
                canvas.drawLine(12, 2, 12, 6, paint); canvas.drawLine(12, 18, 12, 22, paint);
                canvas.drawLine(2, 12, 6, 12, paint); canvas.drawLine(18, 12, 22, 12, paint); break;
            case "minus": canvas.drawLine(5, 12, 19, 12, paint); break;
            case "plus": canvas.drawLine(12, 5, 12, 19, paint); canvas.drawLine(5, 12, 19, 12, paint); break;
            case "wave":
                p.moveTo(2, 12); p.lineTo(6, 12); p.lineTo(9, 6); p.lineTo(13, 18); p.lineTo(16, 10); p.lineTo(18, 12); p.lineTo(22, 12); canvas.drawPath(p, paint); break;
            default: drawn = false;
        }
        canvas.restore();
        if (!drawn) ui.drawIcon(canvas, key, color, cx, cy, size);
    }
    private final class GlyphView extends View {
        private final String key; private int color;
        GlyphView(String key, int color) { super(host.activity()); this.key = key; this.color = color; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void setColor(int value) { color = value; invalidate(); }
        @Override protected void onDraw(Canvas canvas) { glyph(canvas, key, color, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight())); }
    }
    /** A start drawable that shifts itself next to centred text and follows the text colour. */
    private final class CenteredIcon extends Drawable {
        private final TextView owner; private final String key;
        CenteredIcon(TextView owner, String key) { this.owner = owner; this.key = key; }
        @Override public void draw(Canvas canvas) {
            Layout layout = owner.getLayout(); float textWidth = 0;
            if (layout != null) for (int i = 0; i < layout.getLineCount(); i++) textWidth = Math.max(textWidth, layout.getLineWidth(i));
            // Centred text leaves equal spare space on both sides of its area; the icon moves across
            // that space so it sits beside the text (rightwards in LTR, leftwards in RTL).
            int areaLeft = owner.getCompoundPaddingLeft(), areaRight = owner.getWidth() - owner.getCompoundPaddingRight();
            float spare = Math.max(0, (areaRight - areaLeft - textWidth) / 2f);
            float shift = owner.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -spare : spare;
            canvas.save(); canvas.translate(shift, 0);
            glyph(canvas, key, owner.getCurrentTextColor(), getBounds().exactCenterX(), getBounds().exactCenterY(), getBounds().width());
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
    }
    /** Red recording dot with a soft halo. */
    private final class RecordingDot extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas) {
            float cx = getBounds().exactCenterX(), cy = getBounds().exactCenterY(), r = getBounds().width() / 2f;
            paint.setColor(Ui.withAlpha(ui.accent, 80)); canvas.drawCircle(cx, cy, r, paint);
            paint.setColor(ui.accent); canvas.drawCircle(cx, cy, r * .6f, paint);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    private final class RadioDot extends View {
        private final boolean on; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RadioDot(boolean on) { super(host.activity()); this.on = on; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float r = Math.min(getWidth(), getHeight()) / 2f, stroke = on ? ui.dp(5.5f) : ui.dp(2);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setColor(on ? ui.accent : ui.outline);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r - stroke / 2, paint);
        }
    }
    /** Route colour legend: a drawn gradient bar with end labels and a dashed "missing" swatch. */
    private final class LegendView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int[] colors = {0, 0};;
        LegendView() { super(host.activity()); setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)); setFocusable(true); }
        void show(PhoneRideStore.Track track, String metric) {
            int blue = Color.rgb(0, 114, 178), gray = Color.rgb(226, 226, 226), orange = Color.rgb(213, 94, 0);
            int low = RouteSpeedScale.LOW, high = RouteSpeedScale.HIGH; shortTitle = null;
            if ("speed".equals(metric)) {
                RouteSpeedScale scale = RouteSpeedScale.of(track.points);
                title = scale.title(); shortTitle = M.ridesLegendTitleSpeed();
                colors = scale.source == null ? new int[]{RouteSpeedScale.NO_DATA, RouteSpeedScale.NO_DATA} : new int[]{low, high};
                labels = scale.source == null ? new String[]{"", ""} : new String[]{"0", RouteSpeedScale.label(scale.topKmh, preferences.getString("units", "mi"))};
            } else if ("grade".equals(metric)) { title = M.ridesLegendTitleGrade(); colors = new int[]{blue, gray, orange}; labels = new String[]{"−12%", "0", "+12%"}; }
            else if ("accel".equals(metric)) { title = M.ridesLegendTitleAccel(); colors = new int[]{blue, gray, orange}; labels = new String[]{"−2", "0", "+2 m/s²"}; }
            else if ("elevation".equals(metric)) {
                Double bottom = null, top = null; for (PhoneRideStore.Point point : track.points) if (point.relativeAltitudeM != null) {
                    bottom = bottom == null ? point.relativeAltitudeM : Math.min(bottom, point.relativeAltitudeM);
                    top = top == null ? point.relativeAltitudeM : Math.max(top, point.relativeAltitudeM);
                }
                title = M.ridesLegendTitleElev(); colors = new int[]{low, high}; labels = new String[]{height(bottom), height(top)};
            } else { title = M.ridesLegendTitlePower(); colors = new int[]{low, high}; labels = new String[]{"0", RouteSpeedScale.wattsLabel(RouteSpeedScale.powerTopWatts(track.points))}; }
            setContentDescription(mapLegend(track, metric)); requestLayout(); invalidate();
        }
        private float textSize() { return ui.sp(11.5f); }
        private final class LegendLayout {
            String shownTitle;
            boolean stacked, separateLabels;
            float left, right, barLeft, barRight, headerCenter, barCenter, baseline, labelBaseline, line, height;
        }
        /** The drawn titles: Msgs (or the scale's title), rendered when laid out, so they stay in the shown language. */
        private CharSequence title = "", shortTitle;
        private String[] labels = noLabels();
        private String[] noLabels() { return new String[]{"", ""}; }
        private LegendLayout layoutFor(int width) {
            String title = this.title.toString(), shortTitle = this.shortTitle == null ? null : this.shortTitle.toString();
            paint.setTextSize(textSize()); paint.setTypeface(ui.regular());
            Paint.FontMetrics metrics = paint.getFontMetrics();
            LegendLayout layout = new LegendLayout();
            layout.line = metrics.descent - metrics.ascent;
            layout.left = getPaddingLeft(); layout.right = width - getPaddingRight();
            float gap = ui.dp(10), row = Math.max(layout.line, ui.dp(8));
            float first = paint.measureText(labels[0]), last = paint.measureText(labels[labels.length - 1]);
            // Reserve the actual endpoint text, including both units and the user's text size.
            float minimumBar = first + last + gap;
            if (labels.length == 3) {
                float middle = paint.measureText(labels[1]);
                minimumBar = Math.max(minimumBar, 2 * (Math.max(first, last) + middle / 2 + gap));
            }
            layout.barRight = layout.right - paint.measureText(T.ridesLegendMissing()) - ui.dp(6 + 18 + 14);
            layout.shownTitle = title;
            if (shortTitle != null && layout.barRight - layout.left - paint.measureText(title) - gap < minimumBar)
                layout.shownTitle = shortTitle;
            layout.barLeft = layout.left + Math.max(paint.measureText(T.ridesLegendTitlePower()), paint.measureText(layout.shownTitle)) + gap;
            layout.stacked = layout.barRight - layout.barLeft < minimumBar;
            layout.headerCenter = getPaddingTop() + row / 2;
            layout.barCenter = layout.headerCenter;
            if (layout.stacked) {
                // On narrow screens the gradient uses its own full-width row. Keep the source
                // title whenever it fits beside the missing-data key.
                float titleSpace = layout.barRight - layout.left;
                if (paint.measureText(title) <= titleSpace) layout.shownTitle = title;
                layout.barLeft = layout.left; layout.barRight = layout.right;
                layout.barCenter = getPaddingTop() + row + ui.dp(8 + 4);
            }
            layout.baseline = layout.headerCenter - (metrics.ascent + metrics.descent) / 2;
            layout.labelBaseline = layout.barCenter + (layout.stacked ? ui.dp(4) : row / 2) + ui.dp(3) - metrics.ascent;
            // Unusually narrow containers can still keep each endpoint readable on its own line.
            layout.separateLabels = layout.barRight - layout.barLeft < minimumBar;
            layout.height = layout.labelBaseline + metrics.descent + getPaddingBottom()
                    + (layout.separateLabels ? (labels.length - 1) * (layout.line + ui.dp(3)) : 0);
            return layout;
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            setMeasuredDimension(width, resolveSize((int)Math.ceil(layoutFor(width).height), heightSpec));
        }
        @Override protected void onDraw(Canvas canvas) {
            LegendLayout layout = layoutFor(getWidth());
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            paint.setShader(null); paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL); paint.setColor(ui.muted); paint.setTextAlign(align(rtl, Paint.Align.LEFT));
            String missing = T.ridesLegendMissing(); float missingWidth = paint.measureText(missing), dash = ui.dp(18);
            canvas.drawText(layout.shownTitle, mirror(rtl, layout.left), layout.baseline, paint);
            canvas.drawText(missing, mirror(rtl, layout.right - missingWidth), layout.baseline, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(2)); paint.setColor(Color.rgb(128, 128, 128));
            paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{ui.dp(4), ui.dp(3)}, 0));
            canvas.drawLine(mirror(rtl, layout.right - missingWidth - ui.dp(6) - dash), layout.headerCenter,
                    mirror(rtl, layout.right - missingWidth - ui.dp(6)), layout.headerCenter, paint);
            paint.setPathEffect(null); paint.setStyle(Paint.Style.FILL);
            if (layout.barRight <= layout.barLeft) return;
            paint.setShader(new android.graphics.LinearGradient(mirror(rtl, layout.barLeft), 0, mirror(rtl, layout.barRight), 0, colors, null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(rtl ? mirror(rtl, layout.barRight) : layout.barLeft, layout.barCenter - ui.dp(4), rtl ? mirror(rtl, layout.barLeft) : layout.barRight, layout.barCenter + ui.dp(4)), ui.dp(4), ui.dp(4), paint);
            paint.setShader(null); paint.setColor(ui.muted);
            for (int i = 0; i < labels.length; i++) {
                float x = i == 0 ? layout.barLeft : i == labels.length - 1 ? layout.barRight : (layout.barLeft + layout.barRight) / 2;
                paint.setTextAlign(align(rtl, i == 0 ? Paint.Align.LEFT : i == labels.length - 1 ? Paint.Align.RIGHT : Paint.Align.CENTER));
                float baseline = layout.labelBaseline + (layout.separateLabels ? i * (layout.line + ui.dp(3)) : 0);
                canvas.drawText(labels[i], mirror(rtl, x), baseline, paint);
            }
        }
        /** x for this layout direction: the left-to-right position, mirrored across the view when right to left. */
        private float mirror(boolean rtl, float x) { return rtl ? getWidth() - x : x; }
        /** The text alignment for this layout direction: left and right swap when right to left. */
        private Paint.Align align(boolean rtl, Paint.Align ltr) {
            return !rtl || ltr == Paint.Align.CENTER ? ltr : ltr == Paint.Align.LEFT ? Paint.Align.RIGHT : Paint.Align.LEFT;
        }
    }
    /** Small dark rounded route outline; a neutral glyph when the ride has no GPS route. */
    private final class RouteThumb extends View {
        private float[] route; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float shown = 1; private ValueAnimator reveal;
        RouteThumb() { super(host.activity()); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void setRoute(float[] value) {
            // A route that loads while the row is on screen draws in over the placeholder icon.
            boolean arriving = route == null && value != null && isShown() && isLaidOut() && Motion.enabled();
            route = value;
            if (reveal != null) { reveal.cancel(); reveal = null; shown = 1; }
            if (arriving) {
                reveal = ValueAnimator.ofFloat(0, 1); reveal.setDuration(Motion.CHANGE_MS); reveal.setInterpolator(Motion.FADE);
                reveal.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); });
                shown = 0; reveal.start();
            }
            invalidate();
        }
        @Override protected void onDetachedFromWindow() { if (reveal != null) { reveal.cancel(); reveal = null; shown = 1; } super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(ui.raised);
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), ui.dp(9), ui.dp(9), paint);
            boolean placeholder = route == null || route.length < 4;
            if (placeholder || shown < 1) ui.drawIcon(canvas, "route", placeholder ? ui.dim : Ui.withAlpha(ui.dim, Math.round(Color.alpha(ui.dim) * (1 - shown))), getWidth() / 2f, getHeight() / 2f, ui.dp(18));
            if (placeholder) return;
            float inset = ui.dp(7), w = getWidth() - 2 * inset, h = getHeight() - 2 * inset;
            float scale = Math.min(w / Math.max(1e-4f, route[0]), h / Math.max(1e-4f, route[1]));
            if (route[0] < 1e-4f && route[1] < 1e-4f) scale = 0;
            float ox = inset + (w - route[0] * scale) / 2, oy = inset + (h - route[1] * scale) / 2;
            paint.setColor(Ui.withAlpha(ui.accent, Math.round(Color.alpha(ui.accent) * shown)));
            if (route.length == 4 || scale == 0) { canvas.drawCircle(ox + route[2] * scale, oy + route[3] * scale, ui.dp(2.5f), paint); return; }
            Path path = new Path(); path.moveTo(ox + route[2] * scale, oy + route[3] * scale);
            for (int i = 4; i + 1 < route.length; i += 2) path.lineTo(ox + route[i] * scale, oy + route[i + 1] * scale);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(2.2f)); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(path, paint);
        }
    }
    /** Observe without intercepting: buttons, page scrolling and map gestures keep their normal targets. */
    private void dismissOutsideMap(View scope, MotionEvent event) {
        Rect visible = new Rect(); int[] windowOffset = new int[2];
        scope.getRootView().getLocationOnScreen(windowOffset);
        for (RouteMapView map : maps) {
            if (!map.isAttachedToWindow() || !java.util.Objects.equals(map.getWindowToken(), scope.getWindowToken())) continue;
            boolean shown = map.getGlobalVisibleRect(visible);
            visible.offset(windowOffset[0], windowOffset[1]);
            if (!shown || !visible.contains((int) event.getRawX(), (int) event.getRawY())) map.clearSelection();
        }
    }
    private final class MapOutsideTap {
        private float downX, downY; private boolean tap;
        boolean completes(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) { downX = event.getRawX(); downY = event.getRawY(); tap = true; }
            else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) tap = false;
            else if (Math.hypot(event.getRawX() - downX, event.getRawY() - downY)
                    > android.view.ViewConfiguration.get(host.activity()).getScaledTouchSlop()) tap = false;
            if (action != MotionEvent.ACTION_UP) return false;
            boolean completed = tap; tap = false; return completed;
        }
    }
    private class MapTouchScroll extends ScrollView {
        private final MapOutsideTap mapTap = new MapOutsideTap();
        MapTouchScroll() { super(host.activity()); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            boolean clear = mapTap.completes(event), handled = super.dispatchTouchEvent(event);
            // Collapse readouts only after the tapped button has received its complete gesture.
            if (clear) dismissOutsideMap(this, event); return handled;
        }
    }
    private class MapTouchColumn extends LinearLayout {
        private final MapOutsideTap mapTap = new MapOutsideTap();
        MapTouchColumn() { super(host.activity()); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            boolean clear = mapTap.completes(event), handled = super.dispatchTouchEvent(event);
            if (clear) dismissOutsideMap(this, event); return handled;
        }
        // Keep the down/up pair for blank space such as the fullscreen heading; children still get first refusal.
        @Override public boolean onTouchEvent(MotionEvent event) { return true; }
    }
    /**
     * Wide layout root. It is a ScrollView so the activity does not wrap the page in another scroller, but it
     * never scrolls itself: it gives the two panes exactly its own size and each pane scrolls independently.
     */
    private final class PaneHost extends MapTouchScroll {
        PaneHost() { super(); setVerticalScrollBarEnabled(false); setOverScrollMode(OVER_SCROLL_NEVER); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) { super.onMeasure(widthSpec, heightSpec); return; }
            setMeasuredDimension(width, height);
            if (getChildCount() > 0) getChildAt(0).measure(
                    MeasureSpec.makeMeasureSpec(Math.max(0, width - getPaddingLeft() - getPaddingRight()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, height - getPaddingTop() - getPaddingBottom()), MeasureSpec.EXACTLY));
        }
        @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event) { return false; }
        @Override public boolean onTouchEvent(android.view.MotionEvent event) { return true; }
    }
    /** List (about 45%) and detail side by side, 20dp apart; on a vertical hinge the gap covers the hinge instead. */
    private final class Panes extends LinearLayout {
        private final View list, gutter, detail;
        Panes(View list, View detail) {
            super(host.activity()); setOrientation(HORIZONTAL); setBaselineAligned(false);
            this.list = list; this.detail = detail; gutter = new View(host.activity());
            addView(list, new LinearLayout.LayoutParams(0, -1)); addView(gutter, new LinearLayout.LayoutParams(ui.dp(20), -1)); addView(detail, new LinearLayout.LayoutParams(0, -1));
        }
        // The hinge test needs this view's position in the window, which is known only once attached.
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(this::requestLayout); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int size = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
            int gap = ui.dp(20), first = Math.round((size - gap) * .45f);
            Rect hinge = verticalHinge();
            if (hinge != null && isAttachedToWindow()) {
                int relative = hinge.left - Ui.layoutLeftInWindow(this) - getPaddingLeft(), hingeGap = Math.max(ui.dp(20), hinge.width() + ui.dp(16));
                int proposed = relative - ui.dp(8);
                if (proposed >= ui.dp(200) && size - proposed - hingeGap >= ui.dp(220)) { first = proposed; gap = hingeGap; }
            }
            ((LinearLayout.LayoutParams) list.getLayoutParams()).width = Math.max(0, first);
            ((LinearLayout.LayoutParams) gutter.getLayoutParams()).width = gap;
            ((LinearLayout.LayoutParams) detail.getLayoutParams()).width = Math.max(0, size - first - gap);
            super.onMeasure(widthSpec, heightSpec);
        }
    }
    /**
     * The activity's fold bounds (window coordinates) when a vertical hinge splits the screen, else null.
     * Read the same way MainActivity's own two-pane gutter reads them; there is no public accessor.
     */
    private Rect verticalHinge() {
        try {
            java.lang.reflect.Field field = host.activity().getClass().getDeclaredField("fold"); field.setAccessible(true);
            Object fold = field.get(host.activity());
            Rect hinge = fold instanceof FoldMonitor && ((FoldMonitor) fold).isSeparating() ? ((FoldMonitor) fold).hinge : null;
            return hinge != null && hinge.height() > hinge.width() && hinge.left > 0 && hinge.height() > 0 ? hinge : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }
    /** Stat tiles in four columns when there is room (about 85dp a tile), otherwise two; tiles in a row share a height. */
    private final class TileGrid extends ViewGroup {
        TileGrid() { super(host.activity()); }
        private int columns(int available) { return available >= ui.dp(340) ? 4 : 2; }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int columns = columns(available), gap = ui.dp(8), cell = Math.max(0, (available - gap * (columns - 1)) / columns);
            int y = getPaddingTop(), count = getChildCount();
            for (int start = 0; start < count; start += columns) {
                int end = Math.min(count, start + columns), rowHeight = 0;
                for (int i = start; i < end; i++) {
                    getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    rowHeight = Math.max(rowHeight, getChildAt(i).getMeasuredHeight());
                }
                for (int i = start; i < end; i++)
                    getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
                y += rowHeight + (end < count ? gap : 0);
            }
            setMeasuredDimension(width, resolveSize(y + getPaddingBottom(), heightSpec));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int available = getWidth() - getPaddingLeft() - getPaddingRight(), columns = columns(available), gap = ui.dp(8);
            int cell = Math.max(0, (available - gap * (columns - 1)) / columns), y = getPaddingTop();
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); int column = i % columns;
                if (column == 0 && i > 0) y += getChildAt(i - 1).getMeasuredHeight() + gap;
                int x = column * (cell + gap), left = rtl ? getWidth() - getPaddingRight() - x - cell : getPaddingLeft() + x;
                child.layout(left, y, left + cell, y + child.getMeasuredHeight());
            }
        }
    }
    /** Wrapping row for chips. */
    private final class Flow extends ViewGroup {
        Flow() { super(host.activity()); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), available = Math.max(1, width - getPaddingLeft() - getPaddingRight());
            int x = 0, y = getPaddingTop(), rowHeight = 0, gap = ui.dp(6);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > available) { y += rowHeight + gap; x = 0; rowHeight = 0; }
                x += child.getMeasuredWidth() + gap; rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(y + rowHeight + getPaddingBottom(), heightSpec));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int available = getWidth() - getPaddingLeft() - getPaddingRight(), x = 0, y = getPaddingTop(), rowHeight = 0, gap = ui.dp(6);
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
                if (x > 0 && x + w > available) { y += rowHeight + gap; x = 0; rowHeight = 0; }
                int left = rtl ? getWidth() - getPaddingRight() - x - w : getPaddingLeft() + x;
                child.layout(left, y, left + w, y + h);
                x += w + gap; rowHeight = Math.max(rowHeight, h);
            }
        }
    }
    private static final class FontSpan extends MetricAffectingSpan {
        private final Typeface typeface;
        FontSpan(Typeface typeface) { this.typeface = typeface; }
        @Override public void updateDrawState(TextPaint paint) { paint.setTypeface(typeface); }
        @Override public void updateMeasureState(TextPaint paint) { paint.setTypeface(typeface); }
    }

    private List<Ui.Point> points(List<RideStore.Point> source, String key) {
        List<Ui.Point> result = new ArrayList<Ui.Point>(); boolean imperial = !"km".equals(preferences.getString("units", "mi"));
        for (RideStore.Point p : source) {
            Double value = key.equals("voltage_v") ? p.voltageV : key.equals("speed_kmh") ? p.speedKmh : key.equals("battery_percent") ? p.batteryPercent : p.powerW;
            if (value != null) result.add(new Ui.Point(p.wallMs, key.equals("speed_kmh") && imperial ? value / RideStore.KM_PER_MILE : value));
        }
        return result;
    }
    private String chartUnit(String key) { return key.equals("voltage_v") ? "V" : key.equals("battery_percent") ? "%" : key.equals("speed_kmh") ? !"km".equals(preferences.getString("units", "mi")) ? "mph" : "km/h" : "W"; }
    private int chartColor(String key) { return key.equals("voltage_v") ? ui.blue : key.equals("speed_kmh") ? ui.orange : ui.accent; }
    private boolean miles() { return !"km".equals(preferences.getString("units", "mi")); }
    private String shortDate(long wall) { return new SimpleDateFormat(datePattern(RIDE_DATE), Fmt.locale(Locale.getDefault())).format(new Date(wall)) + " · " + DisplayTime.clock(host.activity(), preferences, wall); }
    private String longDate(long wall) { return new SimpleDateFormat(datePattern(RIDE_DAY_DATE), Fmt.locale(Locale.getDefault())).format(new Date(wall)) + " · " + DisplayTime.clock(host.activity(), preferences, wall); }
    private static int blend(int a, int b, double fraction) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * fraction),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * fraction),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * fraction));
    }
    /** The ride dates' patterns, which English and every unshipped language keep (Fmt.datePattern). */
    private static final String RIDE_DATE = "MMM d", RIDE_DAY_DATE = "EEE, MMM d";
    /** A ride date pattern: the legacy pattern while Volt is English, else the display language's best pattern for its fields. */
    // Deferred (l10n/pending/f5-followups.md): the pattern here and Fmt.locale at each date read the language apart.
    private static String datePattern(String legacy) {
        return Fmt.datePattern(legacy, skeleton(legacy), android.text.format.DateFormat::getBestDateTimePattern);
    }
    /** A date pattern's fields as a skeleton: its pattern letters, without the spaces and punctuation between them. */
    private static String skeleton(String pattern) {
        StringBuilder letters = new StringBuilder(pattern.length());
        for (int i = 0; i < pattern.length(); i++) if (Character.isLetter(pattern.charAt(i))) letters.append(pattern.charAt(i));
        return letters.toString();
    }
    private static String percent(double coverage) { return T.fmtPercent(Fmt.decimal(Locale.US, coverage * 100, 0)); }
    private static String duration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return seconds >= 3600 ? String.format(Locale.US, "%dh %02dm", seconds / 3600, seconds % 3600 / 60)
                : seconds >= 60 ? String.format(Locale.US, "%dm %02ds", seconds / 60, seconds % 60) : seconds + "s";
    }
    /** Stopwatch form for the live card: 12:48 or 1:02:05. */
    private static String clock(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return seconds >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
                : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
    /** The small, muted look of what stands beside a tile's number: its unit and any words. */
    private void unitStyle(SpannableString styled, int start, int end) {
        if (start >= end) return;
        styled.setSpan(new AbsoluteSizeSpan(Math.round(ui.sp(11.5f))), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new ForegroundColorSpan(ui.muted), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new FontSpan(ui.medium()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    /** Says where the numbers are in the tile values about to be built; tiles() forgets them when it is done. */
    private void tileReadings(Formatted... readings) {
        for (Formatted reading : readings) if (reading != null && RideText.numberAndRest(reading) != null) tileParts.put(reading.toString(), reading);
    }
    /** A row whose value is null reads rides_not_measured; that and a bare dash take the quiet style. */
    private LinearLayout valueRow(LinearLayout group, String title, String value) {
        LinearLayout row = shownValueRow(group, title, measured(value));
        if (value == null || value.equals("—")) quiet(group, 0);
        return row;
    }
    /** The quiet style on a row counted back from the group's newest (a divider stands between rows). */
    private void quiet(LinearLayout group, int rowsBack) {
        TextView amount = (TextView) ((LinearLayout) group.getChildAt(group.getChildCount() - 1 - 2 * rowsBack)).getChildAt(1);
        amount.setTextColor(ui.muted); amount.setTypeface(ui.regular());
    }
    private static String dash(String value) { return value == null ? "—" : value; }
    private String distance(Double km) {
        return km == null ? T.ridesNotMeasured() : distanceParts(km).toString();
    }
    private String speed(Double kmh) {
        return kmh == null ? T.ridesNotMeasured() : speedParts(kmh).toString();
    }
    private Formatted distanceParts(double km) { return RideText.distance(km, preferences.getString("units", "mi")); }
    private Formatted speedParts(double kmh) { return RideText.speed(kmh, preferences.getString("units", "mi")); }
    private String efficiency(RideStore.Summary s) {
        Formatted parts = efficiencyParts(s);
        return parts == null ? "—" : parts.toString();
    }
    /** The ascent / descent tile's value, when ascent was measured; deferred: its " / " at the tile is not fmt_dual yet (l10n/pending/f5-followups.md). */
    private Formatted ascentParts(Double ascent, Double descent) {
        return ascent == null ? null : RideText.approximate(T.fmtDual(approx(ascent), height(descent)), Fmt.decimal(Locale.US, preferences.getString("units", "mi").equals("km") ? ascent : ascent * 3.280839895, 0));
    }
    private Formatted efficiencyParts(RideStore.Summary s) {
        Double km = s.netWhPerKm == null ? s.throughputWhPerKm : s.netWhPerKm;
        Double mi = s.netWhPerMile == null ? s.throughputWhPerMile : s.netWhPerMile;
        if (km == null || mi == null) return null;
        return RideText.efficiency(km, mi, s.netWhPerKm != null, preferences.getString("units", "mi"));
    }
    /** A reading with its unit, or null when it was not measured; valueRow, compareLine and tiles show that as rides_not_measured. */
    private static String quantity(Double value, String unit, int decimals) { return value == null ? null : RideText.quantity(value, unit, decimals).toString(); }
    private static Formatted quantityParts(Double value, String unit, int decimals) { return value == null ? null : RideText.quantity(value, unit, decimals); }
    private String temperature(Double celsius) {
        return celsius == null ? null : DisplayTemperature.format(celsius, preferences.getString("temperature_unit", "c"), 0);
    }
    /** Battery change in percentage points: "−3%" used, "+2%" gained. */
    private static String change(Double drop) {
        if (drop == null) return null;
        long points = Math.round(Math.abs(drop));
        return points == 0 ? T.fmtPercent(Fmt.integer(points)) : (drop >= 0 ? "−" : "+") + T.fmtPercent(Fmt.integer(points));
    }
    private void measureStorage() {
        File root = host.activity().getFilesDir();
        try {
            thumbLoader.execute(() -> {
                long bytes = bytes(new File(root, "rides"), 0) + bytes(new File(root, "phone-rides"), 0);
                host.activity().runOnUiThread(() -> { if (closed) return; storedBytes = bytes; updateHistoryCount(); });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    /** The saved rides' size in the current language, or nothing when none are saved. */
    private static String storageSize(long bytes) {
        if (bytes <= 0) return "";
        return bytes < 1000 * 1000 ? T.fmtQuantity(Fmt.decimal(Locale.US, Math.max(1, bytes / 1000.0), 0), T.ridesUnitKilobyte()) : T.fmtQuantity(Fmt.decimal(Locale.US, bytes / 1e6, 1), T.ridesUnitMegabyte());
    }
    private View tile(String glyph, String label, String value) {
        LinearLayout tile = vertical(); tile.setBackground(ui.shape(ui.surface, 14, 0)); tile.setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(10));
        LinearLayout heading = horizontal(); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(ui.icon(glyph, 16, ui.muted), new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        TextView name = ui.caption(label); name.setPadding(ui.dp(6), 0, 0, 0); name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12 * ui.textScale); heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); tile.addView(heading);
        TextView amount = ui.text("", 20, true); amount.setPadding(0, ui.dp(5), 0, 0);
        Formatted parts = tileParts.get(value);
        // Durations such as "24m 05s" stay bold; words such as "Not measured" (no digit) read as quiet text, wherever a
        // language's template puts its words, unit or % sign ("%42" in Turkish).
        if (parts == null && (value.isEmpty() || !hasDigit(value) && NUMBER_LEADS.indexOf(value.charAt(0)) < 0)) {
            amount.setText(value); amount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * ui.textScale); amount.setTypeface(ui.regular()); amount.setTextColor(ui.muted);
        } else if (parts == null) amount.setText(value);
        else {
            SpannableString styled = new SpannableString(parts);
            int start = parts.numberEnd(0);
            if (start < styled.length()) {
                styled.setSpan(new AbsoluteSizeSpan(Math.round(ui.sp(11.5f))), start, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new ForegroundColorSpan(ui.muted), start, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new FontSpan(ui.medium()), start, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (parts.numberStart(0) > 0) {
                styled.setSpan(new AbsoluteSizeSpan(Math.round(ui.sp(11.5f))), 0, parts.numberStart(0), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new ForegroundColorSpan(ui.muted), 0, parts.numberStart(0), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new FontSpan(ui.medium()), 0, parts.numberStart(0), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            amount.setText(styled);
        }
        tile.addView(amount);
        tile.setContentDescription(label + ", " + value);
        if (Build.VERSION.SDK_INT >= 28) tile.setScreenReaderFocusable(true);
        return tile;
    }
    /** What a tile value's number may start with besides a digit: a sign, or the ≈ of a smoothed estimate. */
    private static final String NUMBER_LEADS = "+-−≈";
    /** True when value holds a digit anywhere: a measured value, whatever text a language's template puts before the number. */
    private static boolean hasDigit(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isDigit(value.charAt(i))) return true;
        return false;
    }
    /** A chart series' line pattern, so colour is never the only difference. */
    private static final String MARK_SOLID = "━ ", MARK_DASH = "┄ ", MARK_DOT = "⋯ ";
    private static final String WH_PER_DISTANCE = "wh_per_distance";
    /** The icon on every ride export button. */
    private static final String EXPORT_ICON = "download";
    private void show(AlertDialog dialog, int style, Runnable onDismiss) {
        dialogs.add(dialog); dialog.setOnDismissListener(d -> { dialogs.remove(dialog); if (onDismiss != null) onDismiss.run(); });
        Window window = dialog.getWindow();
        Motion.present(dialog, style == PAGE ? Motion.PAGE : style == SHEET ? Motion.SHEET : Motion.DIALOG);
        if (window != null) {
            if (style == PAGE) {
                window.setBackgroundDrawable(new ColorDrawable(ui.bg));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            } else if (style == SHEET) {
                GradientDrawable sheet = new GradientDrawable(); sheet.setColor(ui.dark ? blend(ui.bg, ui.surface, .45) : ui.bg);
                float r = ui.dp(24); sheet.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
                window.setBackgroundDrawable(sheet); window.setGravity(Gravity.BOTTOM);
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            } else window.setBackgroundDrawable(new InsetDrawable(ui.shape(ui.surface, 24, ui.raised), ui.dp(16), ui.dp(24), ui.dp(16), ui.dp(24)));
        }
        dialog.show();
        if (window != null && style == PAGE) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        else if (window != null && style == SHEET) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            TextView button = dialog.getButton(which);
            if (button != null) { button.setTextColor(ui.accent); button.setTypeface(ui.semibold()); }
        }
    }
    @Override public void close() {
        if (wide && !closed) wideListScroll = scroll.getScrollY();
        closed = true; phoneRefreshers.clear(); phoneLoader.shutdownNow(); chartLoader.shutdownNow();
        // Not interrupted: a read in progress finishes and fills the ride cache for the next visit.
        rideListLoader.shutdown(); thumbLoader.shutdownNow(); for (RouteMapView map : maps) map.close(); maps.clear();
        for (AlertDialog dialog : new ArrayList<AlertDialog>(dialogs)) dialog.dismiss(); dialogs.clear();
    }
}
