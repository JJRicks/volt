package com.jjricks.volt;

import android.app.Activity;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The on-screen short-ride message with Undo (owner, Nov 24 2026): "Ride not saved, too short" and an Undo button for
 * about {@link #SHOW_MS} (longer where Android's accessibility timeout asks), at the window's foot over the page and the
 * dock. It sits in the window's decor, so a page rebuild underneath doesn't remove it. The words wrap at spaces only, never
 * inside a word, and the button never shrinks or wraps: the words give way first (narrow label ladder).
 */
final class ShortRideUndoBar {
    /** How long the bar shows. */
    static final long SHOW_MS = 8_000;
    static final String TAG = "short_ride_undo", BUTTON_TAG = "short_ride_undo_button";
    private final Activity activity;
    private final Ui ui;
    private View bar;
    private String rideId;
    private final Runnable hide = this::dismiss;

    ShortRideUndoBar(Activity activity, Ui ui) { this.activity = activity; this.ui = ui; }

    /** The ride the bar shows now, or null. */
    String showing() { return bar == null ? null : rideId; }

    /** How long a bar offered at offeredAt (SystemClock.elapsedRealtime) still shows: SHOW_MS, or Android's longer accessibility timeout. */
    static long showFor(Activity activity, long offeredAt) {
        long length = SHOW_MS;
        if (Build.VERSION.SDK_INT >= 29) {
            AccessibilityManager a11y = activity.getSystemService(AccessibilityManager.class);
            if (a11y != null) length = Math.max(length, a11y.getRecommendedTimeoutMillis((int) SHOW_MS,
                    AccessibilityManager.FLAG_CONTENT_TEXT | AccessibilityManager.FLAG_CONTENT_CONTROLS));
        }
        return length - (SystemClock.elapsedRealtime() - offeredAt);
    }

    /** Shows the bar for rideId for showMs, replacing any bar already up; bottomPx keeps it clear of the dock (its height, the navigation bar's included). */
    void show(String rideId, String words, String undoLabel, long showMs, int bottomPx, Runnable undo) {
        dismiss();
        if (showMs <= 0 || activity.isFinishing()) return;
        this.rideId = rideId;
        LinearLayout row = new LinearLayout(activity);
        row.setTag(TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(16), ui.dp(6), ui.dp(6), ui.dp(6));
        row.setMinimumHeight(ui.dp(56));
        row.setBackground(ui.shape(ui.raised, 16, ui.outline));
        row.setElevation(ui.dp(6));
        row.setClickable(true);
        row.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView text = ui.text(words, 14, false);
        text.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        text.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        text.setPadding(0, ui.dp(8), ui.dp(8), ui.dp(8));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView button = ui.secondaryButton(undoLabel, () -> { dismiss(); if (undo != null) undo.run(); });
        button.setTag(BUTTON_TAG);
        button.setSingleLine(true);
        button.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        row.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int side = ui.dp(12);
        View decor = activity.getWindow().getDecorView();
        int window = decor.getWidth() > 0 ? decor.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(window - 2 * side, ui.dp(560));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = Math.max(bottomPx, navigationInset()) + ui.dp(12);
        ((ViewGroup) decor).addView(row, params);
        bar = row;
        row.postDelayed(hide, showMs);
    }

    void dismiss() {
        View shown = bar;
        bar = null; rideId = null;
        if (shown == null) return;
        shown.removeCallbacks(hide);
        if (shown.getParent() instanceof ViewGroup) ((ViewGroup) shown.getParent()).removeView(shown);
    }

    private int navigationInset() {
        WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
        if (insets == null) return 0;
        if (Build.VERSION.SDK_INT >= 30) return insets.getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.ime()).bottom;
        return insets.getSystemWindowInsetBottom();
    }
}
