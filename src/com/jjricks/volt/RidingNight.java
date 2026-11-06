package com.jjricks.volt;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.TimeZone;
import java.util.WeakHashMap;

/**
 * Riding mode's night vision (ride-display plan, PR 3). A colour-matrix layer on riding mode's root
 * turns everything it draws into shades of red (NightVision.MATRIX); it always draws over the dark
 * palette, so a Light app gets riding mode rebuilt dark while night vision is on. The moon button in the
 * top bar flips it: with a schedule, until the next scheduled switch (NightSchedule), otherwise while
 * riding mode stays open. RidingDisplay dims Volt auto and Fixed to the night range and hides the
 * status bar, which the layer can't reach. Nothing here sends a scooter command.
 *
 * Sunset comes from NightPlaces: the last ride's area once the rider has agreed in settings, otherwise
 * the time zone's reference city. The ride's area is read in the background and kept in memory only.
 *
 * Switching fades (NightFade, owner Nov 6): the layer's matrix and the window level blend over about
 * half a second. When a Light app needs the other palette, a snapshot of the old screen sits on the
 * window's overlay and fades out over the rebuilt one. With the system's animations off, or on opening,
 * it switches at once, so riding mode opened at night is red from its first frame.
 */
final class RidingNight {
    /** The moon's choice for each open riding mode: it survives rebuilds and ends with the visit. */
    private static final Map<RidingSession, NightSchedule.Override> overrides = Collections.synchronizedMap(new WeakHashMap<>());
    private static Map<String, double[]> zones;
    private static volatile NightSchedule.Place rideArea;
    private static volatile boolean rideAreaRead;
    /** A fade carried across a palette rebuild: the old screen's look and its brightness amount. */
    private static final Map<RidingSession, Crossfade> crossfades = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class Crossfade {
        final float from, to;
        final Bitmap snapshot;
        final int left, top;
        Crossfade(float from, float to, Bitmap snapshot, int left, int top) {
            this.from = from; this.to = to; this.snapshot = snapshot; this.left = left; this.top = top;
        }
    }

    private static final long RECHECK_MS = 60 * 60_000L;

    private final ScreenHost host;
    private final Ui ui;
    private final RidingSession session;
    private final View root;
    private final RidingDisplay display;
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout button;
    private final Moon moon;
    private boolean shown, detached;
    /** What the layer and the window show now (0 normal, 1 night vision, between while fading). */
    private float layer, bright;
    private ValueAnimator fade;
    private BitmapDrawable cover;

    private final Runnable recheck = this::evaluate;

    RidingNight(ScreenHost host, Ui ui, RidingSession session, View root, RidingDisplay display) {
        this.host = host;
        this.ui = ui;
        this.session = session;
        this.root = root;
        this.display = display;
        Context context = host.activity();
        preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        button = new FrameLayout(context);
        button.setBackground(ui.shape(ui.dark ? ui.surface : ui.raised, 28, 0));
        button.setClickable(true);
        button.setFocusable(true);
        button.setTag("riding_night_button");
        button.setOnClickListener(v -> tap());
        moon = new Moon(context);
        button.addView(moon, new FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER));
        shown = shown(context, preferences, session, System.currentTimeMillis());
        Crossfade carried = session == null ? null : crossfades.remove(session);
        marks();
        if (carried != null && carried.to == (shown ? 1 : 0) && !instant()) crossfade(carried);
        else { if (carried != null) carried.snapshot.recycle(); look(shown ? 1 : 0, shown ? 1 : 0); }
        schedule();
        // Read each time riding mode opens, so a newer ride (or deleted rides) counts at once.
        if (usesRideArea(preferences)) readRideArea(context, this::evaluate);
    }

    /** The moon disc, for the top bar's end. */
    View button() { return button; }

    static LinearLayout.LayoutParams slot(Ui ui) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56));
        size.setMarginStart(ui.dp(10));
        return size;
    }

    /** Riding mode's screen closed (leaving, or a rebuild): no more switches from this one. */
    void detach() {
        detached = true;
        handler.removeCallbacks(recheck);
        endFade();
    }

    /** The palette riding mode is built with: dark whenever night vision shows, even in a Light app. */
    static Ui palette(ScreenHost host, RidingSession session) {
        Ui app = host.ui();
        if (app.dark || !shown(host.activity(), host.activity().getSharedPreferences("app_settings", Context.MODE_PRIVATE), session, System.currentTimeMillis()))
            return app;
        return new Ui(host.activity(), true, app.textScale);
    }

    /** What the schedule says now, with this phone's place and zone. */
    static NightSchedule.State state(Context context, SharedPreferences preferences, long nowMs) {
        return NightSchedule.state(preferences.getString(NightSchedule.MODE_KEY, NightSchedule.OFF),
                preferences.getString(NightSchedule.SCHEDULE_KEY, NightSchedule.SUN),
                preferences.getInt(NightSchedule.START_KEY, NightSchedule.START_DEFAULT),
                preferences.getInt(NightSchedule.END_KEY, NightSchedule.END_DEFAULT),
                place(context, preferences), ZoneId.systemDefault(), nowMs);
    }

    /** The ride's area once the rider agreed and a ride has one, else the time zone's city, else null. */
    static NightSchedule.Place place(Context context, SharedPreferences preferences) {
        NightSchedule.Place ride = rideArea;
        if (ride != null && usesRideArea(preferences)) return ride;
        return zone(context);
    }

    /** The time zone's reference city, or null when the table doesn't know this zone. */
    static NightSchedule.Place zone(Context context) {
        String id = TimeZone.getDefault().getID();
        Map<String, double[]> table = zones(context);
        NightSchedule.Place place = NightPlaces.zone(table, id);
        if (place == null) {
            try { place = NightPlaces.zone(table, android.icu.util.TimeZone.getCanonicalID(id)); }
            catch (RuntimeException unknown) { place = null; }
        }
        return place;
    }

    static boolean usesRideArea(SharedPreferences preferences) {
        return NightSchedule.AREA_ALLOW.equals(preferences.getString(NightSchedule.AREA_KEY, null));
    }

    /** The last ride's area has been read at least once since Volt started. */
    static boolean rideAreaRead() { return rideAreaRead; }
    /** The last ride's area as last read, or null. */
    static NightSchedule.Place rideArea() { return rideArea; }

    /** Reads the last ride's area in the background (a few KB of the newest journals), then runs done on the main thread. */
    static void readRideArea(Context context, Runnable done) {
        File folder = new File(context.getApplicationContext().getFilesDir(), NightPlaces.JOURNAL_DIR);
        Handler main = new Handler(Looper.getMainLooper());
        Thread reader = new Thread(() -> {
            NightSchedule.Place place = NightPlaces.lastRide(folder);
            rideArea = place;
            rideAreaRead = true;
            if (done != null) main.post(done);
        });
        reader.setDaemon(true);
        reader.start();
    }

    private static synchronized Map<String, double[]> zones(Context context) {
        if (zones == null) {
            try (Reader in = new InputStreamReader(context.getResources().openRawResource(R.raw.tz_coords), StandardCharsets.UTF_8)) {
                zones = NightPlaces.zones(in);
            } catch (IOException | RuntimeException unreadable) {
                zones = Collections.emptyMap();
            }
        }
        return zones;
    }

    private static boolean shown(Context context, SharedPreferences preferences, RidingSession session, long nowMs) {
        return NightSchedule.shown(state(context, preferences, nowMs), session == null ? null : overrides.get(session), nowMs);
    }

    private void tap() {
        if (detached) return;
        long now = System.currentTimeMillis();
        NightSchedule.State state = state(host.activity(), preferences, now);
        if (session != null) overrides.put(session, NightSchedule.tap(state, shown));
        Haptics.tick(button);
        evaluate();
    }

    /** Looks again: a switch, the moon or a newly read ride area may have changed what shows. */
    private void evaluate() {
        if (detached) return;
        boolean now = shown(host.activity(), preferences, session, System.currentTimeMillis());
        if (now != shown) {
            shown = now;
            // A Light app's riding mode needs the dark palette under the layer, or a light one without it.
            if (ui.dark != (now || host.ui().dark)) { rebuild(now); return; }
            marks();
            fadeTo(now ? 1 : 0);
        }
        schedule();
    }

    /** The moon and its description follow the choice at once; the look fades after them. */
    private void marks() {
        moon.on = shown;
        moon.invalidate();
        button.setContentDescription(shown ? T.ridingNightButtonOn() : T.ridingNightButtonOff());
        button.setSelected(shown);
    }

    /** The layer's colour at one amount and the window level at another (they differ in a crossfade). */
    private void look(float layerAmount, float brightAmount) {
        layer = NightFade.clamp(layerAmount);
        bright = NightFade.clamp(brightAmount);
        if (layer <= 0) root.setLayerType(View.LAYER_TYPE_NONE, null);
        else {
            Paint paint = new Paint();
            paint.setColorFilter(new ColorMatrixColorFilter(NightFade.matrix(layer)));
            if (root.getLayerType() == View.LAYER_TYPE_HARDWARE) root.setLayerPaint(paint);
            else root.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
        }
        display.night(bright);
    }

    /** The system's animations off: switch at once. */
    private boolean instant() {
        float scale = Settings.Global.getFloat(host.activity().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        return NightFade.instant(scale);
    }

    /** Same palette: blend the layer and the level from where they are now. */
    private void fadeTo(float target) {
        endFade();
        float fromLayer = layer, fromBright = bright;
        if (instant()) { look(target, target); return; }
        fade = ValueAnimator.ofFloat(0, 1);
        fade.setDuration(NightFade.FADE_MS);
        fade.addUpdateListener(a -> {
            if (detached) return;
            float p = (Float) a.getAnimatedValue();
            look(NightFade.between(fromLayer, target, p), NightFade.between(fromBright, target, p));
        });
        fade.start();
    }

    /**
     * A Light app's riding mode needs the other palette: keep a picture of this screen as it looks now
     * (the layer's colour put back on it, since a software draw skips the layer), then rebuild.
     */
    private void rebuild(boolean now) {
        detached = true;
        handler.removeCallbacks(recheck);
        float fromBright = bright, fromLayer = layer;
        endFade();
        if (session != null && !instant() && root.getWidth() > 0 && root.getHeight() > 0) {
            try {
                Bitmap picture = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                root.draw(new Canvas(picture));
                if (fromLayer > 0) {
                    Bitmap tinted = Bitmap.createBitmap(picture.getWidth(), picture.getHeight(), Bitmap.Config.ARGB_8888);
                    Paint paint = new Paint();
                    paint.setColorFilter(new ColorMatrixColorFilter(NightFade.matrix(fromLayer)));
                    new Canvas(tinted).drawBitmap(picture, 0, 0, paint);
                    picture.recycle();
                    picture = tinted;
                }
                int[] at = new int[2];
                root.getLocationInWindow(at);
                Crossfade old = crossfades.put(session, new Crossfade(fromBright, now ? 1 : 0, picture, at[0], at[1]));
                if (old != null) old.snapshot.recycle();
            } catch (OutOfMemoryError | RuntimeException ignored) {
                // No picture (memory, or a view a software canvas can't draw): the rebuilt screen just appears.
            }
        }
        root.post(host::rebuild);
    }

    /** After a palette rebuild: this screen is already in its final look; the old picture fades out over it. */
    private void crossfade(Crossfade carried) {
        look(carried.to, carried.from);
        View decor = host.activity().getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) { carried.snapshot.recycle(); look(carried.to, carried.to); return; }
        BitmapDrawable picture = new BitmapDrawable(host.activity().getResources(), carried.snapshot);
        picture.setBounds(carried.left, carried.top, carried.left + carried.snapshot.getWidth(), carried.top + carried.snapshot.getHeight());
        ((ViewGroup) decor).getOverlay().add(picture);
        cover = picture;
        fade = ValueAnimator.ofFloat(0, 1);
        fade.setDuration(NightFade.FADE_MS);
        fade.addUpdateListener(a -> {
            if (detached) return;
            float p = (Float) a.getAnimatedValue();
            picture.setAlpha(Math.round(255 * (1 - p)));
            look(carried.to, NightFade.between(carried.from, carried.to, p));
            if (p >= 1) removeCover();
        });
        fade.start();
    }

    private void endFade() {
        if (fade != null) { ValueAnimator running = fade; fade = null; running.removeAllUpdateListeners(); running.cancel(); }
        removeCover();
    }

    private void removeCover() {
        if (cover == null) return;
        View decor = host.activity().getWindow().getDecorView();
        if (decor instanceof ViewGroup) ((ViewGroup) decor).getOverlay().remove(cover);
        if (cover.getBitmap() != null) cover.getBitmap().recycle();
        cover = null;
    }

    /** One callback at the next switch (or the moon's end), checked again at least hourly for clock changes. */
    private void schedule() {
        handler.removeCallbacks(recheck);
        if (detached) return;
        long now = System.currentTimeMillis();
        NightSchedule.Override override = session == null ? null : overrides.get(session);
        long wake = NightSchedule.wake(state(host.activity(), preferences, now), override, now);
        if (!NightSchedule.SCHEDULED.equals(NightSchedule.mode(preferences.getString(NightSchedule.MODE_KEY, NightSchedule.OFF))) && wake < 0) return;
        long delay = wake < 0 ? RECHECK_MS : Math.max(1000, Math.min(RECHECK_MS, wake - now + 500));
        handler.postDelayed(recheck, delay);
    }

    /** A crescent: filled while night vision is on, an outline while it's off, so the state isn't colour alone. */
    private final class Moon extends View {
        boolean on;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Moon(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            canvas.save();
            canvas.translate((getWidth() - 24 * s) / 2, (getHeight() - 24 * s) / 2);
            canvas.scale(s, s);
            Path disc = new Path(), bite = new Path();
            disc.addCircle(12, 12, 9, Path.Direction.CW);
            bite.addCircle(16.5f, 8.5f, 7.5f, Path.Direction.CW);
            disc.op(bite, Path.Op.DIFFERENCE);
            paint.setColor(ui.text);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(on ? Paint.Style.FILL : Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            canvas.drawPath(disc, paint);
            canvas.restore();
        }
    }
}
