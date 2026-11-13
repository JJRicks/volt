package com.jjricks.volt.tests;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.text.Layout;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.*;

/**
 * Disposable empty emulator only. Start recording in the background on the 1.13 layout: its row sits directly under
 * Record rides automatically in More › App preferences › Connection & recording, with its caption whole beside a 48 dp
 * switch that is greyed and drawn off while Record rides automatically is off (stored value kept), and a tap stores it.
 * Then the one-time What's new dialog for an owner who updated with Record rides automatically on: title, message and
 * the switch whole, the switch stores its key, OK closes it, and it never comes back. A fresh install writes no note
 * state. Run at 344 and 725 dp, normal and 1.4x text (-e theme, -e text_scale, -e variant). Never connects Bluetooth;
 * the behaviour itself is AutoStartValidation's background-switch mode.
 */
public final class BackgroundRecordingValidation extends Instrumentation {
    private static final String TITLE = "Start recording in the background", PARENT = "Record rides automatically",
            CAPTION = "A ride that starts while Volt is in the background or with the screen locked has no GPS route.",
            MESSAGE = "Volt now starts recording in the background only if you turn it on. A ride started in the background or with the screen locked has no GPS route.",
            AUTO_RECORD = "auto_record_rides", BACKGROUND = "auto_record_background", NOTICE = "auto_record_background_notice";
    private Bundle args; private MainActivity activity; private int checks; private final List<String> screenshots = new ArrayList<>();
    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"), "Refusing physical phone");
            // onStart runs on its own thread, possibly before VoltApp.onCreate has run on the main thread; a write here
            // first would make that start see settings and seed the note. Wait until the app has started.
            waitForIdleSync();
            check(prefs().getAll().isEmpty(), "Requires empty preferences: a fresh install's first start writes no note state");
            BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter(); check(adapter == null || !adapter.isEnabled(), "Bluetooth must be off");
            prefs().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("map_basemap_enabled", false)
                    .putBoolean("phone_gps", false).putString("theme", args.getString("theme", "dark"))
                    .putFloat("text_scale", Float.parseFloat(args.getString("text_scale", "1"))).commit();
            main(() -> new ScooterProfiles(context).create("Synthetic background-recording scooter", "A1B2C3D4E5F7", 0));
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "Service ready");
            settle();
            check(!prefs().contains(NOTICE), "No What's new note on a fresh install");
            row();
            whatsNew();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(error));
            try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("checks", checks); result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            if (activity != null) main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void openApp() {
        main(() -> { invoke(activity, "select", new Class[]{String.class}, "more"); invoke(field(activity, "screen"), "selectSection", new Class[]{String.class}, "Data");
            invoke(field(activity, "screen"), "selectSection", new Class[]{String.class}, "App"); }); settle();
    }

    private void row() throws Exception {
        openApp();
        View root = activity.getWindow().getDecorView();
        View row = root.findViewWithTag("preference-auto-record-background"); check(row != null, "Start recording in the background row present");
        ViewGroup group = (ViewGroup) row.getParent(); int at = group.indexOfChild(row);
        View previous = null; // the row above, past the divider between rows
        for (int i = at - 1; i >= 0 && previous == null; i--) if (find(group.getChildAt(i), TextView.class) != null) previous = group.getChildAt(i);
        check(previous != null && text(previous, PARENT) != null, "Directly under Record rides automatically");
        check(text(root, "Connection & recording") != null, "In the Connection & recording group");
        TextView title = text(row, TITLE), caption = text(row, CAPTION); Switch toggle = find(row, Switch.class);
        check(title != null && caption != null && toggle != null, "Title, caption (a locked screen counts as background) and switch");
        centre(row);
        fullyShown(title, "Title"); fullyShown(caption, "Caption"); fullyShown(toggle, "Switch");
        Rect t = new Rect(), c = new Rect(), w = new Rect(); title.getGlobalVisibleRect(t); caption.getGlobalVisibleRect(c); toggle.getGlobalVisibleRect(w);
        check(t.right <= w.left && c.right <= w.left && t.bottom <= c.top + 1, "Title, caption and switch do not overlap");
        check(toggle.getHeight() >= activity.ui().dp(48), "48 dp switch target");
        check(!toggle.isChecked() && !toggle.isEnabled() && !prefs().contains(BACKGROUND), "Off by default, greyed while Record rides automatically is off");
        screenshot("row-parent-off");

        prefs().edit().putBoolean(AUTO_RECORD, true).commit(); openApp();
        View on = activity.getWindow().getDecorView().findViewWithTag("preference-auto-record-background"); Switch live = find(on, Switch.class);
        check(live.isEnabled() && !live.isChecked(), "Record rides automatically on: available, still off");
        centre(on);
        main(on::performClick); settle();
        check(live.isChecked() && prefs().getBoolean(BACKGROUND, false), "A tap turns it on and stores it");
        screenshot("row-on");
        prefs().edit().putBoolean(AUTO_RECORD, false).commit(); openApp();
        Switch parked = find(activity.getWindow().getDecorView().findViewWithTag("preference-auto-record-background"), Switch.class);
        check(!parked.isChecked() && !parked.isEnabled() && prefs().getBoolean(BACKGROUND, false), "Parent off again: drawn off and greyed, stored value kept");
        prefs().edit().remove(BACKGROUND).commit();
    }

    private void whatsNew() throws Exception {
        prefs().edit().putBoolean(AUTO_RECORD, true).putString(NOTICE, "due").commit();
        main(() -> invoke(activity, "select", new Class[]{String.class}, "home")); settle();
        await(() -> showing() != null, "The What's new dialog shows on Home");
        takenDownByVolt();
        closedWithOkOnly();
        prefs().edit().putString(NOTICE, "due").commit();
        await(() -> showing() != null, "Due again: the note shows for the switch-then-OK case");
        AlertDialog dialog = showing(); View window = dialog.getWindow().getDecorView();
        check("due".equals(prefs().getString(NOTICE, null)), "Still due while open: only the person closing it ends it");
        String version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        TextView title = text(window, "What's new in Volt " + version), message = text(window, MESSAGE);
        View row = window.findViewWithTag("whats-new-auto-record-background");
        check(title != null && message != null && row != null, "Title with the version, the message and the switch");
        Switch toggle = find(row, Switch.class); TextView label = text(row, TITLE);
        check(toggle != null && label != null && !toggle.isChecked() && toggle.isEnabled(), "The switch, off and available");
        fullyShown(title, "Dialog title"); fullyShown(message, "Dialog message"); fullyShown(label, "Dialog switch label"); fullyShown(toggle, "Dialog switch");
        Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE); fullyShown(ok, "OK button");
        screenshot("whats-new");
        main(row::performClick); settle();
        check(toggle.isChecked() && prefs().getBoolean(BACKGROUND, false), "The dialog's switch stores Start recording in the background");
        check("done".equals(prefs().getString(NOTICE, null)), "Changing the switch in it ends the note");
        screenshot("whats-new-on");
        main(ok::performClick); settle();
        await(() -> showing() == null, "OK closes it");
        SystemClock.sleep(2500); waitForIdleSync();
        check(showing() == null, "Shown once: not again");
        openApp();
        Switch more = find(activity.getWindow().getDecorView().findViewWithTag("preference-auto-record-background"), Switch.class);
        check(more.isChecked() && more.isEnabled(), "App preferences shows the switch as the dialog left it");
    }
    /**
     * The order a review named: the note is open on Home when the service binds again (its listener runs, then rebuild()) or
     * a rebuild runs. Volt takes the dialog down; the note stays due and comes back on the next clear screen, one copy.
     */
    private void takenDownByVolt() throws Exception {
        oneCopy("when it first opens");
        main(() -> { IBinder binder = activity.service().onBind(new Intent(getTargetContext(), ScooterService.class));
            ((ServiceConnection) field(activity, "serviceConnection")).onServiceConnected(new ComponentName(getTargetContext(), ScooterService.class), binder); });
        settle();
        check("due".equals(prefs().getString(NOTICE, null)), "Service bound again while the note was open: still due");
        await(() -> showing() != null, "The note is still there or back after the service bound again");
        oneCopy("after the bind");
        main(() -> invoke(activity, "rebuild", new Class[0]));
        settle();
        check("due".equals(prefs().getString(NOTICE, null)), "A rebuild took it down: still due");
        await(() -> showing() != null, "The note comes back on the next clear screen after a rebuild");
        oneCopy("after the rebuild");
        screenshot("whats-new-back");
    }
    /**
     * The path most owners take: the note due, the switch untouched, OK. The note is done and gone, the switch stays off, and
     * neither a rebuild nor the activity's once-a-second refresh brings it back.
     */
    private void closedWithOkOnly() throws Exception {
        AlertDialog note = showing();
        check(note != null && "due".equals(prefs().getString(NOTICE, null)), "OK-only case: the note open and due");
        Switch untouched = find(note.getWindow().getDecorView().findViewWithTag("whats-new-auto-record-background"), Switch.class);
        check(untouched != null && !untouched.isChecked(), "OK-only case: the switch untouched");
        Button ok = note.getButton(AlertDialog.BUTTON_POSITIVE);
        main(ok::performClick); settle();
        await(() -> showing() == null && !note.isShowing(), "OK closes the note");
        check("done".equals(prefs().getString(NOTICE, null)), "OK on a due note, switch untouched: done");
        check(!prefs().getBoolean(BACKGROUND, false), "OK alone leaves Start recording in the background off");
        main(() -> invoke(activity, "rebuild", new Class[0]));
        settle();
        main(() -> invoke(activity, "automaticRefresh", new Class[0]));
        settle();
        SystemClock.sleep(2500); waitForIdleSync();
        check(showing() == null && copies() == 0, "Closed with OK: not back after a rebuild and the refresh");
        check("done".equals(prefs().getString(NOTICE, null)), "Closed with OK: still done");
    }
    /** Exactly one note window on screen, counted over every window (not just the latest controlDialog), and still one a few refreshes later. */
    private void oneCopy(String when) {
        await(() -> copies() == 1, "One note on screen " + when);
        SystemClock.sleep(2500); waitForIdleSync();
        check(copies() == 1, "Still exactly one note on screen " + when + ", after a few refreshes");
    }
    /** Note dialogs on screen: windows whose content has the note's title. */
    private int copies() {
        if (!windowsRetrieved) {
            android.accessibilityservice.AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            getUiAutomation().setServiceInfo(info); windowsRetrieved = true; SystemClock.sleep(300);
        }
        int count = 0;
        for (android.view.accessibility.AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
            if (root != null && !root.findAccessibilityNodeInfosByText("What's new in Volt").isEmpty()) count++;
        }
        return count;
    }
    private boolean windowsRetrieved;
    private AlertDialog showing() {
        AtomicReference<AlertDialog> found = new AtomicReference<>();
        main(() -> { Object d = field(activity, "controlDialog"); if (d instanceof AlertDialog && ((AlertDialog) d).isShowing()) found.set((AlertDialog) d); });
        return found.get();
    }

    /** Scrolls the row to mid-screen so the floating navigation bar cannot cover it, then checks that. */
    private void centre(View row) {
        main(() -> { View v = row; while (v != null && !(v instanceof ScrollView)) v = v.getParent() instanceof View ? (View) v.getParent() : null;
            check(v != null, "Preference row scrolls"); ScrollView scroll = (ScrollView) v; Rect r = new Rect(); row.getDrawingRect(r); scroll.offsetDescendantRectToMyCoords(row, r);
            scroll.scrollTo(0, Math.max(0, r.centerY() - scroll.getHeight() / 2)); }); settle();
        int[] at = new int[2]; row.getLocationOnScreen(at); int screen = activity.getWindow().getDecorView().getHeight();
        int clearance = activity.getResources().getConfiguration().screenWidthDp >= 600 ? 24 : 140;
        check(at[1] + row.getHeight() <= screen - activity.ui().dp(clearance), "Row clear of the navigation bar and gesture area");
    }
    private void fullyShown(View view, String what) {
        Rect r = new Rect(); check(view.isShown() && view.getGlobalVisibleRect(r) && r.width() == view.getWidth() && r.height() == view.getHeight(), what + " fully visible, not clipped");
        if (view instanceof TextView) { Layout layout = ((TextView) view).getLayout(); check(layout != null && layout.getEllipsisCount(layout.getLineCount() - 1) == 0 && layout.getHeight() <= view.getHeight() - view.getPaddingTop() - view.getPaddingBottom() + 1, what + " text not ellipsized or cut"); }
    }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "background-recording-validation"); dir.mkdirs();
        File file = new File(dir, args.getString("variant", "background-recording") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
    private SharedPreferences prefs() { return getTargetContext().getSharedPreferences("app_settings", 0); }
    private void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private void await(BooleanSupplier test, String why) { long end = SystemClock.elapsedRealtime() + 15000; while (SystemClock.elapsedRealtime() < end) { if (test.getAsBoolean()) { checks++; return; } SystemClock.sleep(50); } throw new AssertionError(why); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(350); waitForIdleSync(); }
    private void main(Runnable action) { AtomicReference<Throwable> failure = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { failure.set(e); } }); if (failure.get() != null) throw new IllegalStateException(failure.get()); }
    private static TextView text(View view, String label) { if (view instanceof TextView && label.contentEquals(((TextView) view).getText()) && view.isShown()) return (TextView) view; if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView r = text(((ViewGroup) view).getChildAt(i), label); if (r != null) return r; } return null; }
    private static <T> T find(View view, Class<T> type) { if (type.isInstance(view)) return type.cast(view); if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { T r = find(((ViewGroup) view).getChildAt(i), type); if (r != null) return r; } return null; }
    private static Object field(Object target, String name) { try { Class<?> c = target.getClass(); while (c != null) { try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException(name, e); } }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... values) { try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, values); } catch (Exception e) { throw new IllegalStateException(name, e); } }
}
