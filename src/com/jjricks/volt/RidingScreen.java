package com.jjricks.volt;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Riding mode (riding-mode plan PRs 2 and 3, with the owner's answers): a full-screen view for a
 * phone on the handlebars, in one of three designed frames. Focus (the default, Q10) shows speed huge
 * at the top, then Range at this pace, battery with its segments, and a bottom row of trip, ride time
 * and battery temperature. Halo puts the speed inside a speedometer ring, with range, two tiles, a
 * strip and a row of chips. Trace puts a live chart of the last minute of speed and power under the
 * speed. Every frame has the tall power bar on the left, and sideways or on a wide screen each puts
 * its parts side by side.
 *
 * Riding it only reads. The owner reversed Q12 A and Q16 on Nov 8: while the scooter is stopped riding
 * mode also shows two 56 dp controls, Lock and Record, which are Home's own: MainActivity's LockControl
 * through its LockTapQueue, and RideRecordingControl. They share the tune button's gate
 * (RidingFrames.tuneShown): shown when stopped or with no scooter connected (owner, Nov 9), gone the
 * moment a connected scooter moves, and a lock tap still waiting in the queue goes back then too.
 * Disconnected, Lock shows the last known state and takes no tap, and Record only stops a recording.
 * Nothing else here sends a scooter
 * command, changes a setting or starts a recording, and there is still no lock state in the readings
 * or the layout spots. Moving, the only control that acts on the scooter or the app is back, top
 * bar, 56 dp; the moon and the brightness slider stay live but change only this screen's look. The
 * gauge area always ignores taps, so a bump or a glove changes nothing.
 */
final class RidingScreen implements AppScreen {
    /** Label size before the cap; labels grow with the text size up to RidingFocus.labelCap, numbers stay fixed. */
    private static final float LABEL_SP = 15, TOUCH_DP = 56;
    /** The most of Trace's top row its chip column may take beside the big number. */
    private static final float CHIP_COLUMN_SHARE = .45f;
    private final float labelCap;
    private final ScreenHost host;
    private final Ui ui;
    private final ScooterService service;
    private final SharedPreferences preferences;
    private final RidingSession session;
    private final RidingConfig config;
    private final RidingLayout layout;
    private final String frameId;
    private final boolean side, large;
    private final Runnable leave;
    private final FrameLayout view;
    private final LinearLayout frame;
    private final View dot;
    private final LinearLayout statusPill;
    /** What the status pill shows now: the connection, or a ride recording (owner, Nov 6); null before the first refresh. */
    private RecordingPill.Look pillLook;
    /** The recording dot's gentle pulse, running only while the pill says RECORDING and animations are on. */
    private ValueAnimator pulse;
    /** A phone-width window: the warning chip shows its short rung so the status pill keeps room. */
    private final boolean narrow;
    private final TextView status, warning, speedLast, wattsCaption;
    /** The layout picker's disc: shown only while stopped (owner Q17 A). */
    private final FrameLayout picker;
    /** Lock and Record over the power readout while stopped (owner, Nov 8); the lock is null on a host without one. */
    private final View stoppedLock;
    private final FrameLayout recordDisc;
    private final LinearLayout stoppedControls;
    private RidingStoppedMotion stoppedMotion;
    /** Home's record and stop marks on the disc. */
    private final RecordGlyph recordGlyph;
    /** Whether the record disc is live now; null before the first refresh. */
    private Boolean recordLive;
    /** The frame's big number; every frame has one. Focus's range, battery and bottom cells are made with it. */
    private Cell big, highlight, battery;
    private final List<Cell> bottom = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    private final List<Chip> chips = new ArrayList<>();
    /** Halo's ring and Trace's chart; null in the frames without them. */
    private Ring ring;
    private TraceView trace;
    private final PowerBar bar;
    private final NumberView watts;
    private final LinearLayout power;
    /** Labels, units and dividers: the static chrome RidingBurnIn dims on a long ride. */
    private final List<View> chrome = new ArrayList<>();
    /** The fastest speed this screen has seen, km/h: the ring and chart scales only grow while it's open. */
    private double fastestKmh;
    private long powerSampleMs = -1;
    private boolean closed;
    /** The tune menu, owned here so a rebuild or real speed can close it. */
    private final RidingFrames.PickerSheet sheetState = new RidingFrames.PickerSheet();
    private AlertDialog sheet;
    /** The menu changed the layout or a display setting: the screen is rebuilt with it once the menu closes. */
    private boolean applyAfterMenu;
    /** The tune disc's motion as it appears and goes (owner, Nov 6). */
    private RidingDiscMotion disc;
    /** Brightness and the rotation lock while riding mode is open (its slider sits below). */
    private final RidingDisplay display;
    /** Night vision: the red layer on this screen and the moon at the top bar's end. */
    private final RidingNight night;
    /** The tune menu's clock seconds switch, as it was when this screen was built (a change rebuilds it). */
    private final boolean clockSeconds;
    /** Redraws the Clock readings each second while seconds show (RidingClockSeconds); idle otherwise. */
    private final RidingClockSeconds.Tick clockTick;
    private final android.os.Handler clockHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable clockRun = this::tickClock;
    /** Pauses and resumes the tick with the activity; registered only while seconds show. */
    private final android.app.Application.ActivityLifecycleCallbacks clockLifecycle;

    RidingScreen(ScreenHost host, RidingSession session, Runnable leave) {
        this.host = host;
        this.session = session;
        this.leave = leave;
        ui = RidingNight.palette(host, session);
        service = host.service();
        Context context = host.activity();
        preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        config = new RidingConfig(preferences, session.profileId);
        layout = config.layout();
        frameId = layout.frame();
        clockSeconds = RidingClockSeconds.on(preferences.getAll().get(RidingClockSeconds.KEY));
        large = RidingFocus.largeText(ui.textScale * context.getResources().getConfiguration().fontScale);
        android.content.res.Configuration configuration = context.getResources().getConfiguration();
        side = RidingFocus.sideBySide(configuration.screenWidthDp, configuration.screenHeightDp, large);
        labelCap = RidingFocus.labelCap(side, configuration.screenHeightDp);
        boolean shortWindow = side && configuration.screenHeightDp < RidingFocus.SHORT_HEIGHT_DP;

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

        // Top bar: the back disc, the status pill and, while stopped, the layout picker: the only things that take taps.
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
        pill.setBackground(ui.shape(cardColor(), TOUCH_DP / 2, 0));
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
        picker = new FrameLayout(context);
        picker.setBackground(disc());
        picker.setClickable(true);
        picker.setFocusable(true);
        picker.setContentDescription(T.ridingLayoutChoose());
        picker.setOnClickListener(v -> { if (disc != null && disc.takesTaps()) edit(); });
        picker.addView(ui.icon("tune", 24, ui.text), new FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER));
        picker.setVisibility(View.GONE);
        LinearLayout.LayoutParams pickerSize = new LinearLayout.LayoutParams(ui.dp(TOUCH_DP), ui.dp(TOUCH_DP));
        pickerSize.setMarginStart(ui.dp(10));
        top.addView(picker, pickerSize);
        disc = new RidingDiscMotion(picker);
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
        // Focus's cells; Halo and Trace build their own in place of these.
        battery = new Cell(layout.content(RidingLayout.FOCUS, RidingFocus.BATTERY_ROW), 84, false);
        for (String spot : RidingFocus.bottomRow(large)) {
            Cell tile = new Cell(layout.content(RidingLayout.FOCUS, spot), 60, false);
            tile.fitGauge(RidingFocus.gaugeFor(spot));
            bottom.add(tile);
        }
        big.fitGauge(RidingFocus.gaugeFor("big"));
        highlight.fitGauge(RidingFocus.gaugeFor("highlight"));
        battery.fitGauge(RidingFocus.gaugeFor(RidingFocus.BATTERY_ROW));
        watts = new NumberView(64, false);
        wattsCaption = ui.text("", labelSp(LABEL_SP), false);
        wattsCaption.setTextColor(ui.muted);
        wattsCaption.setMaxLines(1);
        wattsCaption.setEllipsize(TextUtils.TruncateAt.END);
        // The number draws itself and the caption may be cut to a rung, so neither speaks: the column holds one
        // description with the watts and the caption's whole meaning (showPower).
        wattsCaption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        chrome.add(wattsCaption);
        power = column();
        power.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        power.setFocusable(true);
        power.addView(watts, new LinearLayout.LayoutParams(-1, 0, 1));
        power.addView(wattsCaption, new LinearLayout.LayoutParams(-1, -2));
        bar = new PowerBar();
        stoppedLock = host.ridingLock(() -> stopped(SystemClock.elapsedRealtime()));
        recordDisc = new FrameLayout(context);
        recordDisc.setFocusable(true);
        recordDisc.setOnClickListener(v -> { if (stoppedMotion.takesTaps()) recordTap(); });
        recordGlyph = new RecordGlyph();
        recordDisc.addView(recordGlyph, new FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER));
        stoppedControls = new StoppedControls();
        stoppedMotion = new RidingStoppedMotion(stoppedControls, power, stoppedLock, recordDisc);

        LinearLayout body = row();
        if (layout.on(RidingLayout.POWER_BAR)) body.addView(bar, new LinearLayout.LayoutParams(ui.dp(side ? 40 : 44), -1));
        View content;
        if (RidingLayout.HALO.equals(frameId)) content = halo(shortWindow);
        else if (RidingLayout.TRACE.equals(frameId)) content = trace(shortWindow);
        else content = focus(shortWindow);
        LinearLayout.LayoutParams contentSize = new LinearLayout.LayoutParams(0, -1, 1);
        if (layout.on(RidingLayout.POWER_BAR)) contentSize.setMarginStart(ui.dp(16));
        body.addView(content, contentSize);
        frame.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        display = new RidingDisplay(host.activity(), ui, preferences, host.resumed());
        frame.addView(display.controls(), RidingDisplay.slot(ui));
        night = new RidingNight(host, ui, session, view, display);
        top.addView(night.button(), RidingNight.slot(ui));
        // The big watt number sits on the bar's zero line, so the eye always finds it in the same place.
        body.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> bar.post(this::alignZero));
        clockTick = new RidingClockSeconds.Tick(clockSeconds, clockShown(), host.resumed(), new RidingClockSeconds.Timer() {
            @Override public void post(long delayMs) { clockHandler.postDelayed(clockRun, delayMs); }
            @Override public void cancel() { clockHandler.removeCallbacks(clockRun); }
        });
        clockLifecycle = clockSeconds && clockShown() ? clockLifecycle() : null;
        if (clockLifecycle != null) host.activity().getApplication().registerActivityLifecycleCallbacks(clockLifecycle);
        refresh();
    }

    /** Whether the frame as built shows a Clock reading: a cell, or a chip (chips are built only while they're on). */
    private boolean clockShown() {
        for (Cell cell : cells) if (RidingLayout.CLOCK.equals(cell.content)) return true;
        for (Chip chip : chips) if (RidingLayout.CLOCK.equals(chip.content)) return true;
        return false;
    }

    /** The activity's pause stops the tick at once; its resume starts it again (a refresh would too, within a second). */
    private android.app.Application.ActivityLifecycleCallbacks clockLifecycle() {
        android.app.Activity activity = host.activity();
        return new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(android.app.Activity a) { if (a == activity) clockTick.resumed(true, System.currentTimeMillis()); }
            @Override public void onActivityPaused(android.app.Activity a) { if (a == activity) clockTick.resumed(false, 0); }
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle saved) { }
            @Override public void onActivityStarted(android.app.Activity a) { }
            @Override public void onActivityStopped(android.app.Activity a) { }
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle out) { }
            @Override public void onActivityDestroyed(android.app.Activity a) { }
        };
    }

    /**
     * Just after each second turns: only the Clock readings redraw, from the wall clock read now. The rest of
     * the screen (burn-in drift, chrome dimming, gauges) keeps to its own refresh.
     */
    private void tickClock() {
        if (!clockTick.fired(System.currentTimeMillis())) return;
        long now = SystemClock.elapsedRealtime();
        String units = units();
        for (Cell cell : cells) if (RidingLayout.CLOCK.equals(cell.content)) cell.show(now, units);
        for (Chip chip : chips) if (RidingLayout.CLOCK.equals(chip.content)) chip.show(now, units);
    }

    // The frames. Each returns the area right of the power bar.

    /** Focus: speed huge at the top, then range, battery with its segments, the bottom row and the power readout. */
    private View focus(boolean shortWindow) {
        cells.add(big);
        cells.add(highlight);
        cells.add(battery);
        cells.addAll(bottom);
        LinearLayout bottomRow = row();
        for (int i = 0; i < bottom.size(); i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) p.setMarginStart(ui.dp(14));
            bottomRow.addView(bottom.get(i), p);
        }
        if (side) {
            // Speed and the power readout share the left column, so each reading on the right keeps a large number.
            LinearLayout out = row();
            LinearLayout speed = column();
            speed.addView(big, new LinearLayout.LayoutParams(-1, 0, 2.3f));
            divider(speed);
            speed.addView(stoppedOver(power, false), new LinearLayout.LayoutParams(-1, 0, 1f));
            out.addView(speed, new LinearLayout.LayoutParams(0, -1, 1.1f));
            LinearLayout readings = column();
            readings.addView(highlight, new LinearLayout.LayoutParams(-1, 0, 1.1f));
            divider(readings);
            readings.addView(battery, new LinearLayout.LayoutParams(-1, 0, 1f));
            divider(readings);
            readings.addView(bottomRow, new LinearLayout.LayoutParams(-1, 0, 1f));
            LinearLayout.LayoutParams readingsSize = new LinearLayout.LayoutParams(0, -1, 1);
            readingsSize.setMarginStart(ui.dp(24));
            out.addView(readings, readingsSize);
            return out;
        }
        LinearLayout readings = column();
        readings.addView(big, new LinearLayout.LayoutParams(-1, 0, 3.4f));
        divider(readings);
        readings.addView(highlight, new LinearLayout.LayoutParams(-1, 0, 1.5f));
        divider(readings);
        readings.addView(battery, new LinearLayout.LayoutParams(-1, 0, 1.35f));
        divider(readings);
        readings.addView(bottomRow, new LinearLayout.LayoutParams(-1, 0, 1.25f));
        divider(readings);
        readings.addView(stoppedOver(power, false), new LinearLayout.LayoutParams(-1, 0, 1.3f));
        return readings;
    }

    /**
     * Halo: speed inside a 270° ring with this ride's max, Range at this pace under it, trip and
     * battery tiles, a strip of two small readings, the power readout and a row of chips (drive mode,
     * battery temperature, clock). Sideways the ring takes the middle and the readings the right.
     */
    private View halo(boolean shortWindow) {
        detach(speedLast);
        big = cell(layout.content("big"), 150, true);
        big.hideLabel();
        big.number.centered = true;
        ring = new Ring(layout.content("ring"), big, speedLast);
        Cell range = cell(layout.content("highlight"), !side ? 76 : shortWindow ? 64 : 96, false);
        if (side) range.card();
        range.fitGauge(RidingFocus.Gauge.BAR_BELOW);
        LinearLayout tiles = tiles(!side ? 56 : shortWindow ? 52 : 84, false);
        // The two tiles under the ring show their names one way: both names, or both icons. The range above them is
        // full width and decides alone (RidingNameGroup): its name, which says which range it is, stays while it fits.
        NameGroup under = new NameGroup();
        for (int i = 0; i < tiles.getChildCount(); i++) if (tiles.getChildAt(i) instanceof Cell) under.add((Cell) tiles.getChildAt(i));
        View strip = strip(RidingFrames.haloStrip(large && !side));
        View chipRow = layout.on(RidingLayout.CHIPS) ? chipRow(RidingFrames.HALO_CHIPS) : null;
        if (side) {
            LinearLayout out = row();
            // The power readout sits low beside the bar, under the ring's open side.
            LinearLayout left = column();
            left.addView(new View(host.activity()), new LinearLayout.LayoutParams(-1, 0, 1.4f));
            left.addView(power, new LinearLayout.LayoutParams(-1, 0, 1f));
            // This column is too narrow for a row: the two controls stack, up over its empty top.
            out.addView(stoppedOver(left, true), new LinearLayout.LayoutParams(0, -1, .62f));
            LinearLayout middle = column();
            middle.addView(ring, new LinearLayout.LayoutParams(-1, 0, 1));
            if (strip != null) middle.addView(strip, new LinearLayout.LayoutParams(-1, ui.dp(shortWindow ? 40 : 48)));
            LinearLayout.LayoutParams middleSize = new LinearLayout.LayoutParams(0, -1, 1.25f);
            middleSize.setMarginStart(ui.dp(12));
            out.addView(middle, middleSize);
            LinearLayout right = column();
            right.addView(range, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout.LayoutParams tilesSize = new LinearLayout.LayoutParams(-1, 0, 1);
            tilesSize.topMargin = ui.dp(10);
            right.addView(tiles, tilesSize);
            if (chipRow != null) {
                LinearLayout.LayoutParams chipsSize = new LinearLayout.LayoutParams(-1, -2);
                chipsSize.topMargin = ui.dp(10);
                right.addView(chipRow, chipsSize);
            }
            LinearLayout.LayoutParams rightSize = new LinearLayout.LayoutParams(0, -1, 1.15f);
            rightSize.setMarginStart(ui.dp(16));
            out.addView(right, rightSize);
            return out;
        }
        LinearLayout readings = column();
        readings.addView(ring, new LinearLayout.LayoutParams(-1, 0, 3.3f));
        readings.addView(range, new LinearLayout.LayoutParams(-1, 0, 1.25f));
        LinearLayout.LayoutParams tilesSize = new LinearLayout.LayoutParams(-1, 0, 1.25f);
        tilesSize.topMargin = ui.dp(8);
        readings.addView(tiles, tilesSize);
        if (strip != null) {
            LinearLayout.LayoutParams stripSize = new LinearLayout.LayoutParams(-1, 0, .62f);
            stripSize.topMargin = ui.dp(10);
            readings.addView(strip, stripSize);
        }
        LinearLayout.LayoutParams powerSize = new LinearLayout.LayoutParams(-1, 0, 1.05f);
        powerSize.topMargin = ui.dp(10);
        readings.addView(stoppedOver(power, false), powerSize);
        if (chipRow != null) {
            LinearLayout.LayoutParams chipsSize = new LinearLayout.LayoutParams(-1, -2);
            chipsSize.topMargin = ui.dp(8);
            readings.addView(chipRow, chipsSize);
        }
        return readings;
    }

    /**
     * Trace: the speed at the top with drive mode and battery temperature beside it, the last minute
     * of speed over power as a chart, Range at this pace, trip and battery tiles, and the power
     * readout. Sideways the speed and chart take the left and the readings the right.
     */
    private View trace(boolean shortWindow) {
        detach(speedLast);
        big = cell(layout.content("big"), side ? 120 : 150, !shortWindow);
        big.hideLabel();
        big.addView(speedLast, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout head = row();
        head.addView(big, new LinearLayout.LayoutParams(0, -1, 1));
        if (layout.on(RidingLayout.CHIPS)) {
            LinearLayout beside = new ChipColumn();
            beside.setGravity(Gravity.END | Gravity.TOP);
            // The column sets each chip's top gap by what's shown (fitChips).
            for (int i = 0; i < RidingFrames.TRACE_CHIPS.size(); i++)
                beside.addView(chip(RidingFrames.TRACE_CHIPS.get(i)), new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams besideSize = new LinearLayout.LayoutParams(-2, -1);
            besideSize.setMarginStart(ui.dp(10));
            head.addView(beside, besideSize);
        }
        trace = new TraceView(layout.content("trace"));
        Cell range = cell(layout.content("highlight"), !side ? 72 : shortWindow ? 60 : 96, false);
        range.card();
        range.fitGauge(RidingFocus.Gauge.BAR_BELOW);
        // A short window held sideways leaves the tiles too little height for a name above the number.
        LinearLayout tiles = tiles(!side ? 56 : shortWindow ? 48 : 84, side && shortWindow);
        if (side) {
            LinearLayout out = row();
            LinearLayout left = column();
            left.addView(head, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout.LayoutParams traceSize = new LinearLayout.LayoutParams(-1, 0, 1.35f);
            traceSize.topMargin = ui.dp(8);
            left.addView(trace, traceSize);
            out.addView(left, new LinearLayout.LayoutParams(0, -1, 1.35f));
            LinearLayout right = column();
            right.addView(range, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout.LayoutParams tilesSize = new LinearLayout.LayoutParams(-1, 0, 1);
            tilesSize.topMargin = ui.dp(10);
            right.addView(tiles, tilesSize);
            LinearLayout.LayoutParams powerSize = new LinearLayout.LayoutParams(-1, 0, .9f);
            powerSize.topMargin = ui.dp(10);
            right.addView(stoppedOver(power, false), powerSize);
            LinearLayout.LayoutParams rightSize = new LinearLayout.LayoutParams(0, -1, 1);
            rightSize.setMarginStart(ui.dp(18));
            out.addView(right, rightSize);
            return out;
        }
        LinearLayout readings = column();
        readings.addView(head, new LinearLayout.LayoutParams(-1, 0, 2f));
        LinearLayout.LayoutParams traceSize = new LinearLayout.LayoutParams(-1, 0, 2.5f);
        traceSize.topMargin = ui.dp(6);
        readings.addView(trace, traceSize);
        LinearLayout.LayoutParams highlightSize = new LinearLayout.LayoutParams(-1, 0, 1.25f);
        highlightSize.topMargin = ui.dp(10);
        readings.addView(range, highlightSize);
        LinearLayout.LayoutParams tilesSize = new LinearLayout.LayoutParams(-1, 0, 1.25f);
        tilesSize.topMargin = ui.dp(10);
        readings.addView(tiles, tilesSize);
        LinearLayout.LayoutParams powerSize = new LinearLayout.LayoutParams(-1, 0, 1.05f);
        powerSize.topMargin = ui.dp(10);
        readings.addView(stoppedOver(power, false), powerSize);
        return readings;
    }

    /**
     * Puts the stopped controls over a part of the frame, in a slot the frame sizes as before, so showing
     * and hiding them moves nothing. They sit over the power readout, the reading a stopped rider needs
     * least, and it only goes invisible, keeping the bar's zero line. Stacked is for a slot too narrow
     * for a row.
     */
    private View stoppedOver(View part, boolean stacked) {
        FrameLayout slot = new FrameLayout(host.activity());
        slot.addView(part, new FrameLayout.LayoutParams(-1, -1));
        boolean row = !stacked;
        stoppedControls.setOrientation(row ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        int size = ui.dp(TOUCH_DP);
        if (stoppedLock != null) stoppedControls.addView(stoppedLock, row
                ? new LinearLayout.LayoutParams(0, size, 1) : new LinearLayout.LayoutParams(-1, size));
        LinearLayout.LayoutParams disc = new LinearLayout.LayoutParams(size, size);
        if (stoppedLock != null) {
            if (row) disc.setMarginStart(ui.dp(12));
            else disc.topMargin = ui.dp(8);
        }
        stoppedControls.addView(recordDisc, disc);
        slot.addView(stoppedControls, new FrameLayout.LayoutParams(-1, -2, stacked ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL));
        return slot;
    }

    /** The row of stopped controls; on a tablet the lock's slide stays a sensible length. */
    private final class StoppedControls extends LinearLayout {
        StoppedControls() { super(host.activity()); }
        /** A touch that lands as they hide goes nowhere (one already down was cancelled when the hide began); Lock's queue never hears of it. */
        @Override public boolean onInterceptTouchEvent(MotionEvent event) { return !stoppedMotion.takesTaps() || super.onInterceptTouchEvent(event); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int cap = ui.dp(RidingFrames.STOPPED_CONTROLS_MAX_DP);
            if (MeasureSpec.getSize(widthSpec) > cap) widthSpec = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.getMode(widthSpec));
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /** Takes a view out of the Focus cell it was made in, for another frame to place. */
    private static void detach(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    /** A cell for a spot's content, refreshed with the others. */
    private Cell cell(String content, float maxSp, boolean unitBelow) {
        Cell c = new Cell(content, maxSp, unitBelow);
        cells.add(c);
        return c;
    }

    /**
     * The two tiles as cards. A battery tile gets a bar under its number; an inline tile puts the
     * name beside the number instead and leaves the bar out, so the number keeps its size.
     */
    private LinearLayout tiles(float maxSp, boolean inline) {
        LinearLayout out = row();
        for (int i = 0; i < RidingFrames.TILES.size(); i++) {
            Cell tile = cell(layout.content(RidingFrames.TILES.get(i)), maxSp, false);
            tile.card();
            if (inline) tile.inline();
            else tile.addBelow(new Meter());
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) p.setMarginStart(ui.dp(12));
            out.addView(tile, p);
        }
        return out;
    }

    /** Halo's strip: small readings side by side in one card, or null when none show. */
    private View strip(List<String> spots) {
        if (spots.isEmpty()) return null;
        LinearLayout out = row();
        out.setGravity(Gravity.CENTER_VERTICAL);
        out.setBackground(ui.shape(cardColor(), 18, 0));
        out.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4));
        // Small readings side by side show their names one way: both names, or both icons (RidingNameGroup).
        NameGroup names = new NameGroup();
        for (int i = 0; i < spots.size(); i++) {
            if (i > 0) {
                View line = new View(host.activity());
                line.setBackgroundColor(ui.outline);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Math.max(1, ui.dp(1)), -1);
                p.topMargin = ui.dp(8);
                p.bottomMargin = ui.dp(8);
                p.setMarginStart(ui.dp(10));
                p.setMarginEnd(ui.dp(10));
                out.addView(line, p);
                chrome.add(line);
            }
            Cell c = cell(layout.content(spots.get(i)), 30, false);
            c.inline();
            names.add(c);
            out.addView(c, new LinearLayout.LayoutParams(0, -1, 1));
        }
        return out;
    }

    private LinearLayout chipRow(List<String> spots) {
        LinearLayout out = new ChipRow();
        out.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < spots.size(); i++) {
            out.addView(chip(spots.get(i)), new LinearLayout.LayoutParams(-2, -2));
        }
        return out;
    }

    private Chip chip(String spotId) {
        Chip c = new Chip(layout.content(spotId));
        chips.add(c);
        return c;
    }

    private int cardColor() { return ui.dark ? ui.surface : ui.raised; }

    /** Closes the picker sheet if it's showing; safe while the activity is going away. */
    private void dismissSheet() {
        AlertDialog d = sheet;
        sheet = null;
        sheetState.dismissed();
        if (d != null && d.isShowing()) {
            try { d.dismiss(); } catch (IllegalArgumentException ignored) {}
        }
    }

    /** Stopped for the picker: the scooter says stationary and the latest speed is under 1 km/h. */
    private boolean stopped(long now) {
        BleController ble = service == null ? null : service.ble;
        return RidingFrames.pickerShown(ble != null && ble.stationary(), session.values.speed(now).kmh);
    }

    @Override public View view() { return view; }
    @Override public void close() {
        closed = true;
        // Rotation, folding and every rebuild close this screen: its sheet goes with it.
        sheetState.close();
        dismissSheet();
        if (disc != null) disc.stop();
        stoppedMotion.stop();
        stopPulse();
        clockTick.close();
        if (clockLifecycle != null) host.activity().getApplication().unregisterActivityLifecycleCallbacks(clockLifecycle);
        display.detach();
        night.detach();
    }

    /**
     * The tune button: hidden while moving, shown with no scooter connected (owner, Nov 9). A connected
     * scooter whose settings reply has gone stale (ready() false) still counts as connected.
     */
    private boolean tuneShown(long now) {
        BleController ble = service == null ? null : service.ble;
        return RidingFrames.tuneShown(ble != null && ble.connected(), ble != null && ble.stationary(), session.values.speed(now).kmh);
    }

    /** The speed the menu's close rule looks at: the fastest reading from this connection since the last look, or null. */
    private Double liveKmh() {
        BleController ble = service == null ? null : service.ble;
        return ble == null ? null : RidingFrames.menuKmh(ble.connected(), ble.telemetry.takeSpeedPeak(SystemClock.elapsedRealtime()));
    }

    /**
     * The one check before any change in the tune menu saves, a late permission answer included: reads the
     * newest speed rather than the last screen refresh's, and at 4 mph or more closes the menu instead.
     */
    private boolean menuChangeAllowed() {
        if (closed) return false;
        Double kmh = liveKmh();
        if (sheetState.update(kmh)) { dismissSheet(); return false; }
        return sheetState.accept(kmh);
    }

    /**
     * The tune menu: the layout editor (RidingLayoutSheet), then riding mode's own settings
     * (MoreScreen.RidingSettingsMenu). Everything saves as it changes; the screen is rebuilt with a change once
     * the menu closes, so a save never closes it mid-edit. The ride's readings carry over, since they
     * live in the session.
     */
    private void edit() {
        if (closed || host.activity() == null || sheet != null) return;
        if (!sheetState.open(tuneShown(SystemClock.elapsedRealtime()))) return;
        liveKmh(); // Speeds from before the menu opened don't count against it.
        LinearLayout body = host.sheetContent(T.ridingLayoutTitle(), null, this::dismissSheet);
        body.setTag("riding_layout_editor");
        new RidingLayoutSheet(host.activity(), ui, config.layout(), this::saveLayout).fill(body);
        MoreScreen.RidingSettingsMenu settings = new MoreScreen.RidingSettingsMenu(host, ui, preferences, () -> applyAfterMenu = true, this::menuChangeAllowed);
        settings.fill(body);
        AlertDialog shown = host.showSheet(body);
        if (shown == null) { sheetState.dismissed(); settings.close(); return; }
        sheet = shown;
        shown.setOnDismissListener(d -> {
            settings.close();
            if (sheet == shown) { sheet = null; sheetState.dismissed(); }
            applyAfterMenu();
        });
    }

    /** A change in the editor, saved at once for this scooter; a stale change, or one at riding speed, saves nothing. */
    private void saveLayout(RidingLayout edited) {
        if (!menuChangeAllowed()) return;
        if (edited.toJson().equals(config.layout().toJson())) return;
        config.save(edited);
        applyAfterMenu = true;
    }

    /** The menu closed: rebuild once if it changed anything this screen shows. */
    private void applyAfterMenu() {
        boolean apply = applyAfterMenu && !closed;
        applyAfterMenu = false;
        if (!apply) return;
        // On the activity's window: the menu's own views are detached by now.
        host.activity().getWindow().getDecorView().post(() -> { if (!closed) host.rebuild(); });
    }

    /**
     * The top spot. Record and Status both show the status pill: drive mode and connection, never the
     * lock. While a ride records it becomes the red recording pill with the recording's own timer (owner,
     * Nov 6). The pill takes no taps; a stopped rider starts and ends a recording with the Record disc.
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
        RideStore.Summary ride = service != null && service.isRecording() ? service.rides.activeSummary() : null;
        long wallNow = System.currentTimeMillis();
        // Reboot-safe: a recovered recording's start belongs to the previous boot's monotonic clock (Codex #304).
        Long recordingMs = ride == null ? null : RecordingPill.elapsedMs(ride.elapsedMs, ride.startWallMs, wallNow);
        if (ble != null) {
            session.values.setRecordingStart(recordingMs == null ? null : now - recordingMs);
            session.setRecordingKm(ride == null ? null : ride.distanceKm);
            session.values.setLinkGeneration(ble.connectionGeneration(), ble.connectionStartMs());
            Double charging = ble.telemetry.number("charging_state");
            ble.telemetry.followSpeed(session.speed());
            session.feed(ble.telemetry.metrics(), now, charging != null && charging > 0, units, layout.on(RidingLayout.TENTHS));
            if (session.values.takeScaleChanged()) config.save(session.values.scale());
        }
        if (session.takeBandDrop()) Haptics.tick(view);
        // The recording's own time, apart from ride time: a rider may record only part of a ride (owner).
        long stoppedAt = service == null || service.rides == null ? -1 : service.rides.lastStopWallMs();
        showStatus(ble, now, recordingMs, stoppedAt < 0 ? null : wallNow - stoppedAt,
                stoppedAt >= 0 && service.rides.lastStopAutomatic());
        for (Cell cell : cells) cell.show(now, units);
        for (Chip chip : chips) chip.show(now, units);
        noteFastest(now);
        showSpeedLast(now, units);
        showPower(now);
        if (ring != null) ring.show(now, units);
        if (trace != null) trace.show(now, units);
        // The tune disc, Lock and Record show while stopped (Q17 A; owner, Nov 8) or disconnected (owner, Nov 9), from one
        // reading of the gate, so they change on the same frame: a moving rider sees none.
        boolean shownNow = tuneShown(now);
        disc.show(shownNow);
        showStopped(shownNow, RidingBurnIn.chromeAlpha(now - session.openedMs));
        // Only real speed closes the tune menu, at 4 mph or more; losing the readings doesn't.
        if (sheetState.update(liveKmh())) dismissSheet();
        // OLED care: the whole layout drifts slowly and the static chrome dims on a long ride.
        float[] offset = RidingBurnIn.offsetDp(now - session.openedMs);
        frame.setTranslationX(ui.dp(offset[0]));
        frame.setTranslationY(ui.dp(offset[1]));
        float alpha = RidingBurnIn.chromeAlpha(now - session.openedMs);
        for (View v : chrome) v.setAlpha(alpha);
        clockTick.sync(wallNow);
    }

    /**
     * Lock and Record follow the gate with the tune disc's motion (RidingStoppedMotion), and stop taking
     * taps the moment they start to hide, so a moving rider never finds them live. They dim with the
     * chrome on a long ride, and drift with the frame they sit in.
     */
    private void showStopped(boolean shown, float alpha) {
        stoppedMotion.dim(alpha);
        if (stoppedMotion.show(shown) && !shown) {
            recordDisc.setEnabled(false);
            recordDisc.setClickable(false);
        }
        if (!shown) return;
        BleController ble = service == null ? null : service.ble;
        boolean recording = service != null && service.isRecording(), ready = ble != null && ble.ready();
        boolean saving = service != null && service.phoneSaving(), enabled = RidingFrames.recordEnabled(recording, ready, saving);
        recordDisc.setEnabled(enabled);
        recordDisc.setClickable(enabled);
        // Home's words for a disc that can't start here: saving, connecting, or needing a connection (which this disc never makes).
        recordDisc.setContentDescription(recording ? T.ridesEndDescription() : DashboardScreen.recordIdleSpoken(saving, ready, reconnecting(ble, ready)));
        recordGlyph.set(recording, enabled ? ui.onAccent : ui.dim);
        if (recordLive != null && recordLive == enabled) return;
        recordLive = enabled;
        recordDisc.setBackground(oval(enabled ? ui.accent : ui.raised));
    }

    /** The record mark (a ring and dot) or stop mark (a rounded square), drawn as Home's recorder disc draws them. */
    private final class RecordGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF square = new RectF(6, 6, 18, 18);
        private boolean stop;
        private int color;

        RecordGlyph() {
            super(host.activity());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(boolean stop, int color) {
            if (this.stop == stop && this.color == color) return;
            this.stop = stop;
            this.color = color;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float unit = Math.min(getWidth(), getHeight()) / 24f;
            canvas.save();
            canvas.scale(unit, unit);
            paint.setColor(color);
            if (stop) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(square, 2.5f, 2.5f, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.8f);
                canvas.drawCircle(12, 12, 8, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12, 12, 3.6f, paint);
            }
            canvas.restore();
        }
    }

    /** The Record disc: Home's start and stop, asked once more whether the scooter is still stopped. */
    private void recordTap() {
        if (closed || service == null) return;
        int action = RidingFrames.recordAction(service.ble.connected(), stopped(SystemClock.elapsedRealtime()), service.isRecording(),
                service.ble.ready(), recordGlyph.stop);
        RideRecordingControl recorder = RideRecordingApp.of(host);
        if (action == RecorderTap.STOP) recorder.stop();
        else if (action == RecorderTap.START) { Haptics.tick(view); recorder.start(); }
        host.redraw();
    }

    private void showStatus(BleController ble, long now, Long recordingMs, Long savedAgoMs, boolean savedAutomatic) {
        boolean ready = ble != null && ble.ready();
        RidingFocus.Link link = RidingFocus.link(ready, reconnecting(ble, ready));
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
        // While a ride records, the red recording pill replaces the connection text set above (owner, Nov 6).
        RecordingPill.Look look = RecordingPill.look(recordingMs, link, savedAgoMs, savedAutomatic);
        showLook(look);
        if (look == RecordingPill.Look.SAVED) {
            // The brief "Ride saved" confirmation keeps the connection's dot colour, and TalkBack hears both.
            CharSequence connection = statusPill.getContentDescription();
            status.setTextColor(ui.text);
            ui.statusRungs(status, RecordingPill.savedRungs(savedAutomatic));
            statusPill.setContentDescription(RecordingPill.savedSpoken(savedAutomatic, connection == null ? null : connection.toString()));
        } else if (look != RecordingPill.Look.STATUS) {
            dot.setBackground(recordingDot(look));
            status.setTextColor(ui.onAccent);
            ui.statusRungs(status, RecordingPill.rungs(recordingMs, link, roomForBoth(recordingMs, link), now, Ui.motionEnabled()));
            long seen = link == RidingFocus.Link.NOT_CONNECTED ? session.lastReadingMs(now) : -1;
            statusPill.setContentDescription(RecordingPill.spoken(recordingMs, link, seen >= 0 ? clock(now - seen) : null));
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

    /** Not ready, and on the way back: the pill's Reconnecting, and the Record disc's "Connecting". */
    private static boolean reconnecting(BleController ble, boolean ready) {
        BleController.State state = ble == null ? null : ble.state();
        return ble != null && !ready && (ble.connected() || ble.recoverableConnectionLoss()
                || state == BleController.State.CONNECTING || state == BleController.State.AUTHENTICATING
                || state == BleController.State.SCANNING || state == BleController.State.WAITING);
    }

    /**
     * The pill's background for its look: the card-coloured connection pill, the solid red recording pill,
     * or, with a link problem, the red pill with an amber edge. Only a change of look touches it, and starts
     * or stops the dot's pulse.
     */
    private void showLook(RecordingPill.Look look) {
        if (look == pillLook) return;
        pillLook = look;
        stopPulse();
        if (look == RecordingPill.Look.STATUS || look == RecordingPill.Look.SAVED) {
            statusPill.setBackground(ui.shape(cardColor(), TOUCH_DP / 2, 0));
            return;
        }
        GradientDrawable fill = ui.shape(ui.accent, TOUCH_DP / 2, 0);
        if (look == RecordingPill.Look.RECORDING_PROBLEM) fill.setStroke(ui.dp(3), ui.orange);
        statusPill.setBackground(fill);
        if (RecordingPill.pulses(look, Ui.motionEnabled())) {
            pulse = ValueAnimator.ofFloat(1f, .35f);
            pulse.setDuration(900);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(a -> dot.setAlpha((Float) a.getAnimatedValue()));
            pulse.start();
        }
    }

    /** Whether the recording pill's short line with a link problem ("12:48 · Not connected") fits, at the line's smallest size. */
    private boolean roomForBoth(long recordingMs, RidingFocus.Link link) {
        if (link == RidingFocus.Link.CONNECTED || status.getWidth() <= 0) return true;
        String line = RecordingPill.both(clock(recordingMs), link)[1];
        android.text.TextPaint paint = new android.text.TextPaint(status.getPaint());
        if (status.getAutoSizeTextType() == TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM) paint.setTextSize(status.getAutoSizeMinTextSize());
        return paint.measureText(line) <= status.getWidth() - status.getCompoundPaddingLeft() - status.getCompoundPaddingRight();
    }

    /** The recording dot: solid white, or a hollow white ring while the link has a problem. */
    private GradientDrawable recordingDot(RecordingPill.Look look) {
        GradientDrawable mark = oval(ui.onAccent);
        if (look == RecordingPill.Look.RECORDING_PROBLEM) {
            mark.setColor(android.graphics.Color.TRANSPARENT);
            mark.setStroke(ui.dp(2), ui.onAccent);
        }
        return mark;
    }

    private void stopPulse() {
        if (pulse != null) pulse.cancel();
        pulse = null;
        dot.setAlpha(1f);
    }

    /** The drive mode word as the scooter shows it ("ECO", "SPORT"), or null when unknown or raw. */
    private String mode(BleController ble) {
        RidingValues.Reading r = session.values.reading("driving_mode", SystemClock.elapsedRealtime());
        if (r == null || r.stale || ble == null) return null;
        String label = Telemetry.drivingModeLabel(ModelRegistry.driveModeLabelProduct(ble.descriptor()), r.value);
        return label == null || Telemetry.rawReading(null, label) || label.equals(Telemetry.drivingModeLabel(0, null)) ? null : label;
    }

    /** Keeps the fastest speed seen, for the ring's and chart's scales: the scooter's trip max, else this screen's own readings. */
    private void noteFastest(long now) {
        RidingValues.Reading max = session.values.reading("maximum_speed_kmh", now);
        Double seen = RidingFrames.fastest(session.values.speedTrace(now, RidingValues.TRACE_KEEP_MS), max == null ? null : max.value);
        if (seen != null && seen > fastestKmh) fastestKmh = seen;
    }

    private void showSpeedLast(long now, String units) {
        // Halo's ring shows "last 18.4" in its own caption.
        if (ring != null) return;
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
            power.setContentDescription(spokenReading(powerName(), null));
            bar.set(null, p.direction, scale, p.peakDrawW, 0, false, now);
            return;
        }
        // Confirmed regen only: inferred regen keeps its side of the bar, but its number and caption claim no direction.
        boolean regen = p.direction == RidingValues.Direction.REGEN && p.watts < 0;
        boolean inferred = RidingPowerCue.applies(p.direction);
        boolean unknown = p.direction == RidingValues.Direction.UNKNOWN || inferred;
        int color = regen ? ui.ok : unknown ? ui.muted : ui.text;
        int rung = inferred ? captionRung() : RidingPowerCue.FULL;
        Formatted shown = Fmt.quantity(Fmt.integerNumber(Math.round(RidingPowerCue.shownWatts(p.watts, p.direction, rung))), Fmt.unit(UnitCode.WATT));
        watts.set(shown, color, regen ? ui.ok : ui.muted);
        // A confirmed caption names only a confirmed peak; an inferred one never becomes it (RidingPowerCue).
        String caption = RidingPowerCue.readoutCaption(p, readoutCaptions);
        String brief = regen ? T.ridingPowerRegenPeakShort() : T.ridingPowerDrawPeakShort();
        if (inferred) showInferredCaption(rung, brief);
        else fit(wattsCaption, caption == null ? brief : caption, brief);
        // Heard as one: the watts as shown and the caption in full, at every rung (an unknown direction always says so).
        power.setContentDescription(spokenReading(powerName(), RidingPowerCue.spokenValue(shown.toString(),
                RidingPowerCue.readoutNote(p.direction, powerDirectionUnknown(), caption == null ? brief : caption))));
        // A same-time sign correction (after 0x79) moves the bar too, not only the number.
        boolean fresh = sample != null && sample.updatedMs != powerSampleMs;
        if (sample != null) powerSampleMs = sample.updatedMs;
        bar.set(p.watts, p.direction, scale, p.peakDrawW, sample == null ? 1000 : sample.intervalMs, fresh, now);
    }

    /** The caption for a power reading whose direction isn't confirmed (no catalog wording for an estimate yet). */
    private static String powerDirectionUnknown() { return T.ridingPowerUnknown(); }

    /** The readout caption's wording, for RidingPowerCue.readoutCaption. */
    private static final RidingPowerCue.Captions readoutCaptions = new RidingPowerCue.Captions() {
        @Override public String unknown() { return powerDirectionUnknown(); }
        @Override public String peak(boolean regen, long watts) {
            String peakText = T.fmtQuantity(Fmt.integer(watts), UnitCode.WATT.symbol());
            return regen ? T.ridingPowerRegenPeak(peakText) : T.ridingPowerDrawPeak(peakText);
        }
    };

    /** How much of the inferred-direction caption the readout can show now; its room is settled once it's laid out. */
    private int captionRung() {
        int room = wattsCaption.getWidth() - wattsCaption.getPaddingLeft() - wattsCaption.getPaddingRight();
        if (room <= 0) wattsCaption.post(() -> { if (!closed && wattsCaption.getWidth() > 0) showPower(SystemClock.elapsedRealtime()); });
        return RidingPowerCue.rung(wattsCaption.getPaint().measureText(powerDirectionUnknown()),
                wattsCaption.getPaint().measureText(RidesScreen.directionUnconfirmedChip()), room);
    }

    /** The caption at its rung; the power column's description says the direction is unknown at every one of them. */
    private void showInferredCaption(int rung, String plain) {
        String pick = rung == RidingPowerCue.FULL ? powerDirectionUnknown() : rung == RidingPowerCue.CUE ? RidesScreen.directionUnconfirmedChip() : plain;
        if (TileRung.needsSet(pick, wattsCaption.getText())) wattsCaption.setText(pick);
    }

    /** A power reading's name, as a tile's label and the trace's key show it. */
    private static String powerName() { return T.ridingLabelPower(); }

    /** What a reading says to a screen reader: its name and value, or its name and that there's no reading. */
    private static String spokenReading(String name, String spoken) {
        return spoken == null ? T.ridingReadingSpokenNone(name) : T.ridingReadingSpoken(name, spoken);
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
        /** Heard after the reading only (a strip chip shows spoken as its text). */
        String spokenNote;
        String spokenFull() { return RidingPowerCue.spokenValue(spoken, spokenNote); }
        /** The name heard for this reading, when it isn't the first rung shown. */
        String spokenName;
        String name() { return spokenName != null ? spokenName : rungs.length == 0 ? "" : rungs[0]; }
        /** An inferred regen reading: a cell loses its minus sign when its label ends up with no cue (RidingPowerCue). */
        Double inferredWatts;
        RidingValues.Band band;
        double percent = Double.NaN;
        /**
         * A clock showing seconds: main is the time to the second, in figures of one width so it never changes
         * size as it ticks, and spoken stays the minute, so a screen reader isn't told of every second (it hears
         * the seconds when it reaches the reading: spokenNow).
         */
        boolean seconds;
        /**
         * A shorter form of main for a spot too tight for the whole: the clock with seconds without its AM/PM (null on a
         * 24-hour clock), or a GPS accuracy state's no-reading dash. Null for every other reading.
         */
        CharSequence mainShort;
        /**
         * In a tile, main gives way to mainShort when it would draw smaller than this (px) and than the tile's own largest
         * size: the clock's 32 dp (RidingClockSeconds), or for a GPS state the size the same state has in a chip. 0: the clock's.
         */
        float shortMinPx;
    }

    /** The clock with seconds, when the tune menu's switch is on. */
    private void showSeconds(Shown s) {
        if (!clockSeconds) return;
        String[] time = DisplayTime.clockSeconds(host.activity(), preferences, System.currentTimeMillis());
        s.seconds = true;
        s.main = time[0];
        s.mainShort = time[1];
    }

    /**
     * What a screen reader hears for a Clock reading with seconds when it reaches it: the time to the second
     * then, whatever rung the reading shows. Null for every other reading, which keeps its description.
     */
    private String spokenNow(String content) {
        if (!clockSeconds || !RidingLayout.CLOCK.equals(content)) return null;
        return spokenReading(T.ridingLabelClock(), DisplayTime.clockSeconds(host.activity(), preferences, System.currentTimeMillis())[0]);
    }

    private Shown shown(String content, long now, String units) {
        Shown s = new Shown();
        s.color = ui.text;
        s.unitColor = ui.muted;
        RidingValues v = session.values;
        s.icon = RidingLayout.icon(content);
        switch (content) {
            case RidingLayout.SPEED: {
                s.rungs = new String[]{T.ridingLabelSpeed()};
                RidingValues.Speed speed = v.speed(now);
                UnitCode unit = speedUnit(units);
                s.suffix = unit.symbol();
                if (speed.kmh == null) { none(s); break; }
                double value = speedIn(speed.kmh, units);
                // Tenths only where the scooter sends them (0x92) or the simulator does; never invented.
                boolean tenths = layout.on(RidingLayout.TENTHS) && (speed.tenths || (service != null && service.simulating()));
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
                RidingValues.Reading battery = v.reading(RidingValues.BATTERY_KEY, now);
                Double percent = battery == null ? null : battery.value;
                RidingFocus.Range range = RidingFocus.range(content, session.range(), scooter == null ? null : scooter.value,
                        scooter == null || scooter.stale, session.learnedKm(percent), percent, battery == null || battery.stale, units);
                // The label names whose estimate it is; a chosen source keeps its name with no reading.
                if (range.kind == RidingFocus.RangeKind.SCOOTER || content.equals(RidingLayout.RANGE_SCOOTER))
                    s.rungs = new String[]{T.ridingLabelRangeScooter(), T.ridingLabelRangeScooterShort()};
                else if (range.kind == RidingFocus.RangeKind.VOLT || content.equals(RidingLayout.RANGE_VOLT))
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
                RidingSession.Trip trip = session.trip();
                s.rungs = new String[]{trip.fromRecording ? T.ridingLabelRideDistance() : T.ridingLabelTrip()};
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
                Long ms = ride ? session.rideTimeMs(now) : v.recordingTimeMs(now);
                if (ms == null) { none(s); break; }
                s.main = clock(ms);
                s.spoken = s.main.toString();
                break;
            }
            case RidingLayout.AVERAGE_SPEED: case RidingLayout.MAX_SPEED: {
                boolean average = content.equals(RidingLayout.AVERAGE_SPEED);
                s.rungs = average ? new String[]{T.ridingLabelAverageSpeed(), T.ridingLabelAverageSpeedShort()}
                        : new String[]{T.ridingLabelMaxSpeed(), T.ridingLabelMaxSpeedShort()};
                Double kmh = average ? session.averageKmh() : session.maxKmh();
                if (kmh == null) { none(s); break; }
                split(s, Fmt.quantity(Fmt.decimalNumber(Locale.US, speedIn(kmh, units), 1), Fmt.unit(speedUnit(units))));
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
                s.main = DisplayTime.clock(host.activity(), preferences, System.currentTimeMillis());
                s.spoken = s.main.toString();
                showSeconds(s);
                break;
            }
            case RidingLayout.POWER: {
                String name = powerName();
                s.rungs = new String[]{name};
                RidingValues.Power p = v.power(now);
                if (p.watts == null) { none(s); break; }
                split(s, Fmt.quantity(Fmt.integerNumber(Math.round(p.watts)), Fmt.unit(UnitCode.WATT)));
                if (p.direction == RidingValues.Direction.REGEN && p.watts < 0) s.color = ui.ok;
                if (RidingPowerCue.applies(p.direction)) {
                    // Its label climbs the same ladder as the readout's caption; Cell.show applies the rung it lands on.
                    s.rungs = new String[]{powerDirectionUnknown(), RidesScreen.directionUnconfirmedChip(), name};
                    s.spokenName = name;
                    s.spokenNote = powerDirectionUnknown();
                    s.inferredWatts = p.watts;
                    s.color = ui.muted;
                }
                break;
            }
            case RidingLayout.GPS_ACCURACY: {
                s.rungs = new String[]{T.ridingLabelGpsAccuracy(), T.ridingLabelGpsAccuracyShort()};
                // Read only: asking never starts GPS or keeps it awake. A fix older than 10 s reads as no fix.
                GpsAccuracy.Reading r = service == null ? GpsAccuracy.reading(false, null, null, now) : service.gpsAccuracy(now);
                if (r.state == GpsAccuracy.State.FIX) {
                    boolean feet = GpsAccuracy.feet(units);
                    split(s, GpsAccuracyText.value(GpsAccuracy.shown(r.metres, feet), GpsAccuracy.unit(feet)));
                } else {
                    // A state steps down to the no-reading dash where its words won't fit: a chip that would step aside
                    // shows the dash (fitChips), and a tile shows it before the words would draw smaller than in a chip.
                    // Spoken, it's always the whole state.
                    s.main = GpsAccuracyText.state(r.state);
                    s.mainShort = GpsAccuracyText.compact(r.state);
                    s.shortMinPx = ui.dp(GpsAccuracyText.STATE_MIN_SP * Math.min(ui.textScale, labelCap));
                    s.spoken = s.main.toString();
                    dim(s);
                }
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
        /** A battery gauge beside or under the number, shown while the cell shows the battery. */
        private BatteryGauge gauge;
        /** How a moved battery's gauge sits here, when it comes and goes with the room; null for Halo's and Trace's tiles. */
        private RidingFocus.Gauge fit;
        private float[] referenceEms;
        Cell(String content, float maxSp, boolean unitBelow) {
            super(host.activity());
            this.content = content;
            setOrientation(VERTICAL);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setFocusable(true);
            label = new Label();
            label.rungChanged = () -> { if (!closed) show(SystemClock.elapsedRealtime(), units()); };
            addView(label, new LayoutParams(-1, -2));
            number = new NumberView(maxSp, unitBelow);
            LayoutParams p = new LayoutParams(-1, 0, 1);
            p.topMargin = ui.dp(2);
            addView(number, p);
        }
        /**
         * The battery's gauge for this spot, only while this cell shows the battery, so another reading
         * keeps the cell's full size. It starts gone and onMeasure shows it where the number can spare the room.
         */
        void fitGauge(RidingFocus.Gauge kind) {
            if (!RidingLayout.BATTERY.equals(content)) return;
            fit = kind;
            if (kind == RidingFocus.Gauge.BESIDE)
                addBeside(new Segments(), new LayoutParams(0, ui.dp(RidingFocus.SEGMENTS_HEIGHT_DP), RidingFocus.SEGMENTS_WEIGHT));
            else if (kind == RidingFocus.Gauge.STRIP_BELOW) addBelow(new Segments(), RidingFocus.STRIP_HEIGHT_DP);
            else addBelow(new Meter(), RidingFocus.BAR_HEIGHT_DP);
            ((View) gauge).setVisibility(GONE);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, heightSpec);
            // Only the exact pass decides: a provisional pass mustn't flip the gauge and ask for another layout.
            if (fit == null || MeasureSpec.getMode(widthSpec) != MeasureSpec.EXACTLY || MeasureSpec.getMode(heightSpec) != MeasureSpec.EXACTLY) return;
            View view = (View) gauge;
            boolean shown = view.getVisibility() == VISIBLE;
            float density = getResources().getDisplayMetrics().density;
            // Beside, read the row's own size: a row answered from its measure cache leaves the number's size from an earlier, shorter pass.
            int boxWidth = line != null ? line.getMeasuredWidth() : number.getMeasuredWidth();
            int boxHeight = line != null ? line.getMeasuredHeight() : number.getMeasuredHeight();
            if (shown && fit != RidingFocus.Gauge.BESIDE) boxHeight += view.getMeasuredHeight() + ui.dp(RidingFocus.BELOW_GAP_DP);
            if (referenceEms == null) referenceEms = number.referenceEms();
            boolean wanted = RidingFocus.gaugeFits(fit, boxWidth / density, boxHeight / density, number.maxPx / density, referenceEms[0], referenceEms[1]);
            if (wanted == shown) return;
            view.setVisibility(wanted ? VISIBLE : GONE);
            super.onMeasure(widthSpec, heightSpec);
        }
        /** A battery gauge beside the number (Focus's segments). */
        void addBeside(BatteryGauge extra, LayoutParams size) {
            removeView(number);
            line = new LinearLayout(host.activity());
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(number, new LayoutParams(0, -1, 1));
            size.setMarginStart(ui.dp(14));
            line.addView((View) extra, size);
            LayoutParams p = new LayoutParams(-1, 0, 1);
            p.topMargin = ui.dp(2);
            addView(line, p);
            gauge = extra;
        }
        /** A battery gauge under the number (Halo's bar). */
        void addBelow(BatteryGauge extra) { addBelow(extra, RidingFocus.BAR_HEIGHT_DP); }
        /** A gauge straight under the number, above whatever else the cell holds. */
        void addBelow(BatteryGauge extra, int heightDp) {
            LayoutParams p = new LayoutParams(-1, ui.dp(heightDp));
            p.topMargin = ui.dp(RidingFocus.BELOW_GAP_DP);
            addView((View) extra, indexOfChild(number) + 1, p);
            gauge = extra;
        }
        /** A rounded card behind the cell (Halo's and Trace's tiles). */
        void card() {
            setBackground(ui.shape(cardColor(), 20, 0));
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
            // A tall card keeps its number at its middle, not stranded under the name.
            number.middle = true;
        }
        /** No name above the number: the ring and the big speed speak for themselves (spoken names stay). */
        void hideLabel() { label.setVisibility(GONE); }
        /** The name beside the number on one line, as in Halo's strip. */
        void inline() {
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            label.setLayoutParams(new LayoutParams(0, -2, .9f));
            LayoutParams p = new LayoutParams(0, -1, 1.1f);
            p.setMarginStart(ui.dp(8));
            number.setLayoutParams(p);
            number.middle = true;
        }
        void show(long now, String units) {
            Shown s = shown(content, now, units);
            label.set(s.icon, s.inferredWatts != null, s.rungs);
            if (s.inferredWatts != null) {
                int rung = RidingPowerCue.rungOfLabel(label.picked);
                split(s, Fmt.quantity(Fmt.integerNumber(Math.round(RidingPowerCue.shownWatts(s.inferredWatts, RidingValues.Direction.REGEN_INFERRED, rung))), Fmt.unit(UnitCode.WATT)));
            }
            number.clock(s.seconds, s.mainShort, s.shortMinPx > 0 ? s.shortMinPx : ui.dp(RidingClockSeconds.MARKER_MIN_DP));
            number.set(s.prefix, s.main, s.fraction, s.suffix, s.color, s.unitColor);
            String name = s.name();
            setContentDescription(spokenReading(name, s.spoken == null ? null : s.spokenFull()));
            if (gauge != null) {
                boolean battery = RidingLayout.BATTERY.equals(content);
                if (fit == null) ((View) gauge).setVisibility(battery ? VISIBLE : INVISIBLE);
                if (battery) gauge.set(s.percent, s.band, s.color == ui.dim);
            }
        }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            String now = spokenNow(content);
            if (now != null) info.setContentDescription(now);
        }
    }

    /**
     * Readings whose names share one presentation (RidingNameGroup): Halo's strip of small readings, and the
     * two tiles under its ring (not the full-width range above them, which decides alone). When one of them must fall back to its icon, every one that has an icon shows it,
     * so a word and an icon never sit side by side because one language's word is longer.
     */
    private static final class NameGroup {
        private final List<Label> members = new ArrayList<>();
        private final List<String> contents = new ArrayList<>();
        void add(Cell cell) {
            members.add(cell.label);
            contents.add(cell.content);
            cell.label.group = this;
        }
        /** Works out the group's choice from each member's own ladder and shows it on every member. */
        void settle() {
            int n = members.size();
            int[] fits = new int[n];
            String[] icons = new String[n];
            for (int i = 0; i < n; i++) {
                Label label = members.get(i);
                fits[i] = label.getVisibility() == View.VISIBLE ? label.fit : RidingNameGroup.UNMEASURED;
                icons[i] = label.groupIcon();
            }
            boolean shared = RidingNameGroup.icons(fits, icons, contents.toArray(new String[0]));
            for (Label label : members) label.apply(shared);
        }
    }

    /**
     * A reading's name on the narrow-label ladder: the first rung that fits at full size, then its icon
     * alone when one is named, never a smaller or broken word. In a NameGroup, icon or name is the group's choice.
     */
    private final class Label extends TextView {
        private String[] rungs = new String[0];
        private String icon;
        /** The rung on show, or -1 for its icon alone, no room or a hidden label. */
        int picked = -1;
        /** This label's own ladder choice (RidingNameGroup's fit), before its group has its say. */
        private int fit = RidingNameGroup.UNMEASURED;
        /** The readings it shares a presentation with, or null for a label on its own. */
        NameGroup group;
        /**
         * Its first rungs carry a cue beyond the name (inferred power's "direction unknown" caption and short cue),
         * which the number's sign depends on: while one of those fits it keeps it, whatever its group shows; at its
         * plain name, or with no rung that fits, it follows its group like any other reading.
         */
        private boolean cue;
        /** Runs when a change of width moves the label to another rung, so what depends on the rung follows at once. */
        Runnable rungChanged;
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
        void set(String icon, boolean cue, String... rungs) {
            if (java.util.Arrays.equals(this.rungs, rungs) && TextUtils.equals(this.icon, icon) && this.cue == cue) return;
            this.rungs = rungs;
            this.icon = icon;
            this.cue = cue;
            fit();
        }
        /** The icon it offers its group's choice: none while a rung carrying its cue fits (RidingNameGroup.offered). */
        String groupIcon() { return RidingNameGroup.offered(icon, cue ? RidingPowerCue.NONE : 0, fit); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (w != ow) post(this::fit);
        }
        /** Measures this label's own ladder, then shows it as its group decides (or on its own). */
        private void fit() {
            fit = RidingNameGroup.UNMEASURED;
            int room = getWidth() - getPaddingLeft() - getPaddingRight();
            if (rungs.length > 0 && room > 0) {
                float[] widths = new float[rungs.length];
                for (int i = 0; i < rungs.length; i++) widths[i] = getPaint().measureText(rungs[i]);
                fit = TileRung.first(widths, room);
            }
            if (group != null) group.settle();
            else apply(RidingNameGroup.alone(fit, groupIcon()));
        }
        /** Shows the rung or icon this label takes when its group shows icons (or not); the rung's dependants follow. */
        void apply(boolean icons) {
            int before = picked;
            picked = -1;
            if (rungs.length == 0) setText("");
            else {
                int shown = RidingNameGroup.shown(fit, rungs.length, groupIcon() != null, icons);
                if (shown == RidingNameGroup.ICON) show("", true);
                else {
                    show(rungs[shown], false);
                    if (fit >= 0) picked = shown;
                }
            }
            // A caller that set new names reads picked at once; the others (a width change, or a neighbour's
            // change of mind) hear of it here, after this pass, so nothing re-enters a refresh.
            if (picked != before && rungChanged != null) post(() -> { if (rungChanged != null) rungChanged.run(); });
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
        final float maxPx;
        private final boolean unitBelow;
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private CharSequence prefix = "", main = "", fraction = "", suffix = "";
        private int color, unitColor;
        private float baseline;
        /** Centred across its box (inside Halo's ring), or set at its vertical middle (a strip's one line). */
        boolean centered, middle;
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
        /** A clock with seconds is drawn with the font's tabular figures and its AM/PM can step aside. */
        private boolean tabular;
        private CharSequence mainShort;
        private float shortMinPx;
        /**
         * Draws the clock with seconds (true) in figures of one width, so the reading never changes width as it
         * ticks. shortMain is a shorter form of the reading (the time without its AM/PM, a GPS state's dash), drawn
         * when the whole of it would draw smaller than minPx and than this spot's largest size.
         */
        void clock(boolean seconds, CharSequence shortMain, float minPx) {
            if (tabular == seconds && TextUtils.equals(mainShort, shortMain) && shortMinPx == minPx) return;
            tabular = seconds;
            mainShort = shortMain;
            shortMinPx = minPx;
            big.setFontFeatureSettings(seconds ? "tnum" : null);
            invalidate();
        }
        /** The size main draws at: maxPx, or less until it fits this view. */
        private float fitSize(CharSequence main, float w, float h) {
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
            return size;
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
            CharSequence main = this.main;
            float size = fitSize(main, w, h);
            // A clock with seconds too tight for its AM/PM drops the marker, never the seconds, before it gets small; a GPS
            // state too long for its tile shows the dash before its words get smaller than in a chip.
            if (mainShort != null && !RidingClockSeconds.keepsMarker(size, maxPx, shortMinPx)) {
                main = mainShort;
                size = fitSize(main, w, h);
            }
            float fractionShare = .46f, unitShare = unitBelow ? .30f : .42f;
            big.setTextSize(size);
            small.setTextSize(size * unitShare);
            float cap = capHeight(big);
            baseline = cap + ui.dp(2);
            if (middle) baseline = (h + cap) / 2;
            float x = 0;
            float lineWidth = small.measureText(prefix, 0, prefix.length()) + big.measureText(main, 0, main.length())
                    + measureFraction(size * fractionShare) + (unitBelow ? 0 : gap() + small.measureText(suffix, 0, suffix.length()));
            float unitX = 0;
            if (centered) {
                x = Math.max(0, (w - lineWidth) / 2);
                unitX = Math.max(0, (w - small.measureText(suffix, 0, suffix.length())) / 2);
            }
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
                if (unitBelow) canvas.drawText(suffix, 0, suffix.length(), unitX, baseline + capHeight(small) * 1.9f, small);
                else canvas.drawText(suffix, 0, suffix.length(), x + gap(), baseline, small);
            }
        }
        /**
         * A fixed two-digit reading's width and height per pixel of number size, measured as onDraw does.
         * Fit decisions use this rather than the current digits, so a gauge never comes and goes as the battery drains.
         */
        float[] referenceEms() {
            Shown s = new Shown();
            split(s, Fmt.percent(Fmt.integerNumber(88)));
            float unitShare = unitBelow ? .30f : .42f;
            Paint b = new Paint(big), u = new Paint(small);
            b.setTextSize(100);
            u.setTextSize(100 * unitShare);
            float unit = u.measureText(s.suffix, 0, s.suffix.length());
            float width = u.measureText(s.prefix, 0, s.prefix.length()) + b.measureText(s.main, 0, s.main.length()) + (unitBelow ? 0 : unit);
            float height = capHeight(b);
            if (unitBelow) { width = Math.max(width, unit); height += capHeight(u) * 1.9f; }
            return new float[]{width / 100, height / 100};
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
    /** A battery gauge drawn beside or under a battery reading, in its band's colour. */
    private interface BatteryGauge { void set(double percent, RidingValues.Band band, boolean stale); }

    private int bandColor(RidingValues.Band band, boolean stale) {
        if (stale) return ui.dim;
        return band == RidingValues.Band.RED ? ui.danger : band == RidingValues.Band.AMBER ? ui.orange : ui.ok;
    }

    private final class Segments extends View implements BatteryGauge {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int filled;
        private int color;
        Segments() {
            super(host.activity());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override public void set(double percent, RidingValues.Band band, boolean stale) {
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
     * to the reading's interval, and the bar never runs ahead of the latest real reading. A sign
     * correction moves the bar as well, and a change of sign starts from the zero line, so the bar
     * never shows draw while the number shows regen.
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
        /** intervalMs is the reading's interval; fresh is true for a new reading, false for the latest one again (or corrected). */
        void set(Double watts, RidingValues.Direction direction, RidingValues.PowerScale scale, double peakDraw, long intervalMs, boolean fresh, long now) {
            present = watts != null;
            this.direction = direction;
            drawScale = scale.drawW;
            regenScale = scale.regenW;
            peak = peakDraw;
            if (watts == null) smoother.snap(0, now);
            else smoother.follow(watts, Math.max(1, intervalMs), fresh, now);
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
            } else if (value >= 0 || !direction.regen()) {
                double magnitude = Math.abs(value);
                float top = (float) (zero - (zero - inset) * Math.min(1, magnitude / Math.max(1, drawScale)));
                if (direction == RidingValues.Direction.UNKNOWN) paint.setColor(ui.muted);
                else paint.setShader(new LinearGradient(0, zero, 0, inset, ui.accentDeep, lighter(ui.accent), Shader.TileMode.CLAMP));
                if (zero - top > 1) canvas.drawRoundRect(new RectF(inset, top, w - inset, zero), radius - inset, radius - inset, paint);
                paint.setShader(null);
            } else {
                float bottom = (float) (zero + (h - inset - zero) * Math.min(1, -value / Math.max(1, regenScale)));
                // Green is for regen the scooter confirmed; an inferred reading keeps the side, not the colour.
                paint.setColor(direction == RidingValues.Direction.REGEN ? ui.ok : ui.muted);
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

    /** Halo's battery bar under the tile's number. */
    private final class Meter extends View implements BatteryGauge {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float share;
        private int color;
        Meter() {
            super(host.activity());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override public void set(double percent, RidingValues.Band band, boolean stale) {
            float next = RidingFrames.share(Double.isFinite(percent) ? percent : null, 100);
            int tint = bandColor(band, stale);
            if (next == share && tint == color) return;
            share = next;
            color = tint;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            paint.setColor(ui.dark ? ui.raised : ui.outline);
            canvas.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, paint);
            if (share <= 0) return;
            paint.setColor(color);
            canvas.drawRoundRect(new RectF(0, 0, Math.max(h, w * share), h), h / 2, h / 2, paint);
        }
    }

    /**
     * Fits a group's chips to room (RidingChipFit): side by side with gaps (Halo's row), or stacked (Trace's column beside the
     * big number). The drive mode's letter spacing goes first, then the reading names shorten to their short form and then
     * to an icon, then the decorative icons go, then some padding, and only then a chip. The whole group uses one rung, and
     * a chip never shows cut. A name or icon another chip in the group also carries is skipped for a longer one, so two
     * readings never look alike.
     */
    private void fitChips(ViewGroup group, int room, boolean stacked) {
        List<Chip> wanted = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Chip && ((Chip) child).wanted) wanted.add((Chip) child);
        }
        int n = wanted.size();
        String[][] marks = new String[n][];
        String[] contents = new String[n];
        boolean[] canStep = new boolean[n];
        for (int i = 0; i < n; i++) { marks[i] = wanted.get(i).marks(); contents[i] = wanted.get(i).content; canStep[i] = wanted.get(i).compact != null; }
        // The fit works out names for each set of chips it tries, among the chips in that set (a chip that stepped aside
        // holds no mark); widths come from the probes, never from what's shown, so measuring the result again gives it again.
        final int[][][][] measured = new int[2][n][RidingChipFit.RUNGS][RidingChipFit.NAME_ICON + 2];
        RidingChipFit.Widths widths = (chip, rung, step) -> chipWidth(measured[0], wanted.get(chip), chip, rung, step, false);
        // A GPS state that would step aside in full shows its dash instead (RidingChipFit.stepping): the chip the rider chose stays.
        RidingChipFit.Widths compactWidths = (chip, rung, step) -> chipWidth(measured[1], wanted.get(chip), chip, rung, step, true);
        int gap = ui.dp(8);
        RidingChipFit fit = RidingChipFit.stepping(marks, contents, widths, compactWidths, canStep, stacked, gap, room);
        int shown = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!(child instanceof Chip)) continue;
            Chip chip = (Chip) child;
            int at = wanted.indexOf(chip), want = View.GONE;
            if (at >= 0 && fit.shown[at]) {
                want = View.VISIBLE;
                chip.useRung(fit.rung, fit.steps[at], fit.compact[at]);
                // The gaps go by what's shown, not by slot: if an earlier chip stepped aside, the first one left starts at the edge.
                setLeadingMargin(group, chip, shown == 0 ? 0 : gap, stacked);
                shown++;
            }
            if (chip.getVisibility() != want) chip.setVisibility(want);
        }
    }

    private static int chipWidth(int[][][] measured, Chip chip, int at, int rung, int step, boolean compact) {
        int[] cell = measured[at][rung];
        if (cell[step + 1] == 0) cell[step + 1] = chip.widthAt(rung, step, compact) + 1;
        return cell[step + 1] - 1;
    }

    private static void setLeadingMargin(ViewGroup group, View chip, int margin, boolean stacked) {
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) chip.getLayoutParams();
        if (stacked) {
            if (p.topMargin != margin) p.topMargin = margin;
            return;
        }
        if (p.getMarginStart() == margin) return;
        p.setMarginStart(margin);
        // LinearLayout measures with the left and right margins, and only the child's first measure would resolve them.
        p.resolveLayoutDirection(group.getLayoutDirection());
    }

    /** Halo's row of chips, side by side (fitChips). */
    private final class ChipRow extends LinearLayout {
        ChipRow() {
            super(host.activity());
            setOrientation(HORIZONTAL);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int room = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : MeasureSpec.getSize(widthSpec);
            fitChips(this, room, false);
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /**
     * Trace's chips, stacked beside the big number (fitChips). They take at most CHIP_COLUMN_SHARE of the row, so a long
     * name shortens, or its chip steps aside, before it can squeeze the speed. The chips that name themselves (drive mode,
     * temperature, clock) always keep the room they had before the share existed, so the default column looks as it did.
     */
    private final class ChipColumn extends LinearLayout {
        ChipColumn() {
            super(host.activity());
            setOrientation(VERTICAL);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int room = Integer.MAX_VALUE;
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                int size = MeasureSpec.getSize(widthSpec);
                room = (int) (size * CHIP_COLUMN_SHARE);
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (!(child instanceof Chip)) continue;
                    Chip chip = (Chip) child;
                    if (chip.wanted && chip.namesItself) room = Math.max(room, Math.min(size, chip.widthAt(RidingChipFit.FULL, -1)));
                }
            }
            fitChips(this, room, true);
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /**
     * A small reading in a pill. Drive mode, battery temperature and the clock name themselves ("SPORT" in red like the
     * scooter's own badge, the thermometer or clock face with "31 °C" or "12:48") and look as they always have. Every other
     * reading carries its name ahead of its value, the tile's own names: the full one ("Average speed"), the short one
     * ("Avg"), then the tile's icon when that's narrower and no other chip in the group has it. It shows a value only; it
     * takes no taps. Spoken, it's always the reading's full name and its value, whatever the rung.
     */
    private final class Chip extends LinearLayout {
        private static final float MODE_SPACING = .06f;
        private static final int START_PADDING_DP = 14, END_PADDING_DP = 16, TIGHT_PADDING_DP = 6, ICON_DP = 18, ICON_GAP_DP = 6;
        final String content;
        private final TextView name, text;
        /** Measure a name and the value at each rung; off screen, so trying a rung never disturbs the layout. */
        private final TextView nameProbe, probe;
        /** The thermometer or clock face of a chip that names itself; the reading's icon, a rung of its name, on the others. */
        private final View icon;
        private final String iconKey;
        private final boolean mode, namesItself;
        /** The reading's names, full and short (the same when it has one name), from the same catalog its tile uses. */
        private String[] names = new String[0];
        /** False for a chip with nothing to say (no drive mode yet): it steps aside. */
        boolean wanted = true;
        /** The reading, and the clock with seconds without its AM/PM for the tightest rung (null for any other). */
        private String full, tight;
        /** A GPS state's dash, shown in place of its words when in full the chip would step aside (fitChips); null otherwise. */
        String compact;
        private boolean compactShown;
        private int rung = RidingChipFit.FULL;
        Chip(String content) {
            super(host.activity());
            this.content = content;
            mode = RidingLayout.DRIVE_MODE.equals(content);
            namesItself = RidingLayout.chipNamesItself(content);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setFocusable(true);
            setMinimumHeight(ui.dp(40));
            setPaddingRelative(ui.dp(START_PADDING_DP), ui.dp(4), ui.dp(END_PADDING_DP), ui.dp(4));
            setBackground(ui.shape(mode ? ui.accent : cardColor(), 20, 0));
            // The same icon its tile shows: the thermometer, the clock face, the speedometer and so on. Drive mode's chip is
            // the scooter's red badge, as it always was, so its tile's gear-shift icon stays off it.
            iconKey = mode ? null : shown(content, SystemClock.elapsedRealtime(), units()).icon;
            icon = iconKey == null ? null : ui.icon(iconKey, ICON_DP, ui.muted);
            if (icon != null) {
                LayoutParams p = new LayoutParams(ui.dp(ICON_DP), ui.dp(ICON_DP));
                p.setMarginEnd(ui.dp(ICON_GAP_DP));
                addView(icon, p);
                if (!namesItself) icon.setVisibility(GONE);
            }
            name = nameLabel();
            name.setVisibility(namesItself ? GONE : VISIBLE);
            LayoutParams nameSize = new LayoutParams(-2, -2);
            nameSize.setMarginEnd(ui.dp(ICON_GAP_DP));
            addView(name, nameSize);
            chrome.add(name);
            nameProbe = nameLabel();
            nameProbe.setLayoutParams(new LayoutParams(-2, -2));
            text = label();
            text.setTextColor(mode ? ui.onAccent : ui.text);
            text.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (mode) text.setLetterSpacing(MODE_SPACING);
            addView(text, new LayoutParams(-2, -2));
            probe = label();
            // TextView needs layout params to re-measure after a text change, even off screen.
            probe.setLayoutParams(new LayoutParams(-2, -2));
            if (clockSeconds && RidingLayout.CLOCK.equals(content)) {
                // Figures of one width, so the chip never changes width as the seconds tick.
                text.setFontFeatureSettings("tnum");
                probe.setFontFeatureSettings("tnum");
            }
        }
        /**
         * The text this chip shows at a rung: the clock's AM/PM goes on the tightest one, before the chip would; a GPS
         * state shows its dash when the fit asks for its compact form.
         */
        private CharSequence textAt(int rung, boolean compactForm) {
            if (compactForm && compact != null) return compact;
            return rung == RidingChipFit.TIGHT && tight != null ? tight : full != null ? full : text.getText();
        }
        private TextView label() {
            TextView label = ui.text(T.ridingNoValue(), labelSp(17), true);
            label.setTypeface(ui.bold());
            label.setMaxLines(1);
            return label;
        }
        /** The reading's name, as a tile's label: muted, never smaller than the label size and never broken. */
        private TextView nameLabel() {
            TextView label = ui.text("", labelSp(LABEL_SP), false);
            label.setTypeface(ui.medium());
            label.setTextColor(ui.muted);
            label.setMaxLines(1);
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            return label;
        }
        /** This chip's marks for RidingChipFit.nameStep: its full name, short name and icon; a chip that names itself, its icon. */
        String[] marks() {
            String iconMark = RidingChipFit.iconMark(iconKey);
            if (namesItself || names.length == 0) return new String[]{null, null, iconMark};
            return new String[]{names[0], names[names.length - 1], iconMark};
        }
        private int measured(TextView probe, CharSequence value) {
            if (!TextUtils.equals(probe.getText(), value)) probe.setText(value);
            probe.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            return probe.getMeasuredWidth();
        }
        /** The name text a step shows, or null at no name. */
        private String nameAt(int step) { return step < 0 || names.length == 0 ? null : names[Math.min(step, names.length - 1)]; }
        /** True when a step shows the icon in place of a name: it has one, and it's narrower than the short name. */
        private boolean iconAt(int step) {
            if (step != RidingChipFit.NAME_ICON || icon == null || names.length == 0) return false;
            return ui.dp(ICON_DP) < measured(nameProbe, names[names.length - 1]);
        }
        /** This chip's whole width at a RidingChipFit rung and name step, with the text it shows there (textAt). */
        int widthAt(int rung, int step) { return widthAt(rung, step, false); }
        /** The same, with its compact value (a GPS state's dash) when compactForm. */
        int widthAt(int rung, int step, boolean compactForm) {
            probe.setLetterSpacing(mode && rung == RidingChipFit.FULL ? MODE_SPACING : 0);
            int value = measured(probe, textAt(rung, compactForm));
            int padding = rung == RidingChipFit.TIGHT ? ui.dp(TIGHT_PADDING_DP) * 2 : ui.dp(START_PADDING_DP) + ui.dp(END_PADDING_DP);
            int mark = 0;
            if (namesItself) mark = icon != null && rung < RidingChipFit.NO_ICONS ? ui.dp(ICON_DP) + ui.dp(ICON_GAP_DP) : 0;
            else if (iconAt(step)) mark = ui.dp(ICON_DP) + ui.dp(ICON_GAP_DP);
            else if (nameAt(step) != null) mark = measured(nameProbe, nameAt(step)) + ui.dp(ICON_GAP_DP);
            return padding + mark + value;
        }
        /** The name step this chip last took, beside its rung (-1 before its first fit, or with no name). */
        int shownStep = -1;
        /** Shows this chip at a RidingChipFit rung and name step. Setting the same again changes nothing, so measuring settles. */
        void useRung(int rung, int step, boolean compactForm) {
            this.rung = rung;
            shownStep = step;
            compactShown = compactForm;
            CharSequence shown = textAt(rung, compactForm);
            if (!TextUtils.equals(text.getText(), shown)) text.setText(shown);
            if (mode) text.setLetterSpacing(rung == RidingChipFit.FULL ? MODE_SPACING : 0);
            boolean iconShown, nameShown;
            if (namesItself) { iconShown = rung < RidingChipFit.NO_ICONS; nameShown = false; }
            else {
                iconShown = iconAt(step);
                String shownName = iconShown ? null : nameAt(step);
                nameShown = shownName != null;
                if (nameShown && !TextUtils.equals(name.getText(), shownName)) name.setText(shownName);
            }
            if (icon != null && icon.getVisibility() != (iconShown ? VISIBLE : GONE)) icon.setVisibility(iconShown ? VISIBLE : GONE);
            if (name.getVisibility() != (nameShown ? VISIBLE : GONE)) name.setVisibility(nameShown ? VISIBLE : GONE);
            boolean tight = rung == RidingChipFit.TIGHT;
            int start = ui.dp(tight ? TIGHT_PADDING_DP : START_PADDING_DP), end = ui.dp(tight ? TIGHT_PADDING_DP : END_PADDING_DP);
            if (getPaddingStart() != start || getPaddingEnd() != end) setPaddingRelative(start, ui.dp(4), end, ui.dp(4));
        }
        /**
         * The reading's names, full then short, from its tile's ladder. Power keeps its own name (its tile's ladder starts
         * with the direction cue, which a chip says aloud instead), and Efficiency its name alone: its tile's short rung is
         * its unit, which the value already shows.
         */
        private String[] names(Shown s) {
            if (RidingLayout.POWER.equals(content)) return new String[]{powerName()};
            if (s.rungs.length == 0) return new String[0];
            if (RidingLayout.EFFICIENCY.equals(content)) return new String[]{s.rungs[0]};
            return new String[]{s.rungs[0], s.rungs[s.rungs.length - 1]};
        }
        void show(long now, String units) {
            Shown s = shown(content, now, units);
            if (s.inferredWatts != null) {
                // An inferred regen reading: a chip has no room for the "direction unknown" cue, so it shows the size alone
                // (RidingPowerCue's last rung), and the cue is heard with it.
                split(s, Fmt.quantity(Fmt.integerNumber(Math.round(RidingPowerCue.shownWatts(s.inferredWatts,
                        RidingValues.Direction.REGEN_INFERRED, RidingPowerCue.NONE))), Fmt.unit(UnitCode.WATT)));
            }
            if (!namesItself) {
                String[] fresh = names(s);
                if (!java.util.Arrays.equals(fresh, names)) {
                    names = fresh;
                    // Name the reading at once, before the group measures it to a rung.
                    if (!TextUtils.equals(name.getText(), names[0])) name.setText(names[0]);
                    requestLayout();
                }
            }
            // The formatted reading as it's spoken ("31 °C", "SPORT", "12:48", "14.2 mph"), or the dash.
            String value = s.spoken != null ? s.spoken : s.main.toString();
            // The clock with seconds shows its time to the second; a screen reader still hears the minute.
            if (s.seconds) value = s.main.toString();
            full = value;
            tight = s.seconds && s.mainShort != null ? s.mainShort.toString() : null;
            String stepDown = !s.seconds && s.mainShort != null ? s.mainShort.toString() : null;
            if (!TextUtils.equals(stepDown, compact)) { compact = stepDown; requestLayout(); }
            CharSequence shown = textAt(rung, compactShown);
            if (!TextUtils.equals(text.getText(), shown)) text.setText(shown);
            int color = mode ? ui.onAccent : s.color;
            if (text.getCurrentTextColor() != color) text.setTextColor(color);
            // A mode chip with no mode would be an empty red badge: it steps aside until the scooter says.
            boolean want = !(mode && s.spoken == null);
            if (want != wanted) { wanted = want; requestLayout(); }
            if (!(getParent() instanceof ChipRow) && !(getParent() instanceof ChipColumn)) {
                int visible = want ? VISIBLE : GONE;
                if (getVisibility() != visible) setVisibility(visible);
            }
            String spokenName = s.rungs.length > 0 ? s.name() : s.main.toString();
            setContentDescription(spokenReading(spokenName, s.spoken == null ? null : s.spokenFull()));
        }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            String now = spokenNow(content);
            if (now != null) info.setContentDescription(now);
        }
    }

    /**
     * Halo's ring around the big number: a 270° arc open at the bottom, as the dashboard's gauge.
     * For speed it fills to the current speed on a scale that starts at 20 mph (30 km/h) and grows
     * to the fastest speed seen, a white tick marks this ride's max and a dot its average, and the
     * caption under the number says "max 22.1". It can show battery or power instead; for battery the caption
     * says its percentage, the way the speed ring's says the max. Each new
     * reading glides in (GaugeSmoother) and never runs ahead of the latest real one.
     */
    private final class Ring extends ViewGroup {
        private final String content;
        private final Cell number;
        private final TextView caption;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GaugeSmoother smoother = new GaugeSmoother();
        private long sampleMs = -1;
        private boolean present;
        private int color;
        private float maxShare = -1, averageShare = -1;
        Ring(String content, Cell number, TextView caption) {
            super(host.activity());
            this.content = content;
            this.number = number;
            this.caption = caption;
            setWillNotDraw(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(number);
            caption.setGravity(Gravity.CENTER);
            caption.setTextColor(ui.muted);
            caption.setVisibility(INVISIBLE);
            addView(caption);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }
        private float size() { return Math.min(getWidth(), getHeight()); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(w, h);
            int s = Math.min(w, h);
            number.measure(MeasureSpec.makeMeasureSpec(Math.round(s * .64f), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(s * .46f), MeasureSpec.EXACTLY));
            caption.measure(MeasureSpec.makeMeasureSpec(Math.round(s * .62f), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t, s = Math.min(w, h);
            float top = (h - s) / 2f, cx = w / 2f;
            int nw = number.getMeasuredWidth(), nh = number.getMeasuredHeight();
            int ny = Math.round(top + s * .49f - nh / 2f);
            number.layout(Math.round(cx - nw / 2f), ny, Math.round(cx + nw / 2f), ny + nh);
            int cw = caption.getMeasuredWidth(), ch = caption.getMeasuredHeight();
            int cy = Math.round(top + s * .86f - ch / 2f);
            caption.layout(Math.round(cx - cw / 2f), cy, Math.round(cx + cw / 2f), cy + ch);
        }
        void show(long now, String units) {
            RidingValues v = session.values;
            Double value = null;
            double top = 1;
            long interval = -1;
            boolean regenPower = false;
            RidingValues.Reading sample = null;
            int tint = ui.accent;
            String text = null, spoken = null;
            boolean dimCaption = false;
            maxShare = -1;
            averageShare = -1;
            if (RidingLayout.BATTERY.equals(content)) {
                sample = v.reading(RidingValues.BATTERY_KEY, now);
                if (sample != null) {
                    value = sample.value;
                    tint = bandColor(RidingValues.band(sample.value), sample.stale);
                    // Its percentage under the speed's unit, where the speed ring shows this ride's max (a tester asked, owner agreed).
                    // The same reading the battery tile shows, and its name for a screen reader.
                    Shown battery = shown(RidingLayout.BATTERY, now, units);
                    text = battery.spoken;
                    if (text != null) spoken = spokenReading(battery.name(), text);
                    dimCaption = sample.stale;
                }
                top = 100;
            } else if (RidingLayout.POWER.equals(content)) {
                RidingValues.Power p = v.power(now);
                sample = v.reading(RidingValues.POWER_KEY, now);
                if (p.watts != null) {
                    value = Math.abs(p.watts);
                    boolean regen = p.direction.regen() && p.watts < 0;
                    regenPower = regen;
                    tint = regen && p.direction == RidingValues.Direction.REGEN ? ui.ok
                            : p.direction == RidingValues.Direction.UNKNOWN || p.direction == RidingValues.Direction.REGEN_INFERRED ? ui.muted : ui.accent;
                    top = regen ? v.scale().regenW : v.scale().drawW;
                }
            } else {
                RidingValues.Speed speed = v.speed(now);
                sample = v.reading(RidingValues.SPEED_KEY, now);
                top = RidingFrames.ringTop(fastestKmh > 0 ? fastestKmh : null, units);
                if (speed.kmh != null) { value = RidingFocus.distance(speed.kmh, units); if (speed.stale) tint = ui.dim; }
                Double maxKmh = session.maxKmh(), averageKmh = session.averageKmh();
                if (maxKmh != null) maxShare = RidingFrames.share(RidingFocus.distance(maxKmh, units), top);
                if (averageKmh != null) averageShare = RidingFrames.share(RidingFocus.distance(averageKmh, units), top);
                if (speed.kmh == null && speed.lastKmh != null)
                    text = T.ridingSpeedLast(T.fmtQuantity(Fmt.decimal(Locale.US, RidingFocus.distance(speed.lastKmh, units), 1), speedUnit(units).symbol()));
                else if (maxKmh != null)
                    text = T.ridingRingMax(T.fmtQuantity(Fmt.decimal(Locale.US, RidingFocus.distance(maxKmh, units), 1), speedUnit(units).symbol()));
            }
            if (text == null) caption.setVisibility(INVISIBLE);
            else {
                if (!TextUtils.equals(caption.getText(), text)) caption.setText(text);
                // A bare percentage says what it is aloud: the battery's name, then its value.
                if (!TextUtils.equals(caption.getContentDescription(), spoken)) caption.setContentDescription(spoken);
                dimCaption |= RidingLayout.RING_SPEED.equals(content) && v.speed(now).kmh == null;
                caption.setTextColor(dimCaption ? ui.dim : ui.muted);
                caption.setVisibility(VISIBLE);
            }
            color = tint;
            present = value != null;
            float share = RidingFrames.share(value, top);
            if (!present) smoother.snap(0, now);
            else if (sample != null && RidingLayout.POWER.equals(content)) {
                // Power follows sign corrections; regen is followed as a negative share, so a change of sign restarts from empty.
                boolean fresh = sample.updatedMs != sampleMs;
                sampleMs = sample.updatedMs;
                smoother.follow(regenPower ? -share : share, Math.max(1, sample.intervalMs), fresh, now);
            } else if (sample != null && sample.updatedMs != sampleMs) {
                sampleMs = sample.updatedMs;
                smoother.setTarget(share, Math.max(1, sample.intervalMs), now);
            } else if (!smoother.started()) smoother.snap(share, now);
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float s = size();
            if (s <= 0) return;
            float stroke = s * .065f;
            float cx = getWidth() / 2f, cy = (getHeight() - s) / 2f + s / 2f;
            RectF box = new RectF(cx - s / 2 + stroke, cy - s / 2 + stroke, cx + s / 2 - stroke, cy + s / 2 - stroke);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(cardColor());
            canvas.drawArc(box, RidingFrames.RING_START_DEG, RidingFrames.RING_SWEEP_DEG, false, paint);
            // Scale ticks inside the arc, at each sixth of the sweep.
            paint.setColor(ui.outline);
            paint.setStrokeWidth(Math.max(1, ui.dp(2)));
            float radius = box.width() / 2;
            for (int i = 0; i <= 6; i++) {
                double a = Math.toRadians(RidingFrames.RING_START_DEG + RidingFrames.RING_SWEEP_DEG * i / 6f);
                float r1 = radius - stroke * 1.25f, r2 = r1 - stroke * .55f;
                canvas.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1, cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, paint);
            }
            long now = SystemClock.elapsedRealtime();
            // Regen power is followed as a negative share (Ring.show); the arc is its size.
            double share = present && smoother.started() ? Math.min(1, Math.abs(smoother.value(now))) : 0;
            paint.setStrokeWidth(stroke);
            if (present && share > .002) {
                if (color == ui.accent) paint.setShader(new LinearGradient(0, box.bottom, 0, box.top, ui.accentDeep, ui.accent, Shader.TileMode.CLAMP));
                else paint.setColor(color);
                canvas.drawArc(box, RidingFrames.RING_START_DEG, (float) (RidingFrames.RING_SWEEP_DEG * share), false, paint);
                paint.setShader(null);
            }
            // This ride's max as a white tick across the track, its average as a dot on it.
            if (maxShare >= 0) {
                double a = Math.toRadians(RidingFrames.ringAngle(maxShare));
                float r1 = radius + stroke * .85f, r2 = radius - stroke * .85f;
                paint.setColor(ui.text);
                paint.setStrokeWidth(Math.max(2, ui.dp(3)));
                canvas.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1, cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, paint);
            }
            if (averageShare >= 0) {
                double a = Math.toRadians(RidingFrames.ringAngle(averageShare));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ui.text);
                canvas.drawCircle(cx + (float) Math.cos(a) * radius, cy + (float) Math.sin(a) * radius, stroke * .2f, paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            if (present && !smoother.settled(now, .002)) postInvalidateOnAnimation();
        }
    }

    /**
     * Trace's live chart: the last minute of speed (a line) over power (an area, red above the zero
     * line for draw, green below it for regen, grey where the direction isn't known). Only real
     * readings are drawn: a gap in the data is a gap, never a zero, and nothing runs past the
     * latest reading. Speed shares the ring's scale and power the bar's.
     */
    private final class TraceView extends View {
        private final String content;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<RidingValues.TracePoint> speeds = new ArrayList<>(), powers = new ArrayList<>();
        private long now;
        private double speedTop = 1;
        private String units;
        TraceView(String content) {
            super(host.activity());
            this.content = content;
            setContentDescription(T.ridingTraceDescription());
            label.setTypeface(ui.medium());
            label.setTextSize(ui.dp(labelSp(14) * ui.textScale));
        }
        private boolean showsSpeed() { return !RidingLayout.TRACE_POWER.equals(content); }
        private boolean showsPower() { return !RidingLayout.TRACE_SPEED.equals(content); }
        void show(long nowMs, String units) {
            now = nowMs;
            this.units = units;
            speeds = showsSpeed() ? session.values.speedTrace(nowMs, RidingFrames.TRACE_WINDOW_MS) : new ArrayList<>();
            powers = showsPower() ? session.values.powerTrace(nowMs, RidingFrames.TRACE_WINDOW_MS) : new ArrayList<>();
            speedTop = RidingFrames.ringTop(fastestKmh > 0 ? fastestKmh : null, units);
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || units == null) return;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(cardColor());
            canvas.drawRoundRect(new RectF(0, 0, w, h), ui.dp(20), ui.dp(20), paint);
            float pad = ui.dp(14);
            Paint.FontMetrics fm = label.getFontMetrics();
            float lineH = fm.descent - fm.ascent;
            // The key across the top: each entry only while it fits whole.
            float x = pad, keyY = pad - fm.ascent;
            List<Object[]> key = new ArrayList<>();
            if (showsSpeed()) key.add(new Object[]{T.ridingLabelSpeed(), ui.text});
            if (showsPower()) { key.add(new Object[]{powerName(), ui.accent}); key.add(new Object[]{T.ridingTraceRegen(), ui.ok}); }
            for (Object[] k : key) {
                String name = (String) k[0];
                float need = ui.dp(22) + label.measureText(name);
                if (x + need > w - pad) break;
                paint.setColor((Integer) k[1]);
                float sy = keyY - lineH * .3f;
                canvas.drawRoundRect(new RectF(x, sy - ui.dp(2), x + ui.dp(16), sy + ui.dp(2)), ui.dp(2), ui.dp(2), paint);
                label.setColor(ui.muted);
                canvas.drawText(name, x + ui.dp(22), keyY, label);
                x += need + ui.dp(16);
            }
            // The time axis along the bottom.
            float axisY = h - pad - fm.descent;
            label.setColor(ui.dim);
            String start = T.ridingTraceStart(), end = T.ridingTraceNow();
            canvas.drawText(start, pad, axisY, label);
            canvas.drawText(end, w - pad - label.measureText(end), axisY, label);
            float plotTop = pad + lineH + ui.dp(10), plotBottom = axisY + fm.ascent - ui.dp(8);
            float plotH = plotBottom - plotTop, plotW = w - pad * 2;
            if (plotH <= ui.dp(12) || plotW <= 0) return;
            RidingValues.PowerScale scale = session.values.scale();
            if (showsPower()) {
                float zero = plotTop + plotH * RidingFrames.TRACE_ZERO;
                for (List<RidingValues.TracePoint> run : RidingFrames.runs(powers)) {
                    // A run splits again where the direction becomes confirmed or not, so each part takes its own colour.
                    int from = 0;
                    for (int i = 1; i <= run.size(); i++) {
                        if (i < run.size() && run.get(i).directionUnconfirmed == run.get(from).directionUnconfirmed) continue;
                        area(canvas, run.subList(from > 0 ? from - 1 : 0, i), run.get(from).directionUnconfirmed, scale, pad, plotTop, plotH, plotW, zero);
                        from = i;
                    }
                }
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ui.outline);
                canvas.drawRect(pad, zero - Math.max(1, ui.dp(1)) / 2f, pad + plotW, zero + Math.max(1, ui.dp(1)) / 2f, paint);
            }
            if (showsSpeed()) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(3));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(ui.text);
                for (List<RidingValues.TracePoint> run : RidingFrames.runs(speeds)) {
                    Path line = new Path();
                    for (int i = 0; i < run.size(); i++) {
                        RidingValues.TracePoint p = run.get(i);
                        float px = pad + plotW * RidingFrames.x(p.t, now), py = plotTop + plotH * RidingFrames.speedY(p.value, speedTop, units);
                        if (i == 0) line.moveTo(px, py); else line.lineTo(px, py);
                    }
                    if (run.size() == 1) {
                        RidingValues.TracePoint p = run.get(0);
                        paint.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(pad + plotW * RidingFrames.x(p.t, now), plotTop + plotH * RidingFrames.speedY(p.value, speedTop, units), ui.dp(2.5f), paint);
                        paint.setStyle(Paint.Style.STROKE);
                    } else canvas.drawPath(line, paint);
                }
                if (!speeds.isEmpty()) {
                    RidingValues.TracePoint last = speeds.get(speeds.size() - 1);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(pad + plotW * RidingFrames.x(last.t, now), plotTop + plotH * RidingFrames.speedY(last.value, speedTop, units), ui.dp(5), paint);
                }
            }
        }
        /** One part of a run as a filled area to the zero line: draw above it, regen below, grey without a known direction. */
        private void area(Canvas canvas, List<RidingValues.TracePoint> part, boolean unknown, RidingValues.PowerScale scale,
                float pad, float plotTop, float plotH, float plotW, float zero) {
            if (part.isEmpty()) return;
            Path path = new Path();
            float firstX = pad + plotW * RidingFrames.x(part.get(0).t, now), lastX = firstX;
            path.moveTo(firstX, zero);
            for (RidingValues.TracePoint p : part) {
                lastX = pad + plotW * RidingFrames.x(p.t, now);
                path.lineTo(lastX, plotTop + plotH * RidingFrames.powerY(p.value, scale));
            }
            path.lineTo(lastX, zero);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            if (unknown) {
                paint.setColor(Ui.withAlpha(ui.muted, 150));
                canvas.drawPath(path, paint);
                return;
            }
            canvas.save();
            canvas.clipRect(pad, plotTop, pad + plotW, zero);
            paint.setShader(new LinearGradient(0, plotTop, 0, zero, Ui.withAlpha(ui.accent, 220), Ui.withAlpha(ui.accentDeep, 70), Shader.TileMode.CLAMP));
            canvas.drawPath(path, paint);
            paint.setShader(null);
            canvas.restore();
            canvas.save();
            canvas.clipRect(pad, zero, pad + plotW, plotTop + plotH);
            paint.setColor(Ui.withAlpha(ui.ok, 200));
            canvas.drawPath(path, paint);
            canvas.restore();
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
