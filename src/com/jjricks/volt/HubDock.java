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
import java.util.function.Supplier;

/**
 * The Widgets Home dock (hub layout plan 1.10, PR 11), in the place the tab bar had and with its look (owner's answer 8
 * on #383: not cramped on a portrait phone, not drab or out of place): the same rounded floating bar, its surface,
 * outline, elevation and margins, holding the subjects' icons each with its short name under it (Ui.nav, the rail's
 * item, whose caption ladder falls back to the icon alone) and an "All" item. Where all seven don't fit at a comfortable
 * width (HubPages.dockSubjects: a 344 dp phone shows four), it shows the most-used ones; "All" opens a sheet listing
 * every subject with its name and live summary (CleanSubjects), so each stays one tap away, and so does an upward swipe
 * on the dock. Tapping an icon opens its subject on Home's page stack. Service's icon carries the orange dot while a
 * maintenance check is due, or "All" does while Service isn't shown. Each item is read by its subject's full name (All
 * by HubText.dockAll()), and a long press shows it. On a wide window the dock is centred and holds all seven, each
 * under its full name where every full name fits on one line, else every item under its short name (HubPages.wideDockNames),
 * so the row stays even and no taller than it must be. The sheet also holds Search and Settings at its top: Widgets
 * Home's header keeps its place and height (the owner's answer on #392's early review), so this is where a Widgets owner
 * finds them. Hidden while the dashboard is being edited. Every word comes from HubText, so this file holds no catalog
 * references.
 */
final class HubDock {
    /**
     * View tags: the dock, its "All" item (which took the old grab handle's place and job, so it keeps the handle's tag),
     * the sheet, and each shown subject's icon ("hub-dock-" and the subject's page id).
     */
    static final String TAG = "hub-dock", TAG_HANDLE = "hub-dock-handle", TAG_SHEET = "hub-dock-sheet";
    static String tag(String subject) { return "hub-dock-" + subject; }

    /**
     * The old floating tab bar's place (as MainActivity added it before part 11): 14 dp from each side and 10 dp above the
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
    /** The view the dock never covers (Widgets' Lock bar), or null; read before every frame. */
    private Supplier<View> keepClear;
    /** The sheet's Search and Settings rows: open those pages (Home's header buttons on Clean). */
    private final Runnable openSearch, openSettings;
    /** Wide: whether the items show their full names (else their short names, all alike). */
    private final boolean fullNames;
    private final Dock view;
    private final List<String> shown;
    private final List<View> items = new ArrayList<>();
    /** The item that carries Service's due dot: Service's own, or "All" while Service isn't shown. */
    private View service;
    private AlertDialog sheet;
    private CleanSubjects sheetSubjects;
    private boolean closed;
    private final ViewTreeObserver.OnPreDrawListener follow;

    /**
     * hidden: whether the dock steps aside now (the dashboard is being edited); read before every frame. openSearch and
     * openSettings: the sheet's Search and Settings rows.
     */
    HubDock(ScreenHost host, boolean wide, BooleanSupplier hidden, Runnable openSearch, Runnable openSettings) {
        this.host = host;
        this.ui = host.ui();
        this.wide = wide;
        this.hidden = hidden;
        this.openSearch = openSearch;
        this.openSettings = openSettings;
        shown = wide ? HubPages.SUBJECTS : HubPages.dockSubjects(phoneRoom(host.contentWidthDp()), textScale());
        fullNames = wide && fullNamesFit();
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

    /**
     * Wide: whether every subject's full name fits on one line in an item, measured as Ui.nav draws captions (10 sp at
     * Volt's text size, 2 dp side padding each way), with each item as wide as the dock gives it (Dock.onMeasure).
     */
    private boolean fullNamesFit() {
        android.text.TextPaint paint = new android.text.TextPaint();
        paint.setTypeface(ui.regular());
        paint.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10 * ui.textScale,
                host.activity().getResources().getDisplayMetrics()));
        float density = host.activity().getResources().getDisplayMetrics().density;
        float[] widths = new float[shown.size()];
        for (int i = 0; i < widths.length; i++) widths[i] = paint.measureText(HubText.title(shown.get(i)).render()) / density;
        return HubPages.wideDockNames(widths, host.contentWidthDp() - 2 * WIDE_SIDE_DP, shown.size() + 1, WIDE_ITEM_DP, PADDING_DP);
    }

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
        int visibility = View.VISIBLE;
        float lift = 0;
        if (hidden != null && hidden.getAsBoolean()) visibility = View.GONE;
        else if (coversClearView()) {
            int offset = liftOverClearView();
            if (offset == HubPages.DOCK_STEP_ASIDE) visibility = View.INVISIBLE;
            else lift = offset;
        }
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
        if (view.getTranslationY() != lift) view.setTranslationY(lift);
    }

    /**
     * The view the dock must never cover (Widgets' Lock bar). When the bar sits where the dock floats (the Shortcuts card
     * pushes it down on a 344 or 412 dp phone; a 725x604 dp window at 1.4x text), the dock moves up to float 10 dp above
     * the bar (HubPages.dockLift), so Home always shows its navigation, and drops back to its place once the page scrolls
     * the bar above it. Only where moving up would take it above the page's top does it step aside (invisible, keeping its
     * place, so it comes back as soon as the page scrolls the bar clear).
     */
    void keepClear(Supplier<View> clear) { keepClear = clear; applyHidden(); }

    /** The dock's top and bottom in the window where it sits unmoved (its translation taken off). */
    private int[] restingSpan() {
        int[] dock = new int[2];
        view.getLocationInWindow(dock);
        int top = dock[1] - Math.round(view.getTranslationY());
        return new int[] {top, top + view.getHeight()};
    }

    /** Whether the dock, in its own place (unmoved), is over any visible part of the view it keeps clear. */
    private boolean coversClearView() {
        View clear = keepClear == null ? null : keepClear.get();
        if (clear == null || !clear.isShown() || !clear.isAttachedToWindow() || view.getHeight() == 0) return false;
        int[] at = new int[2];
        clear.getLocationInWindow(at);
        int[] dock = restingSpan();
        android.graphics.Rect visible = new android.graphics.Rect();
        if (!clear.getGlobalVisibleRect(visible)) return false;
        return HubPages.dockCovers(at[1], at[1] + clear.getHeight(), visible.top, visible.bottom, dock[0], dock[1]);
    }

    /** HubPages.dockLift for the view kept clear: the offset that floats the dock 10 dp above it, or DOCK_STEP_ASIDE. */
    private int liftOverClearView() {
        View clear = keepClear.get();
        int[] at = new int[2];
        clear.getLocationInWindow(at);
        // The highest the dock may go: the top of the page frame it floats in (under the status bar and any banner).
        int[] frame = new int[2];
        View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
        if (parent != null) parent.getLocationInWindow(frame);
        int minTop = frame[1] + (parent == null ? 0 : parent.getPaddingTop());
        int[] dock = restingSpan();
        return HubPages.dockLift(at[1], dock[0], dock[1], minTop, ui.dp(BOTTOM_DP));
    }

    /**
     * A subject's item: its icon over its short name on a phone, or its name when wide (Ui.nav's ladder: that label, the
     * short name, or the icon alone). TalkBack and the long-press tooltip always give the full name.
     */
    private View item(String subject) {
        Msg shortName = HubText.shortTitle(subject);
        String name = HubText.title(subject).render();
        String shortLabel = shortName == null ? name : shortName.render();
        View item = ui.nav(fullNames ? name : shortLabel, shortLabel, HubPages.icon(subject), false, () -> open(subject));
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
        // Search and Settings first, with Clean's header tags (the same jobs), then every subject.
        LinearLayout pages = ui.groupCard();
        pages.addView(pageLine(HubPages.SEARCH, CleanHomeScreen.TAG_SEARCH, dismiss, openSearch));
        ui.divider(pages);
        pages.addView(pageLine(HubPages.SETTINGS, CleanHomeScreen.TAG_SETTINGS, dismiss, openSettings));
        LinearLayout.LayoutParams pagesSize = new LinearLayout.LayoutParams(-1, -2);
        pagesSize.bottomMargin = ui.dp(12);
        content.addView(pages, pagesSize);
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
     * A sheet row for a page that is not a subject (Search, Settings), built like CleanSubjects' subject row so the two
     * cards read as one list: the 33 dp raised tile with the glyph in the accent's text colour, then the 15.5 sp title
     * (no summary line) and the chevron. It closes the sheet first.
     */
    private View pageLine(String page, String tag, Runnable dismiss, Runnable action) {
        LinearLayout line = new LinearLayout(host.activity());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setBaselineAligned(false);
        line.setMinimumHeight(ui.dp(48));
        line.setPadding(0, ui.dp(5), 0, ui.dp(5));
        line.setTag(tag);
        FrameLayout tile = new FrameLayout(host.activity());
        tile.setBackground(ui.shape(ui.raised, 10, 0));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(ui.icon(HubPages.icon(page), 18, ui.accentText), new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
        LinearLayout.LayoutParams tileSize = new LinearLayout.LayoutParams(ui.dp(33), ui.dp(33));
        tileSize.setMarginEnd(ui.dp(12));
        line.addView(tile, tileSize);
        android.widget.TextView name = ui.text(HubText.title(page), 15.5f, false);
        name.setTypeface(ui.medium());
        name.setMaxLines(2);
        name.setIncludeFontPadding(false);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams chevronSize = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
        chevronSize.setMarginStart(ui.dp(8));
        line.addView(ui.icon("chevron", 15, ui.muted), chevronSize);
        line.setClickable(true);
        line.setFocusable(true);
        line.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)),
                null, ui.shape(android.graphics.Color.WHITE, 10, 0)));
        line.setOnClickListener(v -> {
            if (closed) return;
            dismiss.run();
            if (action != null) action.run();
        });
        return line;
    }

    /**
     * The dock's own touch handling: an upward swipe that starts anywhere on it opens the list of every subject, at the
     * moment the swipe is recognised (HubPages.dockSwipe), so a quick flick that lifts right after still opens it. Taps
     * and sideways movement go to the icons as usual.
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
                case MotionEvent.ACTION_MOVE:
                    // Taken from the item (its tap is cancelled) and the sheet opens now, not on a later move.
                    if (!swiped && HubPages.dockSwipe(downY - event.getY(), Math.abs(event.getX() - downX), slop)) {
                        swiped = true; showAll(); return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            // The rest of a swipe already taken: the sheet is open.
            if (swiped) return true;
            // A touch on the dock's own padding: the same upward swipe opens the list.
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = event.getX(); downY = event.getY(); return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && HubPages.dockSwipe(downY - event.getY(), Math.abs(event.getX() - downX), slop)) { swiped = true; showAll(); }
            return true;
        }
    }
}
