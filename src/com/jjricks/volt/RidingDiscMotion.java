package com.jjricks.volt;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Shows and hides riding mode's tune disc with {@link RidingDisc}'s motion: its width and margin grow
 * in the top bar so the status pill resizes smoothly, while the disc fades and scales. It honours the
 * system's animation scale (Android scales the length; with animations off the disc just appears or
 * goes), and the disc stops taking taps the moment it starts to hide.
 */
final class RidingDiscMotion {
    private final View disc;
    private final int fullWidth, fullMargin;
    private final RidingDisc state = new RidingDisc();
    private ValueAnimator running;

    /** The disc must already sit in a LinearLayout with its full width and start margin. */
    RidingDiscMotion(View disc) {
        this.disc = disc;
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) disc.getLayoutParams();
        fullWidth = p.width;
        fullMargin = p.getMarginStart();
        apply(0);
    }

    /** True while the disc is meant to show; a tap that arrives while it hides is ignored. */
    boolean takesTaps() { return state.takesTaps(); }

    /** Moves the disc toward shown or gone; nothing happens when it's already going there. */
    void show(boolean shown) {
        if (!state.want(shown)) return;
        // Taps stop at once when hiding, and come back as soon as it starts to show.
        disc.setClickable(shown);
        disc.setEnabled(shown);
        if (running != null) { running.cancel(); running = null; }
        long length = RidingDisc.duration(Motion.CHANGE_MS, state.progress(), shown, Ui.motionEnabled());
        if (length <= 0) {
            state.finish();
            apply(state.progress());
            return;
        }
        ValueAnimator motion = ValueAnimator.ofFloat(state.progress(), shown ? 1 : 0);
        motion.setDuration(length);
        motion.setInterpolator(Motion.STANDARD);
        motion.addUpdateListener(a -> { state.at((Float) a.getAnimatedValue()); apply(state.progress()); });
        motion.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                if (running == a) running = null;
                if (cancelled) return;
                state.finish();
                apply(state.progress());
            }
        });
        running = motion;
        motion.start();
    }

    /** Stops the motion where it is, as the screen closes. */
    void stop() { if (running != null) { running.cancel(); running = null; } }

    private void apply(float progress) {
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) disc.getLayoutParams();
        float w = RidingDisc.width(progress);
        p.width = Math.round(fullWidth * w);
        p.setMarginStart(Math.round(fullMargin * w));
        disc.setLayoutParams(p);
        float scale = RidingDisc.scale(progress);
        disc.setScaleX(scale);
        disc.setScaleY(scale);
        disc.setAlpha(RidingDisc.alpha(progress));
        int visibility = progress > 0 ? View.VISIBLE : View.GONE;
        if (disc.getVisibility() != visibility) disc.setVisibility(visibility);
    }
}
