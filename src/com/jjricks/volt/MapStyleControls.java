package com.jjricks.volt;

import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import java.util.function.Consumer;

/**
 * The Dark map switches for the online maps' shared style (MapStyle; owner, Nov 23 2026): one beside the ride maps' Basemap
 * switch (inline and expanded, RidesScreen.mapPanel), one in Map settings (MapPreferences.show). Both read and save the
 * one setting the Just back card (JustBackView) follows too; never picked, they show the app theme's style, and a flip
 * saves a pick, kept in either theme.
 */
final class MapStyleControls {
    private MapStyleControls() { }

    /** View tags: the Dark map switch beside a ride map's Basemap switch, and Map settings' Dark map row. */
    static final String TAG_RIDE = "ride_map_style", TAG_SETTINGS = "map_settings_style";

    /** The switches' name, "Dark map". */
    static String name() { return T.mapsDarkMap(); }

    /** The stored map style (MapStyle.KEY): "dark", "light", or null while never picked (or not a string). */
    static Object stored(SharedPreferences settings) {
        try { return settings.getString(MapStyle.KEY, null); } catch (ClassCastException unreadable) { return null; }
    }

    /** Whether the online maps draw dark tiles now (MapStyle.dark): the saved pick, else the app's theme. */
    static boolean dark(SharedPreferences settings, boolean darkTheme) { return MapStyle.dark(stored(settings), darkTheme); }

    /** Saves a pick for both maps (MapStyle.pick), kept in either theme until the next flip. */
    static void pick(SharedPreferences settings, boolean dark) {
        settings.edit().putString(MapStyle.KEY, MapStyle.pick(dark)).apply();
    }

    /**
     * The ride maps' row: the Basemap switch (MapPreferences.basemapControl; changed gets its state) and, while the basemap
     * is on, the Dark map switch; dark gets whether the maps draw dark tiles, at once and on every change.
     */
    static View basemapRow(ScreenHost host, Consumer<Boolean> changed, Consumer<Boolean> dark) {
        return new RideRow(host, changed, dark);
    }

    /** Map settings' Dark map row: shown while either online map is on (MapStyle.shown). */
    static View settingsRow(ScreenHost host) { return new SettingsRow(host); }

    private static Switch styleSwitch(ScreenHost host) {
        Ui ui = host.ui();
        Switch toggle = new Haptics.FeedbackSwitch(host.activity());
        toggle.setText(name()); toggle.setContentDescription(name());
        toggle.setTextColor(ui.text); toggle.setTextSize(15 * ui.textScale);
        toggle.setMinHeight(ui.dp(48));
        ui.styleSwitch(toggle);
        return toggle;
    }

    /**
     * Basemap and Dark map share the row, half each, when both labels fit whole beside their thumbs; otherwise (large
     * text, a narrow window, a long translation) Dark map takes its own row under Basemap with its full label, so neither
     * label is ever shortened, cut or broken mid-word.
     */
    private static final class RideRow extends LinearLayout {
        /** The gap between the two switches sharing the row, and the Dark map switch's inset (as Basemap's own row). */
        private static final int GAP_DP = 16, INSET_DP = 4;
        private final Ui ui;
        private final SharedPreferences settings;
        private final Consumer<Boolean> dark;
        private final Switch style;
        private final View basemap;
        private boolean updating, listening;
        private Boolean shownDark;
        private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChanged = (p, key) -> {
            if (key == null || key.equals(MapStyle.KEY)) refresh();
        };

        RideRow(ScreenHost host, Consumer<Boolean> changed, Consumer<Boolean> dark) {
            super(host.activity());
            ui = host.ui(); settings = host.appSettings(); this.dark = dark;
            setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            // Before the Basemap switch, which reports its state as it is made.
            style = styleSwitch(host);
            style.setTag(TAG_RIDE);
            style.setVisibility(GONE);
            style.setOnCheckedChangeListener((button, on) -> {
                if (updating) return;
                pick(settings, on);
                refresh();
            });
            basemap = MapPreferences.basemapControl(host, on -> {
                // Only with the basemap on: the style changes nothing on the plain route grid.
                style.setVisibility(on ? VISIBLE : GONE);
                if (changed != null) changed.accept(on);
            });
            addView(basemap, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.topMargin = params.bottomMargin = ui.dp(INSET_DP);
            addView(style, params);
            refresh();
        }

        private void refresh() {
            boolean now = MapStyleControls.dark(settings, ui.dark);
            updating = true; style.setChecked(now); updating = false;
            if (shownDark == null || shownDark != now) {
                shownDark = now;
                if (dark != null) dark.accept(now);
            }
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            boolean beside = true;
            if (style.getVisibility() != GONE && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                int room = (MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight() - ui.dp(GAP_DP)) / 2;
                beside = natural(basemap) <= room && natural(style) + ui.dp(INSET_DP) <= room;
            }
            arrange(beside);
            super.onMeasure(widthSpec, heightSpec);
        }

        /** A view's width with its whole label on one line. */
        private static int natural(View view) {
            view.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            return view.getMeasuredWidth();
        }

        /** Side by side or stacked; set in place during this measure pass (super.onMeasure reads them next). */
        private void arrange(boolean beside) {
            int orientation = beside ? HORIZONTAL : VERTICAL;
            if (getOrientation() != orientation) setOrientation(orientation);
            LinearLayout.LayoutParams first = (LinearLayout.LayoutParams) basemap.getLayoutParams(),
                    second = (LinearLayout.LayoutParams) style.getLayoutParams();
            int width = beside ? 0 : -1, gap = beside ? ui.dp(GAP_DP) : ui.dp(INSET_DP), end = ui.dp(INSET_DP);
            float weight = beside ? 1 : 0;
            first.width = width; first.weight = weight;
            second.width = width; second.weight = weight; second.setMarginStart(gap); second.setMarginEnd(end);
            // The resolved sides too: margins set after the params were resolved are otherwise read only at the next
            // layout-direction change (as JustBackView's ended line).
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            second.leftMargin = rtl ? end : gap; second.rightMargin = rtl ? gap : end;
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            settings.registerOnSharedPreferenceChangeListener(preferenceChanged); listening = true;
            refresh();
        }

        @Override protected void onDetachedFromWindow() {
            if (listening) { settings.unregisterOnSharedPreferenceChangeListener(preferenceChanged); listening = false; }
            super.onDetachedFromWindow();
        }
    }

    /**
     * Map settings' Dark map row, the same setting as the switch beside a ride map's Basemap switch. It shows while either
     * online map is on; a change anywhere else (a ride map, a restore, a basemap turned on or off) shows here at once.
     */
    private static final class SettingsRow extends LinearLayout {
        private final Ui ui;
        private final SharedPreferences settings;
        private final Switch toggle;
        private boolean updating, listening;
        private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChanged = (p, key) -> {
            if (key == null || key.equals(MapStyle.KEY) || key.equals(MapDefault.ENABLED_KEY) || key.equals(JustBackBasemap.KEY)) refresh();
        };

        SettingsRow(ScreenHost host) {
            super(host.activity());
            ui = host.ui(); settings = host.appSettings();
            setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setPadding(ui.dp(4), ui.dp(12), ui.dp(4), 0);
            setTag(TAG_SETTINGS);
            toggle = styleSwitch(host);
            addView(toggle, new LinearLayout.LayoutParams(-1, -2));
            refresh();
            toggle.setOnCheckedChangeListener((button, on) -> {
                if (updating) return;
                pick(settings, on);
                refresh();
            });
        }

        private void refresh() {
            updating = true; toggle.setChecked(dark(settings, ui.dark)); updating = false;
            int shown = MapStyle.shown(MapPreferences.enabled(settings), MapPreferences.justBackEnabled(settings)) ? VISIBLE : GONE;
            if (getVisibility() != shown) setVisibility(shown);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            settings.registerOnSharedPreferenceChangeListener(preferenceChanged); listening = true;
            refresh();
        }

        @Override protected void onDetachedFromWindow() {
            if (listening) { settings.unregisterOnSharedPreferenceChangeListener(preferenceChanged); listening = false; }
            super.onDetachedFromWindow();
        }
    }
}
