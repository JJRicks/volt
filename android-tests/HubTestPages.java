package com.jjricks.volt.tests;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import com.jjricks.volt.AppScreen;
import com.jjricks.volt.HubPages;
import com.jjricks.volt.MainActivity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reaching a page of the Hub layout from a validation, by page id (HubPages). The Hub layout series (#385, #387, #389)
 * moved the Scooter tab's rows onto subject pages (Battery & charging, Controls, Lights & sound, Security, Service) and
 * More's and Rides' settings onto Ride, Live Stats and Settings pages; docs/development-notes.md has the screen map.
 *
 * <p>{@link #reach} and {@link #route} go the rider's way, by touch: the tab, then each row on the way, each required
 * shown, enabled, on screen and leading to its page (#414 review: opening pages directly passed even with no rows at
 * all). They run from the instrumentation thread. {@link #open} opens a page directly (Home, then each page on
 * {@link HubPages#path} through the activity's own {@code open(String)}) for setup and repeat visits; it and the other
 * readers run on the main thread.
 */
public final class HubTestPages {
    private HubTestPages() { }

    /** Longest wait for a tapped row's page, or for a row to settle on screen. */
    private static final long WAIT_MS = 6000;
    /** Pages each activity has reached by tapping the rider's route (route), so a repeat visit may open them directly. */
    private static final Map<MainActivity, Set<String>> TAPPED = new WeakHashMap<>();

    /**
     * Reaches a page the rider's way the first time this activity shows it ({@link #route}: its tab, then each row on the
     * way, all tapped), and directly ({@link #open}) on a repeat visit once that route has been proven. Call it from the
     * instrumentation thread, not inside runOnMainSync.
     */
    public static String reach(Instrumentation instrumentation, MainActivity activity, String page) {
        String wanted = HubPages.canonical(page);
        boolean tapped;
        synchronized (TAPPED) { tapped = TAPPED.containsKey(activity) && TAPPED.get(activity).contains(wanted); }
        if (!tapped) return route(instrumentation, activity, wanted);
        onMain(instrumentation, () -> open(activity, wanted));
        instrumentation.waitForIdleSync();
        return wanted;
    }

    /**
     * Reaches a page as a rider does, by touch: taps its tab in the navigation bar (the Scooter tab for the Scooter pages,
     * Rides for the Ride pages, Live Stats for Data's), then on each page the row that opens the next page on
     * {@link HubPages#path} ({@link #tapRow}). Throws, naming the row, when a row on the way is missing, hidden, disabled,
     * off screen or opens something else.
     */
    public static String route(Instrumentation instrumentation, MainActivity activity, String page) {
        String wanted = HubPages.canonical(page), tab = HubPages.tabOf(wanted);
        tapTab(instrumentation, activity, tab);
        for (String step : HubPages.path(wanted)) if (!step.equals(HubPages.HOME) && !step.equals(tab)) tapRow(instrumentation, activity, step);
        return wanted;
    }

    /** Taps a tab of the navigation bar (floating bar or rail), found by its spoken name, and waits for its page. */
    public static void tapTab(Instrumentation instrumentation, MainActivity activity, String tab) {
        int index = HubPages.TABS.indexOf(tab);
        if (index < 0) throw new IllegalArgumentException(tab + " is not a tab");
        View[] found = {null};
        String[] label = {null};
        onMain(instrumentation, () -> {
            label[0] = ((String[]) call(activity, "navNames"))[index];
            View page = (View) field(activity, "scroll");
            for (View view : descendants((View) field(activity, "root")))
                if (label[0].contentEquals(String.valueOf(view.getContentDescription())) && view.isClickable() && !inside(view, page)) { found[0] = view; break; }
        });
        if (found[0] == null) throw new IllegalStateException("No " + label[0] + " tab in the navigation bar (page " + currentOn(instrumentation, activity) + ")");
        tap(instrumentation, activity, found[0], "the " + label[0] + " tab");
        awaitPage(instrumentation, activity, tab, "the " + label[0] + " tab");
    }

    /**
     * On the page shown, taps the navigation row that opens target (its tag: HubListScreen's "hub-row-" rows on the Scooter
     * index, Service, the Ride page and Live Stats, or a Scooter page's "hub-link-" row), with a real touch at the row's
     * centre once it is scrolled on screen, and waits for target to be on top. Throws, naming the row and the page, when
     * the row is missing, hidden, disabled, not tappable, off screen, or the tap leaves another page on top.
     */
    public static void tapRow(Instrumentation instrumentation, MainActivity activity, String target) {
        String wanted = HubPages.canonical(target);
        View[] row = {null};
        String[] problem = {null}, from = {null};
        onMain(instrumentation, () -> {
            from[0] = current(activity);
            List<View> tagged = rowsTo(activity, target), shown = new ArrayList<>();
            for (View view : tagged) if (view.isShown()) shown.add(view);
            String name = "The row to " + target + " on page " + from[0];
            if (tagged.isEmpty()) problem[0] = "Page " + from[0] + " has no row to " + target;
            else if (shown.isEmpty()) problem[0] = name + " is hidden";
            else if (shown.size() > 1) problem[0] = "Page " + from[0] + " shows " + shown.size() + " rows to " + target;
            else if (!shown.get(0).isEnabled()) problem[0] = name + " is disabled";
            else if (!shown.get(0).isClickable() || !shown.get(0).hasOnClickListeners()) problem[0] = name + " is not tappable";
            else row[0] = shown.get(0);
        });
        if (problem[0] != null) throw new IllegalStateException(problem[0]);
        String name = "the row " + row[0].getTag() + " on page " + from[0];
        tap(instrumentation, activity, row[0], name);
        awaitPage(instrumentation, activity, wanted, name);
    }

    /** Whether the page shown has a row to target that a rider can see (shown; enabled or not). Main thread. */
    public static boolean rowShown(MainActivity activity, String target) {
        for (View view : rowsTo(activity, target)) if (view.isShown()) return true;
        return false;
    }

    /** Throws unless the page shown has no visible row to target: absent or gone, not merely disabled. Main thread. */
    public static void requireNoRow(MainActivity activity, String target, String why) {
        if (rowShown(activity, target)) throw new IllegalStateException("Page " + current(activity) + " shows a row to " + target + " (" + why + ")");
    }

    /** Views on the shown page's screen tagged as a row that opens target. Main thread. */
    private static List<View> rowsTo(MainActivity activity, String target) {
        List<View> out = new ArrayList<>();
        Object screen = field(activity, "screen");
        if (!(screen instanceof AppScreen)) return out;
        for (View view : descendants(((AppScreen) screen).view())) {
            Object tag = view.getTag();
            if (("hub-row-" + target).equals(tag) || ("hub-link-" + target).equals(tag)) out.add(view);
        }
        return out;
    }

    /**
     * A rider's tap on a view: scrolled to the middle of its page (clear of the floating navigation bar), required fully on
     * screen and still, then a touch down and up injected at its centre.
     */
    private static void tap(Instrumentation instrumentation, MainActivity activity, View view, String name) {
        int[] centre = new int[2];
        String[] problem = {"did not settle on screen"};
        long until = SystemClock.uptimeMillis() + WAIT_MS;
        int[] last = {Integer.MIN_VALUE, Integer.MIN_VALUE};
        int still = 0;
        boolean[] centred = {false};
        while (SystemClock.uptimeMillis() < until) {
            boolean[] ready = {false};
            onMain(instrumentation, () -> {
                if (!view.isShown()) { problem[0] = "is hidden"; return; }
                if (!view.isEnabled()) { problem[0] = "is disabled"; return; }
                Rect visible = new Rect();
                boolean whole = view.getGlobalVisibleRect(visible) && visible.width() >= view.getWidth() - 1 && visible.height() >= view.getHeight() - 1;
                if (!centred[0] || !whole) {
                    // Scroll as a rider would: the row's page scroller brings it to its middle (a page transition or the
                    // tab's own scroll to the top may still be moving it, so this repeats until it holds).
                    centred[0] = true;
                    if (centre(view)) { problem[0] = "is not fully on screen"; return; }
                }
                if (!whole) { problem[0] = "is not fully on screen"; return; }
                int[] at = new int[2]; view.getLocationOnScreen(at);
                centre[0] = at[0] + view.getWidth() / 2; centre[1] = at[1] + view.getHeight() / 2;
                ready[0] = true;
            });
            // Still: the same place on three reads in a row, so nothing is moving it any more.
            if (ready[0] && centre[0] == last[0] && centre[1] == last[1]) { if (++still >= 2) break; }
            else still = 0;
            last[0] = ready[0] ? centre[0] : Integer.MIN_VALUE; last[1] = ready[0] ? centre[1] : Integer.MIN_VALUE;
            SystemClock.sleep(50);
        }
        if (still < 2) throw new IllegalStateException(capital(name) + " " + problem[0] + " (page " + currentOn(instrumentation, activity) + ")");
        long when = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(when, when, MotionEvent.ACTION_DOWN, centre[0], centre[1], 0),
                up = MotionEvent.obtain(when, when + 80, MotionEvent.ACTION_UP, centre[0], centre[1], 0);
        try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up); }
        finally { down.recycle(); up.recycle(); }
        instrumentation.waitForIdleSync();
    }

    /** Scrolls the view's nearest page scroller so the view sits in its middle; false when it has no scroller or is already there. */
    private static boolean centre(View view) {
        for (View parent = view.getParent() instanceof View ? (View) view.getParent() : null; parent != null;
             parent = parent.getParent() instanceof View ? (View) parent.getParent() : null) {
            if (!(parent instanceof ScrollView)) continue;
            int[] at = new int[2], top = new int[2];
            view.getLocationInWindow(at); parent.getLocationInWindow(top);
            int by = at[1] + view.getHeight() / 2 - top[1] - parent.getHeight() / 2, before = parent.getScrollY();
            parent.scrollBy(0, by);
            return parent.getScrollY() != before;
        }
        return false;
    }

    /** Waits until page is on top and its screen is laid out; names what was tapped when it isn't. */
    private static void awaitPage(Instrumentation instrumentation, MainActivity activity, String page, String tapped) {
        long until = SystemClock.uptimeMillis() + WAIT_MS;
        boolean[] there = {false};
        while (SystemClock.uptimeMillis() < until) {
            onMain(instrumentation, () -> {
                Object screen = field(activity, "screen");
                View view = screen instanceof AppScreen ? ((AppScreen) screen).view() : null;
                there[0] = page.equals(current(activity)) && view != null && view.isAttachedToWindow() && view.getWidth() > 0;
            });
            if (there[0]) break;
            SystemClock.sleep(40);
        }
        String shown = currentOn(instrumentation, activity);
        if (!there[0]) throw new IllegalStateException("Tapping " + tapped + " did not open " + page + " (" + shown + " is on top)");
        synchronized (TAPPED) {
            Set<String> pages = TAPPED.get(activity);
            if (pages == null) TAPPED.put(activity, pages = new HashSet<>());
            pages.add(page);
        }
        instrumentation.waitForIdleSync();
    }

    private static String currentOn(Instrumentation instrumentation, MainActivity activity) {
        String[] shown = {null};
        onMain(instrumentation, () -> shown[0] = current(activity));
        return shown[0];
    }

    private static String capital(String text) { return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1); }

    private static boolean inside(View view, View ancestor) {
        if (ancestor == null) return false;
        for (Object at = view; at instanceof View; at = ((View) at).getParent()) if (at == ancestor) return true;
        return false;
    }

    private static List<View> descendants(View root) {
        List<View> out = new ArrayList<>();
        if (root == null) return out;
        out.add(root);
        for (int i = 0; i < out.size(); i++)
            if (out.get(i) instanceof ViewGroup) for (int c = 0; c < ((ViewGroup) out.get(i)).getChildCount(); c++) out.add(((ViewGroup) out.get(i)).getChildAt(c));
        return out;
    }

    /** Runs on the main thread and rethrows what it threw; refuses to run from the main thread itself (taps would deadlock). */
    private static void onMain(Instrumentation instrumentation, Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("HubTestPages taps run from the instrumentation thread");
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { task.run(); }
            catch (RuntimeException error) { failure.set(error); }
            catch (Error error) { failure.set(new IllegalStateException(error.getMessage(), error)); }
        });
        if (failure.get() != null) throw failure.get();
    }

    /** Shows page with its parents below it (Home first); throws when another page is on top afterwards. Direct setup: no taps. */
    public static String open(MainActivity activity, String page) {
        invoke(activity, "select", HubPages.HOME);
        for (String step : HubPages.path(page)) if (!step.equals(HubPages.HOME)) activity.open(step);
        String shown = current(activity), wanted = HubPages.canonical(page);
        if (!wanted.equals(shown)) throw new IllegalStateException("Page " + page + " did not open (" + shown + " is on top)");
        return shown;
    }

    /** The page on top of the stack (MainActivity.selectedPage). */
    public static String current(MainActivity activity) { return (String) field(activity, "selectedPage"); }

    /**
     * The part of the shown screen whose class has this simple name: the screen itself, or one of a HubPageScreen's parts
     * (Battery & charging is HubBatterySummary over a ControlsScreen; Security is its lock bar, a ControlsScreen and
     * More's Security section; Recording settings is More's Recording section over the phone sensors card).
     */
    public static Object part(MainActivity activity, String simpleName) {
        Object screen = field(activity, "screen");
        Object found = part(screen, simpleName);
        if (found == null) throw new IllegalStateException("No " + simpleName + " on page " + current(activity) + " (" + screen.getClass().getSimpleName() + ")");
        return found;
    }

    /** As {@link #part(MainActivity, String)} for a given screen; null when it has no such part. */
    public static Object part(Object screen, String simpleName) {
        if (screen == null) return null;
        if (screen.getClass().getSimpleName().equals(simpleName)) return screen;
        if (!screen.getClass().getSimpleName().equals("HubPageScreen")) return null;
        for (Object item : (List<?>) field(screen, "parts")) if (item.getClass().getSimpleName().equals(simpleName)) return item;
        return null;
    }

    /** The Scooter pages that show rows (ControlsPages.pages() without the index's search), in ControlsPages' order. */
    public static List<String> scooterRowPages() {
        List<String> pages = new java.util.ArrayList<>();
        for (Object page : (List<?>) controlsPages("pages", new Class<?>[0])) {
            Object spec = spec((String) page);
            if (spec != null && !(Boolean) field(spec, "search")) pages.add((String) page);
        }
        return pages;
    }

    /** Whether ControlsPages lists this row id (a feature key or a hand-built row id) on the page. */
    public static boolean pageLists(String page, String id) {
        Object spec = spec(page);
        return spec != null && ((List<?>) field(spec, "rows")).contains(id);
    }

    /** The page's rows that are not links to other pages (ControlsPages.Spec.rows), in order; empty for a page without rows. */
    public static List<String> pageRows(String page) {
        Object spec = spec(page);
        List<String> rows = new ArrayList<>();
        if (spec == null) return rows;
        for (Object id : (List<?>) field(spec, "rows")) if (!((String) id).startsWith("link:")) rows.add((String) id);
        return rows;
    }

    /** Whether the page leads with the UT5 MAX top speed card (ControlsPages.Spec.topSpeed). */
    public static boolean pageHasTopSpeed(String page) {
        Object spec = spec(page);
        return spec != null && (Boolean) field(spec, "topSpeed");
    }

    /** The Scooter page that shows a row (ControlsPages.pageOf); null when no page does. */
    public static String pageOf(String id) { return (String) controlsPages("pageOf", new Class<?>[]{String.class}, id); }

    private static Object spec(String page) { return controlsPages("spec", new Class<?>[]{String.class}, page); }

    private static Object controlsPages(String name, Class<?>[] types, Object... values) {
        try {
            Method method = Class.forName("com.jjricks.volt.ControlsPages").getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(null, values);
        } catch (Exception error) { throw new IllegalStateException("ControlsPages." + name, error); }
    }

    private static Object field(Object target, String name) {
        try {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
                catch (NoSuchFieldException missing) { /* the superclass may declare it */ }
            }
            throw new NoSuchFieldException(name);
        } catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }

    private static Object call(Object target, String name) {
        try { Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }

    private static void invoke(Object target, String name, String argument) {
        try { Method method = target.getClass().getDeclaredMethod(name, String.class); method.setAccessible(true); method.invoke(target, argument); }
        catch (java.lang.reflect.InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
}
