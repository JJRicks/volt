package com.jjricks.volt;

import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
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
 * outline, elevation and margins, under the plan's grab handle, holding the subjects' icons on the plan's raised tiles
 * (accent-coloured), each with its short name under it (Ui.nav, the rail's item, whose caption ladder falls back to the
 * tile alone) and an "All" item. Where all seven don't fit at a comfortable
 * width (HubPages.dockSubjects: a 344 dp phone shows four), it shows the most-used ones; "All" opens a sheet listing
 * every subject with its name and live summary (CleanSubjects), so each stays one tap away, and so does an upward swipe
 * on the dock. Tapping an icon opens its subject on Home's page stack. Service's icon carries the orange dot while a
 * maintenance check is due, or "All" does while Service isn't shown. Each item is read by its subject's full name (All
 * by HubText.dockAll()), and a long press shows it. On a wide window the dock is centred and holds all seven, each
 * under its full name where every full name fits on one line, else every item under its short name (HubPages.wideDockNames),
 * so the row stays even and no taller than it must be. The sheet also holds Search and Settings at its top: Widgets
 * Home's header keeps its place and height (the owner's answer on #392's early review), so this is where a Widgets owner
 * finds them. Hidden while the dashboard is being edited. Every word comes from HubText, so this file holds no catalog
 * references. The sheet follows the finger that swipes it up and springs open or back (DragSheet, the owner's request of
 * Nov 15), and an open one can be swiped away. The dock itself is what moves: it grows into the sheet and the sheet
 * shrinks back into it (DragSheet's Source, his follow-up the same day), so while the sheet shows the dock is hidden
 * (handed) and the sheet draws it.
 *
 * <p>Against the page behind it (asked with that change): a soft shadow (ELEVATION_DP, its colours per theme over the
 * window's raised shadow strength, styles.xml's Volt*.Main) and a 1 dp hairline of the text colour at low strength on the
 * dock's own corners, so it reads as its own surface over busy widgets and over the page's plain top alike, in both
 * themes. The sheet starts from these and eases to its own look (no shadow, its usual outline).
 */
final class HubDock {
    /**
     * View tags: the dock, its "All" item (it opens the sheet, the job the grab handle above the items also shows, and keeps the
     * tag the handle had before part 11's review),
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
    /** The plan's dock look: a 51 x 6 dp grab handle at the top, and each icon (20 dp) on a 39 dp raised tile with 12 dp corners. */
    private static final int HANDLE_W_DP = 51, HANDLE_H_DP = 6, HANDLE_TOP_DP = 4, HANDLE_BOTTOM_DP = 2, TILE_DP = 39, TILE_ICON_DP = 20;
    /** The room left between the page's last widget, scrolled to the end, and the dock's top. */
    private static final int CLEAR_GAP_DP = 16;
    /**
     * The dock's shadow: its elevation, and its colours' strength (of the window's, Ui.WINDOW_AMBIENT and WINDOW_SPOT) per
     * theme; the light theme's softer, as its hairline already draws the edge (tuned on renders over Home's widgets).
     */
    private static final int ELEVATION_DP = 8;
    private static final float AMBIENT_LIGHT = .35f, SPOT_LIGHT = .4f, AMBIENT_DARK = 1f, SPOT_DARK = 1f;
    /** The hairline: the text colour at this strength (0..1) over the dock's fill, light and dark. */
    private static final float HAIR_LIGHT = .12f, HAIR_DARK = .14f;

    private final ScreenHost host;
    private final Ui ui;
    private final boolean wide;
    private final BooleanSupplier hidden;
    /** The sheet's Search and Settings rows: open those pages (Home's header buttons on Clean). */
    private final Runnable openSearch, openSettings;
    /** Wide: whether the items show their full names (else their short names, all alike). */
    private final boolean fullNames;
    private final Dock view;
    private final List<String> shown;
    private final List<View> items = new ArrayList<>();
    /** The item that carries Service's due dot: Service's own, or "All" while Service isn't shown. */
    private View service;
    /** The All sheet while it shows (its overlay in Volt's window, and what moves it). */
    private View sheet;
    private DragSheet drag;
    private CleanSubjects sheetSubjects;
    private boolean closed;
    /** The sheet has the dock now (it draws it, growing it into itself): the dock itself is hidden, keeping its place. */
    private boolean handed;
    /** The dock's outline colour (its background's 1 dp stroke). */
    private final int stroke;
    /** What the sheet grows out of: this dock. */
    private final DragSheet.Source source = new DragSheet.Source() {
        @Override public View view() { return view; }
        @Override public void handed(boolean on) { handed = on; applyHidden(); }
        @Override public void drawContent(Canvas canvas) { view.drawContent(canvas); }
        @Override public int stroke() { return stroke; }
        @Override public float strokeWidth() { return stroke == 0 ? 0 : ui.dp(1); }
    };
    private final ViewTreeObserver.OnPreDrawListener follow;

    /**
     * hidden: whether the dock is gone now (the dashboard is being edited); read before every frame. openSearch and
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
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setTag(TAG);
        int fill = ui.surface;
        // Opaque (the text colour blended over the fill), not a translucent stroke: a GradientDrawable whose stroke and
        // fill differ in opacity reports an outline of alpha 0, which casts no shadow.
        stroke = SheetMorph.mix(fill, ui.text, ui.dark ? HAIR_DARK : HAIR_LIGHT);
        view.setBackground(ui.shape(fill, CORNER_DP, stroke));
        view.setElevation(ui.dp(ELEVATION_DP));
        if (Build.VERSION.SDK_INT >= 28) {
            view.setOutlineAmbientShadowColor(Ui.withAlpha(Color.BLACK, Math.round(255 * (ui.dark ? AMBIENT_DARK : AMBIENT_LIGHT))));
            view.setOutlineSpotShadowColor(Ui.withAlpha(Color.BLACK, Math.round(255 * (ui.dark ? SPOT_DARK : SPOT_LIGHT))));
        }
        view.setPadding(ui.dp(PADDING_DP), ui.dp(PADDING_DP), ui.dp(PADDING_DP), ui.dp(PADDING_DP));
        // The plan's grab handle (51 x 6 dp, the outline colour) centred at the top: the upward swipe opens the All sheet.
        View handle = new View(host.activity());
        handle.setBackground(ui.shape(ui.outline, 3, 0));
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams handleSize = new LinearLayout.LayoutParams(ui.dp(HANDLE_W_DP), ui.dp(HANDLE_H_DP));
        handleSize.topMargin = ui.dp(HANDLE_TOP_DP); handleSize.bottomMargin = ui.dp(HANDLE_BOTTOM_DP);
        view.addView(handle, handleSize);
        LinearLayout row = new LinearLayout(host.activity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        view.addView(row, new LinearLayout.LayoutParams(-1, -2));
        for (String subject : shown) {
            View item = item(subject);
            item.setTag(tag(subject));
            items.add(item);
            if (subject.equals(HubPages.SERVICE)) service = item;
            // Weighted: equal widths, and a wide dock caps its own width (Dock.onMeasure), so every item always fits. Each
            // takes the dock's full height, so the icons stay level even where one label wraps and another doesn't.
            row.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        View all = allItem();
        items.add(all);
        if (service == null) service = all;
        row.addView(all, new LinearLayout.LayoutParams(0, -1, 1));
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

    /**
     * The room the page's scroller leaves under its last card so every widget, the Lock bar included, can be scrolled fully
     * clear of the pinned dock (HubPages.dockClearance): the dock's height once it is laid out, else the height its parts
     * add up to, plus its bottom margin and 16 dp.
     */
    int clearance() {
        int estimate = Math.round(ui.dp(ITEM_DP) * textScale()) + 2 * ui.dp(PADDING_DP) + ui.dp(HANDLE_TOP_DP + HANDLE_H_DP + HANDLE_BOTTOM_DP);
        return HubPages.dockClearance(Math.max(estimate, view.getHeight()), ui.dp(BOTTOM_DP), ui.dp(CLEAR_GAP_DP));
    }

    /**
     * Pads scroll's bottom by basePadding plus the clearance, and again whenever the dock's height changes (a caption that
     * wraps at large text makes it taller than its parts add up to), so the page's end always scrolls clear of it.
     */
    void padScroller(android.widget.ScrollView scroll, int basePadding) {
        scroll.setClipToPadding(false);
        scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(), scroll.getPaddingRight(), basePadding + clearance());
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (closed || b - t == ob - ot) return;
            int wanted = basePadding + clearance();
            if (scroll.getPaddingBottom() != wanted)
                scroll.post(() -> scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(), scroll.getPaddingRight(), wanted));
        });
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
        if (drag != null) drag.leave();
        sheet = null; drag = null; sheetSubjects = null;
    }

    /**
     * The dock is pinned to the bottom of the page at all times (the contributor's decision, 14 Nov, #383): it never lifts
     * over Widgets' Lock bar and never steps aside for it, since the bar is one of the rider's own widgets they can move;
     * the scroller's padding (padScroller) lets every widget scroll clear of it. It is gone only while Home is edited.
     */
    private void applyHidden() {
        // Handed to the sheet: invisible, not gone, so its place (and the sheet's reading of it) stays.
        int visibility = hidden != null && hidden.getAsBoolean() ? View.GONE : handed ? View.INVISIBLE : View.VISIBLE;
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }

    /**
     * A subject's item: its icon over its short name on a phone, or its name when wide (Ui.nav's ladder: that label, the
     * short name, or the icon alone). TalkBack and the long-press tooltip always give the full name.
     */
    private View item(String subject) {
        Msg shortName = HubText.shortTitle(subject);
        String name = HubText.title(subject).render();
        String shortLabel = shortName == null ? name : shortName.render();
        View item = tiled(ui.nav(fullNames ? name : shortLabel, shortLabel, HubPages.icon(subject), false, () -> open(subject)), HubPages.icon(subject));
        item.setContentDescription(name);
        item.setTooltipText(name);
        return item;
    }

    /** "All": the old tab bar's More icon over HubText.all(); read as HubText.dockAll(). It opens every subject's row. */
    private View allItem() {
        String label = HubText.all().render();
        View item = tiled(ui.nav(label, label, HubPages.DOCK_ALL_ICON, false, this::showAll), HubPages.DOCK_ALL_ICON);
        item.setTag(TAG_HANDLE);
        item.setContentDescription(HubText.dockAll());
        item.setTooltipText(HubText.dockAll());
        return item;
    }

    /**
     * The plan's tile on a Ui.nav item: its line icon (first child) moves onto a 39 dp raised rounded square, drawn in the
     * accent's text colour as Clean's subject tiles and the sheet's rows are; the 10 sp caption stays under it. The item's
     * padding shrinks to 4 dp top and bottom so it stays at Ui.nav's 64 dp at normal text. Service's due dot sits at the
     * tile's top corner (MaintenanceScreen.markTab places it on the item's first child).
     */
    private View tiled(View item, String icon) {
        if (!(item instanceof LinearLayout) || ((LinearLayout) item).getChildCount() == 0) return item;
        LinearLayout column = (LinearLayout) item;
        column.removeViewAt(0);
        FrameLayout tile = new FrameLayout(host.activity());
        tile.setBackground(ui.shape(ui.raised, 12, 0));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(ui.icon(icon, TILE_ICON_DP, ui.accentText), new FrameLayout.LayoutParams(ui.dp(TILE_ICON_DP), ui.dp(TILE_ICON_DP), Gravity.CENTER));
        column.addView(tile, 0, new LinearLayout.LayoutParams(ui.dp(TILE_DP), ui.dp(TILE_DP)));
        column.setPadding(column.getPaddingLeft(), ui.dp(4), column.getPaddingRight(), ui.dp(4));
        return column;
    }

    private void open(String subject) {
        if (closed) return;
        host.open(subject);
    }

    /** All's tap: the sheet springs open. */
    private void showAll() {
        DragSheet all = allSheet();
        if (all != null) all.open();
    }

    /**
     * The sheet with every subject's name and live summary, built when none shows; a row opens its subject and closes the
     * sheet. One still settling closed is the same sheet (it holds the dock until it is gone), never a second.
     */
    private DragSheet allSheet() {
        if (closed) return null;
        if (drag != null && drag.showing()) return drag;
        if (drag != null) drag.dismissNow();
        DragSheet[] shown = new DragSheet[1];
        Runnable dismiss = () -> { if (shown[0] != null) shown[0].close(); };
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
        DragSheet made = shown[0] = host.dragSheet(source, content, DragSheet.header(content));
        made.onDismiss(() -> {
            subjects.close();
            if (drag == made) { sheetSubjects = null; sheet = null; drag = null; }
        });
        drag = made;
        sheet = made.view();
        return made;
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
     * The dock's own touch handling: an upward swipe that starts anywhere on it shows the list of every subject at the
     * moment the swipe is recognised (HubPages.dockSwipe), and from then on the sheet's top follows the finger, which
     * springs it open or back as it lets go (DragSheet.Pull); a quick flick that lifts right after still opens it. Taps
     * and sideways movement go to the icons as usual.
     */
    private final class Dock extends LinearLayout {
        private final int slop = ViewConfiguration.get(host.activity()).getScaledTouchSlop();
        private final DragSheet.Pull pull = new DragSheet.Pull();
        private float downX, downY;
        private boolean swiped;

        Dock() { super(host.activity()); }

        /** Its content without its background (DragSheet draws that as its surface), for the sheet that holds it. */
        void drawContent(Canvas canvas) { dispatchDraw(canvas); }

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
            pull.track(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX(); downY = event.getY(); swiped = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    // Taken from the item (its tap is cancelled) and the sheet shows now, not on a later move.
                    if (!swiped && HubPages.dockSwipe(downY - event.getY(), Math.abs(event.getX() - downX), slop)) {
                        swiped = true; pull.start(allSheet(), event); return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            // Every touch's ACTION_DOWN went through onInterceptTouchEvent, which tracked it; the later events come here alone.
            if (event.getActionMasked() != MotionEvent.ACTION_DOWN) pull.track(event);
            // The rest of a swipe already taken: the sheet follows it.
            if (swiped) { pull.handle(event); return true; }
            // A touch on the dock's own padding: the same upward swipe pulls the list.
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = event.getX(); downY = event.getY(); return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && HubPages.dockSwipe(downY - event.getY(), Math.abs(event.getX() - downX), slop)) { swiped = true; pull.start(allSheet(), event); }
            return true;
        }
    }
}
