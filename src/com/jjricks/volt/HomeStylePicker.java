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
 * and nothing in either Home's storage is touched.
 */
final class HomeStylePicker {
    /** View tags of the two choices. */
    static final String TAG_CLEAN = "home-style-clean", TAG_WIDGETS = "home-style-widgets";

    private HomeStylePicker() { }

    static LinearLayout build(ScreenHost host, SharedPreferences preferences) {
        Ui ui = host.ui();
        boolean clean = HomeStylePrefs.of(preferences).equals(HomeStyle.CLEAN);
        LinearLayout view = new LinearLayout(host.activity());
        view.setOrientation(LinearLayout.VERTICAL);
        // Side by side when each half has room; stacked on a narrow screen or with large text.
        float scale = ui.textScale * host.activity().getResources().getConfiguration().fontScale;
        boolean stacked = host.activity().getResources().getConfiguration().screenWidthDp < 360 || scale > 1.15f;
        LinearLayout choices = new LinearLayout(host.activity());
        choices.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (boolean option : new boolean[] {true, false}) {
            View choice = choice(host, ui, preferences, option, option == clean, stacked);
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

    private static View choice(ScreenHost host, Ui ui, SharedPreferences preferences, boolean clean, boolean selected, boolean stacked) {
        LinearLayout card = new LinearLayout(host.activity());
        card.setOrientation(stacked ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        card.setTag(clean ? TAG_CLEAN : TAG_WIDGETS);
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)),
                ui.shape(ui.surface, 16, selected ? ui.accent : ui.outline), ui.shape(Color.WHITE, 16, 0)));
        HomeStylePreview preview = new HomeStylePreview(host.activity(), ui, clean);
        // The card speaks for itself (name, line, selected); the drawing is decoration inside it.
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (stacked) card.addView(preview, new LinearLayout.LayoutParams(ui.dp(72), ui.dp(116)));
        else card.addView(preview, new LinearLayout.LayoutParams(-1, ui.dp(150)));
        LinearLayout words = new LinearLayout(host.activity());
        words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout title = new LinearLayout(host.activity());
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        Ui.RadioDot dot = ui.radioDot();
        dot.set(selected);
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
                info.setChecked(selected);
                info.setClassName(android.widget.RadioButton.class.getName());
            }
        });
        card.setOnClickListener(v -> {
            ui.pressFeedback(v);
            HomeStylePrefs.markChosen(preferences, clean ? HomeStyle.CLEAN : HomeStyle.WIDGETS);
        });
        return card;
    }
}
