package com.jjricks.volt;

import android.content.Context;
import android.graphics.drawable.InsetDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Riding mode's battery alert banner (tester feedback, Nov 14 2026): an alert that fires while riding mode is open
 * shows here, over the top bar right of the back button, instead of a dialog over the whole screen. The speed, the
 * power bar, Lock and Record stay in view and keep working, and so do the back button and the back gesture.
 *
 * It holds a battery icon, the alert's title and, while the alarm sounds, Stop sound; otherwise a close button. What
 * shows and for how long is ScooterService's RidingAlert: a silent alert hides after ten seconds, a sounding one when
 * the sound ends. The title steps down the narrow-label ladder (full, short, then the icon alone) and Stop sound to
 * Stop; neither wraps nor shrinks below the readable floor. TalkBack reads the alert as it appears (a live region).
 */
final class RidingAlertBanner {
    private static final float HEIGHT_DP = 56, ICON_DP = 24, GAP_DP = 10;
    /** How often a showing banner looks again, so it goes within half a second of its sound or its time ending. */
    private static final long TICK_MS = 500;
    private final Ui ui;
    private final ScooterService service;
    private final int startInset;
    private final FrameLayout holder;
    private final LinearLayout banner;
    private final TextView title, stop;
    private final FrameLayout close;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::refresh;
    /** The RidingAlert id shown now, -1 while hidden. */
    private long shown = -1;
    /** The shown title's shortest rung, measured at the readable floor (pixels): the room Stop sound must leave it. */
    private float shortTitleWidth;
    private boolean closed;

    /**
     * @param sp the title's and the button's text size, riding mode's capped label size
     * @param startInset room left of the banner (pixels): the back button and its gap, which stay usable
     */
    RidingAlertBanner(Context context, Ui ui, ScooterService service, float sp, int startInset) {
        this.ui = ui;
        this.service = service;
        this.startInset = startInset;
        holder = new FrameLayout(context);
        banner = new LinearLayout(context);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setTag("riding_alert_banner");
        banner.setBackground(ui.shape(ui.accent, HEIGHT_DP / 2, 0));
        banner.setPaddingRelative(ui.dp(16), 0, 0, 0);
        // The pieces it covers (status pill, tune and moon) take no taps through it.
        banner.setOnTouchListener((v, event) -> true);
        banner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        banner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        View icon = ui.icon("battery", Math.round(ICON_DP), ui.onAccent);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        banner.addView(icon, new LinearLayout.LayoutParams(ui.dp(ICON_DP), ui.dp(ICON_DP)));
        title = ui.text("", sp, true);
        title.setTag("riding_alert_title");
        title.setTextColor(ui.onAccent);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ui.fitStatusLine(title);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(0, -2, 1);
        titleSize.setMarginStart(ui.dp(GAP_DP));
        titleSize.setMarginEnd(ui.dp(8));
        banner.addView(title, titleSize);
        stop = ui.text("", sp, true);
        stop.setTag("riding_alert_stop");
        stop.setTextColor(ui.accent);
        stop.setGravity(Gravity.CENTER);
        stop.setMaxLines(1);
        stop.setMinWidth(ui.dp(HEIGHT_DP));
        // A 44 dp pill drawn inside the banner's full 56 dp height, all of which takes the tap.
        stop.setBackground(new InsetDrawable(ui.shape(ui.onAccent, 22, 0), 0, ui.dp(6), ui.dp(6), ui.dp(6)));
        // After the background, which sets its own padding; the extra end padding centres the label in the pill.
        stop.setPadding(ui.dp(22), 0, ui.dp(22 + 6), 0);
        stop.setClickable(true);
        stop.setFocusable(true);
        stop.setOnClickListener(v -> closeShown());
        stop.setVisibility(View.GONE);
        banner.addView(stop, new LinearLayout.LayoutParams(-2, -1));
        close = new FrameLayout(context);
        close.setTag("riding_alert_close");
        close.setClickable(true);
        close.setFocusable(true);
        close.setContentDescription(T.ridingAlertClose());
        close.setOnClickListener(v -> closeShown());
        close.addView(ui.icon("close", 22, ui.onAccent), new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
        close.setVisibility(View.GONE);
        banner.addView(close, new LinearLayout.LayoutParams(ui.dp(HEIGHT_DP), -1));
        banner.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitStop());
        banner.setVisibility(View.GONE);
    }

    /** The top bar with the banner laid over it, in the top bar's place in riding mode's frame. */
    View over(View top) {
        holder.addView(top, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(-1, ui.dp(HEIGHT_DP), Gravity.TOP | Gravity.START);
        size.setMarginStart(startInset);
        holder.addView(banner, size);
        return holder;
    }

    /** Shows the service's alert, swaps a newer one in, or hides; looks again every half second while showing. */
    void refresh() {
        if (closed) return;
        handler.removeCallbacks(tick);
        RidingAlert alert = service == null ? null : service.ridingAlert(SystemClock.elapsedRealtime());
        if (alert == null) { hide(); return; }
        boolean sounding = service.chargeAlarmPlaying();
        stop.setVisibility(sounding ? View.VISIBLE : View.GONE);
        close.setVisibility(sounding ? View.GONE : View.VISIBLE);
        if (alert.id != shown) {
            String[] titles = alert.titles();
            // The empty last rung leaves the icon alone when no title fits.
            String[] rungs = java.util.Arrays.copyOf(titles, titles.length + 1);
            rungs[titles.length] = "";
            ui.statusRungs(title, rungs);
            TextPaint floor = new TextPaint(title.getPaint());
            if (title.getAutoSizeTextType() == TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM) floor.setTextSize(title.getAutoSizeMinTextSize());
            String shortest = titles[titles.length - 1];
            shortTitleWidth = floor.measureText(shortest, 0, shortest.length());
            banner.setVisibility(View.VISIBLE);
            shown = alert.id;
            // Set after it shows: the live region speaks the change.
            banner.setContentDescription(alert.spoken());
        }
        if (sounding) fitStop();
        handler.postDelayed(tick, TICK_MS);
    }

    /** Riding mode's screen closed (leaving, or a rebuild that builds a new banner): no more looks from this one. */
    void close() {
        closed = true;
        handler.removeCallbacks(tick);
    }

    private void hide() {
        shown = -1;
        if (banner.getVisibility() != View.GONE) banner.setVisibility(View.GONE);
    }

    /** Stop sound or close: the alarm ends and the banner goes; a newer alert that arrived meanwhile stays. */
    private void closeShown() {
        if (closed || service == null || shown < 0) return;
        service.closeRidingAlert(shown);
        refresh();
    }

    /**
     * Stop sound while the title keeps at least its short rung beside it, otherwise Stop, which always stays: the
     * button is never cut to an icon. The full label is spoken either way.
     */
    private void fitStop() {
        if (stop.getVisibility() != View.VISIBLE || banner.getWidth() <= 0) return;
        String[] labels = RidingAlert.stopLabels();
        int room = banner.getWidth() - banner.getPaddingStart() - banner.getPaddingEnd() - ui.dp(ICON_DP + GAP_DP + 8);
        TextPaint paint = stop.getPaint();
        float pad = stop.getPaddingLeft() + stop.getPaddingRight();
        float[] widths = {paint.measureText(labels[0]) + pad + shortTitleWidth, paint.measureText(labels[1]) + pad};
        int pick = TileRung.first(widths, room);
        String next = labels[pick == 0 ? 0 : 1];
        if (!next.contentEquals(stop.getText())) stop.setText(next);
        // View skips an unchanged description itself.
        stop.setContentDescription(pick == 0 ? null : labels[0]);
    }
}
