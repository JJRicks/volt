package com.jjricks.volt;

import android.content.Context;
import android.graphics.drawable.InsetDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Riding mode's battery alert banner (tester feedback, Nov 14 2026): an alert that fires while riding mode is open
 * shows here instead of a dialog over the whole screen. The speed, the power bar, Lock and Record stay in view and
 * keep working, and so do the back button and the back gesture.
 *
 * It holds a battery icon, the alert's title and, while the alarm sounds, Stop sound; otherwise a close button. What
 * shows and for how long is ScooterService's RidingAlert: a silent alert hides after ten seconds in view, a sounding
 * one when the sound ends. A silent alert waits, and its time stops, only while Volt's own sheet or dialog covers
 * riding mode (sheetOver); beside another app in split screen, or under the notification shade, it shows at once. The title steps down the narrow-label ladder (full, short, then the icon alone) and Stop
 * sound to Stop; neither wraps nor shrinks below the readable floor. TalkBack reads the alert as it appears.
 *
 * Where it sits (RidingAlert.inline): over the top bar between back and the scooter's warning chip, covering the
 * connected status pill (and, with no chip, the tune disc and the moon), when that room holds its short title and
 * its button. Otherwise, and whenever the scooter isn't connected, it takes its own row under the top bar, so the
 * warning code and a Reconnecting or Not connected pill always stay in view and take their own taps. What it covers
 * is hidden from TalkBack and the keyboard until it goes.
 */
final class RidingAlertBanner {
    private static final float HEIGHT_DP = 56, ICON_DP = 24, GAP_DP = 10, TITLE_END_DP = 8, START_DP = 16;
    /** Its gap from back, and from the warning chip or the top bar above it. */
    private static final float BACK_GAP_DP = 12, CHIP_GAP_DP = 10, ROW_GAP_DP = 10;
    /** The Stop sound pill's side padding; its end adds the 6 dp the pill is inset by, so the label sits centred. */
    private static final float STOP_PAD_DP = 22, STOP_INSET_DP = 6;
    /** How often a showing banner looks again, so it goes within half a second of its sound or its time ending. */
    private static final long TICK_MS = 500;
    private final Ui ui;
    private final ScooterService service;
    private final FrameLayout holder;
    private final LinearLayout banner;
    private final TextView title, stop;
    private final FrameLayout close;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::refresh;
    /** Volt's own sheet or dialog (the tune sheet, a message) is over riding mode; read on every look, never cached. */
    private final java.util.function.BooleanSupplier sheetOver;
    /** The top bar, its back button and the scooter's warning chip, which the banner never covers. */
    private ViewGroup top;
    private View back, chip;
    /** What the banner covers now, with what it changed to hide each from TalkBack and the keyboard. */
    private final Map<View, int[]> covered = new LinkedHashMap<>();
    /** The RidingAlert id shown now, -1 while hidden. */
    private long shown = -1;
    /** The shown title's shortest rung, measured at the readable floor (pixels): the room Stop sound must leave it. */
    private float shortTitleWidth;
    private boolean closed;

    /**
     * @param sp the title's and the button's text size, riding mode's capped label size
     * @param sheetOver whether Volt's own sheet or dialog is showing over riding mode right now
     */
    RidingAlertBanner(Context context, Ui ui, ScooterService service, float sp, java.util.function.BooleanSupplier sheetOver) {
        this.ui = ui;
        this.service = service;
        this.sheetOver = sheetOver;
        holder = new FrameLayout(context);
        banner = new LinearLayout(context);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setTag("riding_alert_banner");
        banner.setBackground(ui.shape(ui.accent, HEIGHT_DP / 2, 0));
        banner.setPaddingRelative(ui.dp(START_DP), 0, 0, 0);
        // What it covers (the status pill, tune and moon) takes no taps through it.
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
        titleSize.setMarginEnd(ui.dp(TITLE_END_DP));
        banner.addView(title, titleSize);
        stop = ui.text("", sp, true);
        stop.setTag("riding_alert_stop");
        stop.setTextColor(ui.accent);
        stop.setGravity(Gravity.CENTER);
        stop.setMaxLines(1);
        stop.setMinWidth(ui.dp(HEIGHT_DP));
        // A 44 dp pill drawn inside the banner's full 56 dp height, all of which takes the tap.
        stop.setBackground(new InsetDrawable(ui.shape(ui.onAccent, 22, 0), 0, ui.dp(STOP_INSET_DP), ui.dp(STOP_INSET_DP), ui.dp(STOP_INSET_DP)));
        // After the background, which sets its own padding.
        stop.setPadding(ui.dp(STOP_PAD_DP), 0, ui.dp(STOP_PAD_DP + STOP_INSET_DP), 0);
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

    /**
     * The top bar with the banner laid over it, in the top bar's place in riding mode's frame.
     *
     * @param back the back button, never covered
     * @param chip the scooter's warning chip, never covered while it shows
     */
    View over(ViewGroup top, View back, View chip) {
        this.top = top;
        this.back = back;
        this.chip = chip;
        holder.addView(top, new FrameLayout.LayoutParams(-1, -2));
        // No width until place() has measured the top bar, so it never shows over back.
        holder.addView(banner, new FrameLayout.LayoutParams(0, ui.dp(HEIGHT_DP), Gravity.TOP | Gravity.LEFT));
        // The chip coming or going, the picker or a narrower pill: place it again after that layout.
        top.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> holder.post(this::place));
        return holder;
    }

    /** Shows the service's alert, swaps a newer one in, or hides; looks again every half second while showing. */
    void refresh() {
        if (closed) return;
        handler.removeCallbacks(tick);
        // Only Volt's own sheet or dialog holds a silent banner's time, never another app's focus: in split screen
        // beside a map, or under the notification shade, Volt is in view and posts no notice, so the banner must show.
        boolean over = sheetOver.getAsBoolean();
        RidingAlert alert = service == null ? null : service.ridingAlert(SystemClock.elapsedRealtime(), !over);
        if (alert == null) { hide(); return; }
        boolean sounding = service.chargeAlarmPlaying();
        if (alert.id != shown && RidingAlert.waits(sounding, over)) {
            // A silent alert waits for the sheet or dialog to close; it then shows for its ten seconds. Looked at again
            // every half second, so however the sheet or dialog goes, it shows within that.
            hide();
            handler.postDelayed(tick, TICK_MS);
            return;
        }
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
            service.ridingAlertOnScreen(alert.id);
            // Set after it shows: the live region speaks the change.
            banner.setContentDescription(alert.spoken());
        }
        place();
        if (sounding) fitStop();
        handler.postDelayed(tick, TICK_MS);
    }

    /** Riding mode's screen closed (leaving, or a rebuild that builds a new banner): no more looks from this one. */
    void close() {
        closed = true;
        handler.removeCallbacks(tick);
        uncover();
    }

    private void hide() {
        shown = -1;
        if (banner.getVisibility() != View.GONE) banner.setVisibility(View.GONE);
        uncover();
    }

    /**
     * Over the top bar between back and the warning chip when that room holds it with its short title, the scooter
     * is connected and the screen is laid out; otherwise on its own row under the top bar, covering nothing.
     */
    private void place() {
        if (closed || top == null || banner.getVisibility() != View.VISIBLE) return;
        int width = top.getWidth();
        if (width <= 0) return;
        boolean rtl = top.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        boolean chipShown = chip.getVisibility() == View.VISIBLE;
        int start = rtl ? (chipShown ? chip.getRight() + ui.dp(CHIP_GAP_DP) : 0) : back.getRight() + ui.dp(BACK_GAP_DP);
        int end = rtl ? back.getLeft() - ui.dp(BACK_GAP_DP) : chipShown ? chip.getLeft() - ui.dp(CHIP_GAP_DP) : width;
        boolean linked = service != null && service.ble != null && service.ble.ready();
        boolean inline = RidingAlert.inline(linked, end - start, needed());
        FrameLayout.LayoutParams size = (FrameLayout.LayoutParams) banner.getLayoutParams();
        int left = inline ? start : 0, across = inline ? end - start : width;
        int down = inline ? Math.max(0, (top.getHeight() - ui.dp(HEIGHT_DP)) / 2) : top.getHeight() + ui.dp(ROW_GAP_DP);
        if (size.leftMargin != left || size.width != across || size.topMargin != down) {
            size.leftMargin = left;
            size.width = across;
            size.topMargin = down;
            banner.setLayoutParams(size);
        }
        cover(inline, start, end);
    }

    /** The least room it takes over the top bar: its padding, icon, short title at the floor and shortest button. */
    private int needed() {
        float button = ui.dp(HEIGHT_DP);
        if (stop.getVisibility() == View.VISIBLE) {
            String[] labels = RidingAlert.stopLabels();
            button = Math.max(button, stop.getPaint().measureText(labels[labels.length - 1]) + stop.getPaddingLeft() + stop.getPaddingRight());
        }
        return Math.round(ui.dp(START_DP + ICON_DP + GAP_DP + TITLE_END_DP) + shortTitleWidth + button);
    }

    /** Hides what it now covers in the top bar from TalkBack and the keyboard, and gives back what it no longer does. */
    private void cover(boolean inline, int start, int end) {
        for (int i = 0; i < top.getChildCount(); i++) {
            View c = top.getChildAt(i);
            boolean under = inline && c != back && c != chip && c.getVisibility() == View.VISIBLE && c.getRight() > start && c.getLeft() < end;
            if (under && !covered.containsKey(c)) {
                int blocks = c instanceof ViewGroup ? ((ViewGroup) c).getDescendantFocusability() : -1;
                covered.put(c, new int[]{c.getImportantForAccessibility(), c.isFocusable() ? 1 : 0, blocks});
                c.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                c.setFocusable(false);
                if (c instanceof ViewGroup) ((ViewGroup) c).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            } else if (!under && covered.containsKey(c)) give(c);
        }
    }

    private void uncover() {
        for (View c : new java.util.ArrayList<>(covered.keySet())) give(c);
    }

    private void give(View c) {
        int[] was = covered.remove(c);
        c.setImportantForAccessibility(was[0]);
        c.setFocusable(was[1] == 1);
        if (c instanceof ViewGroup && was[2] >= 0) ((ViewGroup) c).setDescendantFocusability(was[2]);
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
        int room = banner.getWidth() - banner.getPaddingStart() - banner.getPaddingEnd() - ui.dp(ICON_DP + GAP_DP + TITLE_END_DP);
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
