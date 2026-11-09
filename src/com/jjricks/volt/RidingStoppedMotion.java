package com.jjricks.volt;

import android.view.View;

/**
 * Shows and hides riding mode's stopped Lock and Record over the power readout with the tune disc's
 * motion (owner, Nov 9): each button fades and grows from its own centre with {@link RidingDisc}'s
 * numbers. Nothing changes size in the layout, so the readout keeps its place, and the power bar its
 * zero line: the readout is gone before the buttons get strong, and comes back only once they've
 * nearly left. Taps stop the moment they start to hide.
 */
final class RidingStoppedMotion {
    private final View controls, readout;
    private final View[] buttons;
    private final RidingDiscAnimator motion = new RidingDiscAnimator(this::apply);
    private float progress, dim = 1;

    /** A null button is one this host doesn't have. */
    RidingStoppedMotion(View controls, View readout, View... buttons) {
        this.controls = controls;
        this.readout = readout;
        this.buttons = buttons;
        apply(0);
    }

    /** True while they're meant to show; a tap that lands as they hide is ignored. */
    boolean takesTaps() { return motion.takesTaps(); }

    /** Moves them toward shown or gone; true when that's a change. */
    boolean show(boolean shown) {
        if (!motion.want(shown)) return false;
        controls.setImportantForAccessibility(shown ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        motion.run();
        return true;
    }

    /** The long-ride dimming (RidingBurnIn), on top of the fade. */
    void dim(float alpha) {
        dim = alpha;
        controls.setAlpha(dim * RidingDisc.alpha(progress));
    }

    /** Stops the motion where it is, as the screen closes. */
    void stop() { motion.stop(); }

    private void apply(float at) {
        progress = at;
        float scale = RidingDisc.scale(at);
        for (View button : buttons) {
            if (button == null) continue;
            button.setScaleX(scale);
            button.setScaleY(scale);
        }
        controls.setAlpha(dim * RidingDisc.alpha(at));
        int visibility = at > 0 ? View.VISIBLE : View.GONE;
        if (controls.getVisibility() != visibility) controls.setVisibility(visibility);
        float under = RidingDisc.readout(at);
        readout.setAlpha(under);
        // Invisible, never gone: the power bar's zero line sits on this readout's number.
        int readoutVisibility = under > 0 ? View.VISIBLE : View.INVISIBLE;
        if (readout.getVisibility() != readoutVisibility) readout.setVisibility(readoutVisibility);
    }
}
