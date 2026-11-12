package com.jjricks.volt;

import android.app.AlertDialog;
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
 * The Widgets Home dock (hub layout plan 1.10, PR 11), in the place the tab bar had and with its look (owner's answer 8
 * on #383: not cramped on a portrait phone, not drab or out of place): the same rounded floating bar, its surface,
 * outline, elevation and margins, holding the subjects' icons each with its short name under it (Ui.nav, the rail's
 * item, whose caption ladder falls back to the icon alone) and an "All" item. Where all seven don't fit at a comfortable
 * width (HubPages.dockSubjects: a 344 dp phone shows four), it shows the most-used ones; "All" opens a sheet listing
 * every subject with its name and live summary (CleanSubjects), so each stays one tap away, and so does an upward swipe
 * on the dock. Tapping an icon opens its subject on Home's page stack. Service's icon carries the orange dot while a
 * maintenance check is due, or "All" does while Service isn't shown. Each item is read by its subject's full name (All
 * by HubText.dockAll()), and a long press shows it. On a wide window (PR 13) the dock is centred and holds all seven,
 * with the same short names, at most 96 dp an item; the page above it takes the window's full width. Hidden
 * while the dashboard is being edited. Every word comes from HubText, so this file holds no catalog references.
 */
final class HubDock {
    /**
     * View tags: the dock, its "All" item (which took the old grab handle's place and job, so it keeps the handle's tag),
     * the sheet, and each shown subject's icon ("hub-dock-" and the subject's page id).
     */
    static final String TAG = "hub-dock", TAG_HANDLE = "hub-dock-handle", TAG_SHEET = "hub-dock-sheet";
    static String tag(String subject) { return "hub-dock-" + subject; }

    /**
     * The old floating tab bar's place (Ui.floatingNav as MainActivity added it): 14 dp from each side and 10 dp above the
     * screen's edge, and its 26 dp corners. Items are Ui.nav's: at least 64 dp tall, growing with the text.
     */
    private static final int SIDE_DP = HubPages.DOCK_SIDE_DP, BOTTOM_DP = 10, CORNER_DP = 26, ITEM_DP = 64,
            PADDING_DP = HubPages.DOCK_PADDING_DP;
    /** A wide window: each item is at most this wide, so the dock sits centred under the dashboard; narrower when room is short. */
    private static final int WIDE_ITEM_DP = 96, WIDE_SIDE_DP = 24;

    private final ScreenHost host;
    private final Ui ui;
    private final boolean wide;
    private final BooleanSupplier hidden;
    private final Dock view;
    private final List<String> shown;
    private final List<View> items = new ArrayList<>();
    /** The item that carries Service's due dot: Service's own, or "All" while Service isn't shown. */
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
        shown = wide ? HubPages.SUBJECTS : HubPages.dockSubjects(phoneRoom(host.contentWidthDp()), textScale());
        view = new Dock();
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTag(TAG);
        view.setBackground(ui.shape(ui.surface, CORNER_DP, ui.raised));
        view.setElevation(ui.dp(6));
        view.setPadding(ui.dp(PADDING_DP), ui.dp(PADDING_DP), ui.dp(PADDING_DP), ui.dp(PADDING_DP));
        for (String subject : shown) {
            View item = item(subject);
            item.setTag(tag(subject));
            items.add(item);
            if (subject.equals(HubPages.SERVICE)) service = item;
            // Weighted: equal widths, and a wide dock caps its own width (Dock.onMeasure), so every item always fits. Each
            // takes the dock's full height, so the icons stay level even where one label wraps and another doesn't.
            view.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        View all = allItem();
        items.add(all);
        if (service == null) service = all;
        view.addView(all, new LinearLayout.LayoutParams(0, -1, 1));
        // Editing toggles inside the dashboard with no rebuild: follow it before each frame, so the dock never covers the editor.
        follow = () -> { applyHidden(); return true; };
        view.getViewTreeObserver().addOnPreDrawListener(follow);
        applyHidden();
        refresh();
    }

    View view() { return view; }

    /** The room a phone's dock gives its items in a window widthDp wide: the window less its margins and padding. */
    static int phoneRoom(int widthDp) { return HubPages.dockRoom(widthDp); }

    /** The subjects the dock shows, in Home's order (all seven when wide). */
    List<String> shown() { return shown; }

    /** App text size times Android's font size, at least 1: the dock's items grow with both. */
    private float textScale() {
        return Math.max(1f, ui.textScale * host.activity().getResources().getConfiguration().fontScale);
    }

    /** The layout the dock takes at the bottom of the page frame: the old tab bar's margins on a phone, centred when wide. */
    FrameLayout.LayoutParams layoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(wide ? -2 : -1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.leftMargin = params.rightMargin = ui.dp(wide ? WIDE_SIDE_DP : SIDE_DP);
        params.bottomMargin = ui.dp(BOTTOM_DP);
        return params;
    }

    /** The room the page's scroller leaves under its last card so it can scroll clear of the dock. */
    int clearance() {
        return Math.round(ui.dp(ITEM_DP) * textScale()) + 2 * ui.dp(PADDING_DP) + ui.dp(BOTTOM_DP) + ui.dp(16);
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

    /**
     * A subject's item: its icon over its short name, the same on a phone and on a wide window (hub layout plan 1.11,
     * PR 13: the wide dock is the phone's, centred, so it reads as the same dock after a fold). Ui.nav's ladder falls
     * back to the icon alone where even the short name doesn't fit. TalkBack and the long-press tooltip always give
     * the full name.
     */
    private View item(String subject) {
        Msg shortName = HubText.shortTitle(subject);
        String name = HubText.title(subject).render();
        String shortLabel = shortName == null ? name : shortName.render();
        View item = ui.nav(shortLabel, shortLabel, HubPages.icon(subject), false, () -> open(subject));
        item.setContentDescription(name);
        item.setTooltipText(name);
        return item;
    }

    /** "All": the old tab bar's More icon over HubText.all(); read as HubText.dockAll(). It opens every subject's row. */
    private View allItem() {
        String label = HubText.all().render();
        View item = ui.nav(label, label, HubPages.DOCK_ALL_ICON, false, this::showAll);
        item.setTag(TAG_HANDLE);
        item.setContentDescription(HubText.dockAll());
        item.setTooltipText(HubText.dockAll());
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

        /** Wide: at most 96 dp an item plus padding, and never wider than the room the page frame gives. */
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (wide) {
                int max = ui.dp(WIDE_ITEM_DP) * (shown.size() + 1) + 2 * ui.dp(PADDING_DP);
                int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? max : Math.min(max, MeasureSpec.getSize(widthSpec));
                widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthSpec, heightSpec);
        }

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
