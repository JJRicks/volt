package com.jjricks.volt.tests;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * G7, the screen text census (l10n/README.md, "Census format"). Every validation's screenshot helper calls
 * {@link #write}, which saves the text of every shown view in every window of the app process, plus the
 * app's active notifications, next to the screenshot as {@code <screenshot name>.census.tsv}. Run the
 * same steps against two builds and tools/l10n/census_diff.py compares them; run-release-sweep.py --census
 * gathers the files.
 *
 * Format volt-text-census-v1: one record per line, field, locator, bounds, value, tab-separated; in
 * locator and value, backslash, tab, line feed and carriage return are written \\, \t, \n and \r.
 * Fields: text, desc, hint, tooltip, state, error, title, message, button, item, notif-title, notif-text,
 * notif-subtext, notif-action, notif-channel. The locator is tag=<tag> for a view with a String tag, else
 * the class path from its window root with child indexes; a window other than the activity's is prefixed
 * dialog[<n>]/. Records are in view-tree order (activity window first), then the other windows, then
 * notifications.
 *
 * It uses only public APIs and no Volt classes, so one test APK censuses an older app build too. A census
 * never fails its validation: if it cannot be taken, <screenshot name>.census-error.txt says why, and the
 * sweep's --census step reports it.
 *
 * A PNG drawn from one view rather than taken of the windows (ResponsiveDashboardValidation's card and
 * gauge renders) is censused by {@link #writeView}, which walks that view's tree the same way, with
 * bounds relative to the view, whether or not the view is in a window. Only a render that holds no
 * text (DeveloperSettingsValidation's switch thumbs) calls {@link #skip} instead, which leaves
 * <name>.census-skip.txt with the reason; the sweep accepts that as the screenshot's census and counts
 * no screen for it.
 *
 * A host runner's own screencap of the system notification shade (run-ride-auto-stop.py) has a census
 * of the app's notifications only, which the validation takes with {@link #writeNotifications} while
 * they are posted.
 */
public final class TextCensus {
    static final String MAGIC = "volt-text-census-v1";

    private TextCensus() {}

    /** Censuses the app's windows and notifications now, into screenshot's folder beside it. */
    public static void write(Instrumentation instrumentation, File screenshot) {
        String base = screenshot.getName().replaceFirst("\\.png$", "");
        File folder = screenshot.getParentFile();
        try {
            AtomicReference<String> text = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Runnable take = () -> { try { text.set(census(instrumentation.getTargetContext())); } catch (Throwable error) { failure.set(error); } };
            if (Looper.myLooper() == Looper.getMainLooper()) take.run(); else instrumentation.runOnMainSync(take);
            if (failure.get() != null) throw failure.get();
            save(new File(folder, base + ".census.tsv"), text.get());
        } catch (Throwable error) {
            try { save(new File(folder, base + ".census-error.txt"), String.valueOf(error) + "\n"); } catch (Throwable ignored) { }
        }
    }

    /**
     * Censuses the view tree drawn into image (root and its visible descendants, attached or not), into
     * image's folder beside it. Locators start at render/<root class>; bounds are relative to root.
     */
    public static void writeView(Instrumentation instrumentation, File image, View root) {
        String base = image.getName().replaceFirst("\\.png$", "");
        File folder = image.getParentFile();
        try {
            AtomicReference<String> text = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Runnable take = () -> {
                try {
                    StringBuilder out = new StringBuilder(MAGIC).append('\n');
                    walk(root, "render/" + root.getClass().getSimpleName(), root, false, false, out);
                    text.set(out.toString());
                } catch (Throwable error) { failure.set(error); }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) take.run(); else instrumentation.runOnMainSync(take);
            if (failure.get() != null) throw failure.get();
            save(new File(folder, base + ".census.tsv"), text.get());
        } catch (Throwable error) {
            try { save(new File(folder, base + ".census-error.txt"), String.valueOf(error) + "\n"); } catch (Throwable ignored) { }
        }
    }

    /**
     * Censuses only the app's active notifications, for a picture of the system's notification shade
     * that a host runner takes after the validation ends (run-ride-auto-stop.py). The shade is not an
     * app window, so the app's windows are left out; the runner moves this file beside its PNG.
     */
    public static void writeNotifications(Instrumentation instrumentation, File image) {
        String base = image.getName().replaceFirst("\\.png$", "");
        File folder = image.getParentFile();
        try {
            StringBuilder out = new StringBuilder(MAGIC).append('\n');
            notifications(instrumentation.getTargetContext(), out);
            save(new File(folder, base + ".census.tsv"), out.toString());
        } catch (Throwable error) {
            try { save(new File(folder, base + ".census-error.txt"), String.valueOf(error) + "\n"); } catch (Throwable ignored) { }
        }
    }

    /** Marks image as having no census on purpose (see the class comment); reason says why. */
    public static void skip(File image, String reason) {
        try { save(new File(image.getParentFile(), image.getName().replaceFirst("\\.png$", "") + ".census-skip.txt"), reason + "\n"); } catch (Throwable ignored) { }
    }

    /** Ut5SimulationScreens' view-tree dump (moved here unchanged): shown views only: type, on-screen bounds, enabled/alpha, text and spoken description. */
    static void dump(View view, int depth, StringBuilder out) {
        if (!view.isShown()) return;
        int[] at = new int[2]; view.getLocationOnScreen(at);
        char[] indent = new char[depth * 2]; Arrays.fill(indent, ' ');
        out.append(indent).append(view.getClass().getSimpleName()).append(" [").append(at[0]).append(',').append(at[1]).append(' ')
                .append(view.getWidth()).append('x').append(view.getHeight()).append(']');
        if (!view.isEnabled()) out.append(" disabled");
        if (view.getAlpha() < 1f) out.append(" alpha=").append(String.format(Locale.US, "%.2f", view.getAlpha()));
        if (view.getTag() instanceof String) out.append(" tag=").append(view.getTag());
        if (view instanceof TextView && ((TextView)view).getText().length() > 0) out.append(" text=\"").append(((TextView)view).getText().toString().replace('\n', '|')).append('"');
        if (view.getContentDescription() != null) out.append(" desc=\"").append(view.getContentDescription().toString().replace('\n', '|')).append('"');
        out.append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) dump(((ViewGroup)view).getChildAt(i), depth + 1, out);
    }
    /** The census text; call on the main thread. */
    static String census(Context context) {
        if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("the census needs API 29 (WindowInspector)");
        StringBuilder out = new StringBuilder(MAGIC).append('\n');
        // Every window root of this process in stacking order; the activity's window comes first.
        List<View> roots = new ArrayList<>(android.view.inspector.WindowInspector.getGlobalWindowViews());
        List<View> ordered = new ArrayList<>();
        for (View root : roots) if (activityWindow(root)) ordered.add(root);
        for (View root : roots) if (!activityWindow(root)) ordered.add(root);
        int index = 0;
        for (View root : ordered) {
            if (!root.isShown()) continue;
            String prefix = index++ == 0 && activityWindow(root) ? "" : "dialog[" + (index - 1) + "]/";
            walk(root, prefix + root.getClass().getSimpleName(), null, !prefix.isEmpty(), false, out);
        }
        notifications(context, out);
        return out.toString();
    }

    private static boolean activityWindow(View root) {
        return root.getLayoutParams() instanceof WindowManager.LayoutParams
                && ((WindowManager.LayoutParams) root.getLayoutParams()).type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
    }

    /** render is null for a window walk; else the rendered root, whose own window state does not matter. */
    private static void walk(View view, String path, View render, boolean otherWindow, boolean inList, StringBuilder out) {
        if (render == null ? !view.isShown() : view.getVisibility() != View.VISIBLE) return;
        String locator = view.getTag() instanceof String ? "tag=" + view.getTag() : path;
        String bounds = render == null ? bounds(view) : boundsIn(view, render);
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getText() != null && text.length() > 0) record(out, textField(text, otherWindow, inList), locator, bounds, text.getText());
            if (text.getHint() != null && text.getHint().length() > 0) record(out, "hint", locator, bounds, text.getHint());
            if (text.getError() != null) record(out, "error", locator, bounds, text.getError());
        }
        if (view.getContentDescription() != null) record(out, "desc", locator, bounds, view.getContentDescription());
        if (view.getTooltipText() != null) record(out, "tooltip", locator, bounds, view.getTooltipText());
        if (Build.VERSION.SDK_INT >= 30 && view.getStateDescription() != null) record(out, "state", locator, bounds, view.getStateDescription());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            boolean list = inList || view instanceof AbsListView;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                walk(child, path + "/" + child.getClass().getSimpleName() + "[" + i + "]", render, otherWindow, list, out);
            }
        }
    }

    /** Dialog parts by their framework ids; popup and list rows in another window are items. */
    private static String textField(TextView text, boolean otherWindow, boolean inList) {
        if (otherWindow) {
            String id = frameworkId(text);
            if ("alertTitle".equals(id) || "title_template".equals(id)) return "title";
            if ("message".equals(id)) return "message";
            if ("button1".equals(id) || "button2".equals(id) || "button3".equals(id)) return "button";
            if (inList) return "item";
        }
        return "text";
    }

    private static String frameworkId(View view) {
        if (view.getId() == View.NO_ID) return null;
        try {
            return "android".equals(view.getResources().getResourcePackageName(view.getId())) ? view.getResources().getResourceEntryName(view.getId()) : null;
        } catch (RuntimeException unnamed) {
            return null;
        }
    }

    private static String bounds(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return at[0] + "," + at[1] + "," + view.getWidth() + "," + view.getHeight();
    }

    /** view's bounds in root's drawing (root at 0,0): layout position and translation, less scrolling. */
    private static String boundsIn(View view, View root) {
        float x = 0, y = 0;
        for (View v = view; v != root && v != null; ) {
            x += v.getLeft() + v.getTranslationX(); y += v.getTop() + v.getTranslationY();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
            if (v != null) { x -= v.getScrollX(); y -= v.getScrollY(); }
        }
        return Math.round(x) + "," + Math.round(y) + "," + view.getWidth() + "," + view.getHeight();
    }

    /** The app's own posted notifications, by channel and id. */
    private static void notifications(Context context, StringBuilder out) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        List<StatusBarNotification> posted = new ArrayList<>(Arrays.asList(manager.getActiveNotifications()));
        Collections.sort(posted, (a, b) -> a.getId() != b.getId() ? Integer.compare(a.getId(), b.getId()) : String.valueOf(a.getTag()).compareTo(String.valueOf(b.getTag())));
        for (StatusBarNotification item : posted) {
            Notification n = item.getNotification();
            String locator = "notification:" + n.getChannelId() + "/" + item.getId();
            NotificationChannel channel = n.getChannelId() == null ? null : manager.getNotificationChannel(n.getChannelId());
            if (channel != null && channel.getName() != null) record(out, "notif-channel", locator, "-", channel.getName());
            Bundle extras = n.extras;
            if (extras != null) {
                CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                CharSequence sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
                CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (title != null) record(out, "notif-title", locator, "-", title);
                if (text != null) record(out, "notif-text", locator, "-", text);
                if (big != null && (text == null || !big.toString().equals(text.toString()))) record(out, "notif-text", locator + "/big", "-", big);
                if (sub != null) record(out, "notif-subtext", locator, "-", sub);
            }
            if (n.actions != null) for (Notification.Action action : n.actions) if (action.title != null) record(out, "notif-action", locator, "-", action.title);
        }
    }

    private static void record(StringBuilder out, String field, String locator, String bounds, CharSequence value) {
        out.append(field).append('\t').append(escape(locator)).append('\t').append(bounds).append('\t').append(escape(value.toString())).append('\n');
    }

    /** census_diff.py's escaping: backslash, tab, line feed, carriage return; other controls as \\uXXXX. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') out.append("\\\\");
            else if (c == '\t') out.append("\\t");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c < 0x20 || c == 0x7f) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }

    private static void save(File file, String text) throws java.io.IOException {
        File folder = file.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("cannot create " + folder);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
}
