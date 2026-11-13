package com.jjricks.volt;

import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Height-aware shortcuts: use available room without clipping text or shrinking touch targets.
 * The same catalog, values and actions also drive Setting tiles: separate cards with a label over
 * the current value and a power cue for toggles or a chevron for panels, the first one highlighted while connected.
 */
final class QuickAccessGrid extends ViewGroup {
    private final ScreenHost host;
    private final Ui ui;
    private final boolean editing, tiles;
    /**
     * Clean Home's four, as the Layout Plan draws them (the contributor's decisions on #383): equal circles in one row
     * and one short name on one line under each circle, no value line; an action that is on fills its circle red, and
     * its state is spoken (QuickActionLayout). Widgets never uses it.
     */
    private final boolean round;
    private final ArrayList<Cell> cells = new ArrayList<>();
    private int budget, columns = 1, rowHeight, fittedWidth = -1, fittedBudget = -1;
    private boolean iconsOnly, tileLabels = true, tileCompact;
    /** A value Volt cannot show (not on this model, not read yet): a dash in every language. */
    private static final String NO_VALUE = "—";
    /** No state to announce after the name. */
    private static final String NO_STATE = "";
    /** The smallest side at which the open padlock still reads differently from the closed one. */
    private static final int LOCK_PADLOCK_MIN_DP = 16;
    /** Shortcut ids (data) whose tiles take the Scooter page's shorter names. */
    private static final String TOP_SPEED_ID = "feature:maximum_speed", CHARGE_SCHEDULE_ID = "feature:timed_charging";

    QuickAccessGrid(ScreenHost host, List<String> ids, boolean editing, Consumer<String> action) {
        this(host, ids, editing, action, false);
    }

    /** {@code tiles} selects the Setting tiles presentation; callers normalize its one-to-four ids. */
    QuickAccessGrid(ScreenHost host, List<String> ids, boolean editing, Consumer<String> action, boolean tiles) {
        this(host, tiles ? ids : QuickAccessActions.normalize(ids), editing, action, tiles, QuickAccessActions::find);
    }

    /**
     * Clean Home's four (HomeQuickActions): ids already normalized by the caller, resolved by entries that may lie
     * outside the catalog (its own Ride and Record), as round buttons.
     */
    QuickAccessGrid(ScreenHost host, List<String> ids, Consumer<String> action, java.util.function.Function<String, QuickAccessActions.Entry> entries) {
        this(host, ids, false, action, false, entries, true);
    }

    private QuickAccessGrid(ScreenHost host, List<String> shown, boolean editing, Consumer<String> action, boolean tiles,
                            java.util.function.Function<String, QuickAccessActions.Entry> entries) {
        this(host, shown, editing, action, tiles, entries, false);
    }

    private QuickAccessGrid(ScreenHost host, List<String> shown, boolean editing, Consumer<String> action, boolean tiles,
                            java.util.function.Function<String, QuickAccessActions.Entry> entries, boolean round) {
        super(host.activity());
        this.host = host; this.ui = host.ui(); this.editing = editing; this.tiles = tiles; this.round = round;
        setTag(tiles ? "setting-tiles" : "quick-access-grid");
        for (String id : shown) {
            QuickAccessActions.Entry entry = entries.apply(id);
            if (entry == null) continue;
            Cell cell = new Cell(entry);
            cell.setOnClickListener(v -> { if (!editing) action.accept(id); });
            cells.add(cell); addView(cell);
        }
        refresh();
    }

    void fitHeight(int height) { budget = Math.max(0, height); }

    void refresh() {
        ScooterService service = host.service();
        BleController ble = service == null ? null : service.ble;
        boolean live = ble != null && ble.ready();
        Map<String, Integer> values = service == null ? Collections.emptyMap() : service.displayFeatures();
        android.content.SharedPreferences preferences = host.activity().getSharedPreferences("app_settings", 0);
        String units = preferences.getString("units", "mi");
        ChargeScheduleController.View charge = service == null ? null : service.chargeSchedule();
        String off = T.commonOff();
        for (int index = 0; index < cells.size(); index++) {
            Cell cell = cells.get(index);
            ScooterCommands.Feature feature = cell.entry.feature();
            boolean doneBy = cell.entry.id.equals("charge_done_by");
            boolean doneTime = cell.entry.id.equals("charge_done_time");
            boolean manual = cell.entry.id.equals("feature:timed_charging");
            boolean startTime = cell.entry.id.equals("feature:charge_start");
            boolean chargeControl = doneBy || doneTime || manual || startTime;
            // Charge alerts are Volt's own setting for the selected scooter: they never wait for a connection, and a
            // model with a built-in charge limit has none (the tile stays, muted, as an unmapped control's does).
            boolean alerts = QuickAccessActions.CHARGE_ALERTS_ID.equals(cell.entry.id);
            boolean alertsMissing = alerts && (service == null || !service.chargeAlertsOffered());
            boolean featureLive = feature != null && live && (feature.type == ScooterCommands.Type.TIME
                    ? service.readingFresh("scheduled_charge_time_raw") : service.featureFresh(feature.key));
            Integer value = feature == null || feature.sensitive ? null : values.get(feature.key);
            if (feature != null && !feature.sensitive && live) {
                Integer fresh = ble.featureValue(feature.key);
                if (fresh != null) value = fresh;
            }
            boolean pending = feature != null && ble != null && feature.key.equals(ble.pending());
            // The resolver's decision for this model; an unmapped tile stays in the saved layout, muted.
            CapabilityResolver.Decision decision = feature == null || ble == null ? null : ble.decision(feature.key);
            boolean unmapped = decision != null && ShortcutPicker.unmapped(feature.key, decision, ble.controlOffered(feature.key));
            if (unmapped) { value = null; featureLive = false; }
            // A choice value outside the model's choices (e.g. a Top speed reading 0) is unreadable, not a setting to show,
            // whatever else holds the control right now (moving, locked).
            boolean unreadable = !unmapped && value != null && decision != null && feature.type == ScooterCommands.Type.CHOICE && !offers(decision, value);
            if (unreadable) value = null;
            String lockState = feature == null || value == null ? null : DisplayUnits.lockStateText(feature, value);
            String state = feature == null || feature.sensitive && !unmapped ? ""
                    : unmapped ? NO_VALUE : pending ? T.dashboardModelTileUpdating() : value == null ? NO_VALUE
                    : lockState != null ? lockState
                    : decision != null && decision.oemText(value) != null ? decision.oemText(value).render()
                    : DisplayUnits.featureValueText(feature, value, units);
            boolean wasLockState = cell.lockState;
            cell.lockState = !pending && lockState != null;
            if (feature != null && feature.key.equals("range_algorithm")) {
                featureLive = service != null && service.profiles.active() != null;
                state = featureLive && service.usesLearnedRange() ? T.dashboardModelTileRangeLearned() : T.dashboardModelTileRangeScooter();
            }
            String chargeBlocked = charge == null ? (doneTime ? T.dashboardModelTileSelectScooter() : T.commonConnectChargerFirst()) : ChargeScheduleController.reasonText(doneTime ? charge.timeBlockedReason
                    : doneBy ? charge.doneByBlockedReason : startTime ? charge.manualTimeBlockedReason : charge.manualBlockedReason);
            // An armed plan deliberately pauses charging; its own off action remains available.
            if (doneBy && charge != null && charge.doneByEnabled)
                chargeBlocked = !live ? T.dashboardModelTileConnectScooter() : charge.busy ? T.commonWaitCurrentChange() : null;
            // A measuring window opens its own Keep / Schedule now / Stop choice.
            boolean measuring = doneBy && service != null && service.isChargeMeasuring();
            if (measuring) chargeBlocked = null;
            if (chargeControl && charge != null && !unmapped) {
                value = (doneBy || doneTime ? charge.doneByEnabled : charge.manualEnabled) ? 1 : 0;
                pending = charge.busy;
                featureLive = (doneTime || live) && chargeBlocked == null;
                if (doneBy || doneTime) {
                    Calendar deadline = Calendar.getInstance();
                    deadline.set(Calendar.HOUR_OF_DAY, charge.doneByMinuteOfDay / 60);
                    deadline.set(Calendar.MINUTE, charge.doneByMinuteOfDay % 60);
                    String time = DisplayTime.clock(host.activity(), preferences, deadline.getTimeInMillis());
                    String activeTime = charge.doneByWallMs > 0
                            ? DisplayTime.clock(host.activity(), preferences, charge.doneByWallMs) : time;
                    state = pending ? T.dashboardModelTileUpdating() : doneTime ? time : measuring ? T.dashboardModelTileMeasuring() : charge.doneByEnabled ? T.dashboardModelTileOnAt(activeTime) : off;
                } else if (manual) state = pending ? T.dashboardModelTileUpdating() : charge.manualEnabled ? T.commonOn() : off;
                else state = charge.manualStartWallMs > 0 ? DisplayTime.clock(host.activity(), preferences, charge.manualStartWallMs) : T.dashboardModelTileNotScheduled();
            } else if (doneBy || doneTime) state = "—";
            // Clean Home's Record action says so while a ride is being recorded.
            if (HomeQuickActions.RECORD.equals(cell.entry.id) && service != null && service.isRecording()) state = HubText.qaRecording().render();
            if (manual && !unmapped && charge != null && charge.manualEnabled && charge.startWallMs > 0 && !pending)
                state += T.dashboardModelTileAtTime(DisplayTime.clock(host.activity(), preferences, charge.startWallMs));
            if (alerts) {
                Integer level = ChargeAlertPolicy.shortcutLevel(!alertsMissing, service == null ? null : service.chargeAlerts());
                state = level == null ? NO_VALUE : level == 0 ? off : ChargeAlertPolicy.levelText(level);
                featureLive = level != null;
            }
            // Another model's mapping can make a UT5 MAX Lab control an ordinary toggle.
            boolean toggle = cell.entry.directToggle(decision);
            if (tiles && toggle != cell.toggle) { cell.toggle(toggle); fittedWidth = -1; }
            boolean selected = toggle && value != null && value == 1 && featureLive;
            if (tiles) {
                // A page or menu shortcut has no value; its name takes the value line instead.
                String shownValue = state.isEmpty() ? tileLabel(cell.entry) : state;
                if (!shownValue.contentEquals(cell.value.getText())) {
                    cell.value.setText(shownValue);
                    fittedWidth = -1;
                    // Whether a lock word drops is decided while measuring, and a text of the same height requests no layout.
                    if (wasLockState || cell.lockState) cell.requestLayout();
                }
                cell.label.setTag(state.isEmpty() ? "" : tileLabel(cell.entry));
                // The lead tile's accent fill would make an unmapped control look active; it stays muted.
                boolean highlight = index == 0 && (live || doneTime || alerts) && !editing && !unmapped && !alertsMissing
                        && (!chargeControl || chargeBlocked == null);
                boolean muted = (feature != null || chargeControl) && !featureLive;
                muted |= alerts && !featureLive;
                cell.label.setTextColor(highlight ? Ui.withAlpha(ui.onAccent, 220) : ui.muted);
                int tone = highlight ? ui.onAccent : muted ? ui.muted : ui.text;
                cell.value.setTextColor(tone);
                if (cell.lockState) ui.setIcon(cell.padlock, DisplayUnits.quickAccessIcon(feature, value, cell.entry.icon), tone);
                if (toggle) {
                    boolean available = !editing && featureLive && value != null && (chargeControl
                            ? charge != null && !charge.busy : ble.featureValue(feature.key) != null
                            && decision != null && decision.available());
                    ((TileAction) cell.icon).appearance(selected && available ? ui.accent : ui.muted, available, highlight);
                } else ((TileAction) cell.icon).appearance(highlight ? ui.onAccent : ui.muted, !editing && !alertsMissing, false);
                if (cell.highlighted != highlight || cell.isSelected() != selected) {
                    cell.highlighted = highlight; cell.setSelected(selected); cell.updateBackground();
                }
                cell.label.setVisibility(tileLabels && !state.isEmpty() ? VISIBLE : GONE);
            } else {
                if (!state.contentEquals(cell.value.getText())) {
                    cell.value.setText(state);
                    // A lock word's fit is decided while measuring, and a text of the same height requests no layout.
                    if (cell.lockState) cell.requestLayout();
                }
                ui.setIcon(cell.icon, DisplayUnits.quickAccessIcon(feature, value, cell.entry.icon));
                // A round action drawn with the speedometer that is not Ride gets the speedometer's max marker (glyph).
                if (round && "speed".equals(cell.entry.icon)) ui.setIcon(cell.icon, cell.glyph(cell.entry.icon));
                // A round action has no value line: its state is the circle's fill and what it says (below).
                cell.value.setVisibility(round || iconsOnly || state.isEmpty() ? GONE : VISIBLE);
                cell.value.setTag(state);
                cell.value.setTextColor(featureLive ? ui.text : ui.muted);
                // A round action is filled while it is on: a control switched on, or Record while a ride records.
                if (round) selected |= HomeQuickActions.RECORD.equals(cell.entry.id) && service != null && service.isRecording();
                if (cell.isSelected() != selected) { cell.setSelected(selected); cell.updateBackground(); }
                cell.setAlpha((feature != null || chargeControl) && !featureLive ? .65f : 1f);
                if (alerts && !featureLive) cell.setAlpha(.65f);
            }
            cell.setEnabled(!editing);
            String description = (tiles ? tileSpokenName(cell.entry) : cell.entry.label()) + (state.isEmpty() ? NO_STATE : T.dashboardModelTileDescPart(state))
                    // A control the official app hides only by region (Top speed) says so, as its tap does.
                    + (unmapped || alertsMissing ? (unmapped && decision.reason == CapabilityResolver.Reason.REGION_RULE ? T.dashboardModelTileDescRegion() : T.dashboardModelTileDescModel())
                            : unreadable ? T.dashboardModelTileDescUnreadable() : chargeControl && chargeBlocked != null ? T.dashboardModelTileDescPart(chargeBlocked)
                            : feature != null && !featureLive ? T.dashboardModelTileDescLastSeen() : "")
                    + (toggle ? T.dashboardModelTileDescToggle() : T.dashboardModelTileDescOpen());
            // The RIDE action is spoken as the RIDE pill is ("Open riding mode"), not as a shortcut that opens a menu.
            Msg spoken = tiles ? null : HubText.quickActionDescription(cell.entry.id);
            // Clean's Record while a ride records ends and saves it, and says so as riding mode's record control does.
            if (HomeQuickActions.endsRide(cell.entry.id, service != null && service.isRecording())) description = RidingScreen.endRideSpoken();
            // Idle, it starts one, and says so in the recorder disc's words, as riding mode's Record does: "Start
            // recording", or why it can't yet (the last ride still saving, no scooter connected).
            else if (HomeQuickActions.startsRide(cell.entry.id, service != null && service.isRecording()))
                description = DashboardScreen.recordIdleSpoken(service != null && service.phoneSaving(),
                        service != null && service.ble.ready(), false);
            else if (spoken != null) description = spoken.render();
            cell.setContentDescription(description);
            if (android.os.Build.VERSION.SDK_INT >= 30) cell.setStateDescription(state);
        }
    }

    /** Decorative cue within the tile's single touch target and accessibility action. */
    private final class TileAction extends View {
        String glyph;
        final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        int color;
        boolean highlighted;
        TileAction(boolean toggle) {
            super(host.activity()); color = ui.muted; kind(toggle);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void kind(boolean toggle) {
            glyph = toggle ? "power" : "chevron";
            setTag("setting-action:" + glyph);
            invalidate();
        }
        void appearance(int next, boolean available, boolean onHighlight) {
            setEnabled(available); setAlpha(available ? 1f : .45f);
            if (next != color || highlighted != onHighlight) { color = next; highlighted = onHighlight; invalidate(); }
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            if (size <= 0) return;
            float x = getWidth() / 2f, y = getHeight() / 2f;
            if (glyph.equals("power")) {
                float stroke = ui.dp(1), radius = (size - stroke) / 2f;
                if (highlighted) {
                    // The first tile has an accent background; retain a neutral interior so On stays legible.
                    ring.setStyle(android.graphics.Paint.Style.FILL); ring.setColor(ui.surface);
                    canvas.drawCircle(x, y, radius, ring);
                }
                ring.setStyle(android.graphics.Paint.Style.STROKE); ring.setStrokeWidth(stroke);
                ring.setColor(Ui.withAlpha(highlighted ? ui.onAccent : color, 90));
                canvas.drawCircle(x, y, radius, ring);
                size *= .55f;
            }
            ui.drawIcon(canvas, glyph, color, x, y, size);
        }
    }

    private final class Cell extends LinearLayout {
        final QuickAccessActions.Entry entry;
        final View icon;
        /** Setting tiles only: stands in for a lock word too wide to stay whole. */
        View padlock;
        final TextView label, value;
        boolean highlighted, toggle, lockState;
        /**
         * The glyph a cell shows. Round: Ride keeps the speedometer, and any other action drawn with it (cruise control,
         * maximum speed) gets the speedometer with its max marker, so no two of the four look the same.
         */
        String glyph(String icon) {
            return round && "speed".equals(icon) && !HomeQuickActions.RIDING.equals(entry.id) ? "speed_max" : icon;
        }
        /** Round only: the circle the icon sits in (filled while the action is on). */
        FrameLayout circle;
        Cell(QuickAccessActions.Entry entry) {
            super(host.activity()); this.entry = entry; toggle = entry.directToggle();
            // Keep the fill, pressed ripple and clipping on the same rounded boundary.
            // Narrow rows must not depend on a device-specific RippleDrawable outline
            // when their pressed/selected state or dimensions change.
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            Math.min(ui.dp(cornerRadius()), Math.min(view.getWidth(), view.getHeight()) / 2f));
                }
            });
            setClipToOutline(true);
            if (tiles) {
                setTag("setting-tile:" + entry.id);
                setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
                setClickable(true); setFocusable(true); setMinimumHeight(ui.dp(48));
                LinearLayout words = new LinearLayout(host.activity());
                words.setOrientation(VERTICAL);
                label = ui.text(tileLabel(entry), 12.5f, false);
                label.setTextColor(ui.muted); label.setMaxLines(1);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setLineSpacing(0, 1);
                words.addView(label, new LinearLayout.LayoutParams(-1, -2));
                value = ui.text("", 18, true);
                value.setMaxLines(1); value.setEllipsize(android.text.TextUtils.TruncateAt.END);
                value.setLineSpacing(0, 1);
                words.addView(value, new LinearLayout.LayoutParams(-1, -2));
                padlock = ui.icon(entry.icon, 20, ui.text);
                padlock.setVisibility(GONE);
                words.addView(padlock, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
                addView(words, new LinearLayout.LayoutParams(0, -2, 1));
                icon = new TileAction(toggle);
                int side = ui.dp(toggle ? 28 : 16);
                LinearLayout.LayoutParams indicator = new LinearLayout.LayoutParams(side, side);
                indicator.setMarginStart(ui.dp(6));
                addView(icon, indicator);
                setTooltipText(entry.label()); updateBackground();
                for (View child : new View[]{words, label, value, icon})
                    child.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                return;
            }
            setTag("quick-action:" + entry.id);
            setOrientation(VERTICAL); setGravity(Gravity.CENTER);
            setClickable(true); setFocusable(true); setMinimumHeight(ui.dp(48));
            if (round) {
                // The plan's round button: the icon in a circle, one short name on one line under it, outside the circle.
                // The whole column is the one touch target (at least 48 dp each way) and is spoken once, with its full
                // name and its state (the value line is gone: on is the circle's red fill).
                setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
                circle = new FrameLayout(host.activity());
                circle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                icon = ui.icon(glyph(entry.icon), 22, ui.text);
                circle.addView(icon, new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
                int side = ui.dp(QuickActionLayout.CIRCLE_DP);
                addView(circle, new LinearLayout.LayoutParams(side, side));
                label = ui.text(roundName(entry), QuickActionLayout.NAME_SP, false);
                label.setTextColor(ui.muted);
                label.setGravity(Gravity.CENTER); label.setIncludeFontPadding(false);
                label.setMaxLines(1); label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nameSize = new LinearLayout.LayoutParams(-1, -2);
                nameSize.topMargin = ui.dp(QuickActionLayout.NAME_GAP_DP);
                addView(label, nameSize);
                // Kept for the shared refresh (its text is the spoken state); never shown on a round action.
                value = ui.text("", 11, false);
                value.setVisibility(GONE);
                setTooltipText(entry.label()); updateBackground();
                for (View child : new View[]{icon, label, value})
                    child.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                return;
            }
            icon = ui.icon(entry.icon, 20, ui.accent);
            addView(icon, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
            label = ui.text(entry.label(), 12, false);
            label.setGravity(Gravity.CENTER); label.setIncludeFontPadding(false);
            label.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
            addView(label, new LinearLayout.LayoutParams(-1, -2));
            value = ui.text("", 11, false); value.setTypeface(ui.medium());
            value.setGravity(Gravity.CENTER); value.setIncludeFontPadding(false);
            addView(value, new LinearLayout.LayoutParams(-1, -2));
            setTooltipText(entry.label()); updateBackground();
            // The grouped button announces both its label and the current state once.
            for (View child : new View[]{icon, label, value})
                child.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        /** Setting tiles only: the cue and its size follow the connected model's decision. */
        void toggle(boolean next) {
            toggle = next; ((TileAction) icon).kind(next);
            int side = ui.dp(next ? 28 : 16);
            icon.getLayoutParams().width = side; icon.getLayoutParams().height = side;
            icon.requestLayout();
        }
        void updateBackground() {
            float radius = cornerRadius();
            if (tiles) {
                // White on accentDeep stays above 4.5:1 in both themes.
                int fill = highlighted ? ui.accentDeep : isSelected() ? blendAccent() : editing ? ui.raised : ui.surface;
                setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(highlighted ? ui.onAccent : ui.accent, 40)),
                        ui.shape(fill, radius, isSelected() && !highlighted ? Ui.withAlpha(ui.accent, 100) : 0),
                        ui.shape(android.graphics.Color.WHITE, radius, 0)));
                return;
            }
            if (round) {
                // Raised while off, filled with the accent while on (a control switched on, a ride recording).
                boolean on = isSelected();
                android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
                disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                disc.setColor(on ? ui.accent : ui.raised);
                circle.setBackground(disc);
                ui.setIcon(icon, null, on ? ui.onAccent : ui.text);
                setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 40)), null,
                        ui.shape(android.graphics.Color.WHITE, radius, 0)));
                return;
            }
            setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 40)),
                    ui.shape(isSelected() ? Ui.withAlpha(ui.accent, 35) : ui.raised, radius,
                            isSelected() ? Ui.withAlpha(ui.accent, 100) : 0),
                    ui.shape(android.graphics.Color.WHITE, radius, 0)));
        }
        private float cornerRadius() { return tiles ? 20 : 12; }
        int blendAccent() {
            float a = ui.dark ? .14f : .08f;
            int from = ui.surface, to = ui.accent;
            return android.graphics.Color.rgb(
                    Math.round(android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * a),
                    Math.round(android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * a),
                    Math.round(android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * a));
        }
        /** Setting tile content at this width: value shrinks to fit, action cue and label drop when needed. */
        void measureTile(int width, boolean refit, int rowLimit) {
            if (!refit) {
                measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                return;
            }
            float factor = Math.max(1, ui.textScale * getResources().getConfiguration().fontScale);
            value.setVisibility(VISIBLE); padlock.setVisibility(GONE);
            icon.setVisibility(width >= ui.dp(110 * factor) ? VISIBLE : GONE);
            label.setVisibility(tileLabels && !"".equals(label.getTag()) ? VISIBLE : GONE);
            int vertical = ui.dp(tileCompact ? 3 : 8);
            setPadding(ui.dp(14), vertical, ui.dp(12), vertical);
            int text = width - getPaddingLeft() - getPaddingRight()
                    - (icon.getVisibility() == VISIBLE ? icon.getLayoutParams().width + ui.dp(6) : 0);
            // Label and value are single lines without extra spacing, so their font metrics are their heights.
            int room = rowLimit - getPaddingTop() - getPaddingBottom() - (label.getVisibility() == VISIBLE
                    ? label.getPaint().getFontMetricsInt().descent - label.getPaint().getFontMetricsInt().ascent : 0);
            float size = 18 * ui.textScale, floor = 12 * ui.textScale;
            // A long scheduled time gets the space before its decorative cue does.
            value.setTextSize(floor);
            if (toggle && icon.getVisibility() == VISIBLE
                    && value.getPaint().measureText(value.getText().toString()) > text) {
                icon.setVisibility(GONE);
                text += icon.getLayoutParams().width + ui.dp(6);
            }
            value.setTextSize(size);
            while (size > floor && (value.getPaint().measureText(value.getText().toString()) > text
                    || value.getPaint().getFontMetricsInt().descent - value.getPaint().getFontMetricsInt().ascent > room)) {
                size -= .5f;
                value.setTextSize(size);
            }
            // At the floor already: an ellipsis could make the two states read alike, so the word gives way to the padlock.
            if (DisplayUnits.lockWordDrops(lockState, value.getPaint().measureText(value.getText().toString()), text)) {
                value.setVisibility(GONE); padlock.setVisibility(VISIBLE);
                // The padded text column can be narrower than the glyph: the padlock then takes the whole tile, centred.
                int full = ui.dp(20);
                boolean bleed = DisplayUnits.lockPadlockFillsTile(text, full);
                if (bleed) setPadding(0, vertical, 0, vertical);
                LinearLayout.LayoutParams padlockSize = (LinearLayout.LayoutParams) padlock.getLayoutParams();
                padlockSize.width = padlockSize.height = DisplayUnits.lockPadlockSide(width, full, ui.dp(LOCK_PADLOCK_MIN_DP));
                padlockSize.gravity = bleed ? Gravity.CENTER_HORIZONTAL : Gravity.START;
            }
            measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        /**
         * Round: the circle at its size for this column and the one short name on one line under it, a little smaller
         * only where it does not fit at full size (QuickActionLayout).
         */
        void measureRound(int width) {
            float density = getResources().getDisplayMetrics().density;
            int side = ui.dp(QuickActionLayout.circleDp(width / density));
            LinearLayout.LayoutParams circleSize = (LinearLayout.LayoutParams) circle.getLayoutParams();
            if (circleSize.width != side) { circleSize.width = circleSize.height = side; }
            int pad = ui.dp(QuickActionLayout.SIDE_DP);
            setPadding(pad, ui.dp(2), pad, ui.dp(4));
            float room = width - 2 * pad;
            // Measured at the full size on a copy of the paint, so the view's size changes only when the choice does.
            QuickActionLayout.Name name = QuickActionLayout.name(roundName(entry), room,
                    fullSize(label, QuickActionLayout.NAME_SP)::measureText);
            label.setVisibility(name.text == null ? GONE : VISIBLE);
            if (name.text != null) {
                float size = QuickActionLayout.NAME_SP * ui.textScale * name.scale;
                if (Math.abs(label.getTextSize() - android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                        size, getResources().getDisplayMetrics())) > .5f) label.setTextSize(size);
                if (TileRung.needsSet(name.text, label.getText())) label.setText(name.text);
            }
            measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        /** A copy of a text's paint at {@code sp} times the app's text scale (and the system's, as sp is). */
        private android.text.TextPaint fullSize(TextView text, float sp) {
            android.text.TextPaint paint = new android.text.TextPaint(text.getPaint());
            paint.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                    sp * ui.textScale, getResources().getDisplayMetrics()));
            return paint;
        }
        void measureContent(int width, boolean compact) {
            label.setVisibility(iconsOnly ? GONE : VISIBLE);
            icon.setAlpha(editing && iconsOnly ? .22f : 1f);
            value.setVisibility(iconsOnly || "".equals(value.getTag()) ? GONE : VISIBLE);
            setPadding(ui.dp(4), ui.dp(compact ? 3 : 7), ui.dp(4), ui.dp(compact ? 3 : 7));
            int side = ui.dp(compact ? 18 : 20);
            icon.getLayoutParams().width = icon.getLayoutParams().height = side;
            ((LinearLayout.LayoutParams)label.getLayoutParams()).topMargin = ui.dp(compact ? 2 : 4);
            ((LinearLayout.LayoutParams)value.getLayoutParams()).topMargin = ui.dp(2);
            label.setTextSize((compact ? 11 : 12) * ui.textScale);
            // Up to two lines of whole words, never a smaller size: the name, then its short rung, then the icon alone.
            int room = width - getPaddingLeft() - getPaddingRight();
            String full = entry.label().render(), brief = entry.shortLabel().render();
            CharSequence rung = StatusRungs.wholeLines(full, label, room, 2) ? full : StatusRungs.wholeLines(brief, label, room, 2) ? brief : null;
            if (rung == null) label.setVisibility(GONE);
            else if (TileRung.needsSet(rung, label.getText())) label.setText(rung);
            value.setTextSize((compact ? 10 : 11) * ui.textScale);
            // A lock state too wide for whole words drops; the open or closed padlock and the spoken state remain.
            if (lockState && !StatusRungs.wholeLines(value.getText().toString(), value, room, 2)) value.setVisibility(GONE);
            measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (tiles) { measureTiles(MeasureSpec.getSize(widthSpec)); return; }
        if (round) { measureRound(MeasureSpec.getSize(widthSpec)); return; }
        int width = MeasureSpec.getSize(widthSpec), gap = ui.dp(6);
        iconsOnly = width < ui.dp(100);
        columns = Math.min(Math.min(4, cells.size()), Math.max(1,
                (width + gap) / Math.max(1, ui.dp(72 * ui.textScale) + gap)));
        if (cells.size() == 4 && columns == 3) columns = 2;
        int cellWidth = Math.max(1, (width - (columns - 1) * gap) / columns);
        int rows = (cells.size() + columns - 1) / columns;
        int natural = measureCells(cellWidth, false);
        int required = rows * natural + (rows - 1) * gap;
        if (budget > 0 && required > budget) {
            natural = measureCells(cellWidth, true);
            required = rows * natural + (rows - 1) * gap;
        }
        // Give back a few redundant padding pixels, never crop a label to hide a scrollbar.
        rowHeight = natural;
        if (budget >= required) rowHeight = Math.max(natural, (budget - (rows - 1) * gap) / rows);
        for (Cell cell : cells) cell.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, rows * rowHeight + (rows - 1) * gap);
    }

    /** Separate cards: wrap below ~88 dp of text each when the height holds the rows, else stay on one row and drop labels. */
    private void measureTiles(int width) {
        int gap = ui.dp(10), count = cells.size();
        if (count == 0) { setMeasuredDimension(width, 0); return; }
        float factor = Math.max(1, ui.textScale * getResources().getConfiguration().fontScale);
        // Wrap only into rows the height can hold (48 dp each); a one-row card keeps every tile on one row.
        int maxRows = budget > 0 ? Math.max(1, (budget + gap) / (ui.dp(48) + gap)) : count;
        columns = count;
        while (columns > 1 && (width - (columns - 1) * gap) / columns < ui.dp(88 * factor)) {
            int next = columns == 4 ? 2 : columns - 1;
            if ((count + next - 1) / next > maxRows) break;
            columns = next;
        }
        int rows = (count + columns - 1) / columns;
        int cellWidth = Math.max(1, (width - (columns - 1) * gap) / columns);
        // Too narrow for a label beside the value: show the value alone (the description keeps the label).
        boolean cramped = columns > 1 && cellWidth < ui.dp(88 * factor);
        int perRow = budget > 0 ? (budget - (rows - 1) * gap) / rows : Integer.MAX_VALUE;
        int natural;
        if (width != fittedWidth || budget != fittedBudget) {
            // Choose once per size so visibility and size changes do not request layout on every pass.
            fittedWidth = width; fittedBudget = budget;
            tileLabels = !cramped; tileCompact = false;
            natural = measureTileCells(cellWidth, true, perRow);
            if (natural > perRow) { tileCompact = true; natural = measureTileCells(cellWidth, true, perRow); }
            if (natural > perRow) { tileLabels = false; natural = measureTileCells(cellWidth, true, perRow); }
        } else natural = measureTileCells(cellWidth, false, perRow);
        rowHeight = perRow != Integer.MAX_VALUE && perRow >= natural ? perRow : natural;
        for (Cell cell : cells) cell.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, rows * rowHeight + (rows - 1) * gap);
    }

    /** Clean's round actions: four equal columns while each holds its circle and words (QuickActionLayout), else two. */
    private void measureRound(int width) {
        int gap = ui.dp(QuickActionLayout.GAP_DP), count = cells.size();
        if (count == 0) { setMeasuredDimension(width, 0); return; }
        float density = getResources().getDisplayMetrics().density;
        columns = QuickActionLayout.columns(width / density, count);
        int cellWidth = Math.max(1, (width - (columns - 1) * gap) / columns);
        int rows = (count + columns - 1) / columns;
        rowHeight = ui.dp(48);
        for (Cell cell : cells) { cell.measureRound(cellWidth); rowHeight = Math.max(rowHeight, cell.getMeasuredHeight()); }
        for (Cell cell : cells) cell.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, rows * rowHeight + (rows - 1) * gap);
    }

    private int measureTileCells(int width, boolean refit, int rowLimit) {
        int height = ui.dp(48);
        for (Cell cell : cells) { cell.measureTile(width, refit, rowLimit); height = Math.max(height, cell.getMeasuredHeight()); }
        return height;
    }

    private int measureCells(int width, boolean compact) {
        int height = ui.dp(48);
        for (Cell cell : cells) { cell.measureContent(width, compact); height = Math.max(height, cell.getMeasuredHeight()); }
        return height;
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (cells.isEmpty()) return;
        int gap = ui.dp(tiles ? 10 : 6), width = cells.get(0).getMeasuredWidth();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < cells.size(); i++) {
            int x = (i % columns) * (width + gap), y = (i / columns) * (rowHeight + gap);
            if (rtl) x = getWidth() - x - width;
            cells.get(i).layout(x, y, x + width, y + rowHeight);
        }
    }

    /** The model's mapped choices include this value (no choices known means nothing to judge it by). */
    private static boolean offers(CapabilityResolver.Decision decision, int value) {
        int[] choices = decision.values(); if (choices.length == 0) return true;
        for (int choice : choices) if (choice == value) return true;
        return false;
    }
    /** Setting tiles show short names: "Max. speed" (the abbreviated rung of Maximum speed), "Charge sched.", a control's TileShortLabels rung, else the name's short rung. */
    /** A round action's one short name (HubText.quickActionRoundName), else its short rung. */
    private static String roundName(QuickAccessActions.Entry entry) {
        Msg name = HubText.quickActionRoundName(entry.id, entry.featureKey);
        return name != null ? name.render() : tileLabel(entry);
    }

    private static String tileLabel(QuickAccessActions.Entry entry) {
        if (TOP_SPEED_ID.equals(entry.id)) return T.dashboardModelTileTopSpeed();
        if (CHARGE_SCHEDULE_ID.equals(entry.id)) return T.dashboardModelTileChargeScheduleShort();
        Msg rung = TileShortLabels.forFeature(entry.featureKey);
        if (rung != null) return rung.render();
        return entry.shortLabel().render();
    }
    /** A Setting tile is announced by the full name (the label's room does not limit speech): Maximum speed in full, or Charge schedule. */
    private static String tileSpokenName(QuickAccessActions.Entry entry) {
        if (CHARGE_SCHEDULE_ID.equals(entry.id)) return T.dashboardModelTileChargeSchedule();
        return entry.label().render();
    }
}
