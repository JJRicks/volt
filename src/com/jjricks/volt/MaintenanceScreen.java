package com.jjricks.volt;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * The Maintenance page, a subpage of the Scooter tab (maintenance plan, PR 2): the master switch, Due now,
 * Coming up, Add reminder, the model's manual intervals and the history count, with the add and edit sheet,
 * the reminder detail, Mark done, Snooze and delete. Reminders are in-app only in this PR: no notification.
 *
 * <p>Everything is local and works disconnected; the distance comes from the odometer readings
 * {@link ScooterService} feeds into {@link MaintenanceKeeper}. While simulating, the page shows the
 * simulator's memory-only reminders. Volt sends no scooter command from here.
 */
final class MaintenanceScreen {
    /** The Scooter tab index's search section and row (its search still finds the page). */
    static final String SECTION = "maintenance", ROW = "maintenance_reminders";
    /** The Hub page (Service › Maintenance reminders) and the preference that remembers the page shown. */
    static final String PAGE = HubPages.SERVICE_MAINTENANCE, LAST_PAGE = "last_page";
    /**
     * A maintenance notice's tap waiting for the next Scooter tab page (MainActivity), so it survives a cold start into
     * unfinished setup: SECTION while pending, as main's Scooter tab remembered its open Maintenance page in this key.
     */
    static final String PENDING_PREF = "scooter_section";
    /** The row's English search words: none, its catalog search words serve every language. */
    static final String NO_WORDS = "";
    private static final String UNITS_PREF = "units", UNITS_DEFAULT = "mi", RESET_SEEN = "reset_seen";
    private static final String TAG_SWITCH = "maintenance_switch", TAG_ADD = "maintenance_add", TAG_SAVE = "maintenance_save",
            TAG_NAME = "maintenance_name", TAG_DISTANCE = "maintenance_distance", TAG_TIME = "maintenance_time",
            TAG_DONE = "maintenance_mark_done", TAG_SNOOZE = "maintenance_snooze", TAG_DUE = "maintenance_due_card",
            TAG_CONFIRM = "maintenance_confirm", TAG_DELETE = "maintenance_delete", TAG_EDIT = "maintenance_edit",
            TAG_MODE = "maintenance_mode", TAG_UNIT = "maintenance_unit", TAG_CHANGE = "maintenance_change";
    private static final String TAG_NOTIFY = "maintenance_notify", TAG_DISTANCE_NOTE = "maintenance_distance_note";

    private final ScreenHost host;
    private final Ui ui;
    private final Runnable back;
    private final boolean wide;
    /** Its sheets open as a centred dialog of at most 480 dp (a wide window), not a full-width bottom sheet. */
    private final boolean centredSheets;
    private final LinearLayout view, body;
    private String shown;
    private AlertDialog sheet;

    /** back null: a Hub page, whose header already shows the title and the back link. */
    MaintenanceScreen(ScreenHost host, Runnable back, boolean wide) { this(host, back, wide, wide); }

    /**
     * wide lays the page out in two columns; centredSheets opens its sheets centred, which a wide window keeps even where
     * the page is one column in the wide Home's page pane (#394 early review, 6).
     */
    MaintenanceScreen(ScreenHost host, Runnable back, boolean wide, boolean centredSheets) {
        this.host = host; this.ui = host.ui(); this.back = back; this.wide = wide; this.centredSheets = centredSheets;
        view = column();
        if (back != null) header();
        body = column(); view.addView(body, new LinearLayout.LayoutParams(-1, -2));
        refresh();
    }

    private void header() {
        LinearLayout backRow = new LinearLayout(host.activity()); backRow.setGravity(Gravity.CENTER_VERTICAL); backRow.setMinimumHeight(ui.dp(48));
        backRow.addView(ui.icon("back", 16, ui.accent), new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        TextView backText = ui.text(T.mainControlsBack(), 13.5f, false); backText.setTypeface(ui.medium()); backText.setTextColor(ui.accent);
        backText.setPadding(ui.dp(4), 0, ui.dp(10), 0); backRow.addView(backText);
        backRow.setClickable(true); backRow.setFocusable(true); backRow.setContentDescription(T.mainControlsBackDescription());
        backRow.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        backRow.setOnClickListener(v -> back.run());
        view.addView(backRow, new LinearLayout.LayoutParams(-2, -2));
        TextView title = ui.text(T.maintenanceTitle(), 24, true); title.setPadding(0, 0, 0, ui.dp(10));
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        view.addView(title);
    }

    View view() { return view; }

    void close() { if (sheet != null) { sheet.dismiss(); sheet = null; } }

    // ---- Data ---------------------------------------------------------------------------------

    private ScooterService service() { return host.service(); }
    private MaintenanceKeeper keeper() { ScooterService s = service(); return s == null ? null : s.maintenance; }
    private String scope() { ScooterService s = service(); return s == null ? "" : s.maintenanceScope(); }
    private SharedPreferences settings() { ScooterService s = service(); return s == null ? null : s.settings; }
    private String units() { SharedPreferences p = settings(); return p == null ? UNITS_DEFAULT : p.getString(UNITS_PREF, UNITS_DEFAULT); }
    private static ZoneId zone() { return ZoneId.systemDefault(); }
    private MaintenanceStore.Settings load() { MaintenanceKeeper k = keeper(); String scope = scope(); return k == null || scope.isEmpty() ? null : k.load(scope); }

    /** Applies a change and redraws; refused while a backup or restore runs. */
    private boolean change(java.util.function.Consumer<MaintenanceStore.Settings> change) {
        ScooterService s = service(); MaintenanceKeeper k = keeper(); String scope = scope();
        if (s == null || k == null || scope.isEmpty()) return false;
        String unavailable = s.dataMaintenanceUnavailable();
        if (unavailable != null) { host.toast(unavailable); return false; }
        k.update(scope, change);
        host.redraw();
        return true;
    }

    private static String date(long wall) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM, Fmt.locale(Locale.getDefault())).format(new Date(wall));
    }

    // ---- The page -----------------------------------------------------------------------------

    /** Rebuilds the page when what it shows changed: the document, the units, the day or the theme. */
    void refresh() {
        MaintenanceStore.Settings s = load();
        long now = System.currentTimeMillis();
        String key = (s == null ? "" : MaintenanceStore.encode(s)) + units() + scope() + LocalDate.now(zone()) + (service() != null && service().simulating());
        if (key.equals(shown)) return;
        shown = key;
        body.removeAllViews();
        if (s == null) { body.addView(ui.caption(T.commonNoScooter())); return; }
        LinearLayout left = body, right = body;
        if (wide && s.on) {
            LinearLayout panes = new LinearLayout(host.activity()); panes.setOrientation(LinearLayout.HORIZONTAL); panes.setBaselineAligned(false);
            left = column(); right = column();
            panes.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1); rp.leftMargin = ui.dp(20); panes.addView(right, rp);
            body.addView(panes, new LinearLayout.LayoutParams(-1, -2));
        }
        left.addView(switchCard(s));
        if (!s.on) {
            TextView explain = ui.caption(T.maintenanceOffExplain()); explain.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(10)); body.addView(explain);
            body.addView(disclaimer());
            return;
        }
        boolean anyDistance = false; for (MaintenancePolicy.Reminder r : s.reminders) anyDistance |= r.byDistance();
        Object seen = s.extra.get(RESET_SEEN);
        if (s.distance.resetWall > 0 && anyDistance && !(seen instanceof Number && ((Number) seen).longValue() == s.distance.resetWall)) {
            long reset = s.distance.resetWall;
            left.addView(note(T.maintenanceReset(), () -> change(x -> x.extra.put(RESET_SEEN, reset))));
        }
        if (anyDistance && s.distance.stale(now)) left.addView(note(staleNote(s), null));
        List<MaintenancePolicy.Reminder> due = MaintenanceView.dueNow(s, now, zone()), coming = MaintenanceView.comingUp(s, now, zone());
        if (!due.isEmpty()) {
            left.addView(ui.sectionLabel(T.maintenanceDueNow()));
            for (MaintenancePolicy.Reminder r : due) left.addView(dueCard(s, r, now));
        }
        left.addView(ui.sectionLabel(T.maintenanceComingUp()));
        LinearLayout list = ui.groupCard();
        if (coming.isEmpty()) { TextView none = ui.caption(T.maintenanceNone()); none.setPadding(ui.dp(2), ui.dp(16), ui.dp(2), ui.dp(16)); list.addView(none); }
        for (int i = 0; i < coming.size(); i++) { if (i > 0) ui.divider(list); list.addView(reminderRow(s, coming.get(i), now)); }
        left.addView(list);
        right.addView(addButton(s));
        right.addView(manual(s));
        right.addView(ui.sectionLabel(T.maintenanceHistory()));
        int done = MaintenanceView.doneCount(s);
        TextView history = ui.caption(done == 0 ? T.maintenanceHistoryNone() : T.maintenanceHistoryDone(done, done));
        history.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8)); right.addView(history);
        right.addView(disclaimer());
    }

    private View switchCard(MaintenanceStore.Settings s) {
        LinearLayout card = ui.groupCard();
        LinearLayout line = new LinearLayout(host.activity()); line.setGravity(Gravity.CENTER_VERTICAL); line.setMinimumHeight(ui.dp(64));
        line.setPadding(0, ui.dp(8), 0, ui.dp(8));
        LinearLayout words = column();
        TextView name = ui.text(T.maintenanceSwitch(), 15, false); words.addView(name);
        TextView detail = ui.caption(T.maintenanceSwitchDetail()); detail.setPadding(0, ui.dp(3), 0, 0); words.addView(detail);
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Haptics.FeedbackSwitch(host.activity()); toggle.setShowText(false); toggle.setMinHeight(ui.dp(48)); ui.styleSwitch(toggle);
        toggle.setChecked(s.on); toggle.setTag(TAG_SWITCH); toggle.setContentDescription(T.maintenanceSwitch());
        toggle.setOnCheckedChangeListener((button, on) -> { if (on != s.on && !change(x -> x.on = on)) button.setChecked(s.on); });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2); tp.leftMargin = ui.dp(10); line.addView(toggle, tp);
        card.addView(line);
        return card;
    }

    private View note(String text, Runnable dismiss) {
        LinearLayout note = new LinearLayout(host.activity()); note.setGravity(Gravity.CENTER_VERTICAL);
        note.setPadding(ui.dp(14), ui.dp(10), ui.dp(10), ui.dp(10));
        note.setBackground(ui.shape(Ui.withAlpha(ui.orange, ui.dark ? 34 : 26), 16, Ui.withAlpha(ui.orange, 90)));
        TextView words = ui.text(text, 13, false); note.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (dismiss != null) {
            TextView ok = pill(T.commonOk(), null, false, dismiss);
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, -2); op.leftMargin = ui.dp(8); note.addView(ok, op);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(10); note.setLayoutParams(p);
        return note;
    }

    /** The stale-odometer note, on the page and in the add and edit sheets: the last reading's date. */
    private static String staleNote(MaintenanceStore.Settings s) { return T.maintenanceStale(date(s.distance.lastWall)); }

    private View disclaimer() {
        TextView line = ui.caption(T.maintenanceDisclaimer()); line.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(4));
        return line;
    }

    private View dueCard(MaintenanceStore.Settings s, MaintenancePolicy.Reminder r, long now) {
        MaintenancePolicy.Status status = MaintenanceView.status(s, r, now, zone());
        LinearLayout card = ui.card(); card.setTag(TAG_DUE);
        card.setBackground(ui.shape(Ui.withAlpha(ui.accent, ui.dark ? 36 : 22), 20, Ui.withAlpha(ui.accent, ui.dark ? 100 : 80)));
        LinearLayout head = new LinearLayout(host.activity()); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = ui.text(MaintenanceView.name(r), 16, true); head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(badge(T.maintenanceBadgeDue(), ui.accent));
        card.addView(head);
        TextView left = ui.text(MaintenanceView.left(r, status, units(), now, MaintenanceScreen::date), 13.5f, false); left.setPadding(0, ui.dp(6), 0, 0);
        card.addView(left);
        TextView every = ui.caption(MaintenanceView.interval(r, units())); every.setPadding(0, ui.dp(3), 0, ui.dp(4)); card.addView(every);
        LinearLayout actions = new LinearLayout(host.activity()); actions.setGravity(Gravity.CENTER_VERTICAL);
        View done = action(T.maintenanceMarkDone(), T.maintenanceMarkDoneShort(), "check", true, () -> markDone(r.id)); done.setTag(TAG_DONE);
        View snooze = action(T.maintenanceSnooze(), null, "clock", false, () -> snooze(r.id)); snooze.setTag(TAG_SNOOZE);
        actions.addView(done, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1); sp.leftMargin = ui.dp(8); actions.addView(snooze, sp);
        card.addView(actions);
        card.setClickable(true); card.setFocusable(true); card.setOnClickListener(v -> detail(r.id));
        return card;
    }

    private View reminderRow(MaintenanceStore.Settings s, MaintenancePolicy.Reminder r, long now) {
        MaintenancePolicy.Status status = MaintenanceView.status(s, r, now, zone());
        LinearLayout row = column(); row.setTag(r.id); row.setPadding(ui.dp(2), ui.dp(12), ui.dp(2), ui.dp(12));
        LinearLayout head = new LinearLayout(host.activity()); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = ui.text(MaintenanceView.name(r), 15, false); head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (status.state == MaintenancePolicy.State.SOON) head.addView(badge(T.maintenanceBadgeSoon(), ui.orange));
        else if (status.state == MaintenancePolicy.State.SNOOZED) head.addView(badge(T.maintenanceBadgeSnoozed(), ui.muted));
        View chevron = ui.icon("chevron", 15, ui.muted); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)); cp.leftMargin = ui.dp(8);
        head.addView(chevron, cp);
        row.addView(head);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, ui.dp(6)); bp.topMargin = ui.dp(8); bp.bottomMargin = ui.dp(6);
        row.addView(new Progress(status), bp);
        row.addView(new Pair(ui.caption(MaintenanceView.interval(r, units())), leftText(MaintenanceView.left(r, status, units(), now, MaintenanceScreen::date), status)));
        // Saved before any odometer reading: its distance part starts counting at the next connection.
        if (MaintenancePolicy.pending(r)) { TextView next = ui.caption(T.maintenanceDistanceNext()); next.setPadding(0, ui.dp(4), 0, 0); row.addView(next); }
        row.setClickable(true); row.setFocusable(true);
        row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        row.setOnClickListener(v -> detail(r.id));
        return row;
    }

    private TextView leftText(String text, MaintenancePolicy.Status status) {
        TextView left = ui.text(text, 12.5f, false);
        left.setTextColor(status.state == MaintenancePolicy.State.SOON ? ui.orange : ui.muted);
        return left;
    }

    private View addButton(MaintenanceStore.Settings s) {
        TextView add = ui.text(T.maintenanceAdd(), 14, true); add.setTypeface(ui.semibold()); add.setGravity(Gravity.CENTER);
        add.setMinHeight(ui.dp(52)); add.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); add.setTextColor(ui.text);
        GradientDrawable dashed = new GradientDrawable(); dashed.setCornerRadius(ui.dp(14)); dashed.setColor(Color.TRANSPARENT);
        dashed.setStroke(ui.dp(1.5f), ui.outline, ui.dp(6), ui.dp(4));
        add.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)), dashed, null));
        add.setClickable(true); add.setFocusable(true); add.setContentDescription(T.maintenanceAdd()); add.setTag(TAG_ADD);
        add.setOnClickListener(v -> edit(null));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = ui.dp(wide ? 0 : 4); p.bottomMargin = ui.dp(4); add.setLayoutParams(p);
        return add;
    }

    private View manual(MaintenanceStore.Settings s) {
        LinearLayout box = column();
        ScooterService service = service(); ScooterProfiles.Profile p = service == null || service.profiles == null ? null : service.profiles.active();
        String model = p == null ? "" : ModelRegistry.modelName(service.modelDescriptor(p));
        MaintenanceDefaults.Result result = MaintenanceDefaults.forModel(model);
        box.addView(ui.sectionLabel(model.isEmpty() ? T.maintenanceManualTitleUnknown() : T.maintenanceManualTitle(model)));
        if (result.kind != MaintenanceDefaults.Kind.LISTED) {
            TextView line = ui.caption(result.kind == MaintenanceDefaults.Kind.UNKNOWN_MODEL ? T.maintenanceManualUnknown() : T.maintenanceManualNone());
            line.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4)); box.addView(line);
            return box;
        }
        TextView line = ui.caption(T.maintenanceManualListed()); line.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8)); box.addView(line);
        LinearLayout list = ui.groupCard();
        for (int i = 0; i < result.intervals.size(); i++) { if (i > 0) ui.divider(list); list.addView(manualRow(s, result.intervals.get(i))); }
        box.addView(list);
        return box;
    }

    /** One manual interval: its task, how often and the page it comes from, with Add (or Added once a reminder has the task). */
    private View manualRow(MaintenanceStore.Settings s, MaintenanceDefaults.Interval interval) {
        MaintenancePolicy.Reminder suggested = MaintenanceView.suggestion(interval);
        LinearLayout row = new LinearLayout(host.activity()); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(2), ui.dp(10), ui.dp(2), ui.dp(10));
        LinearLayout words = column();
        words.addView(ui.text(MaintenanceView.taskName(interval.task), 15, false));
        TextView every = ui.text(MaintenanceView.interval(suggested, units()), 13, false); every.setPadding(0, ui.dp(2), 0, 0); words.addView(every);
        TextView source = ui.caption(MaintenanceView.source(interval.source)); source.setPadding(0, ui.dp(2), 0, 0); words.addView(source);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        boolean added = MaintenanceView.hasTask(s, interval.task);
        TextView add = pill(added ? T.maintenanceManualAdded() : T.maintenanceManualAdd(), null, false, added ? null : () -> edit(null, suggested));
        if (added) { add.setEnabled(false); add.setClickable(false); add.setAlpha(0.55f); }
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2); ap.leftMargin = ui.dp(10); row.addView(add, ap);
        return row;
    }

    // ---- Small views --------------------------------------------------------------------------

    private LinearLayout column() { LinearLayout c = new LinearLayout(host.activity()); c.setOrientation(LinearLayout.VERTICAL); return c; }

    private TextView badge(String text, int color) {
        TextView badge = ui.text(text, 10.5f, true); badge.setTypeface(ui.semibold()); badge.setTextColor(color); badge.setLetterSpacing(0.06f);
        badge.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3)); badge.setBackground(ui.shape(Ui.withAlpha(color, 34), 100, 0));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.leftMargin = ui.dp(8); badge.setLayoutParams(p);
        return badge;
    }

    /** A small pill: red when primary, ghost otherwise. */
    private TextView pill(String label, String icon, boolean primary, Runnable action) {
        TextView pill = ui.text(label, 12.5f, true); pill.setGravity(Gravity.CENTER); pill.setMinHeight(ui.dp(40)); pill.setMinWidth(ui.dp(56));
        pill.setPadding(ui.dp(14), 0, ui.dp(14), 0); pill.setTextColor(primary ? ui.onAccent : ui.text);
        pill.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.withAlpha(Color.WHITE, 50)),
                primary ? ui.shape(ui.accent, 100, 0) : ui.shape(Color.TRANSPARENT, 100, ui.outline), null));
        pill.setClickable(true); pill.setFocusable(true); pill.setContentDescription(label);
        pill.setOnClickListener(v -> { if (action != null) action.run(); });
        ui.pressFeedback(pill);
        return pill;
    }

    /**
     * A Mark done or Snooze action with its icon: the full label, then its short rung, then the icon alone
     * (the narrow-label ladder). The spoken label stays the full one.
     */
    private View action(String full, String shortLabel, String icon, boolean primary, Runnable run) {
        LinearLayout pill = new LinearLayout(host.activity()); pill.setGravity(Gravity.CENTER); pill.setMinimumHeight(ui.dp(44));
        pill.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        int color = primary ? ui.onAccent : ui.text;
        pill.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.withAlpha(Color.WHITE, 50)),
                primary ? ui.shape(ui.accent, 100, 0) : ui.shape(Color.TRANSPARENT, 100, ui.outline), null));
        pill.addView(ui.icon(icon, 16, color), new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        TextView label = ui.text(full, 12.5f, true); label.setTextColor(color); label.setGravity(Gravity.CENTER); label.setMaxLines(1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.leftMargin = ui.dp(6); pill.addView(label, lp);
        StatusRungs rungs = new StatusRungs(label); rungs.iconBeside = true; label.addOnLayoutChangeListener(rungs);
        rungs.show(shortLabel == null ? new CharSequence[]{full} : new CharSequence[]{full, shortLabel});
        pill.setClickable(true); pill.setFocusable(true); pill.setContentDescription(full);
        pill.setOnClickListener(v -> run.run());
        ui.pressFeedback(pill);
        return pill;
    }

    /** A reminder's progress: green, amber from 80 %, red when due, grey while snoozed or waiting. */
    private final class Progress extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float fraction; private final int color;
        Progress(MaintenancePolicy.Status status) {
            super(host.activity());
            fraction = (float) Math.max(0, Math.min(1, status.fraction));
            color = status.state == MaintenancePolicy.State.DUE ? ui.accent : status.state == MaintenancePolicy.State.SOON ? ui.orange
                    : status.state == MaintenancePolicy.State.OK ? ui.ok : ui.muted;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            float h = getHeight(), w = getWidth(), r = h / 2;
            paint.setColor(ui.raised); canvas.drawRoundRect(new RectF(0, 0, w, h), r, r, paint);
            if (fraction <= 0) return;
            paint.setColor(color);
            float fill = Math.max(h, w * fraction);
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            canvas.drawRoundRect(rtl ? new RectF(w - fill, 0, w, h) : new RectF(0, 0, fill, h), r, r, paint);
        }
    }

    /** A progress ring for the detail sheet. */
    private final class Ring extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float fraction; private final int color;
        Ring(MaintenancePolicy.Status status) {
            super(host.activity());
            fraction = (float) Math.max(0, Math.min(1, status.fraction));
            color = status.state == MaintenancePolicy.State.DUE ? ui.accent : status.state == MaintenancePolicy.State.SOON ? ui.orange
                    : status.state == MaintenancePolicy.State.OK ? ui.ok : ui.muted;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            float stroke = ui.dp(7), size = Math.min(getWidth(), getHeight());
            paint.setStrokeWidth(stroke);
            RectF box = new RectF(stroke / 2, stroke / 2, size - stroke / 2, size - stroke / 2);
            paint.setColor(ui.raised); canvas.drawArc(box, 0, 360, false, paint);
            if (fraction > 0) { paint.setColor(color); canvas.drawArc(box, -90, 360 * fraction, false, paint); }
        }
    }

    /** Two texts side by side (interval, what is left), stacked when large text leaves no room for both. */
    private final class Pair extends LinearLayout {
        private final TextView first, second;
        Pair(TextView first, TextView second) {
            super(host.activity()); this.first = first; this.second = second;
            addView(first, new LayoutParams(0, -2, 1)); addView(second, new LayoutParams(-2, -2));
            second.setGravity(Gravity.END);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int available = MeasureSpec.getSize(widthSpec);
            float need = android.text.Layout.getDesiredWidth(first.getText(), first.getPaint()) + android.text.Layout.getDesiredWidth(second.getText(), second.getPaint()) + ui.dp(12);
            boolean stack = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && need > available;
            setOrientation(stack ? VERTICAL : HORIZONTAL);
            LayoutParams a = (LayoutParams) first.getLayoutParams(); a.width = stack ? -1 : 0; a.weight = stack ? 0 : 1;
            LayoutParams b = (LayoutParams) second.getLayoutParams(); b.width = stack ? -1 : -2; b.topMargin = stack ? ui.dp(2) : 0; b.setMarginStart(stack ? 0 : ui.dp(12));
            second.setGravity(stack ? Gravity.START : Gravity.END);
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    // ---- Sheets -------------------------------------------------------------------------------

    private LinearLayout sheetBody(String title, String explanation) {
        close();
        return host.sheetContent(title, explanation, this::close);
    }

    private void show(LinearLayout content) {
        sheet = host.showSheet(content);
        android.view.Window window = sheet.getWindow();
        // Wide screens: a centered dialog of at most 480 dp instead of a full-width sheet.
        if (centredSheets && window != null) {
            int width = Math.min(ui.dp(480), host.activity().getResources().getDisplayMetrics().widthPixels - ui.dp(32));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT); window.setGravity(Gravity.CENTER);
        }
    }

    private TextView fieldLabel(String text) {
        TextView label = ui.text(text, 12.5f, true); label.setTypeface(ui.semibold()); label.setTextColor(ui.muted);
        label.setPadding(ui.dp(2), ui.dp(14), ui.dp(2), ui.dp(6));
        return label;
    }

    private EditText field(String hint, boolean number) {
        EditText field = new EditText(host.activity()); field.setSingleLine(true); field.setHint(hint == null ? "" : hint);
        field.setTextColor(ui.text); field.setHintTextColor(ui.muted); field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15 * ui.textScale);
        field.setTypeface(ui.regular()); field.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); field.setMinHeight(ui.dp(48));
        field.setBackground(ui.shape(ui.surface, 14, ui.raised));
        if (number) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            if (Build.VERSION.SDK_INT >= 26) field.setKeyListener(android.text.method.DigitsKeyListener.getInstance(Fmt.locale(Locale.getDefault()), false, true));
        }
        return field;
    }

    private final class Flow extends ViewGroup {
        Flow() { super(host.activity()); setPadding(0, ui.dp(8), 0, ui.dp(2)); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), available = Math.max(1, width - getPaddingLeft() - getPaddingRight());
            int x = 0, y = getPaddingTop(), rowHeight = 0, gap = ui.dp(6);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
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
                View child = getChildAt(i); if (child.getVisibility() == GONE) continue;
                int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
                if (x > 0 && x + w > available) { y += rowHeight + gap; x = 0; rowHeight = 0; }
                int left = rtl ? getWidth() - getPaddingRight() - x - w : getPaddingLeft() + x;
                child.layout(left, y, left + w, y + h); x += w + gap; rowHeight = Math.max(rowHeight, h);
            }
        }
    }

    /** A typed number in the phone's language (2.5 or 2,5), or NaN. Empty is NaN. */
    private static double parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) return Double.NaN;
        ParsePosition at = new ParsePosition(0);
        Number n = NumberFormat.getInstance(Fmt.locale(Locale.getDefault())).parse(trimmed, at);
        if (n == null || at.getIndex() != trimmed.length()) return Double.NaN;
        double v = n.doubleValue();
        return Double.isInfinite(v) || v < 0 ? Double.NaN : v;
    }

    /** A picked calendar day at noon in the phone's zone, never after now. */
    private static long noon(int year, int month, int day) {
        long wall = ZonedDateTime.of(year, month + 1, day, 12, 0, 0, 0, zone()).toInstant().toEpochMilli();
        return Math.min(wall, System.currentTimeMillis());
    }

    private void pickDate(long current, java.util.function.LongConsumer picked) {
        ZonedDateTime at = Instant.ofEpochMilli(current).atZone(zone());
        DatePickerDialog dialog = new DatePickerDialog(host.activity(), (picker, y, m, d) -> picked.accept(noon(y, m, d)), at.getYear(), at.getMonthValue() - 1, at.getDayOfMonth());
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.show();
    }

    // ---- Add and edit -------------------------------------------------------------------------

    /** The add sheet (id null) or the edit sheet for a reminder. */
    private void edit(String id) { edit(id, null); }

    /** The add sheet prefilled from a manual interval (suggested), or the edit sheet for a reminder (id). */
    private void edit(String id, MaintenancePolicy.Reminder suggested) {
        MaintenanceStore.Settings s = load(); if (s == null) return;
        MaintenancePolicy.Reminder existing = id == null ? null : s.find(id);
        // What the sheet starts from: the reminder being edited, or the manual's interval for a new one.
        MaintenancePolicy.Reminder shown = existing != null ? existing : suggested;
        if (id != null && existing == null) return;
        if (existing == null && s.reminders.size() >= MaintenancePolicy.MAX_REMINDERS) { host.toast(T.maintenanceFull(MaintenancePolicy.MAX_REMINDERS)); return; }
        String units = units(); boolean read = s.distance.everRead();
        LinearLayout content = sheetBody(existing == null ? T.maintenanceAddTitle() : T.maintenanceEditTitle(), null);
        // What to check: a name, or a suggested task (names only, owner answer Q42).
        content.addView(fieldLabel(T.maintenanceWhat()));
        EditText name = field(T.maintenanceNameHint(), false); name.setTag(TAG_NAME);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MaintenancePolicy.MAX_NAME)});
        content.addView(name, new LinearLayout.LayoutParams(-1, -2));
        final String[] task = {existing == null ? "" : existing.task};
        if (existing == null && suggested != null) task[0] = suggested.task;
        final boolean[] typing = {true};
        if (shown != null) { typing[0] = false; name.setText(MaintenanceView.name(shown)); typing[0] = true; }
        Flow tasks = new Flow(); List<TextView> taskChips = new ArrayList<>();
        for (String t : MaintenanceDefaults.TASKS) {
            TextView chip = ui.chip(MaintenanceView.taskName(t), t.equals(task[0]), null); chip.setTag(t);
            chip.setOnClickListener(v -> {
                task[0] = t; typing[0] = false; name.setText(MaintenanceView.taskName(t)); name.setSelection(name.length()); typing[0] = true; name.setError(null);
                for (TextView c : taskChips) ui.styleChip(c, c == chip);
            });
            taskChips.add(chip); tasks.addView(chip);
        }
        content.addView(tasks);
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) { }
            // The owner's own words: no suggested task any more.
            @Override public void afterTextChanged(Editable e) { if (!typing[0] || task[0].isEmpty()) return; task[0] = ""; for (TextView c : taskChips) ui.styleChip(c, false); }
        });
        // Remind me after: Distance, Time or Either.
        content.addView(fieldLabel(T.maintenanceRemindAfter()));
        // Every mode works disconnected and before the first odometer reading: a distance part saved without a
        // reading waits with no base and counts from the next one (MaintenancePolicy.settle); after an old reading
        // it counts from the last known odometer. The note under the modes says which, for Distance and Either.
        MaintenanceView.DistanceNote distanceNote = MaintenanceView.distanceNote(s.distance, existing, System.currentTimeMillis());
        int initialMode = shown == null ? (read ? 0 : 1) : !shown.byDistance() ? 1 : shown.byTime() ? 2 : 0;
        final int[] mode = {initialMode};
        LinearLayout modes = new LinearLayout(host.activity());
        String[] modeNames = {T.maintenanceByDistance(), T.maintenanceByTime(), T.maintenanceByEither()};
        TextView[] modeChips = new TextView[3];
        content.addView(modes);
        TextView reason = ui.caption(distanceNote == MaintenanceView.DistanceNote.STALE ? staleNote(s) : T.maintenanceDistanceNext());
        reason.setPadding(ui.dp(2), ui.dp(6), ui.dp(2), 0); reason.setTag(TAG_DISTANCE_NOTE); content.addView(reason);
        TextView either = ui.caption(T.maintenanceEitherNote()); either.setPadding(ui.dp(2), ui.dp(6), ui.dp(2), 0); content.addView(either);
        // Distance: a number in the rider's units and neutral shortcut chips.
        LinearLayout distanceBox = column();
        distanceBox.addView(fieldLabel(T.maintenanceDistanceField(MaintenanceView.unit(units))));
        EditText distance = field(null, true); distance.setTag(TAG_DISTANCE); distanceBox.addView(distance, new LinearLayout.LayoutParams(-1, -2));
        if (shown != null && shown.byDistance()) distance.setText(MaintenanceView.editNumber(MaintenanceView.fromKm(shown.km, units)));
        Flow distanceChips = new Flow();
        for (int value : MaintenanceView.DISTANCE_CHIPS) {
            String label = T.fmtQuantity(Fmt.groupedInteger(Locale.US, value), MaintenanceView.unit(units));
            distanceChips.addView(ui.chip(label, false, () -> { distance.setText(Fmt.integer(value)); distance.setSelection(distance.length()); distance.setError(null); }));
        }
        distanceBox.addView(distanceChips);
        TextView wholeKm = ui.caption(T.maintenanceWholeKm()); wholeKm.setPadding(ui.dp(2), ui.dp(6), ui.dp(2), 0);
        wholeKm.setVisibility(s.distance.bits == MaintenanceDistance.BITS_8 ? View.VISIBLE : View.GONE); distanceBox.addView(wholeKm);
        content.addView(distanceBox);
        // Time: a count of days, weeks or months, and neutral shortcut chips.
        LinearLayout timeBox = column();
        timeBox.addView(fieldLabel(T.maintenanceTimeField()));
        LinearLayout timeLine = new LinearLayout(host.activity()); timeLine.setGravity(Gravity.CENTER_VERTICAL);
        EditText time = field(null, true); time.setTag(TAG_TIME); time.setInputType(InputType.TYPE_CLASS_NUMBER);
        timeLine.addView(time, new LinearLayout.LayoutParams(ui.dp(96), -2));
        final int[] unit = {MaintenanceView.WEEK};
        if (shown != null && shown.byTime()) {
            unit[0] = shown.months > 0 ? MaintenanceView.MONTH : shown.days % MaintenanceView.WEEK == 0 ? MaintenanceView.WEEK : MaintenanceView.DAY;
            int count = shown.months > 0 ? shown.months : unit[0] == MaintenanceView.WEEK ? shown.days / MaintenanceView.WEEK : shown.days;
            time.setText(Fmt.integer(count));
        }
        Flow units3 = new Flow(); units3.setPadding(ui.dp(8), 0, 0, 0);
        int[] unitValues = {MaintenanceView.DAY, MaintenanceView.WEEK, MaintenanceView.MONTH};
        String[] unitNames = {T.maintenanceUnitDays(), T.maintenanceUnitWeeks(), T.maintenanceUnitMonths()};
        TextView[] unitChips = new TextView[3];
        for (int i = 0; i < 3; i++) {
            int value = unitValues[i];
            unitChips[i] = ui.chip(unitNames[i], unit[0] == value, null); unitChips[i].setTag(TAG_UNIT + value);
            unitChips[i].setOnClickListener(v -> { unit[0] = value; for (int j = 0; j < 3; j++) ui.styleChip(unitChips[j], unitValues[j] == value); });
            units3.addView(unitChips[i]);
        }
        timeLine.addView(units3, new LinearLayout.LayoutParams(0, -2, 1));
        timeBox.addView(timeLine);
        Flow timeChips = new Flow();
        for (int[] choice : MaintenanceView.TIME_CHIPS) {
            timeChips.addView(ui.chip(MaintenanceView.count(choice[0], choice[1]), false, () -> {
                time.setText(Fmt.integer(choice[0])); time.setSelection(time.length()); time.setError(null);
                unit[0] = choice[1]; for (int j = 0; j < 3; j++) ui.styleChip(unitChips[j], unitValues[j] == choice[1]);
            }));
        }
        timeBox.addView(timeChips);
        content.addView(timeBox);
        // Counting from: now, or an earlier date and odometer value ("I changed the tires in August"). New reminders only.
        final long[] fromWall = {System.currentTimeMillis()};
        final boolean[] earlier = {false};
        LinearLayout fromBox = column();
        EditText odometer = field(null, true);
        TextView dateButton = ui.secondaryButton(date(fromWall[0]), null);
        if (existing == null) {
            fromBox.addView(fieldLabel(T.maintenanceCountingFrom()));
            Flow from = new Flow();
            TextView now = ui.chip(T.maintenanceWhenNow(), true, null), past = ui.chip(T.maintenanceWhenEarlier(), false, null);
            from.addView(now); from.addView(past); fromBox.addView(from);
            LinearLayout pastBox = column(); pastBox.setVisibility(View.GONE);
            pastBox.addView(fieldLabel(T.maintenanceDate()));
            dateButton.setOnClickListener(v -> pickDate(fromWall[0], wall -> { fromWall[0] = wall; dateButton.setText(date(wall)); dateButton.setContentDescription(date(wall)); }));
            pastBox.addView(dateButton);
            TextView odometerLabel = fieldLabel(T.maintenanceOdometerThen(MaintenanceView.unit(units)));
            pastBox.addView(odometerLabel); pastBox.addView(odometer, new LinearLayout.LayoutParams(-1, -2));
            TextView odometerNote = ui.caption(T.maintenanceOdometerThenNote()); odometerNote.setPadding(ui.dp(2), ui.dp(6), ui.dp(2), 0); pastBox.addView(odometerNote);
            boolean offerOdometer = s.distance.hasBaseline();
            odometerLabel.setVisibility(offerOdometer ? View.VISIBLE : View.GONE); odometer.setVisibility(offerOdometer ? View.VISIBLE : View.GONE);
            odometerNote.setVisibility(offerOdometer ? View.VISIBLE : View.GONE);
            now.setOnClickListener(v -> { earlier[0] = false; ui.styleChip(now, true); ui.styleChip(past, false); pastBox.setVisibility(View.GONE); fromWall[0] = System.currentTimeMillis(); });
            past.setOnClickListener(v -> { earlier[0] = true; ui.styleChip(now, false); ui.styleChip(past, true); pastBox.setVisibility(View.VISIBLE); });
            fromBox.addView(pastBox);
            content.addView(fromBox);
        }
        // Notify me: a notification once when it's due (MaintenanceNotify). On by default.
        final boolean[] notify = {existing == null || existing.notify};
        content.addView(notifyRow(notify));
        Runnable showMode = () -> {
            for (int i = 0; i < 3; i++) ui.styleChip(modeChips[i], mode[0] == i);
            distanceBox.setVisibility(mode[0] != 1 ? View.VISIBLE : View.GONE);
            timeBox.setVisibility(mode[0] != 0 ? View.VISIBLE : View.GONE);
            either.setVisibility(mode[0] == 2 ? View.VISIBLE : View.GONE);
            reason.setVisibility(mode[0] != 1 && distanceNote != MaintenanceView.DistanceNote.NONE ? View.VISIBLE : View.GONE);
        };
        for (int i = 0; i < 3; i++) {
            int index = i;
            modeChips[i] = ui.chip(modeNames[i], mode[0] == i, () -> { mode[0] = index; showMode.run(); }); modeChips[i].setTag(TAG_MODE + i);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2); if (i > 0) mp.leftMargin = ui.dp(6);
            modes.addView(modeChips[i], mp);
        }
        showMode.run();
        TextView save = ui.button(T.maintenanceSave(), () -> {
            String typed = name.getText().toString().trim();
            if (task[0].isEmpty() && typed.isEmpty()) { name.setError(T.maintenanceNameEmpty()); return; }
            Double km = null; int days = 0, months = 0;
            if (mode[0] != 1) {
                double value = parse(distance.getText().toString());
                double inKm = Double.isNaN(value) ? Double.NaN : MaintenanceView.toKm(value, units);
                if (Double.isNaN(inKm) || inKm <= 0 || inKm > MaintenancePolicy.MAX_KM) { distance.setError(T.maintenanceNumberInvalid()); return; }
                km = inKm;
            }
            if (mode[0] != 0) {
                double value = parse(time.getText().toString());
                int count = Double.isNaN(value) ? 0 : (int) Math.round(value);
                if (unit[0] == MaintenanceView.MONTH) months = count; else days = count * unit[0];
                if (count <= 0 || months > MaintenancePolicy.MAX_MONTHS || days > MaintenancePolicy.MAX_DAYS) { time.setError(T.maintenanceNumberInvalid()); return; }
            }
            // An earlier odometer reading, in km (NaN: count from the latest reading at save time).
            double typedKm = Double.NaN;
            if (existing == null && earlier[0] && odometer.getVisibility() == View.VISIBLE && odometer.length() > 0) {
                double typedRaw = parse(odometer.getText().toString());
                if (Double.isNaN(typedRaw)) { odometer.setError(T.maintenanceNumberInvalid()); return; }
                typedKm = MaintenanceView.toKm(typedRaw, units);
                if (!checkBackdate(odometer, typedKm)) return;
            }
            String customName = task[0].isEmpty() ? typed : "";
            String chosenTask = task[0]; Double interval = km; int intervalDays = days, intervalMonths = months;
            long now = System.currentTimeMillis(), base = earlier[0] ? fromWall[0] : now; double atKm = typedKm;
            boolean notifyOn = notify[0];
            MaintenanceDistance.Backdate[] refused = {null};
            // The baseline comes from the document being written, so readings that arrived while the sheet was open count before it.
            boolean saved = change(x -> {
                if (existing == null) refused[0] = MaintenanceView.add(x, chosenTask, customName, interval, intervalDays, intervalMonths, notifyOn, base, atKm, now);
                else MaintenanceView.edit(x, existing.id, chosenTask, customName, interval, intervalDays, intervalMonths, notifyOn);
            });
            if (refused[0] != null) { odometer.setError(backdateError(refused[0])); return; }
            if (saved) close();
        });
        save.setTag(TAG_SAVE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = ui.dp(18); content.addView(save, sp);
        show(content);
    }

    /** The add and edit sheets' Notify me switch; choice[0] follows it. */
    private View notifyRow(boolean[] choice) {
        LinearLayout line = new LinearLayout(host.activity()); line.setGravity(Gravity.CENTER_VERTICAL); line.setMinimumHeight(ui.dp(56));
        line.setPadding(0, ui.dp(14), 0, 0);
        LinearLayout words = column();
        words.addView(ui.text(T.maintenanceNotify(), 15, false));
        TextView detail = ui.caption(T.maintenanceNotifyDetail()); detail.setPadding(0, ui.dp(3), 0, 0); words.addView(detail);
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Haptics.FeedbackSwitch(host.activity()); toggle.setShowText(false); toggle.setMinHeight(ui.dp(48)); ui.styleSwitch(toggle);
        toggle.setChecked(choice[0]); toggle.setTag(TAG_NOTIFY); toggle.setContentDescription(T.maintenanceNotify());
        toggle.setOnCheckedChangeListener((button, on) -> choice[0] = on);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2); tp.leftMargin = ui.dp(10); line.addView(toggle, tp);
        return line;
    }

    /** Checks a typed earlier odometer reading against the latest document; shows why on the field when it can't be used. */
    private boolean checkBackdate(EditText field, double typedKm) {
        MaintenanceStore.Settings latest = load();
        MaintenanceDistance.AtRaw at = latest == null ? null : MaintenanceView.baseline(latest.distance, typedKm);
        if (at == null || at.kind == MaintenanceDistance.Backdate.OK) return true;
        field.setError(backdateError(at.kind));
        return false;
    }

    private static String backdateError(MaintenanceDistance.Backdate kind) {
        if (kind == MaintenanceDistance.Backdate.AHEAD) return T.maintenanceOdometerAhead();
        if (kind == MaintenanceDistance.Backdate.BEFORE_RESET) return T.maintenanceOdometerBeforeReset();
        if (kind == MaintenanceDistance.Backdate.NO_BASELINE) return T.maintenanceNeverRead();
        return T.maintenanceNumberInvalid();
    }

    // ---- Detail -------------------------------------------------------------------------------

    private void detail(String id) {
        MaintenanceStore.Settings s = load(); if (s == null) return;
        MaintenancePolicy.Reminder r = s.find(id); if (r == null) return;
        long now = System.currentTimeMillis(); String units = units();
        MaintenancePolicy.Status status = MaintenanceView.status(s, r, now, zone());
        LinearLayout content = sheetBody(MaintenanceView.name(r), null);
        LinearLayout top = new LinearLayout(host.activity()); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(0, 0, 0, ui.dp(6));
        top.addView(new Ring(status), new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));
        LinearLayout words = column();
        TextView left = ui.text(MaintenanceView.left(r, status, units, now, MaintenanceScreen::date), 18, true); words.addView(left);
        TextView every = ui.caption(MaintenanceView.interval(r, units)); every.setPadding(0, ui.dp(4), 0, 0); words.addView(every);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.leftMargin = ui.dp(16); top.addView(words, wp);
        content.addView(top);
        LinearLayout facts = ui.groupCard();
        facts.addView(ui.settingRow(T.maintenanceDetailInterval(), "", MaintenanceView.interval(r, units), null));
        ui.divider(facts);
        facts.addView(ui.settingRow(T.maintenanceDetailFrom(), "", date(r.baseWall), null));
        String dueText = dueText(s, r, status, units);
        if (!dueText.isEmpty()) { ui.divider(facts); facts.addView(ui.settingRow(T.maintenanceDetailDue(), "", dueText, null)); }
        content.addView(facts);
        if (r.byDistance() && s.distance.stale(now)) content.addView(note(T.maintenanceStale(date(s.distance.lastWall)), null));
        LinearLayout actions = new LinearLayout(host.activity()); actions.setGravity(Gravity.CENTER_VERTICAL); actions.setPadding(0, ui.dp(4), 0, ui.dp(4));
        View done = action(T.maintenanceMarkDone(), T.maintenanceMarkDoneShort(), "check", true, () -> markDone(r.id)); done.setTag(TAG_DONE);
        actions.addView(done, new LinearLayout.LayoutParams(0, -2, 1));
        if (status.state == MaintenancePolicy.State.DUE || status.state == MaintenancePolicy.State.SOON) {
            View snooze = action(T.maintenanceSnooze(), null, "clock", false, () -> snooze(r.id)); snooze.setTag(TAG_SNOOZE);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1); sp.leftMargin = ui.dp(8); actions.addView(snooze, sp);
        }
        View edit = action(T.maintenanceEdit(), null, "edit", false, () -> edit(r.id)); edit.setTag(TAG_EDIT);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, -2, 1); ep.leftMargin = ui.dp(8); actions.addView(edit, ep);
        content.addView(actions);
        content.addView(fieldLabel(T.maintenanceHistory()));
        LinearLayout history = ui.groupCard();
        List<MaintenanceView.Line> lines = MaintenanceView.history(r);
        boolean first = true;
        for (MaintenanceView.Line line : lines) {
            String event = MaintenanceView.eventName(line.type); if (event.isEmpty()) continue;
            if (!first) ui.divider(history); first = false;
            String value = Double.isNaN(line.kmSincePreviousDone) ? "" : T.maintenanceEventAfter(MaintenanceView.distance(line.kmSincePreviousDone, units));
            history.addView(ui.settingRow(T.maintenancePair(event, date(line.wall)), "", value, null));
        }
        content.addView(history);
        TextView delete = ui.dangerButton(T.maintenanceDelete(), () -> confirmDelete(r.id)); delete.setTag(TAG_DELETE);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2); dp.topMargin = ui.dp(12); content.addView(delete, dp);
        show(content);
    }

    /** When it is due: the date, the odometer reading, or both (Either). Empty while waiting for the odometer. */
    private String dueText(MaintenanceStore.Settings s, MaintenancePolicy.Reminder r, MaintenancePolicy.Status status, String units) {
        String day = r.byTime() ? date(MaintenanceView.dueDate(r, zone())) : "";
        double at = r.byDistance() ? MaintenanceView.dueOdometer(s, status) : Double.NaN;
        String odometer = Double.isNaN(at) ? "" : T.maintenanceAtOdometer(MaintenanceView.odometer(at, units));
        if (!day.isEmpty() && !odometer.isEmpty()) return T.maintenancePair(day, odometer);
        return day.isEmpty() ? odometer : day;
    }

    // ---- Mark done, Snooze, Delete ------------------------------------------------------------

    private void markDone(String id) {
        MaintenanceStore.Settings s = load(); if (s == null) return;
        MaintenancePolicy.Reminder r = s.find(id); if (r == null) return;
        String units = units();
        LinearLayout content = sheetBody(T.maintenanceMarkDone(), MaintenanceView.name(r));
        TextView today = ui.text(s.distance.hasBaseline() ? T.maintenanceDoneTodayAt(MaintenanceView.odometer(s.distance.lastRawKm, units)) : T.maintenanceDoneToday(), 15, false);
        content.addView(today);
        final long[] when = {System.currentTimeMillis()};
        final boolean[] earlier = {false};
        LinearLayout pastBox = column(); pastBox.setVisibility(View.GONE);
        TextView change = pill(T.maintenanceChange(), null, false, null); change.setTag(TAG_CHANGE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.topMargin = ui.dp(8); content.addView(change, cp);
        pastBox.addView(fieldLabel(T.maintenanceDate()));
        TextView dateButton = ui.secondaryButton(date(when[0]), null);
        dateButton.setOnClickListener(v -> pickDate(when[0], wall -> { when[0] = wall; dateButton.setText(date(wall)); dateButton.setContentDescription(date(wall)); }));
        pastBox.addView(dateButton);
        EditText odometer = field(null, true);
        boolean offerOdometer = r.byDistance() && s.distance.hasBaseline();
        if (offerOdometer) {
            pastBox.addView(fieldLabel(T.maintenanceOdometerThen(MaintenanceView.unit(units))));
            pastBox.addView(odometer, new LinearLayout.LayoutParams(-1, -2));
            TextView note = ui.caption(T.maintenanceOdometerThenNote()); note.setPadding(ui.dp(2), ui.dp(6), ui.dp(2), 0); pastBox.addView(note);
        }
        content.addView(pastBox);
        change.setOnClickListener(v -> { earlier[0] = true; today.setVisibility(View.GONE); change.setVisibility(View.GONE); pastBox.setVisibility(View.VISIBLE); });
        TextView confirm = ui.button(T.maintenanceMarkDone(), () -> {
            // An earlier odometer reading, in km (NaN: done at the latest reading at save time).
            double typedKm = Double.NaN;
            if (earlier[0] && offerOdometer && odometer.length() > 0) {
                double typed = parse(odometer.getText().toString());
                if (Double.isNaN(typed)) { odometer.setError(T.maintenanceNumberInvalid()); return; }
                typedKm = MaintenanceView.toKm(typed, units);
                if (!checkBackdate(odometer, typedKm)) return;
            }
            long now = System.currentTimeMillis(), doneWall = earlier[0] ? when[0] : now; double atKm = typedKm;
            MaintenanceDistance.Backdate[] refused = {null};
            // Both the latest count and an earlier reading's place on it come from the document being written.
            boolean saved = change(x -> {
                MaintenancePolicy.Reminder target = x.find(id); if (target == null) return;
                MaintenanceDistance.AtRaw at = MaintenanceView.baseline(x.distance, atKm);
                if (at.kind != MaintenanceDistance.Backdate.OK) { refused[0] = at.kind; return; }
                MaintenancePolicy.markDone(target, doneWall, at.accKm, now);
            });
            if (refused[0] != null) { odometer.setError(backdateError(refused[0])); return; }
            if (saved) close();
        });
        confirm.setTag(TAG_CONFIRM);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.topMargin = ui.dp(16); content.addView(confirm, bp);
        show(content);
    }

    private void snooze(String id) {
        MaintenanceStore.Settings s = load(); if (s == null || s.find(id) == null) return;
        LinearLayout content = sheetBody(T.maintenanceSnooze(), T.maintenanceSnoozeExplain());
        for (int days : MaintenancePolicy.SNOOZE_DAYS) {
            String label = days == MaintenanceView.WEEK ? MaintenanceView.count(1, MaintenanceView.WEEK) : MaintenanceView.count(days, MaintenanceView.DAY);
            TextView choice = ui.secondaryButton(label, () -> {
                long now = System.currentTimeMillis();
                boolean saved = change(x -> {
                    MaintenancePolicy.Reminder target = x.find(id); if (target == null) return;
                    MaintenancePolicy.snooze(target, days, now, x.distance.everRead() ? x.distance.accumulatedKm : Double.NaN, zone());
                });
                if (saved) close();
            });
            choice.setTag(TAG_SNOOZE + days);
            content.addView(choice);
        }
        show(content);
    }

    private void confirmDelete(String id) {
        MaintenanceStore.Settings s = load(); if (s == null) return;
        MaintenancePolicy.Reminder r = s.find(id); if (r == null) return;
        LinearLayout content = sheetBody(T.maintenanceDeleteTitle(MaintenanceView.name(r)), T.maintenanceDeleteBody());
        LinearLayout buttons = new LinearLayout(host.activity());
        TextView cancel = ui.secondaryButton(T.commonCancel(), this::close);
        TextView delete = ui.dangerButton(T.maintenanceDeleteAction(), () -> {
            boolean saved = change(x -> { MaintenancePolicy.Reminder target = x.find(id); if (target != null) x.reminders.remove(target); });
            if (saved) close();
        });
        delete.setTag(TAG_CONFIRM);
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(0, -2, 1); dp.leftMargin = ui.dp(10); buttons.addView(delete, dp);
        content.addView(buttons);
        show(content);
    }

    // ---- The Scooter tab's row and dot --------------------------------------------------------

    static boolean pending(SharedPreferences preferences) { return SECTION.equals(preferences.getString(PENDING_PREF, "")); }

    static void pending(SharedPreferences preferences, boolean on) { preferences.edit().putString(PENDING_PREF, on ? SECTION : "").apply(); }

    /** The active scooter's counts, or null without a service or an active scooter. */
    static MaintenanceView.Summary summary(ScooterService service) {
        String scope = service == null || service.maintenance == null ? "" : service.maintenanceScope();
        return scope.isEmpty() ? null : service.maintenance.summary(scope, System.currentTimeMillis(), zone());
    }

    /**
     * One line of the wide Home's Service card (HubServiceCard): a check, its status and whether it is highlighted (due
     * now: the owner's answer on #394, the orange dot means due only, as the Service row's). It keeps what the words are
     * made from, not the words: name() and left() render them when the line is built, in the current language, with the
     * Maintenance page's own words.
     */
    static final class Check {
        final MaintenancePolicy.Reminder reminder; final MaintenancePolicy.Status status; final String units; final long now;
        final boolean highlighted;
        Check(MaintenancePolicy.Reminder reminder, MaintenancePolicy.Status status, String units, long now, boolean highlighted) {
            this.reminder = reminder; this.status = status; this.units = units; this.now = now; this.highlighted = highlighted;
        }
        String id() { return reminder.id; }
        /** Whether the check is due now (not only coming up soon): its line is then read as "maintenance due". */
        boolean due() { return status.state == MaintenancePolicy.State.DUE; }
        String name() { return MaintenanceView.name(reminder); }
        String left() { return MaintenanceView.left(reminder, status, units, now, MaintenanceScreen::date); }
        /**
         * What the line shows, from its inputs, for the card's has-it-changed key: which part left() names, and the
         * distance to a tenth in km and in miles (the most the words show), so a telemetry refresh that moves the
         * odometer by a few metres builds nothing.
         */
        void key(StringBuilder key) {
            boolean distance = MaintenanceView.namesDistance(reminder, status, now);
            key.append(reminder.id).append('\n').append(reminder.task).append('\n').append(reminder.name).append('\n')
                    .append(status.state).append('\n').append(status.daysLeft).append('\n').append(distance).append('\n');
            if (distance) key.append(Math.round(status.kmLeft * 10)).append('/').append(Math.round(status.kmLeft / MaintenanceView.KM_PER_MILE * 10)).append('\n');
            key.append(reminder.snoozeUntil).append('\n').append(units).append('\n').append(highlighted).append('\n');
        }
    }

    /**
     * The next maintenance checks for the wide Home's Service card (hub layout plan 1.11, PR 13): the due ones first,
     * highlighted, at most max (the most overdue first), then the nearest check that is not due (coming up soon or
     * later), not highlighted. Empty while reminders are off (see {@link #remindersOff}), none is set or there is no
     * active scooter.
     */
    static List<Check> nextChecks(ScooterService service, int max) {
        List<Check> checks = new ArrayList<>();
        MaintenanceStore.Settings s = settings(service);
        if (s == null || !s.on) return checks;
        SharedPreferences settings = service.settings;
        String units = settings == null ? UNITS_DEFAULT : settings.getString(UNITS_PREF, UNITS_DEFAULT);
        long now = System.currentTimeMillis();
        for (MaintenancePolicy.Reminder r : MaintenanceView.dueNow(s, now, zone())) {
            if (checks.size() >= max) break;
            checks.add(new Check(r, MaintenanceView.status(s, r, now, zone()), units, now, true));
        }
        for (MaintenancePolicy.Reminder r : MaintenanceView.comingUp(s, now, zone())) {
            MaintenancePolicy.Status status = MaintenanceView.status(s, r, now, zone());
            if (status.state == MaintenancePolicy.State.DUE) continue;
            checks.add(new Check(r, status, units, now, false));
            break;
        }
        return checks;
    }

    /** The active scooter's maintenance settings, or null without a service or an active scooter. */
    private static MaintenanceStore.Settings settings(ScooterService service) {
        MaintenanceKeeper keeper = service == null ? null : service.maintenance;
        String scope = keeper == null ? "" : service.maintenanceScope();
        return scope.isEmpty() ? null : keeper.load(scope);
    }

    /**
     * Whether the active scooter has maintenance reminders switched off: the Service card then says so, as the Service
     * row beside it does ("Maintenance reminders · Off", HubServiceCard), instead of saying no check is set (#394 early review, 8).
     */
    static boolean remindersOff(ScooterService service) {
        MaintenanceStore.Settings s = settings(service);
        return s != null && !s.on;
    }

    /** The Reminders row's value; empty without an active scooter. */
    static String rowValue(MaintenanceView.Summary summary) { return summary == null ? "" : MaintenanceView.rowValue(summary); }

    // ---- The Scooter tab's dot ----------------------------------------------------------------

    private static final WeakHashMap<View, Drawable> DOTS = new WeakHashMap<>();
    private static final WeakHashMap<View, CharSequence> SPOKEN = new WeakHashMap<>();
    private static final WeakHashMap<View, View.OnLayoutChangeListener> PLACERS = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> COLORS = new WeakHashMap<>();

    /**
     * Shows or hides a small red dot on the Scooter tab (owner answer Q39 B). On the phone's raised Scooter
     * button (corner true) it sits at the button's top end corner, outside the red disc; on the rail it sits
     * at the top end of the tab's icon, its first child. The tab's spoken name says a check is due while it shows.
     */
    static void markTab(Ui ui, View tab, boolean corner, boolean due) { markTab(ui, tab, corner, due, ui.accent); }

    /**
     * The same dot in a colour of the caller's: the Hub's dock shows it orange on the Service icon (hub layout plan,
     * PR 11), as Clean Home's Service row does. A dot already shown in another colour is drawn again in this one.
     */
    static void markTab(Ui ui, View tab, boolean corner, boolean due, int color) {
        if (tab == null) return;
        if (due && DOTS.containsKey(tab) && COLORS.containsKey(tab) && COLORS.get(tab) != color) markTab(ui, tab, corner, false, color);
        Drawable dot = DOTS.get(tab);
        if (!SPOKEN.containsKey(tab)) SPOKEN.put(tab, tab.getContentDescription());
        CharSequence label = SPOKEN.get(tab);
        if (!due) {
            if (dot != null) { tab.getOverlay().remove(dot); DOTS.remove(tab); COLORS.remove(tab); tab.removeOnLayoutChangeListener(PLACERS.remove(tab)); }
            tab.setContentDescription(label);
            return;
        }
        if (label != null) tab.setContentDescription(T.maintenanceTabDue(label.toString()));
        if (dot != null) return;
        GradientDrawable made = new GradientDrawable(); made.setShape(GradientDrawable.OVAL); made.setColor(color); made.setStroke(ui.dp(2), ui.bg);
        DOTS.put(tab, made); COLORS.put(tab, color);
        Runnable place = () -> {
            View icon = !corner && tab instanceof ViewGroup && ((ViewGroup) tab).getChildCount() > 0 ? ((ViewGroup) tab).getChildAt(0) : tab;
            int size = ui.dp(12);
            boolean rtl = tab.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int x, y;
            if (icon == tab) { x = rtl ? ui.dp(2) : tab.getWidth() - size - ui.dp(2); y = ui.dp(2); }
            else { x = rtl ? icon.getLeft() - size / 2 : icon.getRight() - size / 2; y = Math.max(0, icon.getTop() - size / 3); }
            made.setBounds(x, y, x + size, y + size);
            tab.invalidate();
        };
        // Bounds first: the overlay redraws only the area the drawable covers when it is added.
        place.run();
        tab.getOverlay().add(made);
        View.OnLayoutChangeListener placer = (v, l, t, r, b, ol, ot, or, ob) -> place.run();
        PLACERS.put(tab, placer); tab.addOnLayoutChangeListener(placer);
    }
}
