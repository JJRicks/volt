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
 * The wide Clean Home's Service card (hub layout plan 1.11, PR 13), at the foot of the scooter pane, as the Layout Plan
 * draws it: a quiet section label, Service's own name with its small wrench, over the next maintenance checks. A check
 * that is due has the orange dot and its status in orange ("Tyre pressure · 40 mi left"); the nearest check that is not
 * due follows with a grey dot and a muted status ("Brakes · 18 days left"). The orange means due only, as on the Service
 * row and the dock (the owner's answer on #394; the contributor's decision service-dot-due-only). The words for what is
 * left are the Maintenance page's own, rendered when a line is built (MaintenanceScreen.Check); a line puts its status
 * under the name when the two do not fit side by side (HubServiceFit), so a word is never broken. With reminders off the
 * card says so with the page's title and the Service row's own "Off", as that row beside it does. Its title, the empty line and the
 * spoken due line come from HubText. A tap opens Service › Maintenance reminders. Rebuilt only when what it shows
 * changed, never on a plain telemetry refresh.
 */
final class HubServiceCard {
    /** View tags: the card, and each line by its reminder id ("home-service-check-<id>"). */
    static final String TAG = "home-service-card", TAG_CHECK = "home-service-check-";
    /** At most this many due checks; the next other one follows them. */
    private static final int MAX_DUE = 3;

    private final ScreenHost host;
    private final Ui ui;
    private final LinearLayout view, lines;
    private String shown;
    private boolean closed;

    HubServiceCard(ScreenHost host) {
        this.host = host;
        ui = host.ui();
        view = ui.card();
        view.setTag(TAG);
        view.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        // The mock-up's label: a small wrench in the accent's text colour, then SERVICE in the section labels' style.
        LinearLayout head = row();
        head.addView(ui.icon(HubPages.icon(HubPages.SERVICE), 16, ui.accentText), new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        TextView title = ui.sectionLabel(HubText.title(HubPages.SERVICE).render());
        title.setPadding(ui.dp(8), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        view.addView(head, new LinearLayout.LayoutParams(-1, -2));
        lines = new LinearLayout(host.activity());
        lines.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams linesSize = new LinearLayout.LayoutParams(-1, -2);
        linesSize.topMargin = ui.dp(4);
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
        ScooterService service = host.service();
        List<MaintenanceScreen.Check> checks = MaintenanceScreen.nextChecks(service, MAX_DUE);
        boolean off = checks.isEmpty() && MaintenanceScreen.remindersOff(service);
        StringBuilder key = new StringBuilder(off ? "off\n" : "");
        for (MaintenanceScreen.Check check : checks) check.key(key);
        String next = key.toString();
        if (next.equals(shown)) return;
        shown = next;
        lines.removeAllViews();
        if (off) {
            // The page's own title and the Service row's own value ("Off"): no words of the card's own.
            lines.addView(line(null, HubText.title(HubPages.SERVICE_MAINTENANCE).render(),
                    MaintenanceScreen.rowValue(MaintenanceScreen.summary(service)), false, null));
        } else if (checks.isEmpty()) {
            TextView none = ui.caption(HubText.serviceCardNone().render());
            none.setPadding(0, ui.dp(4), 0, ui.dp(2));
            lines.addView(none);
        } else {
            for (MaintenanceScreen.Check check : checks) {
                String name = check.name();
                // The dot is not read: a due check says so with its name; another says what is left.
                lines.addView(line(TAG_CHECK + check.id(), name, check.left(), check.highlighted,
                        check.due() ? HubText.dueDescription(name).render() : null));
            }
        }
    }

    void close() { closed = true; }

    /**
     * One line: a dot (orange while due, grey otherwise), the name in the text colour, and the status at the end, in
     * orange while due; the status goes under the name when the two do not fit side by side (HubServiceFit).
     */
    private View line(String tag, String nameText, String statusText, boolean due, CharSequence spoken) {
        LinearLayout line = row();
        line.setTag(tag);
        line.setGravity(Gravity.TOP);
        line.setPadding(0, ui.dp(5), 0, ui.dp(5));
        View dot = new View(host.activity());
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(due ? ui.orange : Ui.withAlpha(ui.muted, 80));
        dot.setBackground(disc);
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        TextView name = ui.text(nameText, HubServiceFit.NAME_SP, false);
        name.setTypeface(ui.medium());
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        if (spoken != null) name.setContentDescription(spoken);
        TextView status = ui.text(statusText, HubServiceFit.STATUS_SP, false);
        status.setTextColor(due ? ui.orange : ui.muted);
        if (due) status.setTypeface(ui.medium());
        status.setMaxLines(2);
        // The dot sits level with the name's first line.
        LinearLayout.LayoutParams dotSize = new LinearLayout.LayoutParams(ui.dp(HubServiceFit.DOT_DP), ui.dp(HubServiceFit.DOT_DP));
        dotSize.topMargin = Math.max(0, (name.getLineHeight() - ui.dp(HubServiceFit.DOT_DP)) / 2);
        dotSize.setMarginEnd(ui.dp(HubServiceFit.DOT_GAP_DP));
        line.addView(dot, dotSize);
        LinearLayout body = new LinearLayout(host.activity()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                float room = MeasureSpec.getSize(widthSpec);
                boolean beside = HubServiceFit.beside(room, ui.dp(HubServiceFit.GAP_DP),
                        status.getPaint().measureText(statusText),
                        HubServiceFit.longestWord(nameText, name.getPaint()::measureText));
                place(this, name, status, beside);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        body.setBaselineAligned(false);
        body.addView(name);
        body.addView(status);
        place(body, name, status, true);
        line.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        return line;
    }

    /** The name and the status side by side (the status at the end) or the status under the name; set only on change. */
    private void place(LinearLayout body, TextView name, TextView status, boolean beside) {
        // The body's tag remembers the placement set last (none before the first call).
        if (Boolean.valueOf(beside).equals(body.getTag())) return;
        body.setTag(beside);
        body.setOrientation(beside ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nameSize = beside ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        LinearLayout.LayoutParams statusSize = new LinearLayout.LayoutParams(beside ? -2 : -1, -2);
        if (beside) statusSize.setMarginStart(ui.dp(HubServiceFit.GAP_DP));
        else statusSize.topMargin = ui.dp(1);
        status.setGravity(beside ? Gravity.END : Gravity.START);
        name.setLayoutParams(nameSize);
        status.setLayoutParams(statusSize);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(host.activity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);
        return row;
    }
}
