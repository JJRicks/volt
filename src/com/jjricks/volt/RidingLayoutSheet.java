package com.jjricks.volt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The riding layout editor (riding-mode plan PR 4, edit-mode.png; the owner: it "looks great"). It
 * fills the sheet that riding mode's tune disc opens while stopped: the three frames as drawn
 * thumbnails, one row per spot with what it shows, the frame's switches (power bar, chips, tenths)
 * and Reset. Every change goes straight back to be saved ({@link Changed}), with no Done button; the
 * screen that owns the sheet decides whether a late change still counts, and applies the layout when
 * the menu closes.
 */
final class RidingLayoutSheet {
    /** Every change, with the edited layout. */
    interface Changed { void save(RidingLayout edited); }

    private final Context context;
    private final Ui ui;
    private final RidingEditor editor;
    private final Changed changed;
    private LinearLayout content;
    /** The spot whose choices are open under its row, or null. */
    private String open;

    RidingLayoutSheet(Context context, Ui ui, RidingLayout saved, Changed changed) {
        this.context = context;
        this.ui = ui;
        this.editor = new RidingEditor(saved);
        this.changed = changed;
    }

    /** Builds the editor into the sheet's body. */
    void fill(LinearLayout body) {
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        body.addView(content, new LinearLayout.LayoutParams(-1, -2));
        render();
    }

    private void render() {
        content.removeAllViews();
        String frame = editor.frame();
        content.addView(frames(frame), new LinearLayout.LayoutParams(-1, -2));

        content.addView(ui.sectionLabel(T.ridingEditorSpots()));
        LinearLayout spots = ui.groupCard();
        List<RidingLayout.Spot> list = editor.spots(frame);
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) ui.divider(spots);
            spotRow(spots, frame, list.get(i));
        }
        content.addView(spots);

        TextView reset = ui.secondaryButton(T.ridingEditorReset(frameName(frame)), () -> {
            editor.reset();
            open = null;
            changed.save(editor.working());
            render();
        });
        reset.setEnabled(editor.canReset());

        List<String> switches = RidingEditor.switches(frame);
        if (!switches.isEmpty()) content.addView(ui.sectionLabel(T.ridingEditorOptions()));
        for (String id : switches) {
            content.addView(ui.toggleRow(switchName(id), Ui.NO_ICON, switchSummary(id), editor.on(id), true, on -> {
                editor.set(id, on);
                changed.save(editor.working());
                // Every switch can make the frame differ from its defaults, or match them again.
                reset.setEnabled(editor.canReset());
                // Chips switched off take their rows away; the rest of the editor stays as it is.
                if (RidingLayout.CHIPS.equals(id)) content.post(this::render);
            }));
        }
        content.addView(reset);
    }

    /** The three frames side by side, the one on show outlined in red. */
    private View frames(String shown) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, ui.dp(4), 0, ui.dp(4));
        for (int i = 0; i < RidingLayout.FRAMES.length; i++) {
            final String id = RidingLayout.FRAMES[i];
            boolean selected = id.equals(shown);
            LinearLayout option = new LinearLayout(context);
            option.setOrientation(LinearLayout.VERTICAL);
            option.setGravity(Gravity.CENTER_HORIZONTAL);
            option.setClickable(true);
            option.setFocusable(true);
            option.setSelected(selected);
            option.setTag(id);
            option.setContentDescription(frameName(id));
            if (Build.VERSION.SDK_INT >= 30 && selected) option.setStateDescription(T.commonSelected());
            Thumb thumb = new Thumb(id, selected);
            option.addView(thumb, new LinearLayout.LayoutParams(-1, ui.dp(132)));
            TextView name = ui.text(frameName(id), 15, true);
            name.setGravity(Gravity.CENTER);
            name.setTextColor(selected ? ui.text : ui.muted);
            name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams nameSize = new LinearLayout.LayoutParams(-1, -2);
            nameSize.topMargin = ui.dp(8);
            option.addView(name, nameSize);
            option.setOnClickListener(v -> {
                if (!editor.showFrame(id)) return;
                open = null;
                changed.save(editor.working());
                render();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) p.setMarginStart(ui.dp(10));
            row.addView(option, p);
        }
        return row;
    }

    /** A spot's row: its name, and a pill with what it shows; a tap opens its choices under it. */
    private void spotRow(LinearLayout group, String frame, RidingLayout.Spot spot) {
        String name = spotName(frame, spot), shows = contentName(editor.content(spot.id));
        boolean expanded = spot.id.equals(open);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(60));
        row.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
        row.setClickable(true);
        row.setFocusable(true);
        row.setTag(spot.id);
        row.setContentDescription(T.ridingEditorSpotSpoken(name, shows));
        TextView label = ui.text(name, 16, true);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(ui.shape(expanded ? ui.accent : ui.raised, 100, 0));
        pill.setPadding(ui.dp(14), ui.dp(8), ui.dp(10), ui.dp(8));
        pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView value = ui.text(shows, 15, true);
        value.setTextColor(expanded ? ui.onAccent : ui.text);
        value.setMaxLines(2);
        pill.addView(value, new LinearLayout.LayoutParams(-2, -2));
        View chevron = ui.icon("chevron", 18, expanded ? ui.onAccent : ui.muted);
        chevron.setRotation(expanded ? -90 : 90);
        LinearLayout.LayoutParams chevronSize = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18));
        chevronSize.setMarginStart(ui.dp(6));
        pill.addView(chevron, chevronSize);
        LinearLayout.LayoutParams pillSize = new LinearLayout.LayoutParams(-2, -2);
        pillSize.setMarginStart(ui.dp(10));
        row.addView(pill, pillSize);
        row.setOnClickListener(v -> {
            open = expanded ? null : spot.id;
            render();
        });
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (!expanded) return;
        String current = editor.content(spot.id);
        for (String choice : RidingEditor.choices(spot)) {
            boolean selected = choice.equals(current);
            LinearLayout option = new LinearLayout(context);
            option.setOrientation(LinearLayout.HORIZONTAL);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setMinimumHeight(ui.dp(48));
            option.setPadding(ui.dp(16), ui.dp(4), ui.dp(4), ui.dp(4));
            option.setClickable(true);
            option.setFocusable(true);
            option.setSelected(selected);
            option.setTag(choice);
            String choiceName = contentName(choice);
            option.setContentDescription(choiceName);
            if (Build.VERSION.SDK_INT >= 30 && selected) option.setStateDescription(T.commonSelected());
            Ui.RadioDot dot = ui.radioDot();
            dot.set(selected);
            option.addView(dot, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
            TextView text = ui.text(choiceName, 15, selected);
            if (selected) text.setTextColor(ui.accent);
            text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams textSize = new LinearLayout.LayoutParams(0, -2, 1);
            textSize.setMarginStart(ui.dp(12));
            option.addView(text, textSize);
            option.setOnClickListener(v -> {
                editor.choose(spot.id, choice);
                open = null;
                changed.save(editor.working());
                render();
            });
            group.addView(option, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    static String frameName(String id) {
        if (RidingLayout.HALO.equals(id)) return T.ridingFrameHalo();
        if (RidingLayout.TRACE.equals(id)) return T.ridingFrameTrace();
        return T.ridingFrameFocus();
    }

    static String spotName(String frame, RidingLayout.Spot spot) {
        String n = Fmt.integer(RidingEditor.number(frame, spot));
        switch (spot.kind) {
            case BIG: return T.ridingSpotBig();
            case RING: return T.ridingSpotRing();
            case HIGHLIGHT: return T.ridingSpotHighlight();
            case STRIP: return T.ridingSpotStrip(n);
            case CHIP: return T.ridingSpotChip(n);
            case TRACE: return T.ridingSpotChart();
            default: return T.ridingSpotTile(n);
        }
    }

    /** A content's name in the editor: the reading's full name, or the ring's and chart's own options. */
    static String contentName(String id) {
        if (id == null) return T.ridingNoValue();
        switch (id) {
            case RidingLayout.SPEED: return T.ridingLabelSpeed();
            case RidingLayout.POWER: return T.ridingLabelPower();
            case RidingLayout.RANGE_AT_PACE: return T.ridingLabelRangeAtPace();
            case RidingLayout.RANGE_SCOOTER: return T.ridingLabelRangeScooter();
            case RidingLayout.RANGE_VOLT: return T.ridingLabelRangeVolt();
            case RidingLayout.BATTERY: return T.ridingLabelBattery();
            case RidingLayout.TRIP: return T.ridingLabelTrip();
            case RidingLayout.RIDE_TIME: return T.ridingLabelRideTime();
            case RidingLayout.RECORDING_TIME: return T.ridingLabelRecordingTime();
            case RidingLayout.AVERAGE_SPEED: return T.ridingLabelAverageSpeed();
            case RidingLayout.MAX_SPEED: return T.ridingLabelMaxSpeed();
            case RidingLayout.EFFICIENCY: return T.ridingLabelEfficiency();
            case RidingLayout.RIDE_ENERGY: return T.ridingLabelRideEnergy();
            case RidingLayout.VOLTAGE: return T.ridingLabelVoltage();
            case RidingLayout.CURRENT: return T.ridingLabelCurrent();
            case RidingLayout.BATTERY_TEMPERATURE: return T.ridingLabelBatteryTemperature();
            case RidingLayout.DRIVE_MODE: return T.ridingLabelDriveMode();
            case RidingLayout.CLOCK: return T.ridingLabelClock();
            case RidingLayout.ODOMETER: return T.ridingLabelOdometer();
            case RidingLayout.GPS_ACCURACY: return T.ridingLabelGpsAccuracy();
            case RidingLayout.RING_SPEED: return T.ridingContentRingSpeed();
            case RidingLayout.TRACE_SPEED_POWER: return T.ridingContentTraceSpeedPower();
            case RidingLayout.TRACE_POWER: return T.ridingContentTracePower();
            case RidingLayout.TRACE_SPEED: return T.ridingContentTraceSpeed();
            default: return T.ridingNoValue();
        }
    }

    private static String switchName(String id) {
        if (RidingLayout.CHIPS.equals(id)) return T.ridingSwitchChips();
        if (RidingLayout.TENTHS.equals(id)) return T.ridingSwitchTenths();
        return T.ridingSwitchPowerBar();
    }

    private static String switchSummary(String id) {
        if (RidingLayout.CHIPS.equals(id)) return T.ridingSwitchChipsSummary();
        if (RidingLayout.TENTHS.equals(id)) return T.ridingSwitchTenthsSummary();
        return T.ridingSwitchPowerBarSummary();
    }

    /**
     * A frame's thumbnail, drawn as blocks in the frame's shape rather than a picture: Focus's
     * stacked rows, Halo's ring and Trace's chart, with the power bar at the left edge.
     */
    private final class Thumb extends View {
        private final String frame;
        private final boolean selected;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Thumb(String frame, boolean selected) {
            super(context);
            this.frame = frame;
            this.selected = selected;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), d = ui.dp(1);
            // The card, outlined in red when it's the frame on show.
            fill.setColor(ui.dark ? ui.surface : ui.raised);
            r.set(d, d, w - d, h - d);
            canvas.drawRoundRect(r, 16 * d, 16 * d, fill);
            if (selected) {
                line.setColor(ui.accent);
                line.setStrokeWidth(3 * d);
                r.set(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d);
                canvas.drawRoundRect(r, 16 * d, 16 * d, line);
            }
            // A phone screen in the middle of the card.
            float sh = h - 16 * d, sw = Math.min(w - 16 * d, sh * .52f), sx = (w - sw) / 2, sy = 8 * d;
            fill.setColor(ui.bg);
            r.set(sx, sy, sx + sw, sy + sh);
            canvas.drawRoundRect(r, 6 * d, 6 * d, fill);
            float pad = sw * .06f, left = sx + pad, top = sy + pad * 2.2f, right = sx + sw - pad, bottom = sy + sh - pad;
            // The top bar.
            block(canvas, left, sy + pad, right, sy + pad * 1.9f, ui.raised);
            // The power bar at the left edge.
            float bar = sw * .07f;
            block(canvas, left, top, left + bar, bottom, ui.raised);
            block(canvas, left, top + (bottom - top) * .45f, left + bar, bottom, ui.accent);
            float x = left + bar + pad, cw = right - x;
            if (RidingLayout.HALO.equals(frame)) {
                float cx = x + cw / 2, cy = top + cw * .45f, radius = cw * .38f;
                line.setColor(ui.raised);
                line.setStrokeWidth(cw * .07f);
                r.set(cx - radius, cy - radius, cx + radius, cy + radius);
                canvas.drawArc(r, 135, 270, false, line);
                line.setColor(ui.accent);
                canvas.drawArc(r, 135, 170, false, line);
                block(canvas, cx - radius * .45f, cy - radius * .3f, cx + radius * .45f, cy + radius * .25f, ui.text);
                float y = cy + radius + pad * 1.4f;
                block(canvas, x, y, right, y + sh * .07f, ui.muted);
                y += sh * .1f;
                tiles(canvas, x, y, right, y + sh * .13f, pad);
                y += sh * .16f;
                block(canvas, x, y, right, y + sh * .05f, ui.raised);
            } else if (RidingLayout.TRACE.equals(frame)) {
                block(canvas, x, top, x + cw * .55f, top + sh * .1f, ui.text);
                block(canvas, right - cw * .28f, top, right, top + sh * .045f, ui.accent);
                float y = top + sh * .16f, ch = sh * .2f;
                block(canvas, x, y, right, y + ch, ui.raised);
                line.setColor(ui.accent);
                line.setStrokeWidth(2 * d);
                float mid = y + ch * .55f;
                for (int i = 0; i < 6; i++) {
                    float a = x + cw * i / 6f, b = x + cw * (i + 1) / 6f;
                    canvas.drawLine(a, mid - (i % 2 == 0 ? ch * .25f : -ch * .1f), b, mid - (i % 2 == 0 ? -ch * .1f : ch * .25f), line);
                }
                y += ch + pad * 1.4f;
                block(canvas, x, y, right, y + sh * .1f, ui.muted);
                y += sh * .13f;
                tiles(canvas, x, y, right, y + sh * .12f, pad);
            } else {
                block(canvas, x, top, x + cw * .7f, top + sh * .16f, ui.text);
                float y = top + sh * .22f;
                block(canvas, x, y, x + cw * .8f, y + sh * .1f, ui.muted);
                y += sh * .15f;
                tiles(canvas, x, y, right, y + sh * .12f, pad);
                y += sh * .15f;
                tiles(canvas, x, y, right, y + sh * .12f, pad);
            }
        }

        private void tiles(Canvas canvas, float l, float t, float r2, float b, float gap) {
            float m = (l + r2) / 2;
            block(canvas, l, t, m - gap / 2, b, ui.raised);
            block(canvas, m + gap / 2, t, r2, b, ui.raised);
        }

        private void block(Canvas canvas, float l, float t, float r2, float b, int color) {
            fill.setColor(color);
            r.set(l, t, r2, b);
            float radius = Math.min(r.height(), r.width()) * .25f;
            canvas.drawRoundRect(r, radius, radius, fill);
        }
    }
}
