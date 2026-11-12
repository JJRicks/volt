package com.jjricks.volt;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The one-time What's new sheet about Home styles (hub layout plan 1.8, PR 9; Joel 6): an owner who updated from a
 * Volt without Clean Home, and never chose a style, sees it once on the widget dashboard. Its title and one sentence,
 * the two styles drawn side by side (stacked on a narrow window or with large text), then "Switch to Clean" and
 * "Keep Widgets". Either button is an explicit choice (HomeStylePrefs.markChosen); closing the sheet any other way
 * keeps Widgets and records nothing, and MainActivity has already recorded that it was shown, so it never returns.
 * Text from HubText only.
 */
final class WhatsNewSheet {
    /** View tags: the sheet's body and its two buttons. */
    static final String TAG = "home-whats-new", TAG_SWITCH = "home-whats-new-switch", TAG_KEEP = "home-whats-new-keep";

    private WhatsNewSheet() { }

    /** Shows the sheet over the current screen and returns it, so the activity can close it when it goes away. */
    static AlertDialog show(ScreenHost host, SharedPreferences preferences) {
        Ui ui = host.ui();
        AlertDialog[] shown = new AlertDialog[1];
        Runnable close = () -> { if (shown[0] != null) shown[0].dismiss(); };
        LinearLayout content = host.sheetContent(HubText.whatsNewTitle(), HubText.whatsNewBody(), close);
        content.setTag(TAG);
        float scale = ui.textScale * host.activity().getResources().getConfiguration().fontScale;
        boolean stacked = host.activity().getResources().getConfiguration().screenWidthDp < 360 || scale > 1.15f;
        LinearLayout styles = new LinearLayout(host.activity());
        styles.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (boolean clean : new boolean[] {true, false}) {
            LinearLayout.LayoutParams space = stacked ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1);
            if (clean) {
                if (stacked) space.bottomMargin = ui.dp(10);
                else space.setMarginEnd(ui.dp(10));
            }
            styles.addView(style(host, ui, clean, stacked), space);
        }
        content.addView(styles, new LinearLayout.LayoutParams(-1, -2));
        TextView switchButton = ui.button(HubText.whatsNewSwitch().render(), () -> {
            HomeStylePrefs.markChosen(preferences, HomeStyle.CLEAN);
            close.run();
        });
        switchButton.setTag(TAG_SWITCH);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2);
        first.topMargin = ui.dp(16);
        content.addView(switchButton, first);
        TextView keep = ui.secondaryButton(HubText.whatsNewKeep().render(), () -> {
            HomeStylePrefs.markChosen(preferences, HomeStyle.WIDGETS);
            close.run();
        });
        keep.setTag(TAG_KEEP);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-1, -2);
        second.topMargin = ui.dp(8);
        content.addView(keep, second);
        TextView later = ui.caption(HubText.whatsNewLater().render());
        later.setGravity(Gravity.CENTER_HORIZONTAL);
        later.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), 0);
        content.addView(later, new LinearLayout.LayoutParams(-1, -2));
        shown[0] = host.showSheet(content);
        return shown[0];
    }

    /** One style: its drawing (which names itself for screen readers), its name and its one line. Not a button. */
    private static View style(ScreenHost host, Ui ui, boolean clean, boolean stacked) {
        LinearLayout card = new LinearLayout(host.activity());
        card.setOrientation(stacked ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        card.setBackground(ui.shape(ui.surface, 16, clean ? ui.accent : ui.outline));
        HomeStylePreview preview = new HomeStylePreview(host.activity(), ui, clean);
        if (stacked) card.addView(preview, new LinearLayout.LayoutParams(ui.dp(72), ui.dp(116)));
        else card.addView(preview, new LinearLayout.LayoutParams(-1, ui.dp(150)));
        LinearLayout words = new LinearLayout(host.activity());
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(ui.text(HubText.homeStyleName(clean), 15, true), new LinearLayout.LayoutParams(-1, -2));
        TextView line = ui.caption(HubText.homeStyleDescription(clean).render());
        line.setPadding(0, ui.dp(4), 0, 0);
        words.addView(line, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams wordsSpace = stacked ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        if (stacked) wordsSpace.setMarginStart(ui.dp(12));
        else wordsSpace.topMargin = ui.dp(10);
        card.addView(words, wordsSpace);
        return card;
    }
}
