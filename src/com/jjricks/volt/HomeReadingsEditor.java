package com.jjricks.volt;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The overview card's two readings in Settings › Home style (HomeReadings): the active scooter's two, stored per
 * scooter as its quick actions are (HomeQuickEditor), and the page's Overview readings row (Row): its line of names, the
 * sheet with the two slots, and DashboardScreen's reading picker (ReadingChooser) for a slot. Readings are named as the
 * card names them (ScooterReadingLabels, DashboardScreen.cardLabel); the row's own words come from its page (Words).
 */
final class HomeReadingsEditor {
    /** View tag of the Home style page's Overview readings row. */
    static final String TAG_ROW = "home-style-overview-readings";
    /** View tag prefix of the sheet's two slot rows; the slot's number (1 or 2) follows. */
    static final String TAG_SLOT = "home-reading-slot-";

    private HomeReadingsEditor() { }

    /** The row's words, from its page: its title (also the sheet's), a slot's name, and how a slot's row is read. */
    interface Words {
        String title();
        /** Slot 0 is the first reading, 1 the second. */
        String slot(int slot);
        /** A slot's row read aloud: the slot's name, then the reading in it. */
        String slotSpoken(String slot, String reading);
    }

    /** The active scooter's two: the owner's, or Odometer and Drive mode while none are stored. The defaults with no scooter. */
    static List<String> load(ScooterService service, SharedPreferences preferences) {
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        return HomeReadings.load(key -> {
            try { return preferences.getString(key, null); } catch (ClassCastException wrongType) { return null; }
        }, profile == null ? null : profile.id);
    }

    /** Stores the active scooter's two; without a scooter there is nowhere to keep them (nothing offers it then). */
    static void save(ScooterService service, SharedPreferences preferences, List<String> keys) {
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        if (profile == null || keys == null || keys.size() != HomeReadings.COUNT) return;
        preferences.edit().putString(HomeReadings.key(0, profile.id), keys.get(0))
                .putString(HomeReadings.key(1, profile.id), keys.get(1)).apply();
    }

    /** A reading's name as the card's label first shows it ("Odometer", "Battery voltage"). */
    static String name(String key) { return ScooterReadingLabels.label(key, DashboardScreen.cardLabel(key)); }

    /** The two names for the row's line ("Odometer, Drive mode"), joined by the language's list separator. */
    static String summary(List<String> keys) {
        List<String> names = new ArrayList<>();
        for (String key : keys) names.add(name(key));
        return String.join(HubText.listSeparator().render(), names);
    }

    /** The two as TalkBack reads them: each reading's name as the card speaks it ("Total odometer", "Drive mode"). */
    static String spokenSummary(List<String> keys) {
        List<String> names = new ArrayList<>();
        for (String key : keys) names.add(DashboardScreen.cardLabel(key));
        return String.join(HubText.listSeparator().render(), names);
    }

    /**
     * One slot's row in the sheet: the reading's icon, the slot's name ("First reading") over the reading's name, and a
     * chevron; a tap runs tapped (which opens the picker for that slot). Tagged TAG_SLOT + (slot + 1).
     */
    static View slot(ScreenHost host, List<String> keys, int slot, Words words, Runnable tapped) {
        Ui ui = host.ui();
        String key = keys.get(slot), slotName = words.slot(slot), reading = DashboardScreen.cardLabel(key);
        LinearLayout line = new LinearLayout(host.activity());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setTag(TAG_SLOT + (slot + 1));
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(ui.dp(60));
        line.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
        line.addView(ui.icon(DashboardScreen.readingGlyph(ui, key), 20, ui.accent), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        LinearLayout texts = new LinearLayout(host.activity());
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        texts.addView(ui.caption(slotName), new LinearLayout.LayoutParams(-1, -2));
        TextView name = ui.text(reading, 15, false);
        name.setPadding(0, ui.dp(2), 0, 0);
        texts.addView(name, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams textsSize = new LinearLayout.LayoutParams(0, -2, 1);
        textsSize.setMarginStart(ui.dp(12));
        textsSize.setMarginEnd(ui.dp(8));
        line.addView(texts, textsSize);
        line.addView(ui.icon("chevron", 15, ui.muted), new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)));
        line.setContentDescription(words.slotSpoken(slotName, reading));
        line.setClickable(true);
        line.setFocusable(true);
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        line.setOnClickListener(v -> tapped.run());
        return line;
    }

    /**
     * Settings › Home style's Overview readings row (owner, Nov 19 2026: "right under the Quick actions setting"), as the
     * page's Quick actions row: its title over the two readings' names, with a chevron. It opens a sheet with the two
     * slots, as Quick actions opens its four; a slot opens the reading picker. A pick is stored for the active scooter
     * and shows on both Home styles at once: the whole window redraws, as for the quick actions, so the scooter pane
     * beside the page on two panes shows it too. A reading the other slot holds changes places with the slot's own
     * (HomeReadings.choose). Only offered with a scooter, as Quick actions.
     */
    static final class Row {
        private final ScreenHost host;
        private final DashboardScreen.Actions actions;
        private final SharedPreferences preferences;
        private final Words words;
        private final LinearLayout view;
        private final TextView names;
        private android.app.AlertDialog sheet;
        private DashboardScreen.ReadingChooser chooser;
        private boolean closed;

        Row(ScreenHost host, DashboardScreen.Actions actions, SharedPreferences preferences, Words words) {
            this.host = host;
            this.actions = actions;
            this.preferences = preferences;
            this.words = words;
            Ui ui = host.ui();
            view = new LinearLayout(host.activity());
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setTag(TAG_ROW);
            view.setMinimumHeight(ui.dp(56));
            view.setPadding(0, ui.dp(8), 0, ui.dp(8));
            LinearLayout texts = new LinearLayout(host.activity());
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(ui.text(words.title(), 15, false), new LinearLayout.LayoutParams(-1, -2));
            names = ui.caption("");
            names.setPadding(0, ui.dp(2), 0, 0);
            texts.addView(names, new LinearLayout.LayoutParams(-1, -2));
            view.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams chevron = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
            chevron.setMarginStart(ui.dp(12));
            view.addView(ui.icon("chevron", 15, ui.muted), chevron);
            view.setClickable(true);
            view.setFocusable(true);
            view.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
            view.setOnClickListener(v -> open());
            refresh();
        }

        View view() { return view; }

        /** The line names the active scooter's two as the card shows them; TalkBack reads their full names. */
        void refresh() {
            if (closed) return;
            List<String> keys = load(host.service(), preferences);
            String shown = summary(keys), spoken = spokenSummary(keys);
            if (!shown.contentEquals(names.getText())) names.setText(shown);
            if (!spoken.contentEquals(String.valueOf(names.getContentDescription()))) names.setContentDescription(spoken);
        }

        void close() {
            closed = true;
            if (sheet != null && sheet.isShowing()) sheet.dismiss();
            sheet = null;
            if (chooser != null) chooser.close();
            chooser = null;
        }

        private void open() {
            if (closed) return;
            if (sheet != null && sheet.isShowing()) sheet.dismiss();
            ScooterService service = host.service();
            if (service == null || service.profiles.active() == null) return;
            List<String> keys = load(service, preferences);
            LinearLayout content = host.sheetContent(words.title(), null, () -> { if (sheet != null) sheet.dismiss(); });
            Ui ui = host.ui();
            LinearLayout card = ui.groupCard();
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) ui.divider(card);
                int slot = i;
                card.addView(slot(host, keys, slot, words, () -> choose(keys, slot)));
            }
            content.addView(card, new LinearLayout.LayoutParams(-1, -2));
            sheet = host.showSheet(content);
        }

        private void choose(List<String> keys, int slot) {
            if (closed) return;
            if (chooser == null) chooser = DashboardScreen.readingChooser(host, actions);
            chooser.choose(words.slot(slot), keys, slot, words.slot(1 - slot), picked -> {
                save(host.service(), preferences, HomeReadings.choose(keys, slot, picked));
                if (sheet != null && sheet.isShowing()) sheet.dismiss();
                host.redraw();
            });
        }
    }
}
