package com.jjricks.volt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.function.Consumer;

/**
 * The charging view's floor bar with a mark the owner can drag: the scooter's charge limit, or the charge alert's
 * level (ChargeBarDrag decides which, and every rule it follows). At rest it draws exactly the floor line Home's
 * Widgets charging view always drew (the charge in red, grey up to the limit) plus the mark. Grabbing the mark and
 * moving sideways thickens the bar under the finger (about 150 ms, eased; instant with animations off), shows the
 * allowed steps and the value being chosen beside the mark, and ticks once per step. The value is committed on the
 * lift: a limit through the Charge limit control's own gates, confirmation and write (Host.chargeLimit), an alert level
 * in the alert settings store. While a limit write is in flight the mark is dimmed and takes no drag; a refusal or a
 * value the scooter doesn't keep puts it back. No layout pass runs during a drag: only this view's drawing changes.
 *
 * <p>Built to be placed by its parent as a full-width child at the bottom of the charging stage (Clean can adopt it
 * with its own inset, thickness and floor). The view is as tall as the stage's minimum height (from 48 to 96 dp) so
 * the touch band and the label above the mark have room without making the stage taller; where it is short the label
 * goes beside the mark.
 *
 * <p>For TalkBack it is one adjustable control ("Charge limit, 85%"), whose increase and decrease move one step and
 * commit as a lift does. Right to left, the bar and the gesture are mirrored.
 */
final class ChargeBar extends View {
    static final String KEY = "charge_limit";

    /** What the bar needs from Home. */
    interface Host {
        ScooterService service();
        /** Editing, closed, or the view still revealing: the bar takes no touch and no action. */
        boolean inert();
        /** The Charge limit control's write (MainActivity): accepted(true) once the scooter was sent the change. */
        void chargeLimit(int value, Consumer<Boolean> accepted);
        void toast(String text);
    }

    private final Ui ui;
    private final Host host;
    private final float inset, restThick, dragThick, floorFromBottom, bandAbove, bandBelow, grabHalf;
    private final int slop;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    /** Where the mark and the value label were last drawn, in this view's pixels (the label empty when not shown). */
    final RectF markBox = new RectF(), labelBox = new RectF();
    private final ChargeBarDrag.Gesture gesture = new ChargeBarDrag.Gesture();
    private final ChargeBarDrag.Commit commit = new ChargeBarDrag.Commit();

    int kind = ChargeBarDrag.NONE;
    int[] allowed = ChargeBarDrag.NO_VALUES;
    /** The battery, and where the grey track ends when the mark is not the limit (the hero's own limit or 100). */
    double level = Double.NaN, trackEnd = 100;
    /** Why the limit can't move now, in its control's words (null: it can, or the mark is the alert's). */
    String blocked;
    ChargeBarDrag.Gate gate = ChargeBarDrag.Gate.OK;
    /** The scooter has a charge limit write in flight that this bar did not start (the choice sheet's, say). */
    boolean otherPending;
    /** An owner saw this model acknowledge a limit change and keep its old value (ModelRegistry.unconfirmed). */
    boolean unconfirmed;
    /** Where the mark is drawn, in percent (moves to the resting value when it changes). */
    float drawn = Float.NaN;
    /** 0 at rest, 1 fully thickened under the finger. */
    float lift;
    private android.animation.ValueAnimator thickness, settle;
    /** Bumped by every reset, so a late answer from the control can't touch a newer state. */
    private int generation;
    private boolean fingerDown, said;

    ChargeBar(Context context, Ui ui, Host host, float inset, float restThickDp, float floorFromBottomDp) {
        super(context);
        this.ui = ui; this.host = host; this.inset = inset;
        restThick = ui.dp(restThickDp); dragThick = ui.dp(Math.max(restThickDp, 10));
        floorFromBottom = ui.dp(floorFromBottomDp);
        bandAbove = ui.dp(30); bandBelow = ui.dp(18); grabHalf = ui.dp(28);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        text.setTypeface(ui.semibold());
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13 * ui.textScale, context.getResources().getDisplayMetrics()));
        text.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The stage's minimum height decides this view's (48 to 96 dp), so the stage itself never grows for it. */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int wanted = Math.max(ui.dp(48), Math.min(ui.dp(96), getParent() instanceof View ? ((View) getParent()).getMinimumHeight() : 0));
        setMeasuredDimension(width, resolveSize(wanted, heightSpec));
    }

    // ---- State from Home ------------------------------------------------------------------------------------------

    /**
     * Each charging refresh. level: the battery in percent (NaN when unknown); trackEnd: the hero's limit or 100;
     * limit: the charging estimate's target (the limit the scooter reports), or null.
     */
    void update(double level, double trackEnd, Double limit) {
        this.level = level; this.trackEnd = trackEnd;
        ScooterService service = host.service();
        int next = ChargeBarDrag.NONE; int[] values = ChargeBarDrag.NO_VALUES; Integer value = null;
        ChargeBarDrag.Gate nextGate = ChargeBarDrag.Gate.OK; String why = null; boolean other = false, kept = false;
        ChargeAlertPolicy.Settings alerts = null;
        if (service != null) {
            CapabilityResolver.Decision d = service.ble.decision(KEY);
            boolean control = d.support == CapabilityResolver.Support.KNOWN_ENCODING && !d.experimental();
            // The control's own reading wins (it is what a write changes); the charging estimate's target otherwise.
            Integer reported = service.ble.featureValue(KEY);
            if (reported != null) limit = reported.doubleValue();
            alerts = service.chargeAlerts();
            next = ChargeBarDrag.kind(control, limit, service.chargeAlertsOffered(), alerts.charge);
            if (next == ChargeBarDrag.LIMIT) {
                ChargeBarDrag.LimitState state = limitState(service);
                values = ChargeBarDrag.limitValues(state.choices);
                value = (int) Math.round(limit);
                nextGate = ChargeBarDrag.limitGate(state, null);
                why = limitMessage(nextGate, service);
                other = KEY.equals(service.ble.pending());
                kept = ModelRegistry.unconfirmed(service.ble.descriptor(), KEY);
            } else if (next == ChargeBarDrag.ALERT) {
                values = alertValues();
                value = alerts.chargeAt;
            }
        }
        if (next != kind) { reset(); kind = next; }
        allowed = values; gate = nextGate; blocked = why; otherPending = other; unconfirmed = kept;
        // A drag on a limit that can't move any more (the scooter disconnected, a test began) ends; the mark returns.
        if (gesture.dragging() && (host.inert() || kind == ChargeBarDrag.LIMIT && gate != ChargeBarDrag.Gate.OK)) cancelDrag();
        if (kind == ChargeBarDrag.LIMIT && commit.phase == ChargeBarDrag.Commit.IN_FLIGHT) resolve(service);
        commit.report(value);
        if (!gesture.dragging()) settleTo(commit.shown(), Float.isNaN(drawn));
        describe();
        invalidate();
    }

    static int[] alertValues() {
        return ChargeBarDrag.steps(ChargeAlertPolicy.CHARGE_MIN, ChargeAlertPolicy.CHARGE_MAX, ChargeAlertPolicy.STEP);
    }

    /** The gate's inputs, read the way the Charge limit row, its quick action and its choice sheet read them. */
    static ChargeBarDrag.LimitState limitState(ScooterService service) {
        ChargeBarDrag.LimitState s = new ChargeBarDrag.LimitState();
        s.service = service != null;
        if (service == null) return s;
        BleController ble = service.ble;
        CapabilityResolver.Decision d = ble.decision(KEY);
        s.knownEncoding = d.support == CapabilityResolver.Support.KNOWN_ENCODING;
        s.lab = d.experimental();
        s.rule = d.reason == CapabilityResolver.Reason.REGION_RULE || d.reason == CapabilityResolver.Reason.FIRMWARE_UNKNOWN
                || d.reason == CapabilityResolver.Reason.FIRMWARE_OLD || d.reason == CapabilityResolver.Reason.FULL_PID_NEEDED;
        s.ready = ble.ready();
        s.busy = ble.pending() != null;
        s.fresh = service.featureFresh(KEY);
        s.actual = ble.featureValue(KEY);
        s.available = d.available();
        s.parkedOnly = d.blockedByRide();
        s.choices = ble.featureChoices(KEY);
        return s;
    }

    /** A gate in the words its control uses (Quick access's toasts, the resolver's reason, the row's and sheet's). */
    static String limitMessage(ChargeBarDrag.Gate gate, ScooterService service) {
        switch (gate) {
            case NO_SERVICE: return T.mainQuickConnectFirst();
            case UNMAPPED: case LAB: case RULE: case GATED: {
                CharSequence reason = service == null ? null : service.ble.decision(KEY).reasonMessage();
                return reason == null || reason.length() == 0 ? null : reason.toString();
            }
            case NOT_CONNECTED: return T.mainQuickConnectChange();
            case BUSY: return T.mainQuickWaitChange();
            case NOT_FRESH: return T.mainQuickNoFresh();
            case PARKED: return T.mainRowStopToChange();
            case NO_CHOICES: return T.mainEditNotOnFirmware();
            default: return null;
        }
    }

    /** The choice sheet's ending of a write: kept, or its failure words, and the mark back to what the scooter says. */
    private void resolve(ScooterService service) {
        if (service == null) { commit.refused(); return; }
        BleController ble = service.ble;
        int outcome = commit.resolve(KEY.equals(ble.pending()), ble.featureValue(KEY), ble.commandResults().get(KEY));
        if (outcome == ChargeBarDrag.Commit.NOT_CONFIRMED || outcome == ChargeBarDrag.Commit.REJECTED) {
            host.toast((outcome == ChargeBarDrag.Commit.REJECTED ? M.mainChipRejected() : M.mainChipNotConfirmed()).render());
            Haptics.reject(this);
        }
    }

    // ---- Drawing --------------------------------------------------------------------------------------------------

    private boolean rtl() { return getLayoutDirection() == LAYOUT_DIRECTION_RTL; }
    private float start() { return getWidth() * inset; }
    private float end() { return getWidth() * (1 - inset); }
    private float floor() { return getHeight() - floorFromBottom; }
    private float xOf(double percent) { return ChargeBarDrag.x(percent, start(), end(), rtl()); }
    private boolean hasMark() { return kind != ChargeBarDrag.NONE && !Float.isNaN(drawn) && allowed.length > 0; }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth();
        markBox.setEmpty(); labelBox.setEmpty();
        if (w <= 0) return;
        float floor = floor(), thick = restThick + (dragThick - restThick) * lift;
        boolean limitMark = kind == ChargeBarDrag.LIMIT && hasMark();
        double greyTo = limitMark ? drawn : trackEnd;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(thick);
        if (lift > 0 && hasMark()) {
            // While the mark is held, the rest of the track shows faintly, so there is somewhere to drag to.
            paint.setColor(Ui.withAlpha(ui.muted, Math.round(70 * lift)));
            canvas.drawLine(xOf(0), floor, xOf(100), floor, paint);
        }
        paint.setColor(ui.raised);
        canvas.drawLine(xOf(0), floor, xOf(greyTo), floor, paint);
        if (!Double.isNaN(level) && level > 0) {
            paint.setColor(ui.accent);
            canvas.drawLine(xOf(0), floor, xOf(level), floor, paint);
        }
        if (!hasMark()) return;
        if (lift > 0) {
            // The steps a drag can land on.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.withAlpha(ui.text, Math.round(120 * lift)));
            for (int v : allowed) canvas.drawCircle(xOf(v), floor, Math.max(1, thick * .16f), paint);
        }
        float x = xOf(drawn), mw = ui.dp(4) + ui.dp(2) * lift, mh = ui.dp(14) + ui.dp(12) * lift;
        box.set(x - mw / 2, floor - mh / 2, x + mw / 2, floor + mh / 2);
        boolean pending = commit.pending() || otherPending;
        int color = pending ? Ui.withAlpha(ui.text, 120) : blocked != null ? ui.muted : ui.text;
        paint.setColor(color);
        if (unconfirmed && !pending) {
            // "Unconfirmed here" (an owner saw this model keep its old limit): the mark is drawn hollow.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(1.5f));
            box.inset(ui.dp(.75f), ui.dp(.75f));
        } else paint.setStyle(Paint.Style.FILL);
        markBox.set(x - mw / 2, floor - mh / 2, x + mw / 2, floor + mh / 2);
        canvas.drawRoundRect(box, mw / 2, mw / 2, paint);
        float shown = Math.max(lift, pending ? 1 : 0);
        if (shown > 0) drawLabel(canvas, x, floor, mh, shown);
    }

    /** The value being chosen, in a small pill above the mark (beside it where the view is short), kept inside the view. */
    private void drawLabel(Canvas canvas, float x, float floor, float markHeight, float alpha) {
        String words = valueText(Math.round(drawn));
        Paint.FontMetrics m = text.getFontMetrics();
        float padX = ui.dp(8), padY = ui.dp(4), tw = text.measureText(words), th = m.descent - m.ascent;
        float pw = tw + 2 * padX, ph = th + 2 * padY, w = getWidth();
        float top = floor - markHeight / 2 - ui.dp(6) - ph, cx = x, cy;
        if (top >= 0) cy = top + ph / 2;
        else {
            // No room above: beside the mark, on whichever side has room (the far side of the track first).
            cy = floor;
            float side = markHeight / 2 + ui.dp(8) + pw / 2;
            boolean right = rtl() ? x - side - pw / 2 < 0 : x + side + pw / 2 <= w;
            cx = right ? x + side : x - side;
        }
        cx = Math.max(pw / 2, Math.min(w - pw / 2, cx));
        cy = Math.max(ph / 2, Math.min(getHeight() - ph / 2, cy));
        box.set(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2);
        labelBox.set(box);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.withAlpha(ui.surface, Math.round(235 * alpha)));
        canvas.drawRoundRect(box, ph / 2, ph / 2, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(1));
        paint.setColor(Ui.withAlpha(ui.outline, Math.round(((ui.outline >>> 24) & 255) * alpha)));
        canvas.drawRoundRect(box, ph / 2, ph / 2, paint);
        text.setColor(Ui.withAlpha(ui.text, Math.round(255 * alpha)));
        canvas.drawText(words, cx, cy - (m.ascent + m.descent) / 2, text);
    }

    /** "85%" for a limit; the alert sheet's own words for an alert level ("80%", or "Full" at 100). */
    String valueText(int value) {
        return kind == ChargeBarDrag.ALERT ? ChargeAlertPolicy.levelText(value) : T.fmtPercent(Fmt.integer(value));
    }

    String name() { return kind == ChargeBarDrag.ALERT ? M.mainAlertCharge().render() : T.mainShortChargeLimit(); }

    // ---- Motion ---------------------------------------------------------------------------------------------------

    /** The bar thickens under the finger (held) or returns (released), eased over about 150 ms; at once with animations off. */
    private void thicken(boolean held) {
        float goal = held ? 1 : 0;
        if (thickness != null) { android.animation.ValueAnimator old = thickness; thickness = null; old.cancel(); }
        if (!Ui.motionEnabled() || !isAttachedToWindow() || lift == goal) { lift = goal; invalidate(); return; }
        final android.animation.ValueAnimator run = android.animation.ValueAnimator.ofFloat(lift, goal);
        thickness = run;
        run.setDuration(Math.max(40, Math.round(150 * Math.abs(goal - lift))));
        run.setInterpolator(Motion.STANDARD);
        run.addUpdateListener(a -> { if (thickness != run) return; lift = (Float) a.getAnimatedValue(); invalidate(); });
        run.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { if (thickness == run) { thickness = null; lift = goal; invalidate(); } }
        });
        run.start();
    }

    /** The mark goes to value: at once (first showing, animations off) or eased, as a spring back or a new report. */
    private void settleTo(Integer value, boolean instant) {
        if (value == null) { stopSettle(); drawn = Float.NaN; return; }
        float goal = value;
        if (drawn == goal && settle == null) return;
        if (settle != null && settleGoal == goal) return;
        stopSettle();
        if (instant || Float.isNaN(drawn) || !Ui.motionEnabled() || !isAttachedToWindow() || !isShown()) { drawn = goal; invalidate(); return; }
        final android.animation.ValueAnimator run = android.animation.ValueAnimator.ofFloat(drawn, goal);
        settle = run; settleGoal = goal;
        run.setDuration(Motion.CHANGE_MS);
        run.setInterpolator(Motion.STANDARD);
        run.addUpdateListener(a -> { if (settle != run) return; drawn = (Float) a.getAnimatedValue(); invalidate(); });
        run.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { if (settle == run) { settle = null; drawn = goal; invalidate(); } }
        });
        run.start();
    }
    private float settleGoal = Float.NaN;

    private void stopSettle() {
        android.animation.ValueAnimator old = settle; settle = null;
        if (old != null) { old.cancel(); }
    }

    // ---- Touch ----------------------------------------------------------------------------------------------------

    /** A released limit is being asked for or written (the mark is pending). */
    boolean writePending() { return commit.pending(); }

    /** May the mark move now: a mark, nothing in flight, and (for the limit) every gate open. The alert level is the app's own. */
    boolean live() {
        if (!hasMark() || host.inert() || commit.pending() || otherPending) return false;
        return kind == ChargeBarDrag.ALERT || gate == ChargeBarDrag.Gate.OK;
    }

    /**
     * A touch counts only when it starts in the band around the bar and near the mark (ChargeBarDrag.Gesture); there
     * is no tap-to-jump. Until it moves sideways past the touch slop the parents (Home's scroller) may still take it as
     * a scroll; once it is a drag they are held off to the lift. A second finger, or the system's cancel, ends it.
     */
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            fingerDown = true; said = false;
            if (!hasMark() || host.inert()) { gesture.cancel(false); fingerDown = false; return false; }
            boolean taken = gesture.down(event.getX(), event.getY(), xOf(drawn), floor(), bandAbove, bandBelow, grabHalf, live(), Math.round(drawn));
            if (!taken) fingerDown = false;
            return taken;
        }
        if (!gesture.active()) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) fingerDown = false;
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN: cancelDrag(); return true;
            case MotionEvent.ACTION_MOVE: {
                if (host.inert()) { cancelDrag(); return true; }
                int was = gesture.value;
                int result = gesture.move(event.getX(), event.getY(), slop, start(), end(), rtl(), allowed);
                if (result == ChargeBarDrag.Gesture.STARTED) {
                    hold(true);
                    stopSettle();
                    thicken(true);
                    if (gesture.value != was) Haptics.tick(this);
                    drawn = gesture.value; invalidate();
                } else if (result == ChargeBarDrag.Gesture.STEPPED) {
                    Haptics.tick(this);
                    drawn = gesture.value; invalidate();
                } else if (result == ChargeBarDrag.Gesture.REFUSED) {
                    // A deliberate drag on a mark that can't move: said once, in the control's own words; nothing moves.
                    hold(true);
                    refusedDrag();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                fingerDown = false;
                boolean dragged = gesture.dragging();
                Integer value = gesture.up();
                hold(false);
                if (dragged) thicken(false);
                if (value != null) commitValue(value);
                else if (dragged) settleTo(commit.shown(), false);
                return true;
            }
            case MotionEvent.ACTION_CANCEL: fingerDown = false; cancelDrag(); return true;
            default: return true;
        }
    }

    private void hold(boolean on) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(on);
    }

    private void refusedDrag() {
        if (said) return;
        said = true;
        String why = commit.pending() || otherPending ? T.mainQuickWaitChange() : blocked;
        if (why != null) host.toast(why);
        Haptics.reject(this);
    }

    /** Ends a drag (or a refused one) under way without committing anything; the mark goes back. */
    void cancelDrag() {
        boolean was = gesture.cancel(fingerDown);
        hold(false);
        if (was || lift > 0) thicken(false);
        if (was) settleTo(commit.shown(), false);
    }

    /**
     * Charging ended or the view is hiding: any drag ends and a limit write in flight is no longer watched here (its
     * result still reaches the Charge limit row); the mark stays drawn while the view collapses.
     */
    void stop() {
        generation++;
        cancelDrag();
        commit.refused();
        describe();
    }

    /** Everything back to rest: a new kind of mark, detached, hidden. A pending limit write is forgotten here (its result still reaches the row). */
    private void reset() {
        generation++;
        cancelDrag();
        commit.refused();
        stopSettle();
        drawn = Float.NaN;
    }

    // ---- Commit ---------------------------------------------------------------------------------------------------

    /** A released value, once: the limit through its control, the alert level into its store. */
    void commitValue(int value) {
        if (!ChargeBarDrag.offered(value, allowed)) { settleTo(commit.shown(), false); return; }
        ScooterService service = host.service();
        if (kind == ChargeBarDrag.ALERT) {
            ChargeAlertPolicy.Settings alerts = service == null ? null : service.chargeAlerts();
            // The alert sheet's own check (ChargeAlertSheet.current): still offered here and the alert still on.
            boolean saved = alerts != null && alerts.charge && service.chargeAlertsOffered() && service.setChargeAlerts(alerts.charge(true, value));
            if (saved) commit.report(value);
            else Haptics.reject(this);
            settleTo(commit.shown(), false);
            describe();
            return;
        }
        if (kind != ChargeBarDrag.LIMIT) return;
        commit.ask(value);
        settleTo(commit.shown(), true);
        describe();
        invalidate();
        final int asked = generation;
        host.chargeLimit(value, accepted -> {
            if (asked != generation || commit.phase != ChargeBarDrag.Commit.ASKING) return;
            if (Boolean.TRUE.equals(accepted)) { commit.accepted(); resolve(host.service()); }
            else commit.refused();
            if (!gesture.dragging()) settleTo(commit.shown(), false);
            describe();
            invalidate();
        });
    }

    // ---- Lifecycle ------------------------------------------------------------------------------------------------

    @Override protected void onDetachedFromWindow() {
        reset();
        if (thickness != null) { android.animation.ValueAnimator old = thickness; thickness = null; old.cancel(); }
        lift = 0;
        super.onDetachedFromWindow();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (!visible) cancelDrag();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        // Another window took the touch (a dialog, the shade, split screen, the activity pausing): the drag ends.
        if (!focused) cancelDrag();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) cancelDrag();
    }

    // ---- Accessibility --------------------------------------------------------------------------------------------

    /** Name and value for TalkBack; set only when they change, so a refresh is not read out again. */
    private void describe() {
        boolean mark = hasMark();
        int importance = mark ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO;
        if (getImportantForAccessibility() != importance) setImportantForAccessibility(importance);
        if (isFocusable() != mark) setFocusable(mark);
        if (!mark) { if (getContentDescription() != null) setContentDescription(null); return; }
        Integer at = commit.shown();
        String value = valueText(at == null ? Math.round(drawn) : at);
        String note = commit.pending() || otherPending ? T.mainRowConfirmingPart()
                : unconfirmed ? T.mainRowStateNote(T.mainRowUnconfirmed()) : "";
        if (Build.VERSION.SDK_INT >= 30) {
            String state = value + note;
            if (!state.contentEquals(String.valueOf(getStateDescription()))) setStateDescription(state);
            String named = name();
            if (!named.contentEquals(String.valueOf(getContentDescription()))) setContentDescription(named);
        } else {
            String spoken = T.mainLabelValueState(name(), value, note);
            if (!spoken.contentEquals(String.valueOf(getContentDescription()))) setContentDescription(spoken);
        }
    }

    @Override public CharSequence getAccessibilityClassName() { return android.widget.SeekBar.class.getName(); }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (!hasMark()) return;
        Integer at = commit.shown();
        int current = at == null ? Math.round(drawn) : at;
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0, 100, current));
        if (ChargeBarDrag.step(current, 1, allowed) != current) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        if (ChargeBarDrag.step(current, -1, allowed) != current) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
    }

    /** Increase and decrease move one step and commit as a lift does; Set progress lands on the nearest step. */
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        boolean forward = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, back = action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        boolean set = action == android.R.id.accessibilityActionSetProgress;
        if (!forward && !back && !set) return super.performAccessibilityAction(action, arguments);
        if (!hasMark() || host.inert()) return false;
        if (!live()) { said = false; refusedDrag(); return false; }
        Integer at = commit.shown();
        int current = at == null ? Math.round(drawn) : at;
        int next = set ? ChargeBarDrag.snap(arguments == null ? current : arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, current), allowed)
                : ChargeBarDrag.step(current, forward ? 1 : -1, allowed);
        if (next == current) return false;
        Haptics.tick(this);
        commitValue(next);
        return true;
    }
}
