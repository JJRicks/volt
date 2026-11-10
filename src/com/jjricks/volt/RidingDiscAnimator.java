package com.jjricks.volt;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;

/**
 * Runs {@link RidingDisc}'s show and hide for riding mode's tune disc and its stopped Lock and Record
 * buttons, so all of them move alike: the same length and curve, scaled by the system's animation
 * setting (with animations off it jumps to the end). A change of mind mid-way carries on from where
 * the motion is.
 */
final class RidingDiscAnimator {
    /** Draws the motion at this progress, 0 gone to 1 fully shown. */
    interface Frame { void at(float progress); }

    private final RidingDisc state = new RidingDisc();
    private final Frame frame;
    private ValueAnimator running;

    RidingDiscAnimator(Frame frame) { this.frame = frame; }

    /** True while it's meant to show; taps stop the moment it starts to hide. */
    boolean takesTaps() { return state.takesTaps(); }

    /** Sets where it's going; true when that changed and {@link #run} should follow. */
    boolean want(boolean shown) { return state.want(shown); }

    /** Starts the motion toward where {@link #want} last pointed. */
    void run() {
        stop();
        boolean shown = state.target();
        long length = RidingDisc.duration(Motion.CHANGE_MS, state.progress(), shown, Ui.motionEnabled());
        if (length <= 0) {
            state.finish();
            frame.at(state.progress());
            return;
        }
        ValueAnimator motion = ValueAnimator.ofFloat(state.progress(), shown ? 1 : 0);
        motion.setDuration(length);
        motion.setInterpolator(Motion.STANDARD);
        motion.addUpdateListener(a -> { state.at((Float) a.getAnimatedValue()); frame.at(state.progress()); });
        motion.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                if (running == a) running = null;
                if (cancelled) return;
                state.finish();
                frame.at(state.progress());
            }
        });
        running = motion;
        motion.start();
    }

    /** Stops the motion where it is, as the screen closes. */
    void stop() { if (running != null) { running.cancel(); running = null; } }
}
