package com.jjricks.volt;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Scooter tab's menu editor (scooter-menu-edit plan, PR 2). It replaces the live controls while open, so no
 * scooter command can be sent from it: it only reads and writes the scooter's {@link ScooterMenuLayout}.
 *
 * <ul>
 * <li>Sections are cards in an outer {@link ReorderList}; each holds its rows in an inner one. Rows move only
 *     within their own section (owner, Q35 A). Pressing a section's grip folds every section to its header
 *     first, so the whole menu fits while it moves.</li>
 * <li>The eye button hides a section or row (nothing is locked from hiding, Q33 A), with an Undo notice. Add back
 *     lists what is hidden; Reset puts the original menu back, with an Undo.</li>
 * <li>It lists only what this model offers; hidden and unoffered ids keep their places in the layout.</li>
 * </ul>
 * Every step is saved at once ({@link Host#changed}), so Done, Back and leaving the tab need no save of their own.
 */
final class ScooterMenuEditor {
    /** What the editor needs from the Scooter page. */
    interface Host {
        ScooterMenuLayout menu();
        /** The layout changed: save it and lay the live page out again. */
        void changed();
        /** Puts back a layout saved with {@link ScooterMenuLayout#toJson} (Undo after Reset), saved and laid out. */
        void restore(String json);
        /** The row shows on the main page for this model, hiding aside. */
        boolean offered(String row);
        String sectionTitle(String section);
        String sectionIcon(String section);
        String rowTitle(String row);
        String rowIcon(String row);
        /** A second line under the row's name in the editor, or null. */
        String rowDetail(String row);
        /** The setting's current value for the Add back sheet, or null. */
        String rowValue(String row);
    }

    /** View tags the emulator check finds (android-tests/ScooterMenuEditValidation). */
    static final String TAG_EDIT = "menu_edit", TAG_EDITOR = "menu_editor", TAG_ADD_BACK = "menu_add_back", TAG_RESET = "menu_reset",
            TAG_HIDE = "menu_hide", TAG_HEADER = "menu_section_header", TAG_SHOW = "menu_show", TAG_NOTICE = "menu_notice_action";
    /** The live page's edges (PR 3): the "N settings hidden · Edit" footer and the "Hidden" caption on a hidden setting search found. */
    static final String TAG_FOOTER = "menu_footer", TAG_HIDDEN_CAPTION = "menu_hidden_caption";
    private static final int MAX_WIDTH_DP = 560;

    private final ScreenHost screens;
    private final Ui ui;
    private final Host host;
    final LinearLayout view;
    private final ToolButton addBack, reset;
    private final TextView hint;
    private final LinearLayout list;
    private final HashMap<String, View> bodies = new HashMap<>();
    private final HashMap<String, TextView> counts = new HashMap<>();
    private final HashMap<String, ReorderList> rowLists = new HashMap<>();
    private ReorderList sections;
    private PopupWindow notice;
    private AlertDialog sheet;
    private boolean folded, closed;

    ScooterMenuEditor(ScreenHost screens, Host host) {
        this.screens = screens; this.ui = screens.ui(); this.host = host;
        view = new LinearLayout(screens.activity()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                // Wide screens: one centred column, never two panes to drag across.
                int limit = ui.dp(MAX_WIDTH_DP);
                if (MeasureSpec.getSize(widthSpec) > limit) widthSpec = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.EXACTLY);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        view.setOrientation(LinearLayout.VERTICAL);
        view.setTag(TAG_EDITOR);
        LinearLayout tools = new LinearLayout(screens.activity());
        addBack = new ToolButton("plus", this::showAddBack);
        addBack.setTag(TAG_ADD_BACK);
        addBack.rungs(T.menuAddBack(), T.menuAddBackShort());
        LinearLayout.LayoutParams addSpace = new LinearLayout.LayoutParams(0, -2, 1);
        addSpace.setMarginEnd(ui.dp(8));
        tools.addView(addBack, addSpace);
        reset = new ToolButton("restore", this::reset);
        reset.setTag(TAG_RESET);
        reset.rungs(T.menuReset());
        reset.setContentDescription(T.menuResetDescription());
        tools.addView(reset, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams toolsSpace = new LinearLayout.LayoutParams(-1, -2);
        toolsSpace.topMargin = ui.dp(4);
        view.addView(tools, toolsSpace);
        hint = ui.caption(T.menuHint());
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13 * ui.textScale);
        hint.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12));
        view.addView(hint);
        list = new LinearLayout(screens.activity());
        list.setOrientation(LinearLayout.VERTICAL);
        view.addView(list, new LinearLayout.LayoutParams(-1, -2));
        render();
    }

    // ---- Building ----

    /** Builds the cards again from the layout (after Undo, Reset, Add back, or a model or scooter change). */
    void render() {
        if (closed) return;
        hideNotice();
        folded = false;
        list.removeAllViews(); bodies.clear(); counts.clear(); rowLists.clear();
        ScooterMenuLayout menu = host.menu();
        sections = new ReorderList(ui, ids -> { if (menu.placeSections(ids)) host.changed(); });
        sections.setGrab(new ReorderList.Grab() {
            @Override public void grabbing(String id, Runnable ready) { fold(true, ready); }
            @Override public void released(String id) { fold(false, null); }
        });
        for (String section : menu.sections()) {
            if (menu.sectionHidden(section) || offeredRows(section).isEmpty()) continue;
            addSection(section);
        }
        list.addView(sections, new LinearLayout.LayoutParams(-1, -2));
        updateTools();
    }

    /** The section's rows this model offers, in menu order, hidden ones included. */
    private List<String> offeredRows(String section) {
        List<String> out = new ArrayList<>();
        for (String row : host.menu().rows(section)) if (host.offered(row)) out.add(row);
        return out;
    }

    private List<String> shownRows(String section) {
        List<String> out = new ArrayList<>();
        for (String row : offeredRows(section)) if (!host.menu().rowHidden(row)) out.add(row);
        return out;
    }

    private void addSection(String section) {
        ScooterMenuLayout menu = host.menu();
        LinearLayout card = new LinearLayout(screens.activity());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.shape(ui.surface, 20, ui.outline));
        // Rows stay inside the outline and its rounded corners.
        int stroke = Math.max(1, ui.dp(1));
        card.setPadding(stroke, stroke, stroke, stroke);
        card.setClipToOutline(true);
        card.setTag(section);
        LinearLayout header = new LinearLayout(screens.activity());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(ui.dp(60));
        header.setPadding(ui.dp(16), ui.dp(4), ui.dp(4), ui.dp(4));
        header.setTag(TAG_HEADER);
        String title = host.sectionTitle(section);
        TextView label = ui.sectionLabel(title);
        label.setPadding(0, 0, 0, 0);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        int shown = shownRows(section).size();
        TextView count = ui.caption(T.menuSettingsCount(shown, shown));
        count.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13 * ui.textScale);
        count.setVisibility(View.GONE);
        count.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams countSpace = new LinearLayout.LayoutParams(-2, -2);
        countSpace.setMarginStart(ui.dp(8));
        header.addView(count, countSpace);
        header.addView(eye(title, () -> hide(section, title, true)), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        View grip = sections.handle(title, 20, ui.text);
        header.addView(grip, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout body = new LinearLayout(screens.activity());
        body.setOrientation(LinearLayout.VERTICAL);
        ReorderList rows = new ReorderList(ui, ids -> { if (menu.placeRows(section, ids)) host.changed(); });
        for (String row : shownRows(section)) addRow(rows, section, row);
        body.addView(rows, new LinearLayout.LayoutParams(-1, -2));
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));
        bodies.put(section, body); counts.put(section, count); rowLists.put(section, rows);
        List<ReorderList.Action> actions = Collections.singletonList(new ReorderList.Action(R.id.menu_hide, T.menuHide(title), () -> hide(section, title, true)));
        sections.add(section, card, grip, header, T.menuSectionSaid(shown, shown, title), actions, ui.dp(12));
    }

    private void addRow(ReorderList rows, String section, String row) {
        LinearLayout item = new LinearLayout(screens.activity());
        item.setOrientation(LinearLayout.VERTICAL);
        item.setBackground(ui.shape(ui.surface, 16, 0));
        item.setTag(row);
        View line = new View(screens.activity());
        line.setBackgroundColor(ui.raised);
        item.addView(line, new LinearLayout.LayoutParams(-1, ui.dp(1)));
        LinearLayout content = new LinearLayout(screens.activity());
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setMinimumHeight(ui.dp(60));
        content.setPadding(ui.dp(12), ui.dp(6), ui.dp(4), ui.dp(6));
        content.addView(square(host.rowIcon(row)), new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        LinearLayout words = new LinearLayout(screens.activity());
        words.setOrientation(LinearLayout.VERTICAL);
        String title = host.rowTitle(row), detail = host.rowDetail(row);
        words.addView(ui.text(title, 15, false));
        if (detail != null) {
            TextView sub = ui.caption(detail);
            sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13 * ui.textScale);
            sub.setPadding(0, ui.dp(4), 0, 0);
            words.addView(sub);
        }
        words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        LinearLayout.LayoutParams wordsSpace = new LinearLayout.LayoutParams(0, -2, 1);
        wordsSpace.setMarginStart(ui.dp(12));
        content.addView(words, wordsSpace);
        content.addView(eye(title, () -> hide(row, title, false)), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        View grip = rows.handle(title, 20, ui.muted);
        content.addView(grip, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        item.addView(content, new LinearLayout.LayoutParams(-1, -2));
        String said = detail == null ? title : T.menuSheetValue(title, detail);
        List<ReorderList.Action> actions = Collections.singletonList(new ReorderList.Action(R.id.menu_hide, T.menuHide(title), () -> hide(row, title, false)));
        rows.add(row, item, grip, content, said, actions, 0);
    }

    /** The eye button: a 34 dp disc in a 48 dp target. */
    private View eye(String title, Runnable hide) {
        FrameLayout target = new FrameLayout(screens.activity());
        FrameLayout disc = new FrameLayout(screens.activity());
        disc.setBackground(ui.shape(ui.dark ? ui.raised : ui.bg, 100, 0));
        disc.addView(ui.icon("eye_off", 18, ui.text), new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
        target.addView(disc, new FrameLayout.LayoutParams(ui.dp(34), ui.dp(34), Gravity.CENTER));
        target.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 40)), null, ui.shape(Color.WHITE, 100, 0)));
        target.setContentDescription(T.menuHide(title));
        if (Build.VERSION.SDK_INT >= 26) target.setTooltipText(T.menuHide(title));
        target.setClickable(true); target.setFocusable(true);
        target.setTag(TAG_HIDE);
        target.setOnClickListener(v -> hide.run());
        return target;
    }

    private FrameLayout square(String icon) {
        FrameLayout box = new FrameLayout(screens.activity());
        box.setBackground(ui.shape(ui.dark ? ui.bg : ui.raised, 10, 0));
        box.addView(ui.icon(icon, 18, ui.text), new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return box;
    }

    private void updateTools() {
        int hidden = host.menu().hiddenCount(offered());
        addBack.badge(hidden);
        addBack.setContentDescription(T.menuAddBackDescription(hidden, hidden));
        addBack.setAlpha(hidden == 0 ? .55f : 1f);
        reset.setAlpha(host.menu().isDefault() ? .55f : 1f);
    }

    private Set<String> offered() {
        Set<String> out = new HashSet<>();
        for (String section : host.menu().sections()) out.addAll(offeredRows(section));
        return out;
    }

    // ---- Folding ----

    /** Every section down to its header (while one moves), or open again; {@code ready} runs once it has settled. */
    private void fold(boolean fold, Runnable ready) {
        if (closed) return;
        if (folded != fold) {
            folded = fold;
            Motion.animateChanges(view);
            for (View body : bodies.values()) body.setVisibility(fold ? View.GONE : View.VISIBLE);
            for (TextView count : counts.values()) count.setVisibility(fold ? View.VISIBLE : View.GONE);
            hint.setText(fold ? T.menuHintFolded() : T.menuHint());
        }
        if (ready == null) return;
        // Lift once the fold has laid out (and finished gliding, with animations on).
        if (Motion.enabled()) view.postDelayed(ready, Motion.CHANGE_MS + 32);
        else Motion.beforeNextDraw(view, ready, null);
    }

    // ---- Hiding, Add back and Reset ----

    private void hide(String id, String title, boolean section) {
        if (closed || !host.menu().hide(id)) return;
        Motion.animateChanges(view);
        if (section) sections.remove(id);
        else {
            String owner = host.menu().sectionOf(id);
            ReorderList rows = rowLists.get(owner);
            if (rows != null) rows.remove(id);
            int shown = shownRows(owner).size();
            TextView count = counts.get(owner);
            if (count != null) count.setText(T.menuSettingsCount(shown, shown));
        }
        Haptics.tick(view);
        host.changed();
        updateTools();
        showNotice(T.menuHidden(title), () -> { if (host.menu().show(id)) { host.changed(); render(); } });
    }

    private void reset() {
        if (closed || host.menu().isDefault()) return;
        String before = host.menu().toJson();
        host.menu().reset();
        host.changed();
        render();
        showNotice(T.menuResetDone(), () -> { host.restore(before); render(); });
    }

    private void showAddBack() {
        if (closed) return;
        if (sheet != null) sheet.dismiss();
        LinearLayout content = screens.sheetContent(T.menuSheetTitle(), T.menuSheetExplanation(), () -> { if (sheet != null) sheet.dismiss(); });
        fillAddBack(content, content.getChildCount());
        sheet = screens.showSheet(content);
        AlertDialog shown = sheet;
        sheet.setOnDismissListener(d -> { if (sheet == shown) sheet = null; });
    }

    /** The sheet's list after its first {@code keep} views (grab handle, title, explanation): hidden sections, then hidden rows by section. */
    private void fillAddBack(LinearLayout content, int keep) {
        while (content.getChildCount() > keep) content.removeViewAt(keep);
        ScooterMenuLayout menu = host.menu();
        List<String> hiddenSections = new ArrayList<>();
        for (String section : menu.sections()) if (menu.sectionHidden(section) && !offeredRows(section).isEmpty()) hiddenSections.add(section);
        boolean any = false;
        if (!hiddenSections.isEmpty()) {
            content.addView(ui.sectionLabel(T.menuSheetSections()));
            LinearLayout group = ui.groupCard();
            for (String section : hiddenSections) {
                int count = offeredRows(section).size();
                String title = host.sectionTitle(section);
                sheetRow(group, host.sectionIcon(section), title, T.menuSettingsCount(count, count), () -> menu.show(section), content, keep);
            }
            content.addView(group);
            any = true;
        }
        for (String section : menu.sections()) {
            if (menu.sectionHidden(section)) continue;
            List<String> rows = new ArrayList<>();
            for (String row : offeredRows(section)) if (menu.rowHidden(row)) rows.add(row);
            if (rows.isEmpty()) continue;
            content.addView(ui.sectionLabel(host.sectionTitle(section)));
            LinearLayout group = ui.groupCard();
            for (String row : rows) sheetRow(group, host.rowIcon(row), host.rowTitle(row), host.rowValue(row), () -> menu.show(row), content, keep);
            content.addView(group);
            any = true;
        }
        if (!any) {
            TextView empty = ui.caption(T.menuSheetEmpty());
            empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14 * ui.textScale);
            empty.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(16));
            content.addView(empty);
        }
    }

    private void sheetRow(LinearLayout group, String icon, String title, String value, Runnable show, LinearLayout content, int keep) {
        if (group.getChildCount() > 0) ui.divider(group);
        LinearLayout row = new LinearLayout(screens.activity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(64));
        row.setPadding(0, ui.dp(8), 0, ui.dp(8));
        row.addView(square(icon), new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        LinearLayout words = new LinearLayout(screens.activity());
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(ui.text(title, 15, false));
        if (value != null && !value.isEmpty()) {
            TextView sub = ui.caption(value);
            sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13 * ui.textScale);
            sub.setPadding(0, ui.dp(4), 0, 0);
            words.addView(sub);
        }
        words.setFocusable(true);
        words.setContentDescription(value == null || value.isEmpty() ? title : T.menuSheetValue(title, value));
        LinearLayout.LayoutParams wordsSpace = new LinearLayout.LayoutParams(0, -2, 1);
        wordsSpace.setMarginStart(ui.dp(12)); wordsSpace.setMarginEnd(ui.dp(8));
        row.addView(words, wordsSpace);
        FrameLayout plus = new FrameLayout(screens.activity());
        FrameLayout disc = new FrameLayout(screens.activity());
        disc.setBackground(ui.shape(ui.accent, 100, 0));
        disc.addView(ui.icon("plus", 18, ui.onAccent), new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
        plus.addView(disc, new FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER));
        plus.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(Color.WHITE, 45)), null, ui.shape(Color.WHITE, 100, 0)));
        plus.setContentDescription(T.menuShow(title));
        plus.setClickable(true); plus.setFocusable(true);
        plus.setTag(TAG_SHOW);
        plus.setOnClickListener(v -> {
            show.run();
            Haptics.confirm(v);
            host.changed();
            render();
            // A row comes back at its saved place; the sheet lists what is still hidden, or closes when nothing is.
            if (host.menu().hiddenCount(offered()) == 0) { if (sheet != null) sheet.dismiss(); }
            else { Motion.animateChanges(content); fillAddBack(content, keep); }
        });
        row.addView(plus, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    // ---- Notice ----

    /** A short message at the bottom of the screen with an Undo, like Home's editor notices. */
    private void showNotice(String message, Runnable undo) {
        hideNotice();
        if (closed || !view.isAttachedToWindow() || view.getWindowToken() == null) return;
        LinearLayout pill = new LinearLayout(screens.activity());
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setMinimumHeight(ui.dp(48));
        pill.setPadding(ui.dp(16), 0, ui.dp(4), 0);
        pill.setBackground(ui.shape(ui.dark ? ui.raised : ui.text, 14, 0));
        pill.setElevation(ui.dp(6));
        TextView text = ui.text(message, 14, false);
        text.setTextColor(ui.dark ? ui.text : ui.bg);
        pill.addView(text, new LinearLayout.LayoutParams(-2, -2));
        TextView button = ui.text(T.menuUndo(), 14, true);
        button.setTextColor(ui.dark ? ui.accent : Ui.withAlpha(Color.WHITE, 235));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(ui.dp(48)); button.setMinWidth(ui.dp(64));
        button.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 50)), null, ui.shape(Color.WHITE, 12, 0)));
        button.setClickable(true); button.setFocusable(true);
        button.setTag(TAG_NOTICE);
        button.setOnClickListener(v -> { hideNotice(); if (!closed) undo.run(); });
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
        space.setMarginStart(ui.dp(8));
        pill.addView(button, space);
        FrameLayout holder = new FrameLayout(screens.activity());
        holder.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        holder.addView(pill, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        PopupWindow window = new PopupWindow(holder, -2, -2, false);
        window.setTouchable(true);
        window.setOutsideTouchable(false);
        window.setAnimationStyle(0);
        WindowInsets insets = view.getRootWindowInsets();
        int inset = insets == null ? 0 : insets.getSystemWindowInsetBottom();
        // Above the phone's floating tab bar, so the Undo never sits under it.
        int above = ui.wideLayout() ? ui.dp(12) : ui.floatingNavHeight() + ui.dp(16);
        try {
            window.showAtLocation(view.getRootView(), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, above + inset);
        } catch (RuntimeException unavailable) {
            return;
        }
        notice = window;
        view.announceForAccessibility(message);
        if (Motion.enabled()) {
            pill.setAlpha(0); pill.setTranslationY(ui.dp(24));
            pill.animate().alpha(1).translationY(0).setDuration(Motion.CHANGE_MS).setInterpolator(Motion.STANDARD).start();
        }
        holder.postDelayed(() -> { if (notice == window) hideNotice(); }, 5000);
    }

    private void hideNotice() {
        PopupWindow window = notice;
        notice = null;
        if (window != null) try { window.dismiss(); } catch (RuntimeException gone) { /* the window already left */ }
    }

    void close() {
        closed = true;
        hideNotice();
        if (sheet != null) { sheet.dismiss(); sheet = null; }
    }

    /**
     * A half-width tool button (Add back, Reset): glyph, label and an optional count badge, centred. The label steps
     * down its rungs to fit, then leaves the glyph (and badge) alone; it never shrinks or breaks a word.
     */
    private final class ToolButton extends LinearLayout {
        private final TextView label, badge;
        private String[] rungs = new String[0];
        ToolButton(String glyph, Runnable action) {
            super(screens.activity());
            setGravity(Gravity.CENTER);
            setMinimumHeight(ui.dp(52));
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
            setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)), ui.shape(ui.surface, 16, ui.outline), null));
            setClickable(true); setFocusable(true);
            addView(ui.icon(glyph, 18, ui.text), new LayoutParams(ui.dp(18), ui.dp(18)));
            label = ui.text("", 14, false);
            label.setTypeface(ui.semibold());
            label.setMaxLines(1);
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams labelSpace = new LayoutParams(-2, -2);
            labelSpace.setMarginStart(ui.dp(10));
            addView(label, labelSpace);
            badge = ui.text("", 12, true);
            badge.setTextColor(ui.onAccent);
            badge.setGravity(Gravity.CENTER);
            badge.setMinWidth(ui.dp(24)); badge.setMinHeight(ui.dp(24));
            badge.setPadding(ui.dp(6), 0, ui.dp(6), 0);
            badge.setBackground(ui.shape(ui.accent, 100, 0));
            badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            badge.setVisibility(GONE);
            LayoutParams badgeSpace = new LayoutParams(-2, -2);
            badgeSpace.setMarginStart(ui.dp(8));
            addView(badge, badgeSpace);
            setOnClickListener(v -> action.run());
        }
        void rungs(String... rungs) {
            this.rungs = rungs;
            if (getContentDescription() == null) setContentDescription(rungs[0]);
            requestLayout();
        }
        void badge(int count) {
            badge.setText(Fmt.integer(count));
            badge.setVisibility(count > 0 ? VISIBLE : GONE);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && rungs.length > 0) {
                badge.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
                int room = width - getPaddingLeft() - getPaddingRight() - ui.dp(18) - ui.dp(10)
                        - (badge.getVisibility() == VISIBLE ? badge.getMeasuredWidth() + ui.dp(8) : 0);
                String pick = null;
                for (String rung : rungs) if (label.getPaint().measureText(rung) <= room) { pick = rung; break; }
                if (pick == null) label.setVisibility(GONE);
                else {
                    label.setVisibility(VISIBLE);
                    if (!pick.contentEquals(label.getText())) label.setText(pick);
                }
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }
}
