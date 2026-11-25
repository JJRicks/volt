package com.jjricks.volt;

import android.animation.Animator;
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
 * about {@link #SHOW_MS} (longer where Android's accessibility timeout asks). It sits in the window's decor, so a page
 * rebuild underneath doesn't remove it: at the window's foot over the page and the dock, or at its top in riding mode,
 * where the stopped Lock and Record buttons are at the foot (#651 F09). It spans the window less its side margins, up to
 * {@link #MAX_WIDTH_DP}, so a fold or a rotation while it shows never pushes Undo off screen (#651 F08). The words wrap
 * at spaces only, never inside a word, and the button never shrinks or wraps: the words give way first (narrow label
 * ladder). It rises a little and fades in, and sinks and fades out on its timeout, Undo or a dismissal (owner, #651);
 * with Android's animations off it simply appears and goes (Motion).
 */
final class ShortRideUndoBar {
    /** How long the bar shows. */
    static final long SHOW_MS = 8_000;
    static final int MAX_WIDTH_DP = 560;
    /** How far the bar travels as it comes and goes. */
    static final int TRAVEL_DP = 16;
    static final String TAG = "short_ride_undo", BUTTON_TAG = "short_ride_undo_button";
    private final Activity activity;
    private final Ui ui;
    private View bar;
    private String rideId;
    private boolean top;
    private Animator motion;
    private final Runnable hide = this::dismiss;

    ShortRideUndoBar(Activity activity, Ui ui) { this.activity = activity; this.ui = ui; }

    /** The ride the bar shows now, or null (also while it is leaving). */
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

    /**
     * Shows the bar for rideId for showMs, replacing any bar already up. bottomPx keeps it clear of the dock (its height, the
     * navigation bar's included); atTop puts it under the status bar instead (riding mode).
     */
    void show(String rideId, String words, String undoLabel, long showMs, int bottomPx, boolean atTop, Runnable undo) {
        dismiss();
        if (showMs <= 0 || activity.isFinishing()) return;
        this.rideId = rideId; this.top = atTop;
        final int maxWidth = ui.dp(MAX_WIDTH_DP);
        LinearLayout row = new LinearLayout(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = View.MeasureSpec.getSize(widthSpec);
                if (width > maxWidth) widthSpec = View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.EXACTLY);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
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
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                (atTop ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL);
        params.leftMargin = params.rightMargin = side;
        if (atTop) params.topMargin = inset(true) + ui.dp(12);
        else params.bottomMargin = Math.max(bottomPx, inset(false)) + ui.dp(12);
        ((ViewGroup) activity.getWindow().getDecorView()).addView(row, params);
        bar = row;
        motion = Motion.floatIn(row, travel());
        row.postDelayed(hide, showMs);
    }

    /** Takes the bar away: it sinks and fades out (at once with animations off). */
    void dismiss() {
        View shown = bar;
        bar = null; rideId = null;
        if (shown == null) return;
        shown.removeCallbacks(hide);
        // Leaving, it is no longer the bar: no tag, no taps.
        shown.setTag(null); shown.setClickable(false);
        motion = Motion.floatOut(shown, travel(), motion, () -> { if (shown.getParent() instanceof ViewGroup) ((ViewGroup) shown.getParent()).removeView(shown); });
    }

    /** The way in and out: up from below at the foot, down from above at the top. */
    private float travel() { return (top ? -1 : 1) * ui.dp(TRAVEL_DP); }

    private int inset(boolean atTop) {
        WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
        if (insets == null) return 0;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(atTop ? WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()
                    : WindowInsets.Type.navigationBars() | WindowInsets.Type.ime());
            return atTop ? bars.top : bars.bottom;
        }
        return atTop ? insets.getSystemWindowInsetTop() : insets.getSystemWindowInsetBottom();
    }
}
