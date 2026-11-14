package com.jjricks.volt;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/**
 * Settings › Home style (page {@link HubPages#SETTINGS_HOME}, hub layout plan PR 8), as the Layout Plan's mock-up
 * draws it (the contributor's decisions on #383): the style choice with its two drawings, then one card holding
 * "Quick actions" (the four Clean shows, by their names on Clean; it opens the same picker as Clean's Edit sheet) and
 * Clean Home's two moments, "Show charging automatically" and "Show the last ride after riding", each a switch with
 * no line under it (answer 3). Both moments are on until turned off. Text from HubText only.
 */
final class HomeStyleScreen implements AppScreen {
    /** View tags of the two moment switches: the keys they write. */
    static final String TAG_CHARGING = HomeMoment.CHARGING_KEY, TAG_RIDE = HomeMoment.RIDE_KEY;
    /** View tag of the Quick actions row. */
    static final String TAG_QUICK = "home-style-quick-actions";

    private final ScreenHost host;
    private final Ui ui;
    private final SharedPreferences preferences;
    private final DashboardScreen.Actions actions;
    private final LinearLayout view;
    /** The Quick actions row's line of names, or null with no scooter (there is nowhere to keep the four then). */
    private final TextView quickNames;
    /** The scooter whose four the row shows ("" for none); another one being active rebuilds the page. */
    private final String profileId;
    private DashboardScreen.QuickChooser chooser;
    private android.app.AlertDialog slotsSheet;
    private boolean closed;

    HomeStyleScreen(ScreenHost host, DashboardScreen.Actions actions) {
        this.host = host;
        this.actions = actions;
        ui = host.ui();
        preferences = host.activity().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        view = new LinearLayout(host.activity());
        view.setOrientation(LinearLayout.VERTICAL);
        // The activity's header already shows this page's title (HubText) under its back link, as for every page
        // below a tab's root, so the page adds none of its own (as More's sections opened as pages do).
        view.addView(HomeStylePicker.build(host, preferences), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout card = ui.groupCard();
        ScooterService service = host.service();
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        profileId = profile == null ? "" : profile.id;
        if (profile != null) {
            quickNames = ui.caption("");
            card.addView(quickRow(quickNames));
            ui.divider(card);
        } else quickNames = null;
        card.addView(toggle(HomeMoment.CHARGING_KEY, HubText.momentCharging()));
        ui.divider(card);
        card.addView(toggle(HomeMoment.RIDE_KEY, HubText.momentRide()));
        LinearLayout.LayoutParams cardSpace = new LinearLayout.LayoutParams(-1, -2);
        cardSpace.topMargin = ui.dp(12);
        view.addView(card, cardSpace);
        refresh();
    }

    @Override public View view() { return view; }

    @Override public void refresh() {
        if (closed) return;
        ScooterService service = host.service();
        ScooterProfiles.Profile current = service == null ? null : service.profiles.active();
        if (!profileId.equals(current == null ? "" : current.id)) { host.rebuild(); return; }
        if (quickNames != null) {
            String names = HomeQuickEditor.summary(HomeQuickEditor.load(service, preferences));
            if (!names.contentEquals(quickNames.getText())) quickNames.setText(names);
        }
    }

    @Override public void close() {
        closed = true;
        if (slotsSheet != null && slotsSheet.isShowing()) slotsSheet.dismiss();
        slotsSheet = null;
        if (chooser != null) chooser.close();
        chooser = null;
    }

    /** "Quick actions" over the four's names, with a chevron: opens the four, each opening the quick-action picker. */
    private View quickRow(TextView names) {
        LinearLayout line = new LinearLayout(host.activity());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setTag(TAG_QUICK);
        line.setMinimumHeight(ui.dp(56));
        line.setPadding(0, ui.dp(8), 0, ui.dp(8));
        LinearLayout words = new LinearLayout(host.activity());
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = ui.text(HubText.editQuickActions(), 15, false);
        words.addView(title, new LinearLayout.LayoutParams(-1, -2));
        names.setPadding(0, ui.dp(2), 0, 0);
        words.addView(names, new LinearLayout.LayoutParams(-1, -2));
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams chevron = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
        chevron.setMarginStart(ui.dp(12));
        line.addView(ui.icon("chevron", 15, ui.muted), chevron);
        line.setClickable(true);
        line.setFocusable(true);
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        line.setOnClickListener(v -> showSlots());
        return line;
    }

    /** The four in a sheet, as on Clean's Edit sheet; a slot opens the picker for its position. */
    private void showSlots() {
        if (closed) return;
        if (slotsSheet != null && slotsSheet.isShowing()) slotsSheet.dismiss();
        ScooterService service = host.service();
        List<String> ids = HomeQuickEditor.load(service, preferences);
        if (ids.isEmpty()) return;
        LinearLayout content = host.sheetContent(HubText.editQuickActions(), null, () -> { if (slotsSheet != null) slotsSheet.dismiss(); });
        content.addView(HomeQuickEditor.slots(host, ids, index -> {
            if (closed) return;
            if (chooser == null) chooser = DashboardScreen.quickChooser(host, actions);
            chooser.choose(ids, index, picked -> {
                java.util.ArrayList<String> next = new java.util.ArrayList<>(ids);
                next.set(index, picked);
                HomeQuickEditor.save(host.service(), preferences, next);
                if (slotsSheet != null && slotsSheet.isShowing()) slotsSheet.dismiss();
                refresh();
            });
        }), new LinearLayout.LayoutParams(-1, -2));
        slotsSheet = host.showSheet(content);
    }

    /** A moment's switch, with no line under it as the mock-up has it: absent means on (HomeMoment.on); off stores false. */
    private View toggle(String key, Msg name) {
        LinearLayout line = new LinearLayout(host.activity());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(ui.dp(52));
        line.setPadding(0, ui.dp(6), 0, ui.dp(6));
        line.addView(ui.text(name, 15, false), new LinearLayout.LayoutParams(0, -2, 1));
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
