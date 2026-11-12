package com.jjricks.volt;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The Widgets Home dock (hub layout plan 1.10, PR 11), in the place the tab bar had: a grab handle above the seven
 * subjects' icons. Tapping an icon opens its subject on Home's page stack; tapping the handle, or swiping the dock up,
 * opens a sheet listing every subject with its name and live summary (CleanSubjects). Service's icon carries the orange
 * dot while a maintenance check is due. On a phone the icons have no text: each is read by its subject's name, and a
 * long press shows the name. On a wide window each icon has its short label under it (Ui.nav, whose caption ladder
 * falls back to the icon alone). Hidden while the dashboard is being edited. Every word comes from HubText, so this
 * file holds no catalog references.
 */
final class HubDock {
    /** View tags: the dock, its handle, and each subject's icon ("hub-dock-" and the subject's page id). */
    static final String TAG = "hub-dock", TAG_HANDLE = "hub-dock-handle", TAG_SHEET = "hub-dock-sheet";
    static String tag(String subject) { return "hub-dock-" + subject; }

    /**
     * A phone's dock: the handle's full-width strip over a 52 dp row of icons, and the gap above the screen's edge. The
     * strip is shorter than 48 dp so the dock stays near the tab bar's height; it spans the dock's width, and an upward
     * swipe anywhere on the dock does the same.
     */
    private static final int HANDLE_DP = 28, ROW_DP = 52, BOTTOM_DP = 10;
    /** A wide window: each item is at most this wide, so seven sit centred under the dashboard. */
    private static final int WIDE_ITEM_DP = 96;

    private final ScreenHost host;
    private final Ui ui;
    private final boolean wide;
    private final BooleanSupplier hidden;
    private final Dock view;
    private final List<View> items = new ArrayList<>();
    private View service;
    private AlertDialog sheet;
    private CleanSubjects sheetSubjects;
    private boolean closed;
    private final ViewTreeObserver.OnPreDrawListener follow;

    /** hidden: whether the dock steps aside now (the dashboard is being edited); read before every frame. */
    HubDock(ScreenHost host, boolean wide, BooleanSupplier hidden) {
        this.host = host;
        this.ui = host.ui();
        this.wide = wide;
        this.hidden = hidden;
        view = new Dock();
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setClipChildren(false);
        view.setTag(TAG);
        view.setBackground(ui.shape(ui.surface, 26, ui.raised));
        view.setElevation(ui.dp(6));
        view.addView(handle(), new LinearLayout.LayoutParams(-1, ui.dp(HANDLE_DP)));
        LinearLayout row = new LinearLayout(host.activity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(ui.dp(wide ? 8 : 2), 0, ui.dp(wide ? 8 : 2), ui.dp(wide ? 6 : 4));
        for (String subject : HubPages.SUBJECTS) {
            View item = wide ? wideItem(subject) : phoneItem(subject);
            item.setTag(tag(subject));
            items.add(item);
            if (subject.equals(HubPages.SERVICE)) service = item;
            row.addView(item, new LinearLayout.LayoutParams(wide ? ui.dp(WIDE_ITEM_DP) : 0, -2, wide ? 0 : 1));
        }
        view.addView(row, new LinearLayout.LayoutParams(wide ? -2 : -1, -2));
        // Editing toggles inside the dashboard with no rebuild: follow it before each frame, so the dock never covers the editor.
        follow = () -> { applyHidden(); return true; };
        view.getViewTreeObserver().addOnPreDrawListener(follow);
        applyHidden();
        refresh();
    }

    View view() { return view; }

    /** The layout the dock takes at the bottom of the page frame: full width less 14 dp on a phone, centred when wide. */
    FrameLayout.LayoutParams layoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(wide ? -2 : -1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.leftMargin = params.rightMargin = ui.dp(wide ? 24 : 14);
        params.bottomMargin = ui.dp(BOTTOM_DP);
        return params;
    }

    /** The room the page's scroller leaves under its last card so it can scroll clear of the dock. */
    int clearance() {
        float scale = Math.max(1f, ui.textScale * host.activity().getResources().getConfiguration().fontScale);
        int row = wide ? Math.round(ui.dp(64) * scale) + ui.dp(6) : ui.dp(ROW_DP + 4);
        return ui.dp(HANDLE_DP) + row + ui.dp(BOTTOM_DP) + ui.dp(16);
    }

    /** Service's dot and its spoken "check due" text, and the open sheet's summaries. */
    void refresh() {
        if (closed) return;
        MaintenanceScreen.markTab(ui, service, false, HubSubjects.serviceDue(host.service()), ui.orange);
        if (sheetSubjects != null) sheetSubjects.refresh();
    }

    void close() {
        closed = true;
        view.getViewTreeObserver().removeOnPreDrawListener(follow);
        if (sheetSubjects != null) sheetSubjects.close();
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        sheet = null; sheetSubjects = null;
    }

    private void applyHidden() {
        int visibility = hidden != null && hidden.getAsBoolean() ? View.GONE : View.VISIBLE;
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }

    /** The grab handle: a short bar in a full-width target; it opens the list of every subject. */
    private View handle() {
        FrameLayout target = new FrameLayout(host.activity());
        View bar = new View(host.activity());
        bar.setBackground(ui.shape(Ui.withAlpha(ui.muted, 150), 2, 0));
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        target.addView(bar, new FrameLayout.LayoutParams(ui.dp(36), ui.dp(4), Gravity.CENTER));
        target.setTag(TAG_HANDLE);
        target.setContentDescription(HubText.dockAll());
        target.setTooltipText(HubText.dockAll());
        target.setFocusable(true);
        target.setClickable(true);
        target.setMinimumHeight(ui.dp(HANDLE_DP));
        target.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)), null,
                ui.shape(Color.WHITE, 22, 0)));
        target.setOnClickListener(v -> showAll());
        return target;
    }

    /** A phone's icon: no text; read by the subject's name, which a long press shows. */
    private View phoneItem(String subject) {
        FrameLayout item = new FrameLayout(host.activity());
        item.setMinimumHeight(ui.dp(ROW_DP));
        item.setMinimumWidth(ui.dp(44));
        View icon = ui.icon(HubPages.icon(subject), 23, ui.text);
        item.addView(icon, new FrameLayout.LayoutParams(ui.dp(23), ui.dp(23), Gravity.CENTER));
        Msg name = HubText.title(subject);
        item.setContentDescription(name);
        item.setTooltipText(name);
        item.setFocusable(true);
        item.setClickable(true);
        item.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)), null,
                ui.shape(Color.WHITE, 22, 0)));
        item.setOnClickListener(v -> { Haptics.tick(v); open(subject); });
        ui.pressFeedback(item);
        return item;
    }

    /** A wide window's item: the icon over its short label (Ui.nav's two-line ladder), read by the full name. */
    private View wideItem(String subject) {
        Msg shortName = HubText.shortTitle(subject);
        String name = HubText.title(subject).render();
        View item = ui.nav(name, shortName == null ? name : shortName.render(), HubPages.icon(subject), false, () -> open(subject));
        item.setTooltipText(name);
        return item;
    }

    private void open(String subject) {
        if (closed) return;
        host.open(subject);
    }

    /** The sheet with every subject's name and live summary; a row opens its subject and closes the sheet. */
    private void showAll() {
        if (closed || sheet != null && sheet.isShowing()) return;
        AlertDialog[] shown = new AlertDialog[1];
        Runnable dismiss = () -> { if (shown[0] != null && shown[0].isShowing()) shown[0].dismiss(); };
        LinearLayout content = host.sheetContent(HubText.dockAll(), null, dismiss);
        content.setTag(TAG_SHEET);
        CleanSubjects subjects = new CleanSubjects(host, dismiss);
        content.addView(subjects.view(), new LinearLayout.LayoutParams(-1, -2));
        subjects.refresh();
        sheetSubjects = subjects;
        sheet = shown[0] = host.showSheet(content);
        sheet.setOnDismissListener(d -> {
            subjects.close();
            if (sheetSubjects == subjects) { sheetSubjects = null; sheet = null; }
        });
    }

    /**
     * The dock's own touch handling: an upward swipe that starts anywhere on it opens the list of every subject. Taps and
     * sideways movement go to the icons as usual.
     */
    private final class Dock extends LinearLayout {
        private final int slop = ViewConfiguration.get(host.activity()).getScaledTouchSlop();
        private float downX, downY;
        private boolean swiped;

        Dock() { super(host.activity()); }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX(); downY = event.getY(); swiped = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float up = downY - event.getY(), across = Math.abs(event.getX() - downX);
                    if (!swiped && up > slop * 2 && up > across) { swiped = true; return true; }
                    return false;
                }
                default:
                    return false;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (swiped) {
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) { swiped = false; showAll(); }
                return true;
            }
            // A touch on the dock's own padding: the same upward swipe opens the list.
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = event.getX(); downY = event.getY(); return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && downY - event.getY() > slop * 2
                    && downY - event.getY() > Math.abs(event.getX() - downX)) { downY = Float.NaN; showAll(); }
            return true;
        }
    }
}
