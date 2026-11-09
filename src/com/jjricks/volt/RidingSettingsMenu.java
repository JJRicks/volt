package com.jjricks.volt;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Riding mode's own settings, under the layout editor in the tune menu (owner, Nov 9): brightness,
 * rotation, night vision with its schedule and consent, the status bar, and the two automatic
 * switches (the same preferences as More's copies of those two). Everything saves as it changes.
 * The screen behind is told through {@code changed} and rebuilds once the menu closes, so a change
 * never closes the menu mid-edit. The dialogs it opens (time picker, consent) close with it.
 */
final class RidingSettingsMenu {
    private final ScreenHost host;
    private final Ui ui;
    private final SharedPreferences preferences;
    private final Context context;
    private final Runnable changed;
    private final List<Dialog> dialogs = new ArrayList<>();
    private boolean closed;
    private LinearLayout content;
    private Runnable refreshNight = () -> { };

    RidingSettingsMenu(ScreenHost host, Ui ui, SharedPreferences preferences, Runnable changed) {
        this.host = host;
        this.ui = ui;
        this.preferences = preferences;
        this.changed = changed;
        context = host.activity();
    }

    /** Builds the settings into the menu's body. */
    void fill(LinearLayout body) {
        content = body;
        body.addView(ui.sectionLabel(T.ridingDisplaySection()));
        boolean sensor = RidingDisplay.hasLightSensor(context);
        String[] labels = sensor ? new String[]{T.ridingDisplayPhone(), T.ridingDisplayAuto(), T.ridingDisplayFixed()}
                : new String[]{T.ridingDisplayPhone(), T.ridingDisplayFixed()};
        String[] values = sensor ? new String[]{RidingBrightness.PHONE, RidingBrightness.AUTO, RidingBrightness.FIXED}
                : new String[]{RidingBrightness.PHONE, RidingBrightness.FIXED};
        selector(T.ridingDisplayBrightness(), RidingBrightness.MODE_KEY, labels, values, RidingBrightness.PHONE, null);
        selector(T.ridingDisplayRotation(), RidingRotation.KEY, new String[]{T.ridingDisplayRotationLock(), T.ridingDisplayRotationFollow()},
                new String[]{RidingRotation.LOCK, RidingRotation.FOLLOW}, RidingRotation.LOCK, null);
        night();
        gap();
        content.addView(toggle(T.ridingDisplayAutoOpen(), T.ridingDisplayAutoOpenSub(), AutoStartPolicy.AUTO_RIDE_MODE_KEY, "preference_riding_auto_open", false, null));
        content.addView(toggle(T.morePrefsAutoRecord(), T.morePrefsAutoRecordSub(), AutoStartPolicy.AUTO_RECORD_KEY, "preference_riding_auto_record", false, save -> offerRideLocation(host, save)));
        // Off by default: the status bar keeps the phone's clock and battery (#254).
        content.addView(toggle(T.ridingDisplayHideStatusBar(), T.ridingDisplayHideStatusBarSub(), RidingBars.HIDE_STATUS_BAR_KEY, "preference_riding_hide_status_bar", true, null));
        gap();
        LinearLayout box = ui.groupCard();
        box.setTag("preference_riding_brightness");
        RidingBrightness.Store store = RidingDisplay.settings(preferences);
        Runnable[] showReset = new Runnable[1];
        Row reset = row(box, T.ridingDisplayReset(), T.ridingDisplayResetSub(), () -> {
            RidingBrightness.forget(store);
            host.toast(T.ridingDisplayResetDone());
            changed.run();
            showReset[0].run();
        });
        reset.view.setTag("preference_riding_brightness_reset");
        showReset[0] = () -> reset.view.setVisibility(RidingBrightness.learned(store) ? View.VISIBLE : View.GONE);
        showReset[0].run();
        note(box, sensor ? T.ridingDisplayNote() : T.ridingDisplayNoteNoSensor());
        content.addView(box);
    }

    /** The menu closed: the dialogs it opened go with it, and a late answer changes nothing. */
    void close() {
        closed = true;
        for (Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        dialogs.clear();
    }

    /**
     * Turning on Record rides automatically: the same optional prompt a manual recording uses, answered
     * before the switch saves, so a ride can't start behind the prompt. Either answer turns it on.
     * More's copy of the switch comes through here too.
     */
    static void offerRideLocation(ScreenHost host, Runnable save) {
        ScooterService service = host.service();
        if (service != null && AutoStartPolicy.asksLocationFirst(service.settings.getBoolean("phone_gps", true), service.simulating(), service.ridingNow()))
            host.requestAutoRecordLocation(save);
        else save.run();
    }

    /** One switch; applies: riding mode needs rebuilding with it. beforeOn runs ahead of a turn-on and is given the save to run, as in More. */
    private View toggle(String title, String detail, String key, String tag, boolean applies, java.util.function.Consumer<Runnable> beforeOn) {
        View row = ui.toggleRow(title, Ui.NO_ICON, detail, preferences.getBoolean(key, false), true, enabled -> {
            Runnable save = () -> {
                preferences.edit().putBoolean(key, enabled).apply();
                if (applies && !closed) changed.run();
            };
            if (enabled && beforeOn != null) beforeOn.accept(save); else save.run();
        });
        row.setTag(tag);
        return row;
    }

    /**
     * Night vision: Off · On · Scheduled, then sunset to sunrise or custom times. Sunset from the last
     * ride's area needs the rider's yes first (owner Q21 A); without it the time zone's city is used.
     */
    private void night() {
        selector(T.ridingNightVision(), NightSchedule.MODE_KEY, new String[]{T.ridingNightOff(), T.ridingNightOn(), T.ridingNightScheduled()},
                new String[]{NightSchedule.OFF, NightSchedule.ON, NightSchedule.SCHEDULED}, NightSchedule.OFF, this::nightChanged);
        View[] schedule = selector(T.ridingNightSchedule(), NightSchedule.SCHEDULE_KEY, new String[]{T.ridingNightSun(), T.ridingNightCustom()},
                new String[]{NightSchedule.SUN, NightSchedule.CUSTOM}, NightSchedule.SUN, this::nightChanged);
        gap();
        LinearLayout box = ui.groupCard();
        box.setTag("preference_riding_night");
        Row from = row(box, T.ridingNightFrom(), null, this::consent);
        from.view.setTag("preference_riding_night_from");
        Row starts = row(box, T.ridingNightStarts(), null, () -> time(NightSchedule.START_KEY, NightSchedule.START_DEFAULT, T.ridingNightStarts()));
        starts.view.setTag("preference_riding_night_starts");
        Row ends = row(box, T.ridingNightEnds(), null, () -> time(NightSchedule.END_KEY, NightSchedule.END_DEFAULT, T.ridingNightEnds()));
        ends.view.setTag("preference_riding_night_ends");
        TextView next = ui.caption("");
        next.setPadding(0, ui.dp(7), 0, 0);
        next.setTag("preference_riding_night_next");
        box.addView(next);
        note(box, T.ridingNightNote());
        content.addView(box);
        refreshNight = () -> {
            boolean scheduled = NightSchedule.SCHEDULED.equals(NightSchedule.mode(preferences.getString(NightSchedule.MODE_KEY, NightSchedule.OFF)));
            boolean sun = NightSchedule.SUN.equals(NightSchedule.schedule(preferences.getString(NightSchedule.SCHEDULE_KEY, NightSchedule.SUN)));
            schedule[0].setVisibility(scheduled ? View.VISIBLE : View.GONE);
            schedule[1].setVisibility(scheduled ? View.VISIBLE : View.GONE);
            long now = System.currentTimeMillis();
            NightSchedule.State state = RidingNight.state(context, preferences, now);
            boolean custom = scheduled && (!sun || state.source == NightSchedule.Source.NONE);
            shownRows(box, new View[]{from.view, starts.view, ends.view}, new boolean[]{scheduled && sun, custom, custom});
            boolean ride = RidingNight.usesRideArea(preferences);
            from.value(ride ? T.ridingNightFromRide() : T.ridingNightFromZone());
            from.sub(state.source == NightSchedule.Source.RIDE ? T.ridingNightSourceRide()
                    : state.source == NightSchedule.Source.NONE ? T.ridingNightSourceNone()
                    : ride ? T.ridingNightSourceRideNone() : T.ridingNightSourceZone());
            starts.value(clock(preferences.getInt(NightSchedule.START_KEY, NightSchedule.START_DEFAULT), NightSchedule.START_DEFAULT));
            ends.value(clock(preferences.getInt(NightSchedule.END_KEY, NightSchedule.END_DEFAULT), NightSchedule.END_DEFAULT));
            String text = !scheduled ? "" : state.nextMs > 0 && state.nextMs - now < 86_400_000L
                    ? (state.night ? T.ridingNightNextOff(DisplayTime.clock(context, preferences, state.nextMs))
                            : T.ridingNightNextOn(DisplayTime.clock(context, preferences, state.nextMs)))
                    : state.today == SunTimes.Day.Kind.ALWAYS_DAY ? T.ridingNightNoSunset()
                    : state.today == SunTimes.Day.Kind.ALWAYS_NIGHT ? T.ridingNightNoSunrise() : "";
            next.setText(text);
            next.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        };
        refreshNight.run();
        // The ride's area is read in the background (it's never stored); the caption follows once it's known.
        if (RidingNight.usesRideArea(preferences)) RidingNight.readRideArea(context, () -> { if (!closed) refreshNight.run(); });
    }

    private void nightChanged() {
        refreshNight.run();
        boolean scheduled = NightSchedule.SCHEDULED.equals(NightSchedule.mode(preferences.getString(NightSchedule.MODE_KEY, NightSchedule.OFF)));
        boolean sun = NightSchedule.SUN.equals(NightSchedule.schedule(preferences.getString(NightSchedule.SCHEDULE_KEY, NightSchedule.SUN)));
        if (scheduled && sun && !preferences.contains(NightSchedule.AREA_KEY)) consent();
    }

    /** The brief consent sheet: use the last ride's area (rounded, on the phone) or the time zone. */
    private void consent() {
        if (closed) return;
        android.app.AlertDialog[] self = new android.app.AlertDialog[1];
        LinearLayout body = host.sheetContent(T.ridingNightConsentTitle(), T.ridingNightConsentBody(), () -> { if (self[0] != null) self[0].dismiss(); });
        body.setTag("riding_night_consent");
        TextView allow = ui.button(T.ridingNightConsentAllow(), () -> {
            preferences.edit().putString(NightSchedule.AREA_KEY, NightSchedule.AREA_ALLOW).apply();
            changed.run();
            if (self[0] != null) self[0].dismiss();
            RidingNight.readRideArea(context, () -> { if (!closed) refreshNight.run(); });
        });
        allow.setTag("riding_night_consent_allow");
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.topMargin = ui.dp(16);
        body.addView(allow, space);
        TextView zone = ui.secondaryButton(T.ridingNightConsentZone(), () -> {
            preferences.edit().putString(NightSchedule.AREA_KEY, NightSchedule.AREA_DECLINE).apply();
            changed.run();
            if (self[0] != null) self[0].dismiss();
            refreshNight.run();
        });
        zone.setTag("riding_night_consent_zone");
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(-1, -2);
        under.topMargin = ui.dp(8);
        body.addView(zone, under);
        android.app.AlertDialog dialog = host.showSheet(body);
        if (dialog == null) return;
        self[0] = dialog;
        track(dialog);
    }

    /** A custom time's picker, in the phone's 12 or 24 hour clock. */
    private void time(String key, int fallback, String title) {
        if (closed) return;
        int minutes = NightSchedule.minutes(preferences.getInt(key, fallback), fallback);
        android.app.TimePickerDialog picker = new android.app.TimePickerDialog(context, (view, hour, minute) -> {
            if (closed) return;
            preferences.edit().putInt(key, hour * 60 + minute).apply();
            changed.run();
            refreshNight.run();
        }, minutes / 60, minutes % 60, DisplayTime.is24Hour(context, preferences));
        picker.setTitle(title);
        track(picker);
        picker.show();
    }

    private void track(Dialog dialog) {
        dialogs.add(dialog);
        dialog.setOnDismissListener(x -> dialogs.remove(dialog));
    }

    /** A custom time as the phone's clock shows it today. */
    private String clock(int stored, int fallback) {
        int minutes = NightSchedule.minutes(stored, fallback);
        long wall = java.time.LocalDate.now().atTime(minutes / 60, minutes % 60).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        return DisplayTime.clock(context, preferences, wall);
    }

    /** Shows the chosen rows of a group, in order; each row's divider shows only below another shown row. */
    private static void shownRows(LinearLayout box, View[] rows, boolean[] shown) {
        boolean above = false;
        for (int i = 0; i < rows.length; i++) {
            rows[i].setVisibility(shown[i] ? View.VISIBLE : View.GONE);
            int at = box.indexOfChild(rows[i]);
            if (at > 0 && i > 0) box.getChildAt(at - 1).setVisibility(shown[i] && above ? View.VISIBLE : View.GONE);
            above |= shown[i];
        }
    }

    /** A caption and its selector, saved on change; returns {caption, selector} so a row can hide them together. */
    private View[] selector(String title, String key, String[] labels, String[] values, String fallback, Runnable after) {
        TextView caption = ui.text(title, 12.5f, false);
        caption.setTextColor(ui.muted);
        caption.setPadding(ui.dp(2), ui.dp(12), ui.dp(2), ui.dp(6));
        content.addView(caption);
        SegmentedSelector selector = new SegmentedSelector(ui, title, labels, choice -> {
            if (closed || values[choice].equals(preferences.getString(key, fallback))) return;
            preferences.edit().putString(key, values[choice]).apply();
            changed.run();
            if (after != null) after.run();
        });
        selector.setTag("preference-selector-" + key);
        String stored = preferences.getString(key, fallback);
        int selected = -1;
        for (int i = 0; i < values.length; i++) if (values[i].equals(stored)) selected = i;
        if (selected < 0) for (int i = 0; i < values.length; i++) if (fallback.equals(values[i])) selected = i;
        content.addView(selector, new LinearLayout.LayoutParams(-1, -2));
        selector.select(Math.max(0, selected), false);
        return new View[]{caption, selector};
    }

    private void gap() { content.addView(new View(context), new LinearLayout.LayoutParams(-1, ui.dp(16))); }

    private void note(LinearLayout box, String value) {
        TextView text = ui.caption(value);
        text.setPadding(0, ui.dp(7), 0, ui.dp(7));
        box.addView(text);
    }

    /** One divided row in a group card: title, optional sub-text, trailing value and chevron. */
    private final class Row {
        LinearLayout view;
        TextView sub, value;
        void value(String text) {
            value.setText(text == null ? "" : text);
            value.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        }
        void sub(CharSequence text) {
            sub.setText(text == null ? "" : text);
            sub.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
        }
    }

    private Row row(LinearLayout group, String title, String sub, Runnable action) {
        if (group.getChildCount() > 0) ui.divider(group);
        Row r = new Row();
        r.view = new LinearLayout(context);
        r.view.setOrientation(LinearLayout.HORIZONTAL);
        r.view.setGravity(Gravity.CENTER_VERTICAL);
        r.view.setMinimumHeight(ui.dp(58));
        r.view.setPadding(0, ui.dp(9), 0, ui.dp(9));
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(ui.text(title, 15, false));
        r.sub = ui.text("", 11.5f, false);
        r.sub.setTextColor(ui.muted);
        r.sub.setPadding(0, ui.dp(3), 0, 0);
        words.addView(r.sub);
        r.sub(sub);
        r.view.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        r.value = ui.text("", 13.5f, false);
        r.value.setTextColor(ui.muted);
        r.value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        r.value.setMaxLines(2);
        r.value.setMaxWidth(ui.dp(140));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
        vp.leftMargin = ui.dp(10);
        r.view.addView(r.value, vp);
        r.value(null);
        View chevron = ui.icon("chevron", 15, ui.muted);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
        cp.leftMargin = ui.dp(8);
        r.view.addView(chevron, cp);
        r.view.setFocusable(true);
        r.view.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        r.view.setOnClickListener(v -> { if (!closed) action.run(); });
        group.addView(r.view, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }
}
