package com.jjricks.volt;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A Hub page made of link rows (hub layout plan): each row has an icon, a title, a live one-line value and a chevron,
 * and opens another page. A row can carry the maintenance dot (orange, as Home's Service row and the dock's icon).
 * It holds no catalog references: titles and values arrive as text from HubText, so the page renders in the current
 * language on every refresh.
 */
final class HubListScreen implements AppScreen {
    /** One row. Value, dot and visible are read on every refresh; any of them may be null. */
    static final class Row {
        final String id, icon;
        final CharSequence title;
        final Supplier<CharSequence> value;
        final BooleanSupplier dot, visible;
        final Runnable action;
        Row(String id, String icon, CharSequence title, Supplier<CharSequence> value, BooleanSupplier dot, BooleanSupplier visible, Runnable action) {
            this.id = id; this.icon = icon; this.title = title; this.value = value; this.dot = dot; this.visible = visible; this.action = action;
        }
    }

    /** View tag of a row's tappable line: "hub-row-" and the page id it opens. */
    static String tag(String id) { return "hub-row-" + id; }

    private final Ui ui;
    private final LinearLayout view;
    private final List<Row> rows = new ArrayList<>();
    private final List<LinearLayout> lines = new ArrayList<>();
    private final List<TextView> names = new ArrayList<>(), values = new ArrayList<>();
    private final List<View> dots = new ArrayList<>(), dividers = new ArrayList<>();

    HubListScreen(ScreenHost host, List<Row> rows) {
        this.ui = host.ui();
        view = new LinearLayout(host.activity()); view.setOrientation(LinearLayout.VERTICAL);
        LinearLayout card = ui.groupCard(); view.addView(card, new LinearLayout.LayoutParams(-1, -2));
        for (Row row : rows) {
            if (!this.rows.isEmpty()) {
                View line = new View(host.activity()); line.setBackgroundColor(ui.raised);
                card.addView(line, new LinearLayout.LayoutParams(-1, ui.dp(1))); dividers.add(line);
            } else dividers.add(null);
            this.rows.add(row);
            card.addView(line(host, row));
        }
        refresh();
    }

    private LinearLayout line(ScreenHost host, Row row) {
        LinearLayout line = new LinearLayout(host.activity()); line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL); line.setBaselineAligned(false); line.setMinimumHeight(ui.dp(60));
        line.setPadding(0, ui.dp(8), 0, ui.dp(8)); line.setTag(tag(row.id));
        LinearLayout words = new LinearLayout(host.activity()); words.setOrientation(LinearLayout.VERTICAL);
        TextView name = ui.text(row.title, 15, false); name.setMaxLines(2); ui.decorate(name, row.icon);
        words.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView value = ui.caption(""); value.setMaxLines(1); value.setEllipsize(TextUtils.TruncateAt.END);
        value.setPaddingRelative(row.icon == null || row.icon.isEmpty() ? 0 : ui.dp(42), ui.dp(3), 0, 0);
        words.addView(value, new LinearLayout.LayoutParams(-1, -2));
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        View dot = new View(host.activity());
        GradientDrawable disc = new GradientDrawable(); disc.setShape(GradientDrawable.OVAL); disc.setColor(ui.orange); dot.setBackground(disc);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)); dp.setMarginStart(ui.dp(8));
        dot.setVisibility(View.GONE); line.addView(dot, dp);
        View chevron = ui.icon("chevron", 15, ui.muted);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)); cp.setMarginStart(ui.dp(8));
        line.addView(chevron, cp);
        line.setClickable(true); line.setFocusable(true);
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        line.setOnClickListener(v -> { if (row.action != null) row.action.run(); });
        lines.add(line); names.add(name); values.add(value); dots.add(dot);
        return line;
    }

    @Override public View view() { return view; }

    @Override public void refresh() {
        boolean first = true;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            boolean shown = row.visible == null || row.visible.getAsBoolean();
            lines.get(i).setVisibility(shown ? View.VISIBLE : View.GONE);
            View divider = dividers.get(i);
            if (divider != null) divider.setVisibility(shown && !first ? View.VISIBLE : View.GONE);
            if (!shown) continue;
            first = false;
            CharSequence value = row.value == null ? null : row.value.get();
            TextView text = values.get(i);
            if (value == null || value.length() == 0) { text.setText(""); text.setVisibility(View.GONE); }
            else { if (!TextUtils.equals(value.toString(), text.getText())) text.setText(value); text.setVisibility(View.VISIBLE); }
            boolean due = row.dot != null && row.dot.getAsBoolean();
            dots.get(i).setVisibility(due ? View.VISIBLE : View.GONE);
            // TalkBack reads the row's title and value; while a check is due, the title says so (as the tab's dot does).
            names.get(i).setContentDescription(due ? HubText.dueDescription(row.title).render() : null);
        }
    }

    @Override public void close() { }
}
