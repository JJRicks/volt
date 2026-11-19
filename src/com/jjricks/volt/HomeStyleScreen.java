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
 * "Quick actions" (the four Clean shows, by their names on Clean; it opens the same picker as Clean's Edit sheet),
 * "Overview readings" (the battery card's two small readings on both Home styles, HomeReadings; owner, Nov 19 2026) and
 * Clean Home's two moments, "Show charging automatically" and "Show the last ride after riding", each a switch with
 * no line under it (answer 3). Both moments are on until turned off. Text from HubText only.
 */
final class HomeStyleScreen implements AppScreen {
    /** View tags of the two moment switches: the keys they write. */
    static final String TAG_CHARGING = HomeMoment.CHARGING_KEY, TAG_RIDE = HomeMoment.RIDE_KEY;
    /** View tag of the Quick actions row. */
    static final String TAG_QUICK = "home-style-quick-actions";
    /** View tag of the Overview readings row. */
    static final String TAG_READINGS = "home-style-overview-readings";

    private final ScreenHost host;
    private final Ui ui;
    private final SharedPreferences preferences;
    private final DashboardScreen.Actions actions;
    private final LinearLayout view;
    /** The Quick actions row's line of names, or null with no scooter (there is nowhere to keep the four then). */
    private final TextView quickNames;
    /** The Overview readings row's line of names, or null with no scooter (as quickNames). */
    private final TextView readingNames;
    /** The scooter whose four the row shows ("" for none); another one being active rebuilds the page. */
    private final String profileId;
    private DashboardScreen.QuickChooser chooser;
    private DashboardScreen.ReadingChooser readingChooser;
    /** The picker's drawings: refresh redraws their quick actions from the stored four. */
    private final List<HomeStylePreview> previews = new java.util.ArrayList<>();
    private android.app.AlertDialog slotsSheet, readingsSheet;
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
        View picker = HomeStylePicker.build(host, preferences);
        view.addView(picker, new LinearLayout.LayoutParams(-1, -2));
        previews(picker, previews);
        LinearLayout card = ui.groupCard();
        ScooterService service = host.service();
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        profileId = profile == null ? "" : profile.id;
        if (profile != null) {
            quickNames = ui.caption("");
            card.addView(quickRow(quickNames));
            ui.divider(card);
            // The overview card's two readings (owner, Nov 19 2026: "right under the Quick actions setting").
            readingNames = ui.caption("");
            card.addView(readingsRow(readingNames));
            ui.divider(card);
        } else quickNames = readingNames = null;
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
            List<String> ids = HomeQuickEditor.load(service, preferences);
            String names = HomeQuickEditor.summary(ids);
            if (!names.contentEquals(quickNames.getText())) quickNames.setText(names);
            // Read by the actions' full names, as the round buttons speak them ("Charge done by", not "Charge by").
            String spoken = HomeQuickEditor.spokenSummary(ids);
            if (!spoken.contentEquals(String.valueOf(quickNames.getContentDescription()))) quickNames.setContentDescription(spoken);
        }
        if (readingNames != null) {
            List<String> keys = HomeReadingsEditor.load(service, preferences);
            String names = HomeReadingsEditor.summary(keys);
            if (!names.contentEquals(readingNames.getText())) readingNames.setText(names);
            String spoken = HomeReadingsEditor.spokenSummary(keys);
            if (!spoken.contentEquals(String.valueOf(readingNames.getContentDescription()))) readingNames.setContentDescription(spoken);
        }
        // The drawings too: on two panes an edit from Home's long-press sheet in the scooter pane doesn't rebuild this
        // page, so Clean's preview takes the stored four here (#394 council review, third round, P3).
        for (HomeStylePreview preview : previews) preview.refreshQuick(host);
    }

    /** The style drawings under view, in order. */
    private static void previews(View view, List<HomeStylePreview> into) {
        if (view instanceof HomeStylePreview) into.add((HomeStylePreview) view);
        else if (view instanceof android.view.ViewGroup)
            for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) previews(((android.view.ViewGroup) view).getChildAt(i), into);
    }

    @Override public void close() {
        closed = true;
        if (slotsSheet != null && slotsSheet.isShowing()) slotsSheet.dismiss();
        slotsSheet = null;
        if (readingsSheet != null && readingsSheet.isShowing()) readingsSheet.dismiss();
        readingsSheet = null;
        if (chooser != null) chooser.close();
        chooser = null;
        if (readingChooser != null) readingChooser.close();
        readingChooser = null;
    }

    /** "Quick actions" over the four's names, with a chevron: opens the four, each opening the quick-action picker. */
    private View quickRow(TextView names) {
        return chevronRow(TAG_QUICK, HubText.editQuickActions(), names, this::showSlots);
    }

    /** "Overview readings" over the two readings' names, with a chevron: opens the two, each opening the reading picker. */
    private View readingsRow(TextView names) {
        return chevronRow(TAG_READINGS, HubText.overviewReadings(), names, this::showReadings);
    }

    /** A row of this card: its title over a line (names), a chevron, opening tapped. */
    private View chevronRow(String tag, Msg name, TextView names, Runnable tapped) {
        LinearLayout line = new LinearLayout(host.activity());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setTag(tag);
        line.setMinimumHeight(ui.dp(56));
        line.setPadding(0, ui.dp(8), 0, ui.dp(8));
        LinearLayout words = new LinearLayout(host.activity());
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = ui.text(name, 15, false);
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
        line.setOnClickListener(v -> tapped.run());
        return line;
    }

    /**
     * The two readings in a sheet, as Quick actions' four: a slot opens the reading picker for it. A pick shows on both
     * Home styles at once (the whole window redraws, as for the quick actions, so the scooter pane beside this page on two
     * panes shows it too); a reading the other slot holds changes places with this slot's (HomeReadings.choose).
     */
    private void showReadings() {
        if (closed) return;
        if (readingsSheet != null && readingsSheet.isShowing()) readingsSheet.dismiss();
        ScooterService service = host.service();
        if (service == null || service.profiles.active() == null) return;
        List<String> keys = HomeReadingsEditor.load(service, preferences);
        LinearLayout content = host.sheetContent(HubText.overviewReadings(), null, () -> { if (readingsSheet != null) readingsSheet.dismiss(); });
        content.addView(HomeReadingsEditor.slots(host, keys, slot -> {
            if (closed) return;
            if (readingChooser == null) readingChooser = DashboardScreen.readingChooser(host, actions);
            readingChooser.choose(HubText.overviewReadingSlot(slot).render(), keys, slot, HubText.overviewReadingSlot(1 - slot).render(), picked -> {
                HomeReadingsEditor.save(host.service(), preferences, HomeReadings.choose(keys, slot, picked));
                if (readingsSheet != null && readingsSheet.isShowing()) readingsSheet.dismiss();
                host.redraw();
            });
        }), new LinearLayout.LayoutParams(-1, -2));
        readingsSheet = host.showSheet(content);
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
                // The whole window, not this page alone: on two panes the scooter pane's Clean Home shows the new four at
                // once (CleanHomeScreen.followQuick; #394 council review, second round, 1).
                host.redraw();
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
