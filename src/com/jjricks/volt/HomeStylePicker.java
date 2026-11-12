package com.jjricks.volt;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The Home style choice (hub layout plan, PR 8): Clean and Widgets, each with its drawing, name and one line, and the
 * note that switching keeps the widget layout. Settings › Home style and Clean's Edit sheet show it. A pick is an
 * explicit choice (HomeStylePrefs.markChosen); Home rebuilds when the style changes (MainActivity's settings listener),
 * and nothing in either Home's storage is touched. Setup's last step shows it too, holding the pick until setup ends.
 */
final class HomeStylePicker {
    /** View tags of the two choices. */
    static final String TAG_CLEAN = "home-style-clean", TAG_WIDGETS = "home-style-widgets";

    private HomeStylePicker() { }

    /** The picker that writes each pick at once (Settings › Home style, Clean's Edit sheet). */
    static LinearLayout build(ScreenHost host, SharedPreferences preferences) {
        return build(host, HomeStylePrefs.of(preferences), style -> HomeStylePrefs.markChosen(preferences, style));
    }

    /**
     * The picker with selected (HomeStyle.CLEAN or WIDGETS) chosen; each pick moves the selection here and hands the
     * style to picked, which decides when to store it.
     */
    static LinearLayout build(ScreenHost host, String selected, java.util.function.Consumer<String> picked) {
        Ui ui = host.ui();
        boolean clean = HomeStyle.CLEAN.equals(selected);
        LinearLayout view = new LinearLayout(host.activity());
        view.setOrientation(LinearLayout.VERTICAL);
        // Side by side when each half has room; stacked on a narrow screen or with large text.
        float scale = ui.textScale * host.activity().getResources().getConfiguration().fontScale;
        boolean stacked = host.activity().getResources().getConfiguration().screenWidthDp < 360 || scale > 1.15f;
        LinearLayout choices = new LinearLayout(host.activity());
        choices.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        View[] cards = new View[2];
        Ui.RadioDot[] dots = new Ui.RadioDot[2];
        for (boolean option : new boolean[] {true, false}) {
            View choice = choice(host, ui, option, option == clean, stacked, cards, dots, picked);
            LinearLayout.LayoutParams space = stacked ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1);
            if (option) {
                if (stacked) space.bottomMargin = ui.dp(10);
                else space.setMarginEnd(ui.dp(10));
            }
            choices.addView(choice, space);
        }
        view.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        TextView note = ui.caption(HubText.homeStyleKeepsLayout().render());
        note.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(4));
        view.addView(note, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    /** One style's card; cards and dots hold both (Clean first) so a pick can move the selection. */
    private static View choice(ScreenHost host, Ui ui, boolean clean, boolean selected, boolean stacked, View[] cards,
                               Ui.RadioDot[] dots, java.util.function.Consumer<String> picked) {
        LinearLayout card = new LinearLayout(host.activity());
        card.setOrientation(stacked ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        card.setTag(clean ? TAG_CLEAN : TAG_WIDGETS);
        card.setBackground(background(ui, selected));
        cards[clean ? 0 : 1] = card;
        HomeStylePreview preview = new HomeStylePreview(host, clean);
        // The card speaks for itself (name, line, selected); the drawing is decoration inside it.
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Side by side the drawing takes the card's width and a phone's proportions (HomeStylePreview.onMeasure).
        if (stacked) card.addView(preview, new LinearLayout.LayoutParams(ui.dp(104), -2));
        else card.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout words = new LinearLayout(host.activity());
        words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout title = new LinearLayout(host.activity());
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        Ui.RadioDot dot = ui.radioDot();
        dot.set(selected);
        dots[clean ? 0 : 1] = dot;
        title.addView(dot, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        TextView name = ui.text(HubText.homeStyleName(clean), 15, true);
        LinearLayout.LayoutParams nameSpace = new LinearLayout.LayoutParams(0, -2, 1);
        nameSpace.setMarginStart(ui.dp(8));
        title.addView(name, nameSpace);
        words.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView line = ui.caption(HubText.homeStyleDescription(clean).render());
        line.setPadding(0, ui.dp(4), 0, 0);
        words.addView(line, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams wordsSpace = stacked ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        if (stacked) wordsSpace.setMarginStart(ui.dp(12));
        else wordsSpace.topMargin = ui.dp(10);
        card.addView(words, wordsSpace);
        card.setClickable(true);
        card.setFocusable(true);
        card.setSelected(selected);
        card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCheckable(true);
                info.setChecked(host.isSelected());
                info.setClassName(android.widget.RadioButton.class.getName());
            }
        });
        card.setOnClickListener(v -> {
            ui.pressFeedback(v);
            for (int i = 0; i < 2; i++) {
                boolean on = (i == 0) == clean;
                if (cards[i] == null || cards[i].isSelected() == on) continue;
                cards[i].setSelected(on);
                cards[i].setBackground(background(ui, on));
                dots[i].set(on);
            }
            picked.accept(clean ? HomeStyle.CLEAN : HomeStyle.WIDGETS);
        });
        return card;
    }

    private static android.graphics.drawable.Drawable background(Ui ui, boolean selected) {
        return new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)),
                ui.shape(ui.surface, 16, selected ? ui.accent : ui.outline), ui.shape(Color.WHITE, 16, 0));
    }
}
