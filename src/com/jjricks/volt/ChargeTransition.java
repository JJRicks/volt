package com.jjricks.volt;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * PROTOTYPE (phase 1; the owner, 17 Nov 2026: "a sexy animation transition between regular parked mode and charging").
 * Two variants of Home's parked/charging change, behind {@link #variant}, for the owner to pick from recordings; phase 2
 * keeps one, folds it into its owners and adds a motion validation. Today's crossfade (CleanHomeScreen.show) and
 * takeover (ChargingHero.showAnimated) stay as variant 0.
 *
 * <p>Both variants are one progress p, 0 (parked at rest) to 1 (charging at rest): everything on screen is a function
 * of p, so a quick unplug and replug turns the same animator back from where it is, with nothing snapping. Arrivals ease
 * out (Motion.STANDARD); leaving is shorter. The charging picture's pulse is held until the arrival ends.
 *
 * <p>A, "Plug in": the parked photo glides and scales into the charging picture's place while the two crossfade (an
 * overlay copy of the parked photo, the picture's layers transformed from the photo's box), the percentage travels from
 * the card to its charging place, the card fades away, the cable is drawn in from the screen's edge, a bright dash runs
 * in along it, the bay lights up to the battery's percent, then the line and the readings rise in, staggered. Leaving
 * plays it back, quicker: the cable lets go, the picture glides home into the card's photo.
 *
 * <p>B, "Charge sweep": the parked card crossfades to the charging view in place; an accent band sweeps up through the
 * scooter, the line pops in (0.92 to 1) and the readings rise in after it. Leaving is a plain quick fade.
 */
final class ChargeTransition {
    private ChargeTransition() {}

    static final int TODAY = 0, PLUG = 1, SWEEP = 2;
    /** The prototype's switch (android-tests' ChargeTransitionDriver sets it). */
    static volatile int variant = PLUG;

    static final long A_IN_MS = 560, A_OUT_MS = 360, B_IN_MS = 480, B_OUT_MS = 170;
    static final long W_A_IN_MS = 600, W_A_OUT_MS = 300, W_B_IN_MS = 500, W_B_OUT_MS = 200;

    /** The charging view's parts (DashboardScreen.ChargingHero). */
    interface Hero {
        View view();
        /** The line over the percentage (Clean: the line and its short form; Widgets: the chip). */
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
        int accent();
        int dp(float dp);
    }

    static final android.animation.TimeInterpolator LINEAR = new android.view.animation.LinearInterpolator();

    static float seg(float p, float from, float to) { return Math.max(0, Math.min(1, (p - from) / (to - from))); }
    private static float ease(float t) { return Motion.STANDARD.getInterpolation(t); }
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
        private boolean active;
        private int kind;
        private float target;
        private Geometry geometry;
        private final Flight flight = new Flight();
        private final Sweep sweep;
        private ViewTreeObserver.OnPreDrawListener measuring;
        private ValueAnimator fillFade;
        /** The line's and the number's own offsets at rest, read before the change touches them (null: read on layout). */
        private Float restLineY, restPercentX;

        Clean(ViewGroup top, View overview, ImageView parkedPhoto, TextView parkedPercent, Hero hero,
              java.util.function.Consumer<Boolean> chargingShown) {
            this.top = top;
            this.overview = overview;
            this.parkedPhoto = parkedPhoto;
            this.parkedPercent = parkedPercent;
            this.hero = hero;
            this.chargingShown = chargingShown;
            sweep = new Sweep(hero, top);
        }

        /**
         * Plays the change from parked to charging or back when it is one and the prototype is on; false leaves it to the
         * caller (any other change, the first show, animations off, detached), after settling a change under way.
         */
        boolean change(int previous, int next) {
            int chosen = variant;
            boolean pair = (previous == HomeMoment.PARKED && next == HomeMoment.CHARGING) || (previous == HomeMoment.CHARGING && next == HomeMoment.PARKED);
            if (chosen == TODAY || !pair || !Ui.motionEnabled() || !top.isAttachedToWindow() || parkedPhoto == null) { settle(); return false; }
            float to = next == HomeMoment.CHARGING ? 1 : 0;
            if (!active) {
                kind = chosen;
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
            boolean charging = p == 1;
            View[] lines = hero.lines();
            restLineY = charging && lines.length > 0 ? lines[0].getTranslationY() : null;
            restPercentX = charging ? hero.percent().getTranslationX() : null;
            if (fillFade != null) { fillFade.cancel(); fillFade = null; }
            geometry = null;
            overview.setVisibility(View.VISIBLE);
            hero.view().setVisibility(View.VISIBLE);
            hero.view().setAlpha(1);
            overview.setAlpha(1);
            if (kind == PLUG) top.getOverlay().add(flight);
            else top.getOverlay().add(sweep);
            apply();
            // Both are laid out on the next frame: then the boxes they travel between are known.
            measuring = () -> {
                top.getViewTreeObserver().removeOnPreDrawListener(measuring);
                measuring = null;
                if (active) {
                    geometry = Geometry.of(top, parkedPhoto, parkedPercent, hero, restLineY, restPercentX);
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
            // The pulse waits for the arrival; leaving, it runs on under the fade (its lit cells would blink out otherwise).
            // Leaving from rest it was running and keeps on; turned back mid-arrival it never started, and stays held.
            hero.holdPulse(in || p < 1);
            if (in) sweep.gain = 1;
            long full = kind == PLUG ? (in ? A_IN_MS : A_OUT_MS) : (in ? B_IN_MS : B_OUT_MS);
            ValueAnimator next = ValueAnimator.ofFloat(p, to);
            next.setDuration(Math.max(1, Math.round(full * Math.abs(to - p))));
            // p runs evenly in time; each part eases within its own share of it (so the stagger is in time, too).
            next.setInterpolator(LINEAR);
            next.addUpdateListener(a -> {
                if (!Ui.motionEnabled()) { settleAt(to); return; }
                p = (Float) a.getAnimatedValue();
                if (!in) sweep.gain = Math.max(0, sweep.gain - .2f);
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
            if (to == 1) {
                overview.setVisibility(View.GONE);
                hero.holdPulse(false);
                fadeFill();
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

        private void fadeFill() {
            if (kind == PLUG) fillFade = ChargeTransition.fadeFill(hero);
        }

        /** Every view back at rest, the overlays gone. */
        private void rest() {
            top.getOverlay().remove(flight);
            top.getOverlay().remove(sweep);
            overview.setAlpha(1);
            hide(parkedPhoto, false);
            parkedPercent.setAlpha(1);
            hero.view().setAlpha(1);
            for (View image : hero.images()) identity(image);
            TextView percent = hero.percent();
            percent.setAlpha(1); percent.setScaleX(1); percent.setScaleY(1); percent.setTranslationY(0);
            if (geometry != null) percent.setTranslationX(geometry.percentRestX);
            for (View line : hero.lines()) { line.setAlpha(1); line.setScaleX(1); line.setScaleY(1); if (geometry != null) line.setTranslationY(geometry.lineRestY); }
            hero.rows().setAlpha(1); hero.rows().setTranslationY(0);
            hero.glowIn(1);
            ChargePicture picture = hero.picture();
            if (picture != null) picture.arrival(1, Float.NaN, 0);
            geometry = null;
        }

        /**
         * Hides the card's photo without its alpha: an ImageView passes its view alpha on to its drawable, which did not
         * come back at rest on the emulator (the photo stayed invisible). An empty clip draws nothing and moves nothing.
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
            if (kind == PLUG) applyPlug(); else applySweep();
        }

        private void applyPlug() {
            Geometry g = geometry;
            boolean in = target == 1;
            overview.setAlpha(1 - Motion.FADE.getInterpolation(seg(p, 0, .3f)));
            // Until both are measured (the first frame), each end keeps its own photo and number.
            hide(parkedPhoto, g != null || p >= .5f);
            parkedPercent.setAlpha(g == null ? (p < .5f ? 1 : 0) : 0);
            float images = Motion.FADE.getInterpolation(seg(p, .15f, .5f));
            for (View image : hero.images()) image.setAlpha(images);
            hero.glowIn(Motion.FADE.getInterpolation(seg(p, .3f, .8f)));
            float line = ease(seg(p, .5f, .82f));
            float restY = g != null ? g.lineRestY : restLineY != null ? restLineY : 0;
            for (View v : hero.lines()) { v.setAlpha(line); v.setTranslationY(restY + (1 - line) * hero.dp(10)); }
            float rows = ease(seg(p, .6f, .92f));
            hero.rows().setAlpha(rows);
            hero.rows().setTranslationY((1 - rows) * hero.dp(14));
            ChargePicture picture = hero.picture();
            if (picture != null) {
                float plug = ease(seg(p, .4f, .68f));
                float charge = in && p < 1 ? seg(p, .58f, .82f) : Float.NaN;
                float fill = in && !Double.isNaN(hero.level()) ? ease(seg(p, .76f, 1)) * (float) (hero.level() / 100) : 0;
                picture.arrival(plug, charge > 0 && charge < 1 ? charge : Float.NaN, fill);
            }
            if (g == null) { flight.hide(); hero.percent().setAlpha(p < .5f ? 0 : 1); return; }
            // The photo and the picture share a centre and a height on the way, crossfading.
            float f = ease(seg(p, 0, .62f));
            float cx = lerp(g.parked.centerX(), g.image.centerX(), f), cy = lerp(g.parked.centerY(), g.image.centerY(), f);
            float h = lerp(g.parked.height(), g.image.height(), f);
            float pw = h * g.parked.width() / Math.max(1, g.parked.height());
            flight.show(parkedPhoto.getDrawable(), cx - pw / 2, cy - h / 2, cx + pw / 2, cy + h / 2, 1 - Motion.FADE.getInterpolation(seg(p, .12f, .45f)));
            float k = h / Math.max(1, g.image.height());
            for (View image : hero.images()) {
                image.setPivotX(0); image.setPivotY(0);
                image.setScaleX(k); image.setScaleY(k);
                image.setTranslationX(cx - g.imageOrigin[0] - k * g.imageLocal.centerX());
                image.setTranslationY(cy - g.imageOrigin[1] - k * g.imageLocal.centerY());
            }
            // The percentage travels from the card's to its own place, its size between the two.
            TextView percent = hero.percent();
            float q = ease(seg(p, .05f, .6f));
            float ax = lerp(g.parkedAnchor[0], g.heroAnchor[0], q), ay = lerp(g.parkedAnchor[1], g.heroAnchor[1], q);
            float size = lerp(g.parkedSize, g.heroSize, q) / Math.max(1, g.heroSize);
            percent.setPivotX(g.heroAnchorLocal[0]); percent.setPivotY(g.heroAnchorLocal[1]);
            percent.setScaleX(size); percent.setScaleY(size);
            percent.setTranslationX(g.percentRestX + ax - g.heroAnchor[0]);
            percent.setTranslationY(ay - g.heroAnchor[1]);
            percent.setAlpha(1);
            hero.stage().invalidate();
        }

        private void applySweep() {
            Geometry g = geometry;
            overview.setAlpha(1 - Motion.FADE.getInterpolation(seg(p, 0, .4f)));
            float images = Motion.FADE.getInterpolation(seg(p, 0, .4f));
            for (View image : hero.images()) image.setAlpha(images);
            hero.percent().setAlpha(Motion.FADE.getInterpolation(seg(p, .3f, .62f)));
            hero.glowIn(Motion.FADE.getInterpolation(seg(p, .1f, .55f)));
            float line = ease(seg(p, .3f, .65f));
            for (View v : hero.lines()) {
                v.setAlpha(line);
                v.setPivotX(v.getWidth() / 2f); v.setPivotY(v.getHeight() / 2f);
                v.setScaleX(.92f + .08f * line); v.setScaleY(.92f + .08f * line);
            }
            float rows = ease(seg(p, .45f, .85f));
            hero.rows().setAlpha(rows);
            hero.rows().setTranslationY((1 - rows) * hero.dp(12));
            sweep.at(seg(p, .25f, 1f), images, g == null ? null : g.imageOrigin);
            hero.stage().invalidate();
        }
    }

    /** The boxes Clean's variant A travels between, in the top area's pixels, from one layout of both moments. */
    static final class Geometry {
        RectF parked, image, imageLocal;
        float[] imageOrigin, parkedAnchor, heroAnchor, heroAnchorLocal;
        float parkedSize, heroSize, percentRestX, lineRestY;

        static Geometry of(View top, ImageView parkedPhoto, TextView parkedPercent, Hero hero, Float lineY, Float percentX) {
            Geometry g = new Geometry();
            View[] images = hero.images();
            RectF local = hero.imageBounds();
            float[] pa = anchor(parkedPercent), ha = anchor(hero.percent());
            if (images.length == 0 || local == null || pa == null || ha == null || parkedPhoto.getWidth() == 0) return null;
            g.parked = content(parkedPhoto);
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
            g.percentRestX = percentX != null ? percentX : percent.getTranslationX();
            g.heroAnchorLocal = ha;
            g.heroAnchor = new float[] {hpo[0] + ha[0] + g.percentRestX, hpo[1] + ha[1]};
            g.parkedSize = parkedPercent.getTextSize();
            g.heroSize = percent.getTextSize();
            View[] lines = hero.lines();
            g.lineRestY = lineY != null ? lineY : lines.length > 0 ? lines[0].getTranslationY() : 0;
            return g;
        }
    }

    /** Variant A: the parked photo on its way, drawn over the top area (never clipped by the card it leaves). */
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

        void hide() { alpha = 0; invalidateSelf(); }

        @Override public void draw(Canvas canvas) {
            if (bitmap == null || bitmap.isRecycled() || alpha <= 0) return;
            paint.setAlpha(Math.round(255 * Math.min(1, alpha)));
            canvas.drawBitmap(bitmap, null, at, paint);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * Variant B: an accent band sweeping up through the scooter, masked to the picture's own pixels (the picture's
     * resting layer, or the photo, drawn again into a layer the band then colours in place).
     */
    static final class Sweep extends Drawable {
        private final Hero hero;
        private final View host;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float position = -1, shown;
        private float[] origin;
        float gain = 1;

        Sweep(Hero hero, View host) {
            this.hero = hero;
            this.host = host;
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        }

        /** The band's place, 0 (under the wheels) to 1 (past the top); shown: the picture's own opacity now. */
        void at(float position, float shown, float[] origin) {
            this.position = position;
            this.shown = shown;
            this.origin = origin;
            invalidateSelf();
        }

        @Override public void draw(Canvas canvas) {
            View[] images = hero.images();
            RectF local = hero.imageBounds();
            if (origin == null || local == null || images.length == 0 || position <= 0 || position >= 1 || gain <= 0) return;
            float alpha = gain * shown * (float) Math.sin(Math.PI * position);
            if (alpha <= .01f) return;
            RectF box = new RectF(local);
            box.offset(origin[0], origin[1]);
            float band = box.height() * .22f;
            float y = box.bottom + band - position * (box.height() + 2 * band);
            int save = canvas.saveLayerAlpha(box.left - 2, box.top - 2, box.right + 2, box.bottom + 2, Math.round(255 * Math.min(1, alpha)));
            canvas.translate(origin[0], origin[1]);
            images[0].draw(canvas);
            canvas.translate(-origin[0], -origin[1]);
            int accent = hero.accent();
            paint.setShader(new LinearGradient(0, y - band, 0, y + band,
                    new int[] {Ui.withAlpha(accent, 0), Ui.withAlpha(accent, 150), Ui.withAlpha(0xFFFFFFFF, 120), Ui.withAlpha(accent, 150), Ui.withAlpha(accent, 0)},
                    new float[] {0, .4f, .5f, .6f, 1}, Shader.TileMode.CLAMP));
            canvas.drawRect(box.left - 2, box.top - 2, box.right + 2, box.bottom + 2, paint);
            paint.setShader(null);
            canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Variant A's bay fill hands over to the pulses: lit to the percent, it fades out as they start. Null: nothing to fade. */
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

    // ===== Widgets' takeover =====

    /** The takeover's height share at progress p (it opens first, then its parts arrive). */
    static float widgetsHeight(int kind, float p) {
        return new android.view.animation.DecelerateInterpolator().getInterpolation(seg(p, 0, kind == PLUG ? .45f : .5f));
    }

    static long widgetsMs(int kind, boolean show) {
        return kind == PLUG ? (show ? W_A_IN_MS : W_A_OUT_MS) : (show ? W_B_IN_MS : W_B_OUT_MS);
    }

    /** Widgets' takeover at progress p: the hero's own fade and rise, then its parts (the variant's). */
    static void styleWidgets(Hero hero, int kind, float p, boolean in, Sweep sweep) {
        View view = hero.view();
        float whole = seg(p, 0, .35f);
        view.setAlpha(whole);
        view.setTranslationY(-hero.dp(16) * (1 - whole));
        float line, rows;
        if (kind == PLUG) {
            line = ease(seg(p, .3f, .6f));
            // The whole stage (picture, number and charge bar) arrives together, so the bar never shows alone.
            float images = Motion.FADE.getInterpolation(seg(p, .18f, .5f));
            hero.stage().setAlpha(images);
            hero.percent().setAlpha(seg(p, .35f, .65f));
            hero.glowIn(seg(p, .4f, .8f));
            rows = ease(seg(p, .55f, .9f));
            ChargePicture picture = hero.picture();
            if (picture != null) {
                float charge = in && p < 1 ? seg(p, .58f, .85f) : Float.NaN;
                float fill = in && !Double.isNaN(hero.level()) ? ease(seg(p, .74f, 1)) * (float) (hero.level() / 100) : 0;
                picture.arrival(seg(p, .45f, .72f), charge > 0 && charge < 1 ? charge : Float.NaN, fill);
            }
            for (View v : hero.lines()) { v.setAlpha(line); v.setTranslationY((1 - line) * hero.dp(8)); }
        } else {
            line = ease(seg(p, .35f, .7f));
            rows = ease(seg(p, .5f, .9f));
            float images = Motion.FADE.getInterpolation(seg(p, .15f, .5f));
            hero.stage().setAlpha(images);
            hero.glowIn(seg(p, .2f, .6f));
            for (View v : hero.lines()) {
                v.setAlpha(line);
                v.setPivotX(v.getWidth() / 2f); v.setPivotY(v.getHeight() / 2f);
                v.setScaleX(.92f + .08f * line); v.setScaleY(.92f + .08f * line);
            }
            if (sweep != null) {
                if (!in) sweep.gain = Math.max(0, sweep.gain - .2f);
                sweep.at(seg(p, .35f, 1f), images, origin(hero.images()[0], hero.stage()));
            }
        }
        hero.rows().setAlpha(rows);
        hero.rows().setTranslationY((1 - rows) * hero.dp(12));
        hero.stage().invalidate();
    }

    /** Widgets' takeover back at rest. */
    static void restWidgets(Hero hero) {
        View view = hero.view();
        view.setAlpha(1); view.setTranslationY(0);
        for (View image : hero.images()) image.setAlpha(1);
        hero.stage().setAlpha(1);
        hero.percent().setAlpha(1);
        for (View v : hero.lines()) { v.setAlpha(1); v.setScaleX(1); v.setScaleY(1); v.setTranslationY(0); }
        hero.rows().setAlpha(1); hero.rows().setTranslationY(0);
        hero.glowIn(1);
        ChargePicture picture = hero.picture();
        if (picture != null) picture.arrival(1, Float.NaN, 0);
    }
}
