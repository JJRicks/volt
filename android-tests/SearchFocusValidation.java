package com.jjricks.volt.tests;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.BleController;
import com.jjricks.volt.DashboardConfig;
import com.jjricks.volt.DashboardScreen;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterService;
import com.jjricks.volt.StatsScreen;
import com.jjricks.volt.Telemetry;

/** Real IME and touch checks on a fresh emulator; no scooter or radio changes. */
public final class SearchFocusValidation extends Instrumentation {
    private static final String[] PREFS = {"app_settings", "profiles", "scooter", "pending_documents", "onboarding"};
    private final List<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    private MainActivity activity;
    private boolean admitted;
    private Bundle arguments;
    private int keyboardRequests;
    private Rect lastTapScreenBounds = new Rect();
    /** Each window's SearchFocus observer, kept before a touch trace wraps the window callback. */
    private final java.util.Map<Window, Object> observers = new java.util.WeakHashMap<>();
    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            requireFreshEmulator(); admitted = true;
            android.accessibilityservice.AccessibilityServiceInfo accessibility = getUiAutomation().getServiceInfo();
            accessibility.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            getUiAutomation().setServiceInfo(accessibility);
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putString("theme", "dark").commit(), "Could not prepare emulator preferences");
            // These runners check the tabs, so skip the first-run onboarding a fresh install shows.
            OnboardingScreen.markDone(getTargetContext());
            activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, 6000, "Workspace did not initialize");
            for (String theme : new String[] {"dark", "light"}) {
                main(() -> settings().edit().putString("theme", theme).apply());
                await(() -> activity.ui().dark == theme.equals("dark"), 4000, "Theme did not apply");
                controlsChecks(theme); statsChecks(theme); dialogChecks(theme); assertNoScooterWork();
            }
            result.putString("result", "PASS"); result.putStringArrayList("checks", new ArrayList<>(checks));
            result.putStringArrayList("screenshots", new ArrayList<>(screenshots)); result.putInt("check_count", checks.size());
            result.putBoolean("actual_ime_visible", true); result.putInt("scooter_mutations", 0); cleanup(); finish(0, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            result.putStringArrayList("completed_checks", new ArrayList<>(checks));
            try { if (admitted && activity != null && !activity.isDestroyed()) screenshot("failure"); } catch (Throwable ignored) { }
            result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            try { cleanup(); } catch (Throwable failure) { result.putString("cleanup_error", failure.toString()); }
            finish(1, result);
        }
    }
    private void controlsChecks(String theme) {
        clickDescription("Scooter"); Object controls = screen(); EditText search = (EditText)field(controls, "search");
        showKeyboard(search, activity.getWindow(), "speed"); tap((View)field(controls, "results"), activity.getWindow());
        assertDismissed(search, activity.getWindow(), "speed", "Outside results tap");
        require(screen() == controls, "An inert outside tap navigated away from settings");
        showKeyboard(search, activity.getWindow(), "speed"); sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        assertDismissed(search, activity.getWindow(), "speed", "System Back");
        require(screen() == controls, "Hiding the keyboard also navigated away");
        showKeyboard(search, activity.getWindow(), "speed"); tap(findDescription(root(), "Live Stats"), activity.getWindow());
        await(() -> screen() instanceof StatsScreen && !imeVisible(activity.getWindow()), 4000,
                "Outside navigation tap was swallowed or left the keyboard open");
        checks.add(theme + ": outside taps and system Back clear settings search focus, preserve the filter, and deliver navigation actions");
    }
    private void statsChecks(String theme) {
        // Wide Stats shows Readings in its own pane (no tab) and scrolls that pane rather than the page.
        boolean wide = Boolean.TRUE.equals(field(screen(), "wide"));
        if (!wide) clickText(root(), "Readings");
        Object stats = screen(); EditText search = (EditText)field(stats, "search");
        showKeyboard(search, activity.getWindow(), "battery");
        // The Favourites filter is gone; a visible reading's star is the outside action instead.
        final View[] starView = {null}; final String[] starKey = {null};
        main(() -> { for (Object row : ((java.util.Map<?, ?>)field(stats, "rows")).values()) {
            View candidate = (View)field(row, "star");
            if (candidate.isShown()) { starView[0] = candidate; starKey[0] = (String)field(row, "key"); break; } } });
        require(starView[0] != null, "No visible Stats favourite star");
        boolean original = favourite(starKey[0]); tap(starView[0], activity.getWindow());
        assertDismissed(search, activity.getWindow(), "battery", "Stats favourite tap");
        require(favourite(starKey[0]) != original, "Dismissing search consumed the favourite action");
        if (favourite(starKey[0]) != original) click(starView[0]);
        showKeyboard(search, activity.getWindow(), "battery"); ScrollView scroll = (ScrollView)field(wide ? stats : activity, wide ? "readingsScroll" : "scroll");
        int before = scroll.getScrollY(); Rect bounds = new Rect(), fieldBounds = new Rect();
        main(() -> { require(scroll.getGlobalVisibleRect(bounds), "Stats scroller is not visible");
            require(search.getGlobalVisibleRect(fieldBounds), "Stats search is not visible"); });
        // Start above the scroller's bottom padding: the floating navigation bar sits over it and takes touches there.
        float x = bounds.centerX(), start = bounds.bottom - scroll.getPaddingBottom() - activity.ui().dp(18);
        float end = Math.max(fieldBounds.bottom + activity.ui().dp(18), start - activity.ui().dp(120));
        require(start - end > activity.ui().dp(25), "No outside-search area available for a real swipe");
        long now = SystemClock.uptimeMillis();
        inject(now, MotionEvent.ACTION_DOWN, x, start);
        for (int i = 1; i <= 10; i++) { inject(now, MotionEvent.ACTION_MOVE, x, start + (end - start) * i / 10f); SystemClock.sleep(16); }
        inject(now, MotionEvent.ACTION_UP, x, end);
        assertDismissed(search, activity.getWindow(), "battery", "Outside swipe");
        try { await(() -> scroll.getScrollY() > before, 3000, "Dismissing search consumed the scrolling gesture"); }
        catch (IllegalStateException failed) {
            AtomicReference<String> why = new AtomicReference<>();
            main(() -> why.set("scrollY " + before + "->" + scroll.getScrollY() + ", content " + scroll.getChildAt(0).getHeight() + " in " + scroll.getHeight()
                    + " (padding " + scroll.getPaddingTop() + "/" + scroll.getPaddingBottom() + ")"));
            throw new IllegalStateException(failed.getMessage() + ": " + why.get());
        }
        checks.add(theme + ": measurement search preserves its query while outside taps and swipes still filter and scroll");
    }
    private void dialogChecks(String theme) throws Exception {
        clickDescription("Home"); await(() -> screen() instanceof DashboardScreen, 4000, "Dashboard did not appear");
        DashboardScreen dashboard = (DashboardScreen)screen();
        if (!dashboard.isEditing()) clickDescription("Edit dashboard");
        Object tile = liveTile(dashboard); final DashboardConfig.Widget widget = (DashboardConfig.Widget)field(tile, "widget");
        click(findDescription((View)tile, "Options for " + widget.title)); clickText(dialogRoot(dashboard), "Parameter");
        AlertDialog picker = dialog(dashboard); EditText search = first(picker.getWindow().getDecorView(), EditText.class);
        showKeyboard(search, picker.getWindow(), "voltage");
        AtomicReference<WindowTouchTrace> trace = new AtomicReference<>();
        main(() -> trace.set(new WindowTouchTrace(picker.getWindow())));
        try {
            tap(findText(picker.getWindow().getDecorView(), "Choose parameter"), picker.getWindow());
            assertDismissed(search, picker.getWindow(), "voltage", "Dialog title tap");
        } catch (IllegalStateException error) {
            throw new IllegalStateException(error.getMessage() + "; " + trace.get().describe(), error);
        } finally { main(() -> trace.get().restore()); }
        require(picker.isShowing(), "An inert title tap dismissed the parameter picker");
        showKeyboard(search, picker.getWindow(), "voltage");
        require(search.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                && ((ViewGroup.MarginLayoutParams)search.getLayoutParams()).bottomMargin >= activity.ui().dp(12),
                "Parameter search lacks its expected inert bottom margin");
        int margin = ((ViewGroup.MarginLayoutParams)search.getLayoutParams()).bottomMargin;
        main(() -> trace.set(new WindowTouchTrace(picker.getWindow())));
        try {
            tap(search, picker.getWindow(), margin);
            assertDismissed(search, picker.getWindow(), "voltage", "Dialog search margin tap");
            require(picker.isShowing(), "A tap below search dismissed the parameter picker; tapped="
                    + lastTapScreenBounds + ", metric=" + widget.metric);
        } catch (IllegalStateException error) {
            throw new IllegalStateException(error.getMessage() + "; " + trace.get().describe(), error);
        } finally { main(() -> trace.get().restore()); }
        showKeyboard(search, picker.getWindow(), "voltage"); sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        assertDismissed(search, picker.getWindow(), "voltage", "Dialog system Back");
        require(picker.isShowing(), "Hiding the picker keyboard also dismissed the dialog");
        showKeyboard(search, picker.getWindow(), "voltage"); tap(findText(picker.getWindow().getDecorView(), metricLabel("voltage_v")), picker.getWindow());
        await(() -> !picker.isShowing() && widget.metric.equals("voltage_v") && !imeVisible(activity.getWindow()), 4000,
                "Outside parameter tap was swallowed or did not save the selection");
        checks.add(theme + ": IME-panned picker clears focus from title and search-margin taps without clearing its query or consuming a selection");

        tile = liveTile(dashboard); DashboardConfig.Widget current = (DashboardConfig.Widget)field(tile, "widget");
        // The title is an inline field in the widget options sheet; it saves when the sheet closes.
        String originalTitle = current.title;
        click(findDescription((View)tile, "Options for " + current.title));
        AlertDialog title = dialog(dashboard); EditText ordinary = first(title.getWindow().getDecorView(), EditText.class);
        showKeyboard(ordinary, title.getWindow(), "Ordinary title form"); tap(findText(title.getWindow().getDecorView(), "Title"), title.getWindow());
        waitForIdleSync(); SystemClock.sleep(180);
        main(() -> require(ordinary.hasFocus() && "Ordinary title form".contentEquals(ordinary.getText()),
                "Search-only dismissal changed focus or text in an ordinary title form"));
        screenshot(theme + "-title-form"); main(() -> ordinary.setText(originalTitle));
        click(findDescription(title.getWindow().getDecorView(), "Close widget options"));
        main(() -> getTargetContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root().getWindowToken(), 0));
        await(() -> !imeVisible(activity.getWindow()), 4000, "Form keyboard did not close"); clickText(root(), "Done");
        checks.add(theme + ": ordinary widget title editing retains its normal focus and text behavior");
    }
    private void showKeyboard(EditText search, Window window, String text) {
        require(search != null, "Search field is missing");
        String stage = "IME request #" + (++keyboardRequests) + " in "
                + (window == activity.getWindow() ? screen().getClass().getSimpleName() : "widget dialog")
                + " (" + text + ")";
        // A loaded emulator restarts a System UI that stopped responding; its windows take focus for seconds.
        await(() -> window.getDecorView().hasWindowFocus() && search.isAttachedToWindow() && search.isShown(),
                15000, stage + ": input window did not gain focus");
        awaitKeyboardSettled(window, stage);
        main(() -> search.requestRectangleOnScreen(new Rect(0, 0, search.getWidth(), search.getHeight()), true)); waitForIdleSync();
        awaitStableInputBounds(search, window, stage);
        // Touch focus follows the same path as the user. Calling requestFocus and
        // showSoftInput together can race IMM after the neutral root held focus.
        AtomicReference<WindowTouchTrace> focusTrace = new AtomicReference<>();
        AtomicReference<View.OnFocusChangeListener> previousFocusListener = new AtomicReference<>();
        ArrayList<String> focusChanges = new ArrayList<>();
        main(() -> {
            focusTrace.set(new WindowTouchTrace(window));
            previousFocusListener.set(search.getOnFocusChangeListener());
            search.setOnFocusChangeListener((view, focused) -> {
                ArrayList<String> appStack = new ArrayList<>();
                for (StackTraceElement frame : Thread.currentThread().getStackTrace())
                    if (frame.getClassName().startsWith("com.jjricks.volt")) appStack.add(frame.toString());
                focusChanges.add("focused=" + focused + ", ime=" + imeVisible(window) + ", via=" + appStack);
                if (previousFocusListener.get() != null) previousFocusListener.get().onFocusChange(view, focused);
            });
        });
        try { tap(search, window); await(search::hasFocus, 3000, stage + ": tapping the field did not focus it"); }
        catch (IllegalStateException error) {
            throw new IllegalStateException(error.getMessage() + "; " + keyboardDiagnostics(search, window)
                    + "; " + focusTrace.get().describe() + "; focusChanges=" + focusChanges, error);
        } finally { main(() -> { focusTrace.get().restore(); search.setOnFocusChangeListener(previousFocusListener.get()); }); }
        waitForIdleSync(); SystemClock.sleep(100);
        main(() -> {
            search.setText(text); search.setSelection(search.length());
            InputMethodManager keyboard = getTargetContext().getSystemService(InputMethodManager.class);
            require(keyboard != null, "No input method service"); keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        });
        try {
            // Under load Android can stall a show (its keyboard window not drawn yet) and then abort it without
            // retrying, while the client still counts the keyboard as requested and drops a repeated show. The
            // app never shows the keyboard itself, so while the field keeps its focus withdraw that request
            // (a hide of a keyboard that never appeared changes nothing the app observes) and ask again.
            for (int request = 1; ; request++) {
                try { await(() -> search.hasFocus() && imeVisible(window), 8000,
                        stage + ": software keyboard was not shown; enable the emulator software keyboard for this validation"); break; }
                catch (IllegalStateException missing) {
                    AtomicReference<Boolean> focused = new AtomicReference<>(false); main(() -> focused.set(search.hasFocus()));
                    if (request == 3 || !focused.get()) throw missing;
                    InputMethodManager keyboard = getTargetContext().getSystemService(InputMethodManager.class);
                    main(() -> keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0)); awaitKeyboardSettled(window, stage);
                    main(() -> require(search.hasFocus(), stage + ": withdrawing a keyboard that never appeared cleared search focus"));
                    main(() -> keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT));
                }
            }
            // Visible insets can precede the keyboard window; Back must reach a keyboard that exists.
            await(() -> { for (android.view.accessibility.AccessibilityWindowInfo item : getUiAutomation().getWindows())
                if (item.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true; return false; },
                    5000, stage + ": keyboard window did not appear");
        } catch (IllegalStateException error) { throw new IllegalStateException(error.getMessage() + "; " + keyboardDiagnostics(search, window), error); }
        waitForIdleSync(); awaitStableInputBounds(search, window, stage + " after IME");
    }
    private void awaitStableInputBounds(EditText search, Window window, String stage) {
        Rect previous = new Rect(); final long[] since = {SystemClock.elapsedRealtime()}; final int[] previousIme = {-2};
        await(() -> {
            Rect next = screenBounds(search); WindowInsets insets = window.getDecorView().getRootWindowInsets();
            // The IME inset still moving means its show/hide animation (and any pan) is in flight.
            int ime = insets == null ? -1 : insets.getInsets(WindowInsets.Type.ime()).bottom;
            boolean steady = next.equals(previous) && ime == previousIme[0] && !next.isEmpty() && window.getDecorView().hasWindowFocus()
                    && !window.getDecorView().isLayoutRequested();
            if (!steady) since[0] = SystemClock.elapsedRealtime();
            previous.set(next); previousIme[0] = ime;
            return steady && SystemClock.elapsedRealtime() - since[0] >= 300;
        }, 4000, stage + ": input bounds or window focus did not settle");
    }
    private Rect screenBounds(View view) {
        Rect visible = new Rect();
        if (!view.getLocalVisibleRect(visible)) return new Rect();
        int[] origin = new int[2]; view.getLocationOnScreen(origin);
        visible.offset(origin[0], origin[1]); return visible;
    }
    private String keyboardDiagnostics(EditText search, Window window) {
        AtomicReference<String> details = new AtomicReference<>();
        main(() -> {
            View focus = window.getCurrentFocus(), decor = window.getDecorView();
            InputMethodManager keyboard = getTargetContext().getSystemService(InputMethodManager.class);
            ArrayList<String> windows = new ArrayList<>();
            for (android.view.accessibility.AccessibilityWindowInfo item : getUiAutomation().getWindows())
                windows.add(item.getType() + ":" + item.getTitle());
            details.set("searchBounds=" + screenBounds(search) + ", tappedBounds=" + lastTapScreenBounds
                    + ", tapped=[" + lastTapScreenBounds.centerX() + "," + lastTapScreenBounds.centerY() + "]"
                    + ", decorBounds=" + screenBounds(decor)
                    + ", windowCallback=" + (window.getCallback() == null ? "null" : window.getCallback().getClass().getName())
                    + ", currentFocus=" + (focus == null ? "null" : focus.getClass().getSimpleName() + ":" + focus.getContentDescription())
                    + ", windowFocus=" + decor.hasWindowFocus() + ", imeVisible=" + imeVisible(window)
                    + ", immActive=" + (keyboard != null && keyboard.isActive(search))
                    + ", fieldFocus=" + search.hasFocus() + ", enabled=" + search.isEnabled()
                    + ", focusable=" + search.isFocusable() + "/" + search.isFocusableInTouchMode()
                    + ", attached=" + search.isAttachedToWindow() + ", selection=[" + search.getSelectionStart() + "," + search.getSelectionEnd() + "]"
                    + ", searchCoordinates=" + coordinateDiagnostics(search)
                    + ", decorCoordinates=" + coordinateDiagnostics(decor)
                    + ", softInputMode=" + window.getAttributes().softInputMode
                    + ", accessibilityWindows=" + windows);
        });
        return details.get();
    }
    private String coordinateDiagnostics(View view) {
        int[] screen = new int[2], inWindow = new int[2];
        view.getLocationOnScreen(screen); view.getLocationInWindow(inWindow);
        Rect global = new Rect(), frame = new Rect(), accessible = new Rect();
        view.getGlobalVisibleRect(global); view.getWindowVisibleDisplayFrame(frame);
        android.view.accessibility.AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        try { node.getBoundsInScreen(accessible); } finally { node.recycle(); }
        return "screen=[" + screen[0] + "," + screen[1] + "], window=[" + inWindow[0] + "," + inWindow[1]
                + "], global=" + global + ", accessible=" + accessible + ", frame=" + frame
                + ", rootScroll=" + view.getRootView().getScrollX() + "," + view.getRootView().getScrollY();
    }
    /** Insets report a hide's end state when it starts, and under load that hide was seen finishing 10 s later.
     *  A show requested meanwhile is aborted by Android and the app rightly blurs its field, so begin each
     *  focus session only once the app's IME animation tracker is idle and a hidden keyboard's window is gone. */
    private void awaitKeyboardSettled(Window window, String stage) {
        main(() -> { if (window.getCallback() != null && window.getCallback().getClass().getName().endsWith("SearchFocus$TouchObserver"))
            observers.put(window, window.getCallback()); });
        await(() -> {
            Object observer = observers.get(window), animations = observer == null ? null : field(observer, "imeAnimations");
            try {
                if (animations != null) {
                    java.lang.reflect.Method running = animations.getClass().getDeclaredMethod("running"); running.setAccessible(true);
                    if ((Boolean)running.invoke(animations)) return false;
                }
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot inspect IME animations", error); }
            if (imeVisible(window)) return true;
            for (android.view.accessibility.AccessibilityWindowInfo item : getUiAutomation().getWindows())
                if (item.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) return false;
            return true;
        }, 12000, stage + ": the keyboard's show/hide transition did not finish");
    }
    private boolean imeVisible(Window window) {
        WindowInsets insets = window.getDecorView().getRootWindowInsets();
        return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }
    private void assertDismissed(EditText input, Window window, String text, String action) {
        try { await(() -> !input.hasFocus() && !imeVisible(window), 4000, action + " did not hide keyboard and clear search focus"); }
        catch (IllegalStateException error) {
            throw new IllegalStateException(error.getMessage() + "; " + keyboardDiagnostics(input, window), error);
        }
        awaitKeyboardSettled(window, action);
        main(() -> require(text.contentEquals(input.getText()), action + " changed the search text to \"" + input.getText() + "\""));
    }
    private boolean favourite(String key) {
        return settings().getStringSet("stats_favourites", java.util.Collections.<String>emptySet()).contains(key);
    }
    private void tap(View view, Window window) { tap(view, window, 0); }
    /** Taps the view's centre or, with below > 0, the middle of that strip under it. The window must
     *  receive the press inside that target in its own coordinates, which an IME pan cannot shift: a
     *  press that landed elsewhere tested nothing (a stale pan offset). It is measured and sent again
     *  only if the miss left the case's preconditions intact (same focus, keyboard and text, and no
     *  other control under the press); otherwise the retry would test a different scene, so it fails. */
    private void tap(View view, Window window, int below) {
        require(view != null, "Missing touch target");
        for (int attempt = 1; ; attempt++) {
            Rect rect = nativeScreenBounds(view);
            require(rect.width() > 0 && rect.height() > 0, "Touch target is not visible");
            float x = rect.centerX(), y = below > 0 ? rect.bottom + below / 2f : rect.centerY();
            lastTapScreenBounds = below > 0 ? new Rect((int)x, (int)y, (int)x + 1, (int)y + 1) : new Rect(rect);
            Rect target = new Rect(); AtomicReference<WindowTouchTrace> trace = new AtomicReference<>(); AtomicReference<String> before = new AtomicReference<>();
            main(() -> {
                before.set(scene(window));
                int[] at = new int[2]; view.getLocationInWindow(at);
                target.set(at[0], at[1] + (below > 0 ? view.getHeight() : 0), at[0] + view.getWidth(), at[1] + view.getHeight() + below);
                trace.set(new WindowTouchTrace(window));
            });
            try {
                long now = SystemClock.uptimeMillis(); inject(now, MotionEvent.ACTION_DOWN, x, y);
                inject(now, MotionEvent.ACTION_UP, x, y); waitForIdleSync();
            } finally { main(() -> trace.get().restore()); }
            float[] down = trace.get().firstDown();
            if (down != null && target.contains((int)down[0], (int)down[1])) return;
            AtomicReference<String> changed = new AtomicReference<>();
            main(() -> {
                View focus = window.getCurrentFocus(), hit = down == null ? null : controlAt(window.getDecorView(), (int)down[0], (int)down[1]);
                String after = scene(window);
                if (!after.equals(before.get())) changed.set("before: " + before.get() + "; after: " + after);
                else if (hit != null && hit != focus) changed.set("it pressed another control: " + hit.getClass().getSimpleName()
                        + (hit.getContentDescription() == null ? "" : " \"" + hit.getContentDescription() + "\""));
            });
            require(changed.get() == null, "Touch at screen [" + x + "," + y + "] missed window target " + target
                    + " and changed the case's preconditions, so it cannot be retried (" + changed.get() + "); " + trace.get().describe());
            require(attempt < 3, "Touch at screen [" + x + "," + y + "] missed window target " + target + " three times"
                    + (down == null ? " (no press reached this window: another window, such as a keyboard hint, covered it)" : "") + "; " + trace.get().describe());
        }
    }
    /** What a stray press can disturb: the focused view, the keyboard and the focused field's text. */
    private String scene(Window window) {
        View focus = window.getCurrentFocus();
        return "focus=" + (focus == null ? "none" : focus.getClass().getSimpleName() + "@" + System.identityHashCode(focus))
                + " ime=" + imeVisible(window) + (focus instanceof TextView ? " text=\"" + ((TextView)focus).getText() + "\"" : "");
    }
    /** The topmost shown clickable view under a window-local point, or null when only inert content is there. */
    private static View controlAt(View view, int x, int y) {
        if (!view.isShown()) return null;
        int[] at = new int[2]; view.getLocationInWindow(at);
        if (x < at[0] || y < at[1] || x >= at[0] + view.getWidth() || y >= at[1] + view.getHeight()) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) { View hit = controlAt(group.getChildAt(i), x, y); if (hit != null) return hit; }
        }
        return view.isClickable() || view.isLongClickable() ? view : null;
    }
    private Rect nativeScreenBounds(View view) {
        // The accessibility server applies an IME pan's surface move asynchronously, so one reading
        // can be a pan behind. Use bounds that repeat across reads instead of the first one.
        Rect previous = nativeScreenBoundsOnce(view);
        for (long end = SystemClock.elapsedRealtime() + 3000; SystemClock.elapsedRealtime() < end; ) {
            SystemClock.sleep(100); Rect next = nativeScreenBoundsOnce(view);
            if (next.equals(previous)) return next;
            previous = next;
        }
        return previous;
    }
    private Rect nativeScreenBoundsOnce(View view) {
        // injectInputEvent also waits for these animations, but doing it only
        // after selecting coordinates can move the target underneath the tap.
        try { getUiAutomation().getClass().getMethod("syncInputTransactions").invoke(getUiAutomation()); }
        catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Emulator cannot synchronize native touch coordinates", error);
        }
        ArrayList<android.view.accessibility.AccessibilityNodeInfo> candidates = new ArrayList<>();
        Rect visible = new Rect();
        AtomicReference<Boolean> ime = new AtomicReference<>(false);
        main(() -> {
            visible.set(screenBounds(view));
            WindowInsets insets = view.getRootView().getRootWindowInsets();
            ime.set(insets != null && insets.isVisible(WindowInsets.Type.ime()));
            if (!ime.get()) return;
            View candidate = view;
            while (true) {
                candidates.add(candidate.createAccessibilityNodeInfo());
                if (!(candidate.getParent() instanceof View)) break;
                candidate = (View)candidate.getParent();
            }
        });
        // Once IME is hidden the view's screen origin is correct again. The
        // accessibility service can retain its previous pan transform briefly.
        if (!ime.get()) return visible;
        // An IME surface pan can change screen coordinates without a view-layout
        // event. Do not reuse the accessibility cache from the previous IME state.
        if (Build.VERSION.SDK_INT >= 33) getUiAutomation().clearCache();
        android.view.accessibility.AccessibilityNodeInfo root = null;
        int wantedWindow = candidates.get(0).getWindowId();
        for (android.view.accessibility.AccessibilityWindowInfo window : getUiAutomation().getWindows())
            if (window.getId() == wantedWindow) { root = window.getRoot(); break; }
        Rect actual = new Rect();
        try {
            require(root != null, "Touch target's accessibility window is unavailable: " + wantedWindow);
            for (android.view.accessibility.AccessibilityNodeInfo expected : candidates) {
                if (!findNativeBounds(root, expected, actual)) continue;
                Rect reported = new Rect(); expected.getBoundsInScreen(reported);
                // Server bounds include IME surface pan. An inert container may be
                // merged out of accessibility, so its same-window ancestor supplies
                // the translation while the target's original visible region is kept.
                visible.offset(actual.left - reported.left, actual.top - reported.top);
                return visible;
            }
            throw new IllegalStateException("No accessible same-window ancestor for touch target " + candidates.get(0).getText());
        } finally {
            if (root != null) root.recycle();
            for (android.view.accessibility.AccessibilityNodeInfo candidate : candidates) candidate.recycle();
        }
    }
    private boolean findNativeBounds(android.view.accessibility.AccessibilityNodeInfo node,
            android.view.accessibility.AccessibilityNodeInfo wanted, Rect output) {
        if (node.equals(wanted)) { node.getBoundsInScreen(output); return !output.isEmpty(); }
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) try { if (findNativeBounds(child, wanted, output)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }
    private void inject(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(getUiAutomation().injectInputEvent(event, true), "Emulator rejected a touch event"); } finally { event.recycle(); }
    }
    private void requireFreshEmulator() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Search validation refuses physical phones");
        require(Build.VERSION.SDK_INT >= 31, "Use Android 12 or newer");
        for (String key : PREFS) require(getTargetContext().getSharedPreferences(key, Context.MODE_PRIVATE).getAll().isEmpty(),
                "Fresh emulator app data required; existing preferences refused");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Fresh emulator files and databases required");
        require(getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
                "Grant Nearby devices externally on the fresh emulator");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be off; validation never changes radio state");
    }
    private void assertNoScooterWork() {
        main(() -> {
            ScooterService service = activity.service();
            require(service != null && service.profiles.all().isEmpty() && !service.isRecording() && !service.phoneSaving()
                    && service.ble.state() == BleController.State.DISCONNECTED, "Unexpected scooter or recording work");
            require(((Number)field(service.ble, "mutationSequence")).longValue() == 0 && service.ble.commandResults().isEmpty(), "Unexpected scooter write");
        });
    }
    private Object liveTile(DashboardScreen dashboard) {
        for (Object tile : (List<?>)field(dashboard, "tiles"))
            if (((DashboardConfig.Widget)field(tile, "widget")).type.equals("live")) return tile;
        // The redesigned default Home has no live trend; add one explicitly for the parameter-picker checks.
        main(() -> {
            DashboardConfig config = (DashboardConfig)field(dashboard, "config");
            DashboardConfig.Widget live = new DashboardConfig.Widget("live");
            int end = 0;
            for (DashboardConfig.Widget widget : config.widgets)
                for (com.jjricks.volt.DashboardLayout.Cell cell : widget.layouts.values()) end = Math.max(end, cell.y + cell.h);
            for (int columns = 1; columns <= DashboardConfig.MAX_COLUMNS; columns++)
                live.layouts.put(columns, new com.jjricks.volt.DashboardLayout.Cell(live.id, 0, end, columns, live.defaultHeight()));
            config.widgets.add(live); config.save();
            try {
                java.lang.reflect.Method render = DashboardScreen.class.getDeclaredMethod("render");
                render.setAccessible(true); render.invoke(dashboard);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Could not render seeded live widget", error); }
        });
        waitForIdleSync();
        for (Object tile : (List<?>)field(dashboard, "tiles"))
            if (((DashboardConfig.Widget)field(tile, "widget")).type.equals("live")) return tile;
        throw new IllegalStateException("Seeded live widget is missing");
    }
    private AlertDialog dialog(Object owner) {
        AlertDialog value = (AlertDialog)field(owner, "dialog"); require(value != null && value.isShowing(), "Widget dialog is missing"); return value;
    }
    private View dialogRoot(Object owner) { return dialog(owner).getWindow().getDecorView(); }
    private Object screen() { return field(activity, "screen"); }
    private View root() { return (View)field(activity, "root"); }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private String metricLabel(String key) { for (Telemetry.Metric m : new Telemetry().metrics()) if (m.key.equals(key)) return m.label; throw new IllegalStateException(key); }
    private void clickDescription(String name) { click(findDescription(root(), name)); }
    private void clickText(View root, String text) { click(findText(root, text)); }
    private void click(View target) {
        require(target != null, "UI action is missing"); View candidate = target;
        while (!candidate.isClickable() && candidate.getParent() instanceof View) candidate = (View)candidate.getParent();
        View action = candidate; require(action.isClickable() && action.isEnabled(), "UI action is disabled");
        main(() -> { action.requestRectangleOnScreen(new Rect(0, 0, action.getWidth(), action.getHeight()), true);
            require(action.performClick(), "UI action did not dispatch"); }); waitForIdleSync();
    }
    private View findDescription(View view, String name) {
        if (name.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View found = findDescription(((ViewGroup)view).getChildAt(i), name); if (found != null) return found;
        }
        return null;
    }
    private TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView)view).getText()) && view.isShown()) return (TextView)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup)view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private <T extends View> T first(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            T found = first(((ViewGroup)view).getChildAt(i), type); if (found != null) return found;
        }
        return null;
    }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        File folder = new File(getTargetContext().getExternalFilesDir(null), "search-validation"); require(folder.isDirectory() || folder.mkdirs(), "Cannot create screenshot directory");
        String variant = arguments.getString("variant", "search").replaceAll("[^A-Za-z0-9_-]", ""); File file = new File(folder, variant + "-" + name + ".png");
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        finally { bitmap.recycle(); } screenshots.add(file.getAbsolutePath());
    }
    private void cleanup() {
        if (!admitted) return;
        if (activity != null && !activity.isDestroyed()) { main(activity::finish); await(activity::isDestroyed, 4000, "Activity did not finish"); }
        main(() -> { ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); waitForIdleSync(); admitted = false;
    }
    private void await(BooleanSupplier condition, long timeout, String message) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) { AtomicReference<Boolean> result = new AtomicReference<>(false);
            main(() -> result.set(condition.getAsBoolean())); if (result.get()) return; SystemClock.sleep(40); }
        throw new IllegalStateException(message);
    }
    private void main(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new IllegalStateException(error.get().getMessage(), error.get());
    }
    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot inspect " + name, error); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
