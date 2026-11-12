package com.jjricks.volt.tests;

import com.jjricks.volt.HubPages;
import com.jjricks.volt.MainActivity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Opening a page of the Hub layout from a validation, by page id (HubPages), the way a rider reaches it: Home, then
 * each page on {@link HubPages#path} through the activity's own {@code open(String)}. The Hub layout series (#385,
 * #387, #389) moved the Scooter tab's rows onto subject pages (Battery & charging, Controls, Lights & sound, Security,
 * Service) and More's and Rides' settings onto Ride, Live Stats and Settings pages; docs/development-notes.md has the
 * screen map. Every method runs on the main thread.
 */
public final class HubTestPages {
    private HubTestPages() { }

    /** Shows page with its parents below it (Home first); throws when another page is on top afterwards. */
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

    private static void invoke(Object target, String name, String argument) {
        try { Method method = target.getClass().getDeclaredMethod(name, String.class); method.setAccessible(true); method.invoke(target, argument); }
        catch (java.lang.reflect.InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
}
