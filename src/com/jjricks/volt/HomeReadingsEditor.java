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
 * scooter as its quick actions are (HomeQuickEditor), the row's line of names and the sheet's two slot rows. A slot row
 * opens DashboardScreen's reading picker (ReadingChooser). Readings are named as the card names them
 * (ScooterReadingLabels, DashboardScreen.cardLabel); the rest of the text comes from HubText.
 */
final class HomeReadingsEditor {
    /** View tag prefix of the sheet's two slot rows; the slot's number (1 or 2) follows. */
    static final String TAG_SLOT = "home-reading-slot-";

    private HomeReadingsEditor() { }

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

    /** The two names for the Home style row's line ("Odometer, Drive mode"), joined by the language's list separator. */
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
    static View slot(ScreenHost host, List<String> keys, int slot, Runnable tapped) {
        Ui ui = host.ui();
        String key = keys.get(slot);
        LinearLayout line = new LinearLayout(host.activity());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setTag(TAG_SLOT + (slot + 1));
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(ui.dp(60));
        line.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
        line.addView(ui.icon(DashboardScreen.readingGlyph(ui, key), 20, ui.accent), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        LinearLayout words = new LinearLayout(host.activity());
        words.setOrientation(LinearLayout.VERTICAL);
        words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        String slotName = HubText.overviewReadingSlot(slot).render();
        words.addView(ui.caption(slotName), new LinearLayout.LayoutParams(-1, -2));
        TextView reading = ui.text(DashboardScreen.cardLabel(key), 15, false);
        reading.setPadding(0, ui.dp(2), 0, 0);
        words.addView(reading, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams wordsSize = new LinearLayout.LayoutParams(0, -2, 1);
        wordsSize.setMarginStart(ui.dp(12));
        wordsSize.setMarginEnd(ui.dp(8));
        line.addView(words, wordsSize);
        line.addView(ui.icon("chevron", 15, ui.muted), new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)));
        line.setContentDescription(HubText.overviewReadingSlotSpoken(slotName, DashboardScreen.cardLabel(key)).render());
        line.setClickable(true);
        line.setFocusable(true);
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        line.setOnClickListener(v -> tapped.run());
        return line;
    }

    /** The two slots in one card, for a sheet; each slot runs tapped with its slot (0 or 1). */
    static LinearLayout slots(ScreenHost host, List<String> keys, java.util.function.IntConsumer tapped) {
        Ui ui = host.ui();
        LinearLayout card = ui.groupCard();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) ui.divider(card);
            int slot = i;
            card.addView(slot(host, keys, i, () -> tapped.accept(slot)));
        }
        return card;
    }
}
