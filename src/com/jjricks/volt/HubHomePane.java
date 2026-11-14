package com.jjricks.volt;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * The wide Clean Home's page pane while Home itself is shown (hub layout plan 1.11, PR 13): a search field, which opens
 * the search page in this pane with its own field focused, above the seven subjects with their summaries
 * (CleanSubjects). Words from HubText; no catalog references.
 */
final class HubHomePane implements AppScreen {
    private final LinearLayout view;
    private final CleanSubjects subjects;
    private boolean closed;

    HubHomePane(ScreenHost host, Runnable openSearch) {
        Ui ui = host.ui();
        view = new LinearLayout(host.activity());
        view.setOrientation(LinearLayout.VERTICAL);
        view.addView(searchField(host, ui, () -> { if (!closed) openSearch.run(); }), ui.searchLayout());
        subjects = new CleanSubjects(host);
        view.addView(subjects.view(), new LinearLayout.LayoutParams(-1, -2));
        refresh();
    }

    /**
     * Looks like the search page's field and opens it (a tap, or Enter once a keyboard has moved focus to it): read as a
     * button named like Home's search button, and it carries
     * that button's tag (CleanHomeScreen.TAG_SEARCH), which the scooter pane's header leaves out on this layout.
     */
    private static View searchField(ScreenHost host, Ui ui, Runnable open) {
        EditText field = new EditText(host.activity());
        field.setSingleLine(true);
        // The search page's own field style and placeholder rule, so tapping it opens a field of the same size in the
        // same place, its placeholder never cut (the short "Search" where the long one doesn't fit the pane).
        HubSearchScreen.styleField(ui, field);
        field.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> HubSearchScreen.fitHint(field));
        field.setKeyListener(null);
        field.setCursorVisible(false);
        // Reached by Tab or a D-pad like the buttons beside it (Enter opens the search); a tap never focuses it, so it
        // never raises the keyboard here (#394 early review, finding 1).
        field.setFocusable(true);
        field.setFocusableInTouchMode(false);
        field.setLongClickable(false);
        field.setClickable(true);
        field.setTag(CleanHomeScreen.TAG_SEARCH);
        field.setContentDescription(HubText.searchDescription().render());
        field.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), field.getBackground(), ui.shape(Color.WHITE, 14, 0)));
        field.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.setEditable(false);
            }
        });
        field.setOnClickListener(v -> open.run());
        return field;
    }

    /** The last rung of the panes' ladder: a subject's name has a word too wide for this pane (CleanSubjects.wordsBreak). */
    boolean wordsBreak() { return !closed && subjects.wordsBreak(); }

    @Override public View view() { return view; }

    @Override public void refresh() { if (!closed) subjects.refresh(); }

    @Override public void close() {
        closed = true;
        subjects.close();
    }
}
