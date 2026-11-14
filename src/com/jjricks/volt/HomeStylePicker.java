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
 * The Home style choice (hub layout plan, PR 8), as the Layout Plan's mock-up draws it (the contributor's decisions on
 * #383): Clean and Widgets side by side, each card its drawing over its name with the radio at the right of the name,
 * the chosen one outlined in red; under them, the chosen style's one line and the note that switching keeps the widget
 * layout. Settings › Home style and Clean's Edit sheet show it. A pick is an
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
        // Side by side as the mock-up, while each half has room for its drawing and name (HomeStyle.pickerSideBySide).
        float scale = ui.textScale * host.activity().getResources().getConfiguration().fontScale;
        boolean stacked = !HomeStyle.pickerSideBySide(host.activity().getResources().getConfiguration().screenWidthDp, scale);
        LinearLayout choices = new LinearLayout(host.activity());
        choices.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        View[] cards = new View[2];
        Ui.RadioDot[] dots = new Ui.RadioDot[2];
        TextView note = ui.caption(note(clean));
        note.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f * ui.textScale);
        java.util.function.Consumer<String> pick = style -> {
            note.setText(note(HomeStyle.CLEAN.equals(style)));
            picked.accept(style);
        };
        for (boolean option : new boolean[] {true, false}) {
            View choice = choice(host, ui, option, option == clean, stacked, cards, dots, pick);
            LinearLayout.LayoutParams space = stacked ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1);
            if (option) {
                if (stacked) space.bottomMargin = ui.dp(10);
                else space.setMarginEnd(ui.dp(10));
            }
            choices.addView(choice, space);
        }
        view.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        note.setPadding(ui.dp(2), ui.dp(10), ui.dp(2), ui.dp(4));
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
        TextView name = ui.text(HubText.homeStyleName(clean), 15.5f, true);
        title.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        // The radio at the right of the name, as the mock-up has it.
        Ui.RadioDot dot = ui.radioDot();
        dot.set(selected);
        dots[clean ? 0 : 1] = dot;
        LinearLayout.LayoutParams dotSpace = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20));
        dotSpace.setMarginStart(ui.dp(8));
        title.addView(dot, dotSpace);
        words.addView(title, new LinearLayout.LayoutParams(-1, -2));
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

    /** The line under the cards: the chosen style's description, then that switching keeps the widget layout. */
    private static String note(boolean clean) {
        return HubText.homeStyleDescription(clean).render() + " " + HubText.homeStyleKeepsLayout().render();
    }

    /** A card: the surface with a 2 dp red outline when chosen, as the mock-up; none otherwise. */
    private static android.graphics.drawable.Drawable background(Ui ui, boolean selected) {
        android.graphics.drawable.GradientDrawable fill = ui.shape(ui.surface, 18, 0);
        if (selected) fill.setStroke(ui.dp(2), ui.accent);
        return new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)), fill, ui.shape(Color.WHITE, 18, 0));
    }
}
