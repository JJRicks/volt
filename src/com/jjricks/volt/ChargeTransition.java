package com.jjricks.volt;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Home's change between parked and charging, "Plug in" (the owner, 17 Nov 2026: "a sexy animation transition between
 * regular parked mode and charging"; the pick from two recorded prototypes). Clean Home's top area and Widgets Home's
 * charging takeover each play it on a real change between the two only: never on a telemetry refresh, on Home opening,
 * on a rebuild (recreation, a fold or rotation, another scooter) or with animations off, where the change is instant.
 *
 * <p>Clean, arriving (about 560 ms): the parked card's photo glides and scales into the charging picture's place while
 * the two crossfade (a copy of the photo drawn over the top area; the picture's layers transformed from the photo's
 * box), the percentage travels from the card to its charging place and size, and the card fades away. Then the cable
 * past the cut-out is drawn in from the screen's edge, a bright dash runs in along it to the bay, the bay lights up to the
 * battery's percent, and the Charging pill and the readings rise in, about 55 ms apart. The bay's light fades as the charge pulse
 * takes over; the pulse is held until the arrival ends. Leaving (about 360 ms) plays it back: the readings and the pill
 * go, the cable lets go, and the picture glides home into the card while the number flies back. Widgets' takeover opens
 * (or closes) as before, its parts arriving in the same order once it is open.
 *
 * <p>Each change is one progress p, 0 (parked at rest) to 1 (charging at rest), running evenly in time; every part eases
 * within its own share of p, so the stagger is in time too and everything on screen is a function of p. A quick unplug
 * and replug turns the same progress back from where it is, so nothing snaps and nothing plays twice. At rest every view
 * has no transform and full opacity, so the charging view's layout (ChargingFit, ChargePictureFit) is exactly its own.
 */
final class ChargeTransition {
    private ChargeTransition() {}

    /** Clean's whole arrival and leaving; Widgets' opening and closing (each scaled by how far is left to go). */
    static final long IN_MS = 560, OUT_MS = 360, WIDGETS_IN_MS = 600, WIDGETS_OUT_MS = 300;

    /** The charging view's parts (DashboardScreen.ChargingHero). */
    interface Hero {
        View view();
        /** The Charging pill over the percentage (Clean: the pill and its short form; Widgets: the pill). */
        View[] lines();
        TextView percent();
        ViewGroup stage();
        View rows();
        /** The picture's layers, or the photo when the model has no picture; their pixels share one frame. */
        View[] images();
        /** Where the image draws in its views' pixels, or null. */
        RectF imageBounds();
        ChargePicture picture();
        /** The battery's percent (NaN: not reported). */
        double level();
        /** Holds the charge pulse off (true) or lets it run (false, and starts it when it should). */
        void holdPulse(boolean hold);
        /** How far the floor glow and the track have come in, 0..1 (1 at rest). */
        void glowIn(float shown);
        /**
         * The percentage's way from elsewhere: an offset, a scale round a pivot (its own pixels), on top of its own place.
         * The hero keeps it across its own layout passes (its measuring sets the number's resting offset, never this).
         */
        void flyPercent(float dx, float dy, float scale, float pivotX, float pivotY);
        /** The number's resting offset across (ChargePercentShade's nudge), without any flight. */
        float percentRestX();
        /** The line's rise below its resting place (0 at rest), kept across layout passes like flyPercent. */
        void riseLines(float dy);
        int dp(float dp);
    }

    static final android.animation.TimeInterpolator LINEAR = new android.view.animation.LinearInterpolator();

    static float seg(float p, float from, float to) { return Math.max(0, Math.min(1, (p - from) / (to - from))); }
    private static float ease(float t) { return Motion.STANDARD.getInterpolation(t); }
    private static float fade(float t) { return Motion.FADE.getInterpolation(t); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** A view's (0, 0) in {@code ancestor}'s pixels, without its own or its ancestors' transforms. */
    static float[] origin(View view, View ancestor) {
        float x = 0, y = 0;
        for (View v = view; v != null && v != ancestor; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            x += v.getLeft(); y += v.getTop();
            if (v.getParent() instanceof View && v.getParent() != ancestor) {
                View p = (View) v.getParent();
                x -= p.getScrollX(); y -= p.getScrollY();
            }
        }
        return new float[] {x, y};
    }

    /** Where an ImageView's picture draws in its own pixels (FIT_CENTER and the like). */
    static RectF content(ImageView view) {
        Drawable d = view.getDrawable();
        if (d == null || d.getIntrinsicWidth() <= 0) return new RectF(0, 0, view.getWidth(), view.getHeight());
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        view.getImageMatrix().mapRect(r);
        r.offset(view.getPaddingLeft(), view.getPaddingTop());
        return r;
    }

    /** The percentage's anchor (its line's left at the baseline) in its own pixels, or null before it is laid out. */
    private static float[] anchor(TextView text) {
        Layout layout = text.getLayout();
        if (layout == null) return null;
        return new float[] {text.getTotalPaddingLeft() + layout.getLineLeft(0), text.getTotalPaddingTop() + layout.getLineBaseline(0)};
    }

    /**
     * The bay's light hands over to the charge pulse: lit to the percent, it fades out as the pulse starts. Null when
     * there is nothing to fade (no wire picture, no percent, animations off).
     */
    static ValueAnimator fadeFill(Hero hero) {
        ChargePicture picture = hero.picture();
        if (picture == null || Double.isNaN(hero.level()) || !Ui.motionEnabled()) return null;
        float from = (float) (hero.level() / 100);
        picture.arrival(1, Float.NaN, from);
        ValueAnimator fade = ValueAnimator.ofFloat(1, 0);
        fade.setDuration(700);
        fade.setInterpolator(Motion.FADE);
        fade.addUpdateListener(a -> { ChargePicture now = hero.picture(); if (now != null) now.arrival(1, Float.NaN, from * (Float) a.getAnimatedValue()); });
        fade.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { ChargePicture now = hero.picture(); if (now != null) now.arrival(1, Float.NaN, 0); }
        });
        fade.start();
        return fade;
    }

    /** The picture's cable, dash and bay at progress p (arriving: the dash runs in and the bay fills; leaving: neither). */
    private static void picture(Hero hero, float p, boolean in, float plugFrom, float plugTo) {
        ChargePicture picture = hero.picture();
        if (picture == null) return;
        float charge = in && p < 1 ? seg(p, plugFrom + .18f, plugTo + .14f) : Float.NaN;
        float fill = in && !Double.isNaN(hero.level()) ? ease(seg(p, .76f, 1)) * (float) (hero.level() / 100) : 0;
        picture.arrival(ease(seg(p, plugFrom, plugTo)), charge > 0 && charge < 1 ? charge : Float.NaN, fill);
    }

    /** The view leaving is skipped by accessibility services until it has gone (it takes no touches either). */
    private static void quiet(View view, boolean quiet) {
        int wanted = quiet ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        if (view.getImportantForAccessibility() != wanted) view.setImportantForAccessibility(wanted);
    }

    // ===== Clean's top area =====

    /** Clean Home's top area: the parked card and the charging view, one over the other (CleanHomeScreen). */
    static final class Clean {
        private final ViewGroup top;
        private final View overview;
        private final ImageView parkedPhoto;
        private final TextView parkedPercent;
        private final Hero hero;
        /** Called with true when the charging view should count as shown (its pulse may run), false when it is gone. */
        private final java.util.function.Consumer<Boolean> chargingShown;
        private ValueAnimator animator;
        private float p;
        private boolean active, measured;
        private float target;
        private Geometry geometry;
        private final Flight flight = new Flight();
        private ViewTreeObserver.OnPreDrawListener measuring;
        private ValueAnimator fillFade;

        Clean(ViewGroup top, View overview, ImageView parkedPhoto, TextView parkedPercent, Hero hero,
              java.util.function.Consumer<Boolean> chargingShown) {
            this.top = top;
            this.overview = overview;
            this.parkedPhoto = parkedPhoto;
            this.parkedPercent = parkedPercent;
            this.hero = hero;
            this.chargingShown = chargingShown;
        }

        /** A change is under way: the top area takes no touches meanwhile (CleanHomeScreen's top area). */
        boolean changing() { return active; }

        /** The change's progress, 0 parked to 1 charging (android-tests' ChargeTransitionValidation reads it). */
        float progress() { return p; }

        /**
         * Plays a change between parked and charging; false leaves it to the caller (any other change, animations off,
         * the top area detached or not laid out), after settling a change under way. The caller never asks on Home's
         * first show, so opening Home, recreation and every rebuild stay instant.
         */
        boolean change(int previous, int next) {
            boolean pair = (previous == HomeMoment.PARKED && next == HomeMoment.CHARGING) || (previous == HomeMoment.CHARGING && next == HomeMoment.PARKED);
            if (!pair || !Ui.motionEnabled() || !top.isAttachedToWindow() || !top.isLaidOut() || parkedPhoto == null || parkedPercent == null) {
                settle();
                return false;
            }
            float to = next == HomeMoment.CHARGING ? 1 : 0;
            if (!active) {
                p = previous == HomeMoment.CHARGING ? 1 : 0;
                begin();
            }
            run(to);
            return true;
        }

        /** Ends a change under way where it is going, leaving every view at rest (the caller then sets what shows). */
        void settle() {
            if (fillFade != null) { fillFade.cancel(); fillFade = null; }
            if (!active) return;
            boolean charging = target >= .5f;
            stop();
            rest();
            hero.holdPulse(false);
            if (!charging) chargingShown.accept(false);
        }

        private void begin() {
            active = true;
            measured = false;
            if (fillFade != null) { fillFade.cancel(); fillFade = null; }
            geometry = null;
            overview.setVisibility(View.VISIBLE);
            hero.view().setVisibility(View.VISIBLE);
            hero.view().setAlpha(1);
            overview.setAlpha(1);
            top.getOverlay().add(flight);
            apply();
            // Both are laid out on the next frame: then the boxes they travel between are known.
            measuring = () -> {
                top.getViewTreeObserver().removeOnPreDrawListener(measuring);
                measuring = null;
                if (active) {
                    geometry = Geometry.of(top, parkedPhoto, parkedPercent, hero);
                    measured = true;
                    apply();
                }
                return true;
            };
            top.getViewTreeObserver().addOnPreDrawListener(measuring);
        }

        private void run(float to) {
            target = to;
            if (animator != null) { animator.removeAllListeners(); animator.removeAllUpdateListeners(); animator.cancel(); }
            boolean in = to == 1;
            if (in) chargingShown.accept(true);
            quiet(overview, in);
            quiet(hero.view(), !in);
            // The pulse waits for the arrival. Leaving from rest it was running and runs on under the fade (its lit
            // cells would blink out otherwise); turned back mid-arrival it never started, and stays held.
            hero.holdPulse(in || p < 1);
            ValueAnimator next = ValueAnimator.ofFloat(p, to);
            next.setDuration(Math.max(1, Math.round((in ? IN_MS : OUT_MS) * Math.abs(to - p))));
            next.setInterpolator(LINEAR);
            next.addUpdateListener(a -> {
                if (!Ui.motionEnabled()) { settleAt(to); return; }
                p = (Float) a.getAnimatedValue();
                apply();
            });
            next.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { if (animator == animation) settleAt(to); }
            });
            animator = next;
            next.start();
        }

        private void settleAt(float to) {
            stop();
            rest();
            p = to;
            if (to == 1) {
                overview.setVisibility(View.GONE);
                hero.holdPulse(false);
                fillFade = fadeFill(hero);
            } else {
                hero.view().setVisibility(View.GONE);
                hero.holdPulse(false);
                chargingShown.accept(false);
            }
        }

        private void stop() {
            if (measuring != null) { top.getViewTreeObserver().removeOnPreDrawListener(measuring); measuring = null; }
            if (animator != null) { ValueAnimator a = animator; animator = null; a.removeAllListeners(); a.removeAllUpdateListeners(); a.cancel(); }
            active = false;
        }

        /** Every view back at rest, the overlay gone. */
        private void rest() {
            top.getOverlay().remove(flight);
            overview.setAlpha(1);
            hide(parkedPhoto, false);
            parkedPercent.setAlpha(1);
            quiet(overview, false);
            quiet(hero.view(), false);
            hero.view().setAlpha(1);
            for (View image : hero.images()) identity(image);
            hero.percent().setAlpha(1);
            hero.flyPercent(0, 0, 1, 0, 0);
            for (View line : hero.lines()) line.setAlpha(1);
            hero.riseLines(0);
            hero.rows().setAlpha(1);
            hero.rows().setTranslationY(0);
            hero.glowIn(1);
            ChargePicture picture = hero.picture();
            if (picture != null) picture.arrival(1, Float.NaN, 0);
            geometry = null;
        }

        /**
         * Hides the card's photo without touching its alpha: an ImageView passes its view alpha on to its drawable, and
         * on the emulator the drawable's alpha did not come back at rest (the photo stayed invisible). An empty clip
         * draws nothing and moves nothing.
         */
        private static void hide(View v, boolean hidden) {
            android.graphics.Rect clip = v.getClipBounds();
            if (hidden && clip == null) v.setClipBounds(new android.graphics.Rect(0, 0, 0, 0));
            else if (!hidden && clip != null) v.setClipBounds(null);
        }

        private static void identity(View v) {
            v.setAlpha(1); v.setScaleX(1); v.setScaleY(1); v.setTranslationX(0); v.setTranslationY(0); v.setPivotX(0); v.setPivotY(0);
        }

        private void apply() {
            Geometry g = geometry;
            boolean in = target == 1;
            overview.setAlpha(1 - fade(seg(p, 0, .3f)));
            float images = fade(seg(p, .15f, .5f));
            for (View image : hero.images()) image.setAlpha(images);
            hero.glowIn(fade(seg(p, .3f, .8f)));
            // The pill and the readings rise in on Motion's even curve, their opacity with them: their shares are short,
            // and the arrival curve's fast start moved them too far in one frame (most of their rise when leaving).
            float line = fade(seg(p, .5f, .82f)), rows = fade(seg(p, .6f, .92f));
            for (View v : hero.lines()) v.setAlpha(line);
            hero.riseLines((1 - line) * hero.dp(10));
            hero.rows().setAlpha(rows);
            hero.rows().setTranslationY((1 - rows) * hero.dp(14));
            picture(hero, p, in, .4f, .68f);
            if (g == null) {
                flight.hide();
                hide(parkedPhoto, false);
                if (!measured) {
                    // The first frame, before both are laid out: each end keeps its own number (one of them shows).
                    parkedPercent.setAlpha(p < .5f ? 1 : 0);
                    hero.percent().setAlpha(p < .5f ? 0 : 1);
                } else {
                    // Nothing to travel between (a card without its photo, a slot without a picture): a crossfade.
                    parkedPercent.setAlpha(1);
                    hero.percent().setAlpha(fade(seg(p, .25f, .6f)));
                }
                return;
            }
            // The photo and the picture share a centre and a height on the way, crossfading.
            hide(parkedPhoto, true);
            parkedPercent.setAlpha(0);
            float f = ease(seg(p, 0, .62f));
            float cx = lerp(g.parked.centerX(), g.image.centerX(), f), cy = lerp(g.parked.centerY(), g.image.centerY(), f);
            float h = lerp(g.parked.height(), g.image.height(), f);
            float pw = h * g.parked.width() / Math.max(1, g.parked.height());
            flight.show(parkedPhoto.getDrawable(), cx - pw / 2, cy - h / 2, cx + pw / 2, cy + h / 2, 1 - fade(seg(p, .12f, .45f)));
            float k = h / Math.max(1, g.image.height());
            for (View image : hero.images()) {
                image.setPivotX(0); image.setPivotY(0);
                image.setScaleX(k); image.setScaleY(k);
                image.setTranslationX(cx - g.imageOrigin[0] - k * g.imageLocal.centerX());
                image.setTranslationY(cy - g.imageOrigin[1] - k * g.imageLocal.centerY());
            }
            // The percentage travels from the card's to its own place, its size between the two.
            float q = ease(seg(p, .05f, .6f));
            float ax = lerp(g.parkedAnchor[0], g.heroAnchor[0], q), ay = lerp(g.parkedAnchor[1], g.heroAnchor[1], q);
            float size = lerp(g.parkedSize, g.heroSize, q) / Math.max(1, g.heroSize);
            hero.flyPercent(ax - g.heroAnchor[0], ay - g.heroAnchor[1], size, g.heroAnchorLocal[0], g.heroAnchorLocal[1]);
            hero.percent().setAlpha(1);
            hero.stage().invalidate();
        }
    }

    /** The boxes Clean's change travels between, in the top area's pixels, from one layout of both moments. */
    static final class Geometry {
        RectF parked, image, imageLocal;
        float[] imageOrigin, parkedAnchor, heroAnchor, heroAnchorLocal;
        float parkedSize, heroSize;

        /** Null when there is nothing to travel between (no photo in the card, no picture or photo in the slot). */
        static Geometry of(View top, ImageView parkedPhoto, TextView parkedPercent, Hero hero) {
            View[] images = hero.images();
            RectF local = hero.imageBounds();
            float[] pa = anchor(parkedPercent), ha = anchor(hero.percent());
            if (images.length == 0 || local == null || local.isEmpty() || pa == null || ha == null
                    || parkedPhoto.getVisibility() != View.VISIBLE || parkedPhoto.getWidth() == 0 || parkedPhoto.getDrawable() == null
                    || images[0].getVisibility() != View.VISIBLE || hero.percent().getVisibility() != View.VISIBLE) return null;
            Geometry g = new Geometry();
            g.parked = content(parkedPhoto);
            if (g.parked.isEmpty()) return null;
            float[] po = origin(parkedPhoto, top);
            g.parked.offset(po[0], po[1]);
            g.imageLocal = local;
            g.imageOrigin = origin(images[0], top);
            g.image = new RectF(local);
            g.image.offset(g.imageOrigin[0], g.imageOrigin[1]);
            float[] ppo = origin(parkedPercent, top);
            g.parkedAnchor = new float[] {ppo[0] + pa[0], ppo[1] + pa[1]};
            TextView percent = hero.percent();
            float[] hpo = origin(percent, top);
            g.heroAnchorLocal = ha;
            g.heroAnchor = new float[] {hpo[0] + ha[0] + hero.percentRestX(), hpo[1] + ha[1]};
            g.parkedSize = parkedPercent.getTextSize();
            g.heroSize = percent.getTextSize();
            return g;
        }
    }

    /** The parked photo on its way, drawn over the top area (never clipped by the card it leaves). */
    static final class Flight extends Drawable {
        private android.graphics.Bitmap bitmap;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF at = new RectF();
        private float alpha;

        /** The photo's own bitmap (never its drawable, whose alpha the ImageView sets from its own). */
        void show(Drawable photo, float l, float t, float r, float b, float alpha) {
            bitmap = photo instanceof android.graphics.drawable.BitmapDrawable ? ((android.graphics.drawable.BitmapDrawable) photo).getBitmap() : null;
            at.set(l, t, r, b);
            this.alpha = alpha;
            invalidateSelf();
        }

        void hide() { if (alpha != 0) { alpha = 0; invalidateSelf(); } }

        @Override public void draw(Canvas canvas) {
            if (bitmap == null || bitmap.isRecycled() || alpha <= 0) return;
            paint.setAlpha(Math.round(255 * Math.min(1, alpha)));
            canvas.drawBitmap(bitmap, null, at, paint);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ===== Widgets' takeover =====

    /** The takeover's height share at progress p: it opens first, then its parts arrive. */
    static float widgetsHeight(float p) {
        return new android.view.animation.DecelerateInterpolator().getInterpolation(seg(p, 0, .45f));
    }

    /** Widgets' takeover at progress p: the hero's own fade and rise, then its parts. */
    static void styleWidgets(Hero hero, float p, boolean in) {
        View view = hero.view();
        float whole = fade(seg(p, 0, .35f));
        view.setAlpha(whole);
        view.setTranslationY(-hero.dp(16) * (1 - whole));
        quiet(view, !in);
        // The whole stage (picture, number and charge bar) arrives together, so the bar never shows alone.
        hero.stage().setAlpha(fade(seg(p, .18f, .5f)));
        hero.percent().setAlpha(fade(seg(p, .35f, .65f)));
        hero.glowIn(fade(seg(p, .4f, .8f)));
        float line = fade(seg(p, .3f, .6f)), rows = fade(seg(p, .55f, .9f));
        for (View v : hero.lines()) v.setAlpha(line);
        hero.riseLines((1 - line) * hero.dp(8));
        hero.rows().setAlpha(rows);
        hero.rows().setTranslationY((1 - rows) * hero.dp(12));
        picture(hero, p, in, .45f, .72f);
        hero.stage().invalidate();
    }

    /** Widgets' takeover back at rest. */
    static void restWidgets(Hero hero) {
        View view = hero.view();
        view.setAlpha(1); view.setTranslationY(0);
        quiet(view, false);
        hero.stage().setAlpha(1);
        hero.percent().setAlpha(1);
        for (View v : hero.lines()) v.setAlpha(1);
        hero.riseLines(0);
        hero.rows().setAlpha(1); hero.rows().setTranslationY(0);
        hero.glowIn(1);
        ChargePicture picture = hero.picture();
        if (picture != null) picture.arrival(1, Float.NaN, 0);
    }
}
