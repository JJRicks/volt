package com.jjricks.volt;

import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Clean Home's recording moment, the Ride clock (Ollie's mock-up, Nov 24 2026: its "C · Ride clock"): while a
 * ride records, the top area shows it on the page itself, no card, as charging and Just back do. Top to bottom: a red
 * dot that pulses gently and "Recording" in the accent's text colour with "Started 9:24 AM" muted at the right; the
 * recording's time in large figures (the same time and format as Home's Ride row and riding mode's REC pill:
 * RecordingFit.elapsedMs, RidingFocus.clockText), ticking on the second while it shows; this ride's speed so far on a
 * rounded panel in the card colour (RecordingFit.trace: the active ride's own readings, gaps as breaks, its speed now
 * at the line's end); Trip, Battery and the estimated range between hairlines (the range is the parked card's own
 * reading, so it follows the range source and the units as that card does); and a full-width Open riding mode button
 * that opens riding mode exactly as the Ride quick action does.
 *
 * <p>A tap anywhere else opens the Ride page, where the live recording card and its End button are: this moment adds no
 * way to end a ride (RideRecordTile). With the link lost, reconnecting or the scooter switched off, the head row says so
 * in riding mode's REC pill's words (RecordingPill's RECORDING_PROBLEM look: the dot hollow and still, the words in
 * orange in place of the start time) and the scooter's readings show as last seen (Ui.dim), as elsewhere. While the
 * service has paused saving the ride (a full storage: ScooterService.recordingError), the head row takes the same look
 * with the service's own words for it (as the Ride page's live card shows them), and the speed now goes: the ride is not
 * being saved.
 *
 * <p>It fills the top area's fixed height, so the Lock bar never moves: RecordingFit.layout shares it out (the room
 * under the button goes first, then the ribbon shrinks and goes, then the time gets smaller), "Started" drops where the
 * head row cannot hold it, and the readings take their short rungs where a third is too narrow. Text from HubText and
 * the ride formatters; this file holds no catalog references. Spoken as one description (HubText.recordingSpoken), not
 * a live region; the button has its own name.
 */
final class RecordingView extends LinearLayout {
    private final Ui ui;
    private final SharedPreferences settings;
    private final float density, textScale;
    private final FrameLayout head, clockBox;
    private final View dot;
    private final TextView label, side, clock;
    private final Ribbon ribbon;
    private final Stats stats;
    private final LinearLayout button;
    private final TextView buttonLabel;
    /** The button's gauge and chevron: on a narrow phone with large text they give way, so its words stay whole. */
    private final View buttonIcon, buttonChevron;
    /** The bound recording, its time's text as shown, and the link's state. */
    private RideStore.Summary active;
    private String shownClock = "";
    private RecordingPill.Look look = RecordingPill.Look.RECORDING;
    private RidingFocus.Link link = RidingFocus.Link.CONNECTED;
    private boolean off, closed, running, attached;
    /** When the shown ride stopped (wall clock), or -1 while it records. */
    private long endedWall = -1;
    private CharSequence startedText = "", problemText, headProblem;
    private ValueAnimator pulse;
    private final Runnable tick = this::tick;

    /** openRide opens the Ride page (the Ride subject row's place); openRiding opens riding mode (the Ride quick action's way). */
    RecordingView(ScreenHost host, Runnable openRide, Runnable openRiding) {
        super(host.activity());
        ui = host.ui();
        settings = host.appSettings();
        density = getResources().getDisplayMetrics().density;
        // The app's text size and the system's together, as the top area takes it (TopArea, CleanHomeScreen.topHeight).
        textScale = ui.textScale * getResources().getConfiguration().fontScale;
        setOrientation(VERTICAL);
        setTag(RecordingFit.TAG);
        // The mock-up's rows sit by their CSS line boxes: a text's own line may reach past its row, never clipped.
        setClipChildren(false);
        setClipToPadding(false);
        // On the page background, as Just back: only the press ripple shows, inside the area's bounds.
        setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 20, 0)));
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> { if (!closed) openRide.run(); });

        // The head row: the dot and "Recording" at the start, the start time (or a link problem) at the end.
        head = new FrameLayout(host.activity());
        head.setClipChildren(false);
        LinearLayout recording = new LinearLayout(host.activity());
        recording.setOrientation(HORIZONTAL);
        recording.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(host.activity());
        recording.addView(dot, new LinearLayout.LayoutParams(ui.dp(RecordingFit.DOT_DP), ui.dp(RecordingFit.DOT_DP)));
        label = text(HubText.recordingLabel().render(), RecordingFit.HEAD_SP, ui.semibold(), ui.accentText);
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(-2, -2);
        labelSize.setMarginStart(ui.dp(8));
        recording.addView(label, labelSize);
        head.addView(recording, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        side = text("", RecordingFit.STARTED_SP, ui.regular(), ui.muted);
        side.setEllipsize(TextUtils.TruncateAt.END);
        side.setGravity(Gravity.END);
        head.addView(side, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        addView(head, new LinearLayout.LayoutParams(-1, ui.dp(RecordingFit.HEAD_DP)));

        // The time: the mock-up's 78 bold figures with tabular digits (Saira has tnum), so the width holds as they change.
        clockBox = new FrameLayout(host.activity());
        clockBox.setClipChildren(false);
        clock = text("", RecordingFit.CLOCK_SP, ui.bold(), ui.text);
        clock.setFontFeatureSettings("tnum");
        clock.setLetterSpacing(-1.5f / RecordingFit.CLOCK_SP);
        clock.setTag(RecordingFit.TAG_CLOCK);
        clockBox.addView(clock, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams clockSize = new LinearLayout.LayoutParams(-1, ui.dp(76));
        clockSize.topMargin = ui.dp(RecordingFit.GAP_DP);
        addView(clockBox, clockSize);

        ribbon = new Ribbon(host);
        ribbon.setTag(RecordingFit.TAG_RIBBON);
        LinearLayout.LayoutParams ribbonSize = new LinearLayout.LayoutParams(-1, ui.dp(RecordingFit.RIBBON_DP));
        ribbonSize.topMargin = ui.dp(RecordingFit.GAP_DP);
        addView(ribbon, ribbonSize);

        stats = new Stats(host);
        stats.setTag(RecordingFit.TAG_STATS);
        LinearLayout.LayoutParams statsSize = new LinearLayout.LayoutParams(-1, ui.dp(RecordingFit.STATS_DP));
        statsSize.topMargin = ui.dp(RecordingFit.GAP_DP);
        addView(stats, statsSize);

        // Open riding mode: a pill in the raised colour, the Ride quick action's gauge and a chevron in the accent's text colour.
        button = new LinearLayout(host.activity());
        button.setOrientation(HORIZONTAL);
        button.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(ui.raised);
        pill.setCornerRadius(ui.dp(200));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 35)), pill, null));
        button.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        buttonIcon = ui.icon(HomeQuickActions.ICON_RIDING, 20, ui.accentText);
        button.addView(buttonIcon, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        buttonLabel = text(HubText.recordingOpenRiding().render(), RecordingFit.BUTTON_SP, ui.semibold(), ui.text);
        buttonLabel.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams buttonLabelSize = new LinearLayout.LayoutParams(-2, -2, 0);
        buttonLabelSize.setMarginStart(ui.dp(10));
        buttonLabelSize.setMarginEnd(ui.dp(10));
        button.addView(buttonLabel, buttonLabelSize);
        // The approved render's chevron: 18 dp in the accent's text colour, as the gauge.
        buttonChevron = ui.icon("chevron", 18, ui.accentText);
        button.addView(buttonChevron, new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
        String name = HubText.recordingOpenRiding().render();
        button.setContentDescription(name);
        button.setTooltipText(name);
        button.setTag(RecordingFit.TAG_RIDING);
        button.setClickable(true);
        button.setFocusable(true);
        button.setMinimumHeight(ui.dp(48));
        button.setOnClickListener(v -> { if (!closed) openRiding.run(); });
        LinearLayout.LayoutParams buttonSize = new LinearLayout.LayoutParams(-1, ui.dp(RecordingFit.BUTTON_DP));
        buttonSize.topMargin = ui.dp(RecordingFit.GAP_DP);
        addView(button, buttonSize);

        // One description for the whole moment (bind sets it); its parts are not read one by one. The button is its own.
        for (View part : new View[] {head, clockBox, ribbon, stats}) part.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        showLook();
    }

    private TextView text(String value, float sp, Typeface face, int color) {
        TextView view = ui.text(value, sp, false);
        view.setTypeface(face);
        view.setTextColor(color);
        view.setMaxLines(1);
        view.setLineSpacing(0, 1);
        return view;
    }

    // ===== Layout =====

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY && width > 0) {
            int area = Math.round(MeasureSpec.getSize(heightSpec) / density);
            RecordingFit.Layout fit = RecordingFit.layout(area, width / density, textScale);
            size(head, fit.head, 0);
            size(clockBox, fit.clock, fit.gap + fit.spread);
            size(ribbon, Math.max(0, fit.ribbon), fit.ribbon > 0 ? fit.ribbonGap + fit.spread : 0);
            ribbon.setVisibility(fit.ribbon > 0 ? VISIBLE : GONE);
            size(stats, fit.stats, fit.gap + fit.spread);
            size(button, fit.button, fit.gap + fit.spread);
            // The time at the fit's size, smaller still where its width runs out (a long "1:02:48" on a narrow phone).
            float top = fit.clockSize * density;
            TextPaint measure = new TextPaint(clock.getPaint());
            measure.setTextSize(top);
            float wide = measure.measureText(clockTemplate());
            float sizePx = RecordingFit.clockFit(top, Math.min(top, RecordingFit.CLOCK_FLOOR_SP * density), wide, width);
            if (Math.abs(clock.getTextSize() - sizePx) > .5f) clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
            // The time keeps its whole line (Saira's ascent and descent), centred on its box as the mock-up's 76 dp line
            // height centres it: taller than the box, never cut (the box and this view draw outside their bounds).
            measure.setTextSize(sizePx);
            Paint.FontMetrics metrics = measure.getFontMetrics();
            int line = (int) Math.ceil(metrics.descent - metrics.ascent);
            FrameLayout.LayoutParams clockSize = (FrameLayout.LayoutParams) clock.getLayoutParams();
            if (clockSize.height != line) clockSize.height = line;
            fitHead(width);
            fitButton(width);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    /**
     * The button's words stay whole: where they don't fit beside the gauge and the chevron (a narrow phone with large
     * text), the chevron goes first, then the gauge; only then would they end in an ellipsis.
     */
    private void fitButton(int width) {
        float words = buttonLabel.getPaint().measureText(String.valueOf(buttonLabel.getText()));
        float room = width - button.getPaddingLeft() - button.getPaddingRight();
        // Each part counted once (RecordingFit.buttonParts): the gauge's 20 dp and its 10 dp gap, the chevron's 10 and 18.
        int parts = RecordingFit.buttonParts(words, room, ui.dp(20), ui.dp(10), ui.dp(18));
        boolean chevron = parts == 2, icon = parts >= 1;
        if ((buttonChevron.getVisibility() == VISIBLE) != chevron) buttonChevron.setVisibility(chevron ? VISIBLE : GONE);
        if ((buttonIcon.getVisibility() == VISIBLE) != icon) buttonIcon.setVisibility(icon ? VISIBLE : GONE);
        LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) buttonLabel.getLayoutParams();
        int start = icon ? ui.dp(10) : 0, end = chevron ? ui.dp(10) : 0;
        if (size.getMarginStart() != start || size.getMarginEnd() != end) { size.setMarginStart(start); size.setMarginEnd(end); }
    }

    /** The time's text for measuring: the shown one, so the size changes only when its digit count does (tabular figures). */
    private String clockTemplate() { return shownClock.isEmpty() ? RidingFocus.clockText(0) : shownClock; }

    private void size(View view, int dp, int gapDp) {
        LayoutParams params = (LayoutParams) view.getLayoutParams();
        int height = ui.dp(dp), gap = ui.dp(gapDp);
        if (params.height != height || params.topMargin != gap) { params.height = height; params.topMargin = gap; }
    }

    /**
     * The head row: a link problem always shows at the end (ellipsized, never at the cost of "Recording"); the start time
     * only where it fits whole beside "Recording" (RecordingFit.startedFits), else it drops.
     */
    private void fitHead(int width) {
        float recording = ui.dp(RecordingFit.DOT_DP + 8) + label.getPaint().measureText(String.valueOf(label.getText()));
        int gap = ui.dp(12);
        CharSequence want = problemText != null ? problemText : startedText;
        boolean show = problemText != null
                || RecordingFit.startedFits(recording, side.getPaint().measureText(String.valueOf(startedText)), gap, width);
        CharSequence shown = show ? want : "";
        if (!TextUtils.equals(side.getText(), shown)) side.setText(shown);
        side.setVisibility(show && want.length() > 0 ? VISIBLE : GONE);
        side.setMaxWidth(Math.max(0, Math.round(width - recording - gap)));
    }

    // ===== Binding =====

    /**
     * Shows the recording now: its summary (its start, time and distance), the link (RidingFocus.Link, off for a scooter
     * switched off but answering), the readings the parked card shows (ScooterService.dashboardReadings: battery_percent
     * and remaining_range_km, which follows the range source) and the speed reading, in the "units" preference; paused is
     * the service's words while it has stopped saving the ride (ScooterService.recordingError), null while it saves.
     * Cheap to call on every refresh.
     */
    void bind(RideStore.Summary summary, RidingFocus.Link link, boolean off, String paused,
              java.util.Map<String, TelemetryHistory.Reading> readings, String units, long wallNow) {
        active = summary;
        endedWall = -1;
        this.link = link == null ? RidingFocus.Link.NOT_CONNECTED : link;
        this.off = off;
        if (paused != null && paused.isEmpty()) paused = null;
        long elapsed = RecordingFit.elapsedMs(summary, wallNow);
        RecordingPill.Look next = RecordingFit.look(elapsed, this.link, off, paused != null);
        if (next != look) { look = next; showLook(); }
        Msg problem = look == RecordingPill.Look.RECORDING_PROBLEM && paused == null ? HubText.recordingProblem(this.link, off) : null;
        problemText = paused != null ? paused : problem == null ? null : problem.render();
        side.setTextColor(problemText != null ? ui.orange : ui.muted);
        CharSequence started = summary == null ? "" : HubText.recordingStarted(DisplayTime.clock(getContext(), settings, summary.startWallMs)).render();
        // The head row is fitted again only when its words change (fitHead), not on every second's refresh.
        if (!TextUtils.equals(started, startedText) || !TextUtils.equals(problemText, headProblem)) {
            startedText = started;
            headProblem = problemText;
            requestLayout();
        }
        updateClock(wallNow);

        // The readings: Trip as the saved ride and Just back will show it, Battery and Range as the parked card does.
        Double km = summary == null ? null : summary.distanceKm;
        Formatted trip = km == null || !Double.isFinite(km) ? null : RideText.distance(km, units, 1);
        Formatted tripShort = trip != null && "dual".equals(units) ? RideText.distance(km, "mi", 1) : null;
        TelemetryHistory.Reading battery = readings == null ? null : readings.get("battery_percent");
        Double level = battery == null || battery.value == null || battery.number == null ? null : Math.max(0, Math.min(100, battery.number));
        Formatted percent = level == null ? null : Fmt.percent(Fmt.integerNumber(Math.round(level)));
        TelemetryHistory.Reading range = readings == null ? null : readings.get("remaining_range_km");
        // The parked card's own figure (RangeFigure), its text where the reading has no number.
        Double rangeKm = range == null || range.value == null || range.number == null || !Double.isFinite(range.number) ? null : range.number;
        String rangeText = range == null ? null : range.value;
        Formatted rangeShown = HubText.recordingRangeValue(rangeKm, rangeText, units);
        Formatted rangeShort = rangeKm != null && "dual".equals(units) ? HubText.recordingRangeShort(rangeKm, units) : null;
        stats.set(0, trip, tripShort, true);
        stats.set(1, percent, null, battery != null && battery.live);
        stats.set(2, rangeShown, rangeShort, range != null && range.live);

        // The speed now, beside the ribbon's label, in the primary unit (both units do not fit a label's room): only a
        // reading riding mode would still show (RecordingFit.speedNowShown), never while saving is paused.
        TelemetryHistory.Reading speed = readings == null ? null : readings.get("speed_kmh");
        boolean speedLive = speed != null && speed.number != null
                && RecordingFit.speedNowShown(speed.live, speed.capturedWallMs, wallNow, this.link == RidingFocus.Link.CONNECTED && !off, paused != null);
        Formatted now = speedLive ? DisplayUnits.speedQuantity(speed.number, "dual".equals(units) ? "mi" : units) : null;
        ribbon.now(now == null ? null : HubText.recordingSpeedNow(now).render());

        // One description, set again only when its words change, never a live region: the time by the minute
        // (HubText.recordingTimeSpoken) with any problem (saving paused first), and the readings; the ribbon and its speed
        // are decorative and not spoken (the speed changes too often for a description read once).
        Msg rangeSpoken = HubText.recordingRangeSpoken(rangeKm, rangeText, units);
        String spoken = HubText.recordingSpoken(elapsed, this.link, off, paused, new CharSequence[] {
                HubText.recordingTrip().render(), trip == null ? null : trip.toString(),
                HubText.recordingBattery().render(), percent == null ? null : percent.toString(),
                HubText.recordingRange(true).render(), rangeSpoken == null ? null : rangeSpoken.render()}).render();
        if (!spoken.contentEquals(getContentDescription() == null ? "" : getContentDescription())) setContentDescription(spoken);
    }

    /**
     * The active ride's points (RideStore.trend, read off the UI thread) for ride id, as of wallNow (when they were
     * read): the ribbon draws them from the ride's start to then. An empty list shows the panel with its label and baseline only.
     */
    void setTrend(String id, List<RideStore.Point> points, long wallNow) {
        if (active != null && id != null && !id.equals(active.id)) return;
        ribbon.points(points, active == null ? wallNow : active.startWallMs, wallNow);
    }

    /**
     * The ride stopped (stopWallMs, RideStore.lastStopWallMs) while it shows, for the moment Clean reads the ride just
     * saved (HomeMoment.holdRecording): the time shows the ride's time at its stop (RecordingFit.shownElapsedMs), not the
     * moment Home next looked. The next bind starts it again.
     */
    void ended(long stopWallMs) {
        endedWall = stopWallMs;
        updateClock(System.currentTimeMillis());
    }

    /** The ride shown, or null before the first bind. */
    String rideId() { return active == null ? null : active.id; }

    private void updateClock(long wallNow) {
        String text = RidingFocus.clockText(RecordingFit.shownElapsedMs(active, endedWall, wallNow));
        if (!text.equals(shownClock)) {
            boolean longer = text.length() != shownClock.length();
            shownClock = text;
            clock.setText(text);
            if (longer) requestLayout();
        }
    }

    // ===== The tick and the dot's pulse: only while shown =====

    private void tick() {
        if (!running) return;
        long wall = System.currentTimeMillis();
        updateClock(wall);
        removeCallbacks(tick);
        postDelayed(tick, RecordingFit.untilNextSecond(RecordingFit.elapsedMs(active, wall)));
    }

    /** Ticks and pulses only while attached, shown (this moment, Home, the window) and not closed. */
    private void follow(boolean shown) {
        boolean next = attached && shown && !closed;
        if (next == running) return;
        running = next;
        removeCallbacks(tick);
        if (running) tick();
        showLook();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        follow(isShown() && getWindowVisibility() == VISIBLE);
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        follow(false);
        super.onDetachedFromWindow();
    }

    @Override public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        follow(isVisible);
    }

    /** Home closed: no more ticks or pulse. */
    void close() {
        closed = true;
        follow(false);
    }

    /**
     * The dot as riding mode's REC pill draws it (RecordingPill): solid, pulsing gently while recording with a good link
     * and the system's animations on (RecordingPill.pulses); a hollow, still ring with a link problem, whose words say it
     * too, so colour is never the only signal.
     */
    private void showLook() {
        GradientDrawable mark = new GradientDrawable();
        mark.setShape(GradientDrawable.OVAL);
        if (look == RecordingPill.Look.RECORDING_PROBLEM) {
            mark.setColor(Color.TRANSPARENT);
            mark.setStroke(ui.dp(2), ui.accent);
        } else mark.setColor(ui.accent);
        dot.setBackground(mark);
        boolean pulses = running && RecordingPill.pulses(look, Ui.endlessMotionEnabled());
        if (pulses && pulse == null) {
            // The mock-up's gentle blink: full to 30% and back over 1.6 s, easing in and out.
            pulse = ValueAnimator.ofFloat(1f, .3f);
            pulse.setDuration(800);
            pulse.setInterpolator(new AccelerateDecelerateInterpolator());
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(a -> dot.setAlpha((Float) a.getAnimatedValue()));
            pulse.start();
        } else if (!pulses && pulse != null) {
            pulse.cancel();
            pulse = null;
            dot.setAlpha(1f);
        }
    }

    /** The value's numbers at full size and its units (with the space before each) smaller and muted, as the mock-up's. */
    private CharSequence styled(Formatted value, boolean live) {
        SpannableString styled = new SpannableString(value.toString());
        if (!live) styled.setSpan(new ForegroundColorSpan(ui.dim), 0, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (int i = 0; i < value.units(); i++) {
            int from = value.unitStart(i), to = value.unitEnd(i);
            int unit = from;
            while (from > 0 && Character.isWhitespace(value.charAt(from - 1))) from--;
            // The mock-up sets the unit 2 dp after its number: the space before it a little narrower than the unit's own.
            if (from < unit) styled.setSpan(new android.text.style.ScaleXSpan(RecordingFit.UNIT_SPACE), from, unit, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new RelativeSizeSpan(RecordingFit.UNIT_RATIO), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new ForegroundColorSpan(ui.muted), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (android.os.Build.VERSION.SDK_INT >= 28)
                styled.setSpan(new android.text.style.TypefaceSpan(ui.semibold()), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }

    /** A child's place in its parent, with the line box a CSS line height gives: the text centred in it. */
    private static void centreIn(View child, int top, int box) {
        int height = child.getMeasuredHeight();
        int y = top + Math.round((box - height) / 2f);
        child.layout(child.getLeft(), y, child.getLeft() + child.getMeasuredWidth(), y + height);
    }

    // ===== The readings: Trip, Battery and Range between hairlines =====

    /**
     * The three readings, as the mock-up's row: three columns 8 dp apart, a 1 dp hairline at the start of the second and
     * third with 12 dp before their words; each a label (13 sp, muted, its short rung where the full one is too wide) over
     * a value (20 sp bold, its unit smaller and muted), in the mock-up's line boxes (1.25 and 1.15 of their sizes), the
     * three values at one size on one baseline. The columns are equal thirds wherever the values fit them, as the mock-up
     * draws them; where a value doesn't (units set to both: "2.5 mi / 4.0 km", "≈ 23 mi / 37 km"), the columns take the
     * width their values need and the values get smaller together, down to the readable floor (RecordingFit.row), so the
     * Range cell shows both units wherever the parked card does. Only where even that does not fit (the narrowest phones
     * with large text) do Trip and Range take their first unit alone, together.
     */
    final class Stats extends ViewGroup {
        private final TextView[] labels = new TextView[3], values = new TextView[3];
        /** Each label's rungs, full first, kept as messages and rendered when measured (rule R2). */
        private final Msg[][] names = new Msg[3][];
        private final CharSequence[] full = new CharSequence[3], shorter = new CharSequence[3];
        private final boolean[] shownLive = {true, true, true};
        private final Paint hairline = new Paint();
        private final TextPaint measure = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final int gap = ui.dp(8), indent = ui.dp(12);
        private final float valueTop;
        /** Each column's left edge and its words' width (px), from the last measure. */
        private final int[] starts = new int[3], rooms = new int[3];

        Stats(ScreenHost host) {
            super(host.activity());
            setWillNotDraw(false);
            setClipChildren(false);
            hairline.setColor(ui.dark ? ui.raised : ui.outline);
            names[0] = new Msg[] {HubText.recordingTrip()};
            names[1] = new Msg[] {HubText.recordingBattery()};
            names[2] = new Msg[] {HubText.recordingRange(true), HubText.recordingRange(false)};
            for (int i = 0; i < 3; i++) {
                labels[i] = text(names[i][0].render(), RecordingFit.LABEL_SP, ui.regular(), ui.muted);
                labels[i].setEllipsize(TextUtils.TruncateAt.END);
                values[i] = text(RecordingFit.DASH, RecordingFit.VALUE_SP, ui.bold(), ui.text);
                values[i].setEllipsize(TextUtils.TruncateAt.END);
                addView(labels[i]);
                addView(values[i]);
                full[i] = RecordingFit.DASH;
            }
            valueTop = values[0].getTextSize();
        }

        /** Value i (null: none, a muted dash), its short rung (null: none) and whether it is live (else last seen, dim). */
        void set(int i, Formatted value, Formatted shortValue, boolean live) {
            CharSequence next = value == null ? RecordingFit.DASH : styled(value, live);
            CharSequence nextShort = value == null || shortValue == null ? null : styled(shortValue, live);
            values[i].setAlpha(value == null ? .6f : 1f);
            if (!TextUtils.equals(String.valueOf(full[i]), String.valueOf(next)) || !TextUtils.equals(String.valueOf(shorter[i]), String.valueOf(nextShort))
                    || live != shownLive[i]) {
                full[i] = next;
                shorter[i] = nextShort;
                shownLive[i] = live;
                values[i].setText(next);
                requestLayout();
            }
        }

        /** The room a column keeps before its words: the hairline and its gap, none for the first. */
        private int pad(int i) { return i == 0 ? 0 : 1 + indent; }

        /** Whether value i shows its short rung (the first unit alone) now. */
        boolean shortShown(int i) { return shorter[i] != null && TextUtils.equals(String.valueOf(values[i].getText()), String.valueOf(shorter[i])); }

        private float widthAt(CharSequence text, float size) {
            measure.set(values[0].getPaint());
            measure.setTextSize(size);
            return android.text.Layout.getDesiredWidth(text, measure) + 2;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            float floor = Math.min(valueTop, ui.sp(Ui.READABLE_FLOOR_SP));
            float[] labelMin = new float[3], pads = new float[3], fullWidths = new float[3], shortWidths = new float[3];
            for (int i = 0; i < 3; i++) {
                pads[i] = pad(i);
                labelMin[i] = Float.MAX_VALUE;
                for (Msg name : names[i]) labelMin[i] = Math.min(labelMin[i], labels[i].getPaint().measureText(name.render()));
                fullWidths[i] = widthAt(full[i], valueTop);
                shortWidths[i] = widthAt(shorter[i] != null ? shorter[i] : full[i], valueTop);
            }
            float room = width - 2 * gap;
            // Both units while the row holds them at the readable floor or larger; else the first unit, together.
            float[] fit = RecordingFit.row(labelMin, fullWidths, pads, room, valueTop, floor);
            boolean primary = fit == null;
            if (primary) fit = RecordingFit.row(labelMin, shortWidths, pads, room, valueTop, floor);
            if (fit == null) fit = new float[] {floor, room / 3, room / 3, room / 3};
            float row = fit[0];
            int x = 0;
            for (int i = 0; i < 3; i++) {
                int column = Math.round(fit[i + 1]);
                starts[i] = x + pad(i);
                rooms[i] = Math.max(0, column - pad(i));
                x += column + gap;
                // The label's rung: the full name where it fits its column, else the short one (an ellipsis only at the last).
                float[] widths = new float[names[i].length];
                for (int r = 0; r < widths.length; r++) widths[r] = labels[i].getPaint().measureText(names[i][r].render());
                String name = names[i][Math.max(0, RecordingFit.rung(widths, rooms[i]))].render();
                if (!name.contentEquals(labels[i].getText())) labels[i].setText(name);
                CharSequence pick = primary && shorter[i] != null ? shorter[i] : full[i];
                if (!TextUtils.equals(String.valueOf(values[i].getText()), String.valueOf(pick))) values[i].setText(pick);
                if (Math.abs(values[i].getTextSize() - row) > .5f) values[i].setTextSize(TypedValue.COMPLEX_UNIT_PX, row);
                labels[i].measure(MeasureSpec.makeMeasureSpec(rooms[i], MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                values[i].measure(MeasureSpec.makeMeasureSpec(rooms[i], MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            }
            setMeasuredDimension(width, height);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            // The mock-up's line boxes, grown with the text: the label's 13 x 1.25, the value's 20 x 1.15.
            int labelBox = Math.round(ui.sp(RecordingFit.LABEL_SP) * RecordingFit.LABEL_LINE);
            int valueBox = Math.round(ui.sp(RecordingFit.VALUE_SP) * RecordingFit.VALUE_LINE);
            for (int i = 0; i < 3; i++) {
                int x = starts[i];
                for (View view : new View[] {labels[i], values[i]}) {
                    int left = rtl ? width - x - view.getMeasuredWidth() : x;
                    view.layout(left, 0, left + view.getMeasuredWidth(), view.getMeasuredHeight());
                }
                centreIn(labels[i], 0, labelBox);
                centreIn(values[i], labelBox, valueBox);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            int width = getWidth();
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            for (int i = 1; i < 3; i++) {
                float x = starts[i] - pad(i);
                if (rtl) x = width - x - ui.dp(1);
                canvas.drawRect(x, 0, x + ui.dp(1), getHeight(), hairline);
            }
        }
    }

    // ===== The speed ribbon =====

    /**
     * This recording's speed from its start to now on a rounded panel in the card colour (Just back's route panel's
     * colour in either theme): a 1.8 dp line in the text colour with round joins over a faint fill to the bottom, broken
     * where readings stop, stopped stretches on the baseline, a 10 dp accent dot ringed in the panel's colour at its end
     * while the speed is live, "Speed, this ride" at the top left and the speed now at the top right. Decorative for
     * accessibility: the moment's description does not read its speed (it changes too often for a description read
     * once); riding mode, a tap away, speaks it. With no reading yet, the label and a faint baseline.
     */
    private final class Ribbon extends FrameLayout {
        private final TextView name, now;
        private final Msg[] names = {HubText.recordingSpeed(true), HubText.recordingSpeed(false)};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path line = new Path(), fill = new Path(), shape = new Path();
        private final RectF box = new RectF();
        private List<RideStore.Point> points;
        private long startWall, readWall;
        private RecordingFit.Trace trace;
        private boolean nowShown;

        Ribbon(ScreenHost host) {
            super(host.activity());
            setWillNotDraw(false);
            name = text(names[0].render(), RecordingFit.RIBBON_LABEL_SP, ui.regular(), ui.muted);
            now = text("", RecordingFit.RIBBON_LABEL_SP, ui.semibold(), ui.text);
            now.setFontFeatureSettings("tnum");
            FrameLayout.LayoutParams nameSize = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            nameSize.topMargin = ui.dp(RecordingFit.LABEL_TOP_DP);
            nameSize.setMarginStart(ui.dp(RecordingFit.LABEL_SIDE_DP));
            addView(name, nameSize);
            FrameLayout.LayoutParams nowSize = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            nowSize.topMargin = ui.dp(RecordingFit.LABEL_TOP_DP);
            nowSize.setMarginEnd(ui.dp(RecordingFit.LABEL_SIDE_DP));
            addView(now, nowSize);
        }

        private int panelColor() { return ui.dark ? ui.surface : ui.raised; }

        /** The speed now (null: not live), redrawn only when it changes. */
        void now(CharSequence text) {
            CharSequence shown = text == null ? "" : text;
            if (nowShown == (text != null) && TextUtils.equals(now.getText(), shown)) return;
            nowShown = text != null;
            if (!TextUtils.equals(now.getText(), shown)) { now.setText(shown); requestLayout(); }
            now.setVisibility(nowShown ? VISIBLE : GONE);
            invalidate();
        }

        void points(List<RideStore.Point> value, long start, long read) {
            points = value;
            startWall = start;
            readWall = read;
            trace = null;
            invalidate();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            // The label's rung: the full one while it and the speed now fit side by side, else the short one.
            int width = MeasureSpec.getSize(widthSpec);
            float room = width - 2 * ui.dp(RecordingFit.LABEL_SIDE_DP) - ui.dp(12) - (nowShown ? now.getPaint().measureText(String.valueOf(now.getText())) : 0);
            String[] rungs = {names[0].render(), names[1].render()};
            float[] widths = {name.getPaint().measureText(rungs[0]), name.getPaint().measureText(rungs[1])};
            String pick = rungs[Math.max(0, RecordingFit.rung(widths, room))];
            if (!pick.contentEquals(name.getText())) name.setText(pick);
            super.onMeasure(widthSpec, heightSpec);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            trace = null;
            shape.reset();
            float radius = ui.dp(RecordingFit.RIBBON_RADIUS_DP);
            shape.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float radius = ui.dp(RecordingFit.RIBBON_RADIUS_DP);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(panelColor());
            box.set(0, 0, w, h);
            canvas.drawRoundRect(box, radius, radius, paint);
            float right = w - ui.dp(RecordingFit.RIBBON_END_DP), baseline = h - ui.dp(RecordingFit.BASELINE_DP);
            float plotTop = Math.min(baseline - ui.dp(8), name.getBottom() + ui.dp(RecordingFit.PLOT_TOP_GAP_DP) - ui.dp(1));
            if (trace == null && points != null) trace = RecordingFit.trace(points, startWall, readWall, RecordingFit.columns(Math.round(right), density));
            canvas.save();
            canvas.clipPath(shape);
            if (trace == null || trace.isEmpty()) {
                // No reading yet: the panel, its label and a faint baseline; never a made-up line.
                paint.setColor(Ui.withAlpha(ui.text, 40));
                canvas.drawRect(0, baseline - ui.dp(1), right, baseline, paint);
                canvas.restore();
                return;
            }
            line.reset();
            fill.reset();
            float startX = 0, lastX = 0, lastY = baseline;
            for (int i = 0; i < trace.size(); i++) {
                float x = trace.x[i] * right, y = baseline - trace.y[i] * (baseline - plotTop);
                if (trace.breaks[i]) {
                    if (i > 0) { fill.lineTo(lastX, h); fill.lineTo(startX, h); fill.close(); }
                    line.moveTo(x, y);
                    fill.moveTo(x, h);
                    fill.lineTo(x, y);
                    startX = x;
                } else {
                    line.lineTo(x, y);
                    fill.lineTo(x, y);
                }
                lastX = x;
                lastY = y;
            }
            fill.lineTo(lastX, h);
            fill.lineTo(startX, h);
            fill.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.withAlpha(ui.text, RecordingFit.FILL_ALPHA));
            canvas.drawPath(fill, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(RecordingFit.LINE_DP * density);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ui.text);
            canvas.drawPath(line, paint);
            canvas.restore();
            if (nowShown) {
                // The speed now: the accent dot at the line's end, ringed in the panel's colour.
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(panelColor());
                float dotRadius = ui.dp(RecordingFit.NOW_DOT_DP) / 2f;
                canvas.drawCircle(lastX, lastY, dotRadius + ui.dp(RecordingFit.NOW_RING_DP), paint);
                paint.setColor(ui.accent);
                canvas.drawCircle(lastX, lastY, dotRadius, paint);
            }
        }
    }
}
