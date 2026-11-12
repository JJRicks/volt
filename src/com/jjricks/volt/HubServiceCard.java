package com.jjricks.volt;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The wide Clean Home's Service card (hub layout plan 1.11, PR 13), at the foot of the scooter pane, filling the room
 * the pane has left: the next maintenance checks. A due check comes first, in orange with a dot ("Tyre pressure · 40 mi
 * overdue"); then the nearest one coming up, muted ("Brakes · 18 days left"). The words for what is left are the
 * Maintenance page's own (MaintenanceScreen.nextChecks); the title and the empty line come from HubText. A tap opens
 * Service › Maintenance reminders. Rebuilt only when what it shows changed, never on a plain telemetry refresh.
 */
final class HubServiceCard {
    /** View tags: the card, and each line by its reminder id ("home-service-check-<id>"). */
    static final String TAG = "home-service-card", TAG_CHECK = "home-service-check-";
    /** At most this many due checks; the next one coming up follows them. */
    private static final int MAX_DUE = 3;

    private final ScreenHost host;
    private final Ui ui;
    private final LinearLayout view, lines;
    private String shown;
    private boolean closed;

    HubServiceCard(ScreenHost host) {
        this.host = host;
        ui = host.ui();
        view = ui.groupCard();
        view.setTag(TAG);
        view.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        LinearLayout head = row();
        head.setMinimumHeight(ui.dp(32));
        TextView title = ui.text(HubText.serviceCardTitle(), 15, true);
        ui.decorate(title, HubPages.icon(HubPages.SERVICE));
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams chevronSize = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
        chevronSize.setMarginStart(ui.dp(8));
        head.addView(ui.icon("chevron", 15, ui.muted), chevronSize);
        view.addView(head, new LinearLayout.LayoutParams(-1, -2));
        lines = new LinearLayout(host.activity());
        lines.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams linesSize = new LinearLayout.LayoutParams(-1, -2);
        linesSize.topMargin = ui.dp(6);
        view.addView(lines, linesSize);
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), view.getBackground(), null));
        view.setOnClickListener(v -> { if (!closed) host.open(HubPages.SERVICE_MAINTENANCE); });
        refresh();
    }

    View view() { return view; }

    void refresh() {
        if (closed) return;
        List<MaintenanceScreen.Check> checks = MaintenanceScreen.nextChecks(host.service(), MAX_DUE);
        StringBuilder key = new StringBuilder();
        for (MaintenanceScreen.Check check : checks) key.append(check.id).append('\n').append(check.left).append('\n').append(check.due).append('\n');
        String next = key.toString();
        if (next.equals(shown)) return;
        shown = next;
        lines.removeAllViews();
        if (checks.isEmpty()) {
            TextView none = ui.caption(HubText.serviceCardNone().render());
            none.setPadding(0, ui.dp(4), 0, ui.dp(2));
            lines.addView(none);
            return;
        }
        for (MaintenanceScreen.Check check : checks) lines.addView(line(check));
    }

    void close() { closed = true; }

    /** One check: the orange dot while it is due, its name, and what is left at the end. */
    private View line(MaintenanceScreen.Check check) {
        LinearLayout line = row();
        line.setTag(TAG_CHECK + check.id);
        line.setMinimumHeight(ui.dp(36));
        line.setPadding(0, ui.dp(4), 0, ui.dp(4));
        View dot = new View(host.activity());
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(ui.orange);
        dot.setBackground(disc);
        dot.setVisibility(check.due ? View.VISIBLE : View.INVISIBLE);
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.addView(dot, new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)));
        TextView name = ui.text(check.name, 14, false);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(check.due ? ui.text : ui.muted);
        // The dot is not read: a due check says so with its name.
        if (check.due) name.setContentDescription(HubText.dueDescription(check.name).render());
        LinearLayout.LayoutParams nameSize = new LinearLayout.LayoutParams(0, -2, 1);
        nameSize.setMarginStart(ui.dp(10));
        line.addView(name, nameSize);
        TextView left = ui.text(check.left, 12.5f, check.due);
        left.setTextColor(check.due ? ui.orange : ui.muted);
        left.setMaxLines(2);
        left.setGravity(Gravity.END);
        LinearLayout.LayoutParams leftSize = new LinearLayout.LayoutParams(-2, -2);
        leftSize.setMarginStart(ui.dp(10));
        line.addView(left, leftSize);
        return line;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(host.activity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);
        return row;
    }
}
