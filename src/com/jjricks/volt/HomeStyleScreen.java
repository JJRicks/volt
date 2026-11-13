package com.jjricks.volt;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Settings › Home style (page {@link HubPages#SETTINGS_HOME}, hub layout plan PR 8): the style choice with its two
 * drawings, then Clean Home's two moments, "Show charging automatically" and "Show the last ride after riding".
 * Text from HubText only.
 */
final class HomeStyleScreen implements AppScreen {
    /** View tags of the two moment switches: the keys they write. */
    static final String TAG_CHARGING = HomeMoment.CHARGING_KEY, TAG_RIDE = HomeMoment.RIDE_KEY;

    private final ScreenHost host;
    private final Ui ui;
    private final SharedPreferences preferences;
    private final LinearLayout view;

    HomeStyleScreen(ScreenHost host) {
        this.host = host;
        ui = host.ui();
        preferences = host.activity().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        view = new LinearLayout(host.activity());
        view.setOrientation(LinearLayout.VERTICAL);
        // The activity's header already shows this page's title (HubText) under its back link, as for every page
        // below a tab's root, so the page adds none of its own (as More's sections opened as pages do).
        view.addView(HomeStylePicker.build(host, preferences), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout card = ui.groupCard();
        card.addView(toggle(HomeMoment.CHARGING_KEY, HubText.momentCharging(), HubText.momentChargingDescription()));
        ui.divider(card);
        card.addView(toggle(HomeMoment.RIDE_KEY, HubText.momentRide(), HubText.momentRideDescription()));
        LinearLayout.LayoutParams cardSpace = new LinearLayout.LayoutParams(-1, -2);
        cardSpace.topMargin = ui.dp(16);
        view.addView(card, cardSpace);
    }

    @Override public View view() { return view; }

    @Override public void refresh() { }

    /** A moment's switch: absent means on (HomeMoment.on); turning it off stores false. */
    private View toggle(String key, Msg name, Msg description) {
        LinearLayout line = new LinearLayout(host.activity());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(ui.dp(64));
        line.setPadding(0, ui.dp(10), 0, ui.dp(10));
        LinearLayout words = new LinearLayout(host.activity());
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(ui.text(name, 15, false));
        TextView sub = ui.caption(description.render());
        sub.setPadding(0, ui.dp(3), 0, 0);
        words.addView(sub);
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(host.activity());
        ui.styleSwitch(toggle);
        toggle.setTag(key);
        toggle.setChecked(HomeMoment.on(stored(key)));
        toggle.setContentDescription(name.render());
        toggle.setOnCheckedChangeListener((button, on) -> preferences.edit().putBoolean(key, on).apply());
        LinearLayout.LayoutParams switchSpace = new LinearLayout.LayoutParams(-2, -2);
        switchSpace.setMarginStart(ui.dp(12));
        line.addView(toggle, switchSpace);
        line.setOnClickListener(v -> toggle.toggle());
        return line;
    }

    private Object stored(String key) {
        try { return preferences.getAll().get(key); } catch (RuntimeException e) { return null; }
    }
}
