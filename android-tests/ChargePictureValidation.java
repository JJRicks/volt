package com.jjricks.volt;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Disposable empty emulator, radio off. The charging pictures (ChargePicture: the animated wire picture of the UT5
 * MAX and UT5 Ultra X, the folded stills of the ST3 Pro, N65i, GT3 Max, GT5 Max and XT5 Ultra) on Widgets Home's
 * charging takeover and Clean Home's charging view, on the developer simulator (a UT5 MAX charging). Each model's
 * picture is shown through ChargePicture.tourKey; the wire pictures are also frozen mid-pulse, mid-sweep and in the
 * fast mode (ChargeWireView.tourSeconds and tourWatts), with the animator off (it turns it off itself).
 *
 * For every picture, on both Homes: it shows, under the percentage, with the prototype's relationship
 * (ChargePictureFit: the number no larger than the picture allows unless at its 1.0x size, its baseline no deeper into
 * the picture than the prototype's); on Widgets the percentage is on the picture, the stage keeps its least height
 * (the charge bar never makes it taller) and a measure with more height than it needs (the takeover animating open)
 * leaves the stage at that height. The parents clip again whenever the picture is not at rest (editing, animating
 * open or closed), and open only where the wire picture's cable runs off the screen's edge. With animations on, the
 * Widgets takeover is held at two moments of its opening: the tiles keep their height and nothing is unclipped.
 * With Clean Home on screen, the animator duration scale goes from 0 to 1, 0.5 and 2 and back to 0: the wire picture
 * runs again, takes each speed without restarting and rests again, as the setting changes (review of #483, P3).
 * Screenshots of every state go to files/charge-picture-validation.
 */
public final class ChargePictureValidation extends Instrumentation {
    private static final String[] KEYS = {"ut5max", "ut5ultrax", "st3pro", "n65i", "gt3max", "gt5max", "xt5ultra"};
    /** The frozen moments: {seconds, watts} and their names (the prototype's stills). */
    private static final double[][] PHASES = {{1.0, 119}, {2.0, 119}, {3.0, 540}};
    private static final String[] PHASE_NAMES = {"pulse", "sweep", "fast"};

    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private boolean admitted;
    private String ut5, label = "";
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String theme = args.getString("theme", "dark"), text = args.getString("text_scale", "1");
        label = args.getString("size_label", "size") + "-" + theme + "-" + text;
        try {
            admit(); admitted = true;
            // Animations off for the frozen frames (the release sweep's emulator leaves them on): with them on, the
            // pulses never let the main thread go idle and every settle waits forever. The app takes the setting with
            // its first window (checked once it shows); restored in finally.
            foundScale = shell("settings get global animator_duration_scale").trim();
            shell("settings put global animator_duration_scale 0");
            seed(theme, text, "widgets");
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile pu = profiles.create("Synthetic UT5 MAX", "000000000000", 0); pu.serial = "X2538-SYNTHETIC"; profiles.update(pu); ut5 = pu.id;
                profiles.select(ut5); OnboardingScreen.markDone(getTargetContext());
            });
            launch(false);
            scale("0");
            verifyWidgets();
            verifyOpening();
            relaunch(theme, text, "clean");
            verifyClean();
            verifyScaleFollowed();
            assertNoRadio();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            ChargePicture.tourKey = null; ChargeWireView.tourSeconds = Double.NaN; ChargeWireView.tourWatts = null;
            restoreFound();
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (admitted && activity != null) try { main(() -> { if (service != null) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ---- Cases ------------------------------------------------------------------------------------------------

    private void verifyWidgets() throws Exception {
        for (String key : KEYS) {
            show(key);
            main(() -> {
                View hero = hero(), stage = stage();
                ChargePicture picture = picture();
                require(picture != null && picture.key.equals(key), "Widgets: no " + key + " picture");
                TextView percent = (TextView) field(hero, "percent");
                require(percent.getParent() == stage, "Widgets " + key + ": the percentage is not on the picture");
                require(stage.getHeight() == stage.getMinimumHeight(), "Widgets " + key + ": the stage " + stage.getHeight() + " is not its least height " + stage.getMinimumHeight());
                requireFit(key, hero, stage, percent, false);
                // The takeover animating open: a measure with more height than it needs leaves the stage at its own.
                View rows = (View) field(hero, "rows");
                int width = View.MeasureSpec.makeMeasureSpec(hero.getWidth(), View.MeasureSpec.EXACTLY);
                hero.measure(width, View.MeasureSpec.makeMeasureSpec(hero.getHeight() + ui(300), View.MeasureSpec.EXACTLY));
                require(stage.getMeasuredHeight() == stage.getMinimumHeight(), "Widgets " + key + ": an open measure stretched the stage to " + stage.getMeasuredHeight());
                require(rows.getMeasuredHeight() > 0, "Widgets " + key + ": an open measure squeezed the tiles");
                hero.requestLayout();
                // At rest the wire picture's cable runs off the screen's edge (its parents open up to the scroller); a
                // folded picture keeps every parent clipping.
                require(clipsAll() == !(picture instanceof ChargeWireView && opens()), "Widgets " + key + ": clipping at rest: " + clipsAll());
                // The takeover clips to its padding (its own width) unless the cable runs off: then that opens too.
                require(((ViewGroup) hero).getClipToPadding() == !(picture instanceof ChargeWireView && opens()), "Widgets " + key + ": the takeover's padding clip at rest: " + ((ViewGroup) hero).getClipToPadding());
            });
            settle();
            shot("widgets-" + key);
            requirePillContrast("Widgets " + key);
            requireCableAtEdge("Widgets " + key);
            if (key.startsWith("ut5")) phases("widgets-" + key);
        }
        // Editing clips again at once, and leaving it opens again.
        show("ut5max");
        Object screen = screen();
        main(() -> invoke(screen, "toggleEditing"));
        settle();
        main(() -> require(clipsAll() && ((ViewGroup) hero()).getClipToPadding(), "Widgets: editing left a parent unclipped"));
        main(() -> invoke(screen, "toggleEditing"));
        redraw();
        main(() -> require(!clipsAll() || !opens(), "Widgets: leaving edit mode did not open the cable's parents again"));
        // Charging stopping clips again.
        main(() -> { service.ble.setSimulationCharging(false); service.ble.tickSimulation(); });
        redraw();
        main(() -> require(clipsAll(), "Widgets: charging stopped, a parent stayed unclipped"));
        main(() -> { service.ble.setSimulationCharging(true); service.ble.tickSimulation(); });
        redrawUntil(() -> hero().isShown() && field(hero(), "reveal") == null, "Widgets: charging did not show again");
        checks.add(label + " Widgets: each picture on the stage with the percentage on it (prototype relationship), the stage at its least height, "
                + "an open measure keeps it there; clipped while editing or not charging, open at rest only where the cable runs off the edge");
    }

    /** The takeover opening with animations on, held at two moments: tiles keep their height, every parent clips. */
    private void verifyOpening() throws Exception {
        show("ut5max");
        scale("1");
        try {
            main(() -> { service.ble.setSimulationCharging(false); service.ble.tickSimulation(); activity.redraw(); });
            SystemClock.sleep(900); settle();
            main(() -> { service.ble.setSimulationCharging(true); service.ble.tickSimulation(); activity.redraw();
                ValueAnimator reveal = (ValueAnimator) field(hero(), "reveal");
                require(reveal != null, "The takeover did not animate open");
                reveal.pause(); reveal.setCurrentFraction(.35f); });
            for (float at : new float[] {.35f, .7f}) {
                main(() -> { ValueAnimator reveal = (ValueAnimator) field(hero(), "reveal"); reveal.setCurrentFraction(at); });
                settle(); SystemClock.sleep(200); settle();
                main(() -> {
                    View stage = stage(), rows = (View) field(hero(), "rows");
                    require(field(hero(), "reveal") != null, "The opening ended early");
                    require(stage.getHeight() <= stage.getMinimumHeight(), "Opening at " + at + ": the stage " + stage.getHeight() + " over its least " + stage.getMinimumHeight());
                    require(clipsAll(), "Opening at " + at + ": a parent unclipped while animating");
                    require(rows.getHeight() == 0 || rows.getHeight() == rows.getMeasuredHeight(), "Opening at " + at + ": the tiles squeezed to " + rows.getHeight());
                });
                screenshot("widgets-opening-" + Math.round(at * 100));
            }
            main(() -> ((ValueAnimator) field(hero(), "reveal")).end());
            settle();
            main(() -> require(field(hero(), "reveal") == null && (!clipsAll() || !opens()), "Opened: the cable's parents did not open"));
        } finally { scale("0"); }
        checks.add(label + " Widgets opening (animations on, held at 35% and 70%): the stage at most its least height, every parent clipping");
    }

    private void verifyClean() throws Exception {
        for (String key : KEYS) {
            show(key);
            main(() -> {
                View hero = hero(), stage = stage();
                ChargePicture picture = picture();
                require(picture != null && picture.key.equals(key), "Clean: no " + key + " picture");
                TextView percent = (TextView) field(hero, "percent");
                require(percent.getParent() == stage, "Clean " + key + ": the percentage is not in the stage");
                requireFit(key, hero, stage, percent, true);
                require(clipsAll() == !(picture instanceof ChargeWireView && opens()), "Clean " + key + ": clipping at rest: " + clipsAll());
            });
            shot("clean-" + key);
            requirePillContrast("Clean " + key);
            requireCableAtEdge("Clean " + key);
            if (key.startsWith("ut5")) phases("clean-" + key);
        }
        checks.add(label + " Clean: each picture under the percentage with the prototype's relationship; the cable's parents open only at rest");
    }

    /**
     * The animator duration scale changed with Home on screen (Developer options in split screen; review of #483, P3):
     * the wire picture follows it without being hidden, shown again or restarted. From 0 (resting) to 1 it runs again;
     * at 0.5 and 2 it runs at that speed on the same start (ChargeWirePulses.Clock); back at 0 it rests. Waits by
     * polling, never for idle (the pulses keep the main thread busy while they run); 0 again in finally, and the
     * validation's own finally restores the scale it found.
     */
    private void verifyScaleFollowed() throws Exception {
        show("ut5max");
        main(() -> require(picture() instanceof ChargeWireView && !running() && hero().isShown(), "Scale: the UT5 MAX picture is not resting at 0"));
        AtomicReference<Long> started = new AtomicReference<>();
        StringBuilder seen = new StringBuilder();
        try {
            for (String value : new String[] {"1", "0.5", "2"}) {
                float target = Float.parseFloat(value);
                scale(value);
                await(() -> running() && (float) field(picture(), "durationScale") == target && clockDivisor() == target,
                        "Scale " + value + " with Home on screen: the picture did not follow (running " + running() + ", its scale " + field(picture(), "durationScale") + ")");
                main(() -> {
                    long start = clockStarted();
                    if (started.get() == null) started.set(start);
                    else require(start == started.get(), "Scale " + value + ": the animation restarted instead of changing speed");
                    seen.append(seen.length() == 0 ? "" : ", ").append(value).append(": running at ").append(clockDivisor());
                });
                SystemClock.sleep(400);
            }
            scale("0");
            await(() -> !running(), "Scale 0 with Home on screen: the pulses kept running");
            seen.append(", 0: resting");
            main(() -> require(hero().isShown(), "Scale: Home's charging view went away"));
        } finally { scale("0"); }
        checks.add(label + " Clean UT5 MAX, the animator duration scale changed with Home on screen: " + seen + " (no restart between speeds)");
    }
    private boolean running() { return (boolean) field(picture(), "running"); }
    private double clockDivisor() { return (double) invoke(field(picture(), "clock"), "divisor"); }
    private long clockStarted() { return (long) field(field(picture(), "clock"), "startedMs"); }

    /** The number and the picture as ChargePictureFit sized them, and the number at its fitted size. */
    private void requireFit(String key, View hero, View stage, TextView percent, boolean clean) {
        ChargePictureFit.Fit fit = (ChargePictureFit.Fit) field(hero, "fit");
        require(fit != null && fit.box > 0, key + ": no fit");
        require(Math.abs(percent.getTextSize() - fit.text) < .5f, key + ": the number " + percent.getTextSize() + " is not its fitted " + fit.text);
        float density = getTargetContext().getResources().getDisplayMetrics().density;
        float oneX = 60 * density * getTargetContext().getResources().getConfiguration().fontScale;
        require(fit.text <= ChargePictureFit.TEXT_PER_BOX * fit.box + .5f || fit.text <= oneX + .5f, key + ": the number " + fit.text + " outgrows its picture " + fit.box);
        int floor = (int) field(hero, "pictureFloorPx");
        float baseline = percent.getTop() + percent.getBaseline(), boxTop = floor - fit.box;
        require(baseline - boxTop <= ChargePictureFit.OVERLAP_PER_BOX * fit.box + (ChargePictureFit.SLACK_DP + 1) * density,
                key + ": the baseline " + (baseline - boxTop) + " px into the " + fit.box + " px picture");
        // On Clean the number stays on its picture however tall the stage (ChargePictureFit.attached): never above the
        // prototype's overlap, so it never floats over empty space above the stem.
        if (clean) require(baseline - boxTop >= ChargePictureFit.OVERLAP_PER_BOX * fit.box - density,
                key + ": the number floats " + (ChargePictureFit.OVERLAP_PER_BOX * fit.box - (baseline - boxTop)) + " px above its picture");
        require(boxTop >= -1 && floor <= stage.getHeight(), key + ": the picture leaves the stage (" + boxTop + ", " + floor + " of " + stage.getHeight() + ")");
    }

    private void phases(String prefix) throws Exception {
        try {
            for (int i = 0; i < PHASES.length; i++) {
                double t = PHASES[i][0]; Double w = PHASES[i][1];
                main(() -> { ChargeWireView.tourWatts = w; ChargeWireView.tourSeconds = t; picture().invalidateLive(); });
                settle(); SystemClock.sleep(300);
                shot(prefix + "-" + PHASE_NAMES[i]);
            }
        } finally { main(() -> { ChargeWireView.tourSeconds = Double.NaN; ChargeWireView.tourWatts = null; ChargePicture p = picture(); if (p != null) p.invalidateLive(); }); }
    }

    // ---- Fixtures -------------------------------------------------------------------------------------------

    private void show(String key) throws Exception {
        main(() -> ChargePicture.tourKey = key);
        redrawUntil(() -> { ChargePicture p = picture(); return hero().isShown() && field(hero(), "reveal") == null && p != null && p.key.equals(key); },
                "The " + key + " picture did not show");
    }
    private void seed(String theme, String text, String style) {
        require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                .putString("theme", theme).putFloat("text_scale", Float.parseFloat(text)).putString("home_style", style).putBoolean("home_style_chosen", true)
                .commit(), "Could not seed preferences");
    }
    private void relaunch(String theme, String text, String style) throws Exception {
        MainActivity old = activity; main(old::finish); SystemClock.sleep(800); settle();
        seed(theme, text, style); launch(true);
    }
    private void launch(boolean clean) throws Exception {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().getWidth() > 0, "App did not launch"); service = activity.service();
        SystemClock.sleep(1500); settle();
        main(() -> {
            if (!service.simulating()) require(service.setSimulation(true), "Simulation did not start");
            service.ble.setSimulationModel(""); service.ble.setSimulationCharging(true); service.ble.tickSimulation();
        });
        await(() -> service.ble.ready(), "The simulated UT5 MAX is not ready");
        await(() -> field(activity, "screen") != null, "No screen");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home"));
        settle();
        redrawUntil(() -> hero() != null && hero().isShown(), (clean ? "Clean" : "Widgets") + ": the charging view did not show on the simulator");
    }
    private void redraw() throws Exception { main(() -> activity.redraw()); settle(); SystemClock.sleep(200); settle(); }
    private void redrawUntil(BooleanSupplier condition, String message) throws Exception {
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> ok = new AtomicReference<>();
            main(() -> activity.redraw());
            settle(); SystemClock.sleep(150);
            main(() -> ok.set(condition.getAsBoolean()));
            if (Boolean.TRUE.equals(ok.get())) { SystemClock.sleep(300); settle(); return; }
        }
        throw new IllegalStateException(message);
    }
    /** The charging view: Clean's (tagged) when it shows, else Widgets' takeover. */
    private View hero() {
        View clean = root().findViewWithTag("clean-home-charging");
        if (clean != null) return clean;
        Object screen = field(activity, "screen");
        return screen instanceof DashboardScreen ? (View) field(screen, "chargingHero") : null;
    }
    private Object screen() { return field(activity, "screen"); }
    private View stage() { return (View) field(hero(), "stage"); }
    private ChargePicture picture() { View h = hero(); return h == null ? null : (ChargePicture) field(h, "picture"); }
    /**
     * Every parent of the picture's layers up to the nearest scroller clips its children (on Clean, from the charging
     * view's parent: Clean's view and stage never clip, as on the base branch, so its number can reach up).
     */
    private boolean clipsAll() {
        ChargePicture p = picture();
        if (p == null) return true;
        View hero = hero();
        boolean clean = hero != null && "clean-home-charging".equals(hero.getTag());
        for (android.view.ViewParent v = clean ? hero.getParent() : p.layers()[0].getParent(); v instanceof ViewGroup; v = v.getParent()) {
            if (!((ViewGroup) v).getClipChildren()) return false;
            if (v instanceof ScrollView) break;
        }
        return true;
    }
    /**
     * Where the wire picture's cable runs off the screen's edge, the drawn screen shows it there: the resting cable's
     * grey in the scroller's last pixel column, within the picture's layer (no parent's clip or padding cuts it short).
     */
    private void requireCableAtEdge(String where) throws Exception {
        AtomicReference<int[]> geometry = new AtomicReference<>();
        main(() -> {
            ChargePicture p = picture();
            if (!(p instanceof ChargeWireView) || !runsOff()) return;
            View layer = p.layers()[0], scroller = layer;
            while (scroller != null && !(scroller instanceof ScrollView)) scroller = scroller.getParent() instanceof View ? (View) scroller.getParent() : null;
            if (scroller == null) return;
            int[] at = new int[2], scrollAt = new int[2];
            layer.getLocationOnScreen(at); scroller.getLocationOnScreen(scrollAt);
            geometry.set(new int[] {scrollAt[0] + scroller.getWidth() - 1, at[1], at[1] + layer.getHeight(),
                    (int) field(p, "cableEdge"), (int) field(p, "cableCore")});
        });
        int[] g = geometry.get();
        if (g == null) return;
        Bitmap screen = getUiAutomation().takeScreenshot();
        require(screen != null, "Screenshot unavailable");
        try {
            int x = Math.min(g[0], screen.getWidth() - 1);
            boolean found = false;
            for (int y = Math.max(0, g[1]); y < Math.min(screen.getHeight(), g[2]) && !found; y++) {
                int c = screen.getPixel(x, y);
                found = near(c, g[3]) || near(c, g[4]);
            }
            require(found, where + ": the cable does not reach the screen's edge (x " + x + ", y " + g[1] + "-" + g[2] + ")");
        } finally { screen.recycle(); }
    }
    /**
     * The red Charging pill as it renders (council review of #527, round 2): on the real screen, its words' strongest
     * pixels against its own tint (the median of its padding beside the bolt and after the words, on its middle row)
     * keep 4.5:1, Ui's bar for small text. The percentage's shade once drew over the light pill and took it to about
     * 4.4:1; nothing drawn over the pill may lower it.
     */
    private void requirePillContrast(String where) throws Exception {
        AtomicReference<int[]> place = new AtomicReference<>();
        main(() -> {
            View hero = hero();
            TextView pill = null;
            for (String name : new String[] {"chip", "chipShort"}) {
                Object view = field(hero, name);
                if (view instanceof TextView && ((TextView) view).isShown() && ((TextView) view).getWidth() > 0) pill = (TextView) view;
            }
            require(pill != null, where + ": no Charging pill shows");
            int[] at = new int[2];
            pill.getLocationOnScreen(at);
            place.set(new int[] {at[0], at[1], pill.getWidth(), pill.getHeight(), pill.getPaddingLeft(), pill.getPaddingRight()});
        });
        int[] p = place.get();
        Bitmap screen = getUiAutomation().takeScreenshot();
        require(screen != null, "Screenshot unavailable");
        try {
            int x0 = Math.max(0, p[0]), y0 = Math.max(0, p[1]), x1 = Math.min(screen.getWidth(), p[0] + p[2]), y1 = Math.min(screen.getHeight(), p[1] + p[3]);
            int mid = (y0 + y1) / 2, edge = Math.max(2, ui(1.5f));
            ArrayList<Integer> tint = new ArrayList<>();
            for (int x = x0 + edge; x < x0 + p[4] - edge; x++) tint.add(screen.getPixel(x, mid));
            for (int x = x1 - p[5] + edge; x < x1 - edge; x++) tint.add(screen.getPixel(x, mid));
            require(tint.size() >= 4, where + ": the pill's padding is too narrow to sample (" + tint.size() + " px)");
            int bg = median(tint);
            double best = 0; int strongest = bg;
            for (int y = y0 + edge; y < y1 - edge; y++)
                for (int x = x0 + edge; x < x1 - edge; x++) {
                    int c = screen.getPixel(x, y);
                    double ratio = contrast(c, bg);
                    if (ratio > best) { best = ratio; strongest = c; }
                }
            String seen = String.format(Locale.US, "#%06X on #%06X, %.2f:1", strongest & 0xFFFFFF, bg & 0xFFFFFF, best);
            require(best >= 4.5, where + ": the Charging pill's words render at " + seen + ", under 4.5:1");
            checks.add(label + " " + where + ": the Charging pill renders at " + seen);
        } finally { screen.recycle(); }
    }
    private static int median(ArrayList<Integer> colours) {
        int out = 0xFF000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int[] channel = new int[colours.size()];
            for (int i = 0; i < channel.length; i++) channel[i] = colours.get(i) >> shift & 255;
            Arrays.sort(channel);
            out |= channel[channel.length / 2] << shift;
        }
        return out;
    }
    private static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + .05) / (Math.min(la, lb) + .05);
    }
    private static double luminance(int rgb) {
        double[] w = {.2126, .7152, .0722};
        double sum = 0;
        for (int i = 0; i < 3; i++) {
            double c = (rgb >> (16 - 8 * i) & 255) / 255.0;
            sum += w[i] * (c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4));
        }
        return sum;
    }
    private static boolean near(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) <= 30 && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) <= 30 && Math.abs((a & 255) - (b & 255)) <= 30;
    }

    /**
     * The wire picture's parents open at rest here: its cable runs off the screen's edge, or fades out past the edge a
     * parent clips it at (Clean's column on a wide portrait window; review of #483, P3-2). There the fade must still end
     * inside the scroller.
     */
    private boolean opens() {
        ChargePicture p = picture();
        if (!(p instanceof ChargeWireView)) return false;
        if (runsOff()) return true;
        if (!(boolean) field(p, "fadesPastClip")) return false;
        ChargeWireModels.Placement at = (ChargeWireModels.Placement) field(p, "placement");
        double end = at.left + (double) field(p, "fadeEnd") * at.scale;
        require(end < (float) field(p, "edge"), "The cable's fade ends past the screen's edge: " + end);
        return true;
    }

    /** The wire picture's cable runs off the screen's edge here (not faded out short of it on a wide window). */
    private boolean runsOff() {
        ChargePicture p = picture();
        return p instanceof ChargeWireView && Double.isInfinite((double) field(p, "fadeEnd")) && !Float.isNaN((float) field(p, "edge"));
    }

    // ---- Plumbing ---------------------------------------------------------------------------------------------

    private String foundScale;
    private void restoreFound() {
        if (foundScale != null) try {
            // An unset setting comes back as an explicit 1, Android's default, never a delete: Android keeps its
            // in-memory scale when the setting is deleted, so animations would stay off (the release sweep's rule, #507).
            shell("settings put global animator_duration_scale " + (foundScale.equals("null") || foundScale.isEmpty() ? "1" : foundScale));
        } catch (Throwable ignored) {}
    }
    /**
     * Sets the animator scale and waits for the app's own ValueAnimator.getDurationScale() to take it. WindowManager sends
     * a new scale only to processes whose windows it has registered, and only when the setting changes: a process whose
     * first window opened its session while the setting was changing (setup puts 0 just before launch) can keep the old
     * scale, and putting the same value again sends nothing (3 of 19 runs under load failed here within 10 s of launch;
     * #521's review). So when the app has not taken it within 10 s, the setting is changed away and back, which makes
     * WindowManager send it again to every registered window's process; up to three times, about 45 s in all. A scale the
     * app still has not taken fails, naming the app's scale and the setting.
     */
    private void scale(String value) throws Exception {
        if (foundScale == null) foundScale = shell("settings get global animator_duration_scale").trim();
        shell("settings put global animator_duration_scale " + value);
        float target = Float.parseFloat(value);
        for (int resent = 0; ; resent++) {
            if (within(10000, () -> ValueAnimator.getDurationScale() == target)) return;
            if (resent == 3) break;
            shell("settings put global animator_duration_scale " + (target == 1 ? "0.5" : "1"));
            shell("settings put global animator_duration_scale " + value);
        }
        AtomicReference<Float> app = new AtomicReference<>(); main(() -> app.set(ValueAnimator.getDurationScale()));
        throw new IllegalStateException("Animator scale did not reach " + value + " (the app's is " + app.get() + ", the setting "
                + shell("settings get global animator_duration_scale").trim() + ", sent again 3 times)");
    }
    private String shell(String command) throws Exception {
        ParcelFileDescriptor pipe = getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe)) {
            StringBuilder out = new StringBuilder(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) >= 0) out.append(new String(buffer, 0, n, "UTF-8"));
            return out.toString();
        }
    }
    private int ui(float dp) { return Math.round(dp * getTargetContext().getResources().getDisplayMetrics().density); }
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "charge-picture-validation"); d.mkdirs(); return d; }
    private void shot(String name) throws Exception { settle(); SystemClock.sleep(300); waitForIdleSync(); screenshot(name); }
    private void screenshot(String name) throws Exception {
        File file = new File(dir(), label + "-" + name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); com.jjricks.volt.tests.TextCensus.write(this, file);
    }
    private void admit() {
        String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hw.contains("ranchu") || hw.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"}) require(prefs(name).getAll().isEmpty(), "Refusing existing preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing app data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant emulator Nearby permission");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Disable emulator Bluetooth first");
    }
    private void assertNoRadio() {
        main(() -> {
            for (String name : new String[]{"gatt", "writer", "scanner", "mutation"}) require(field(service.ble, name) == null, "Validation touched " + name);
            require(((Collection<?>) field(service.ble, "queue")).isEmpty(), "Validation queued radio bytes");
        });
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View) field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(160); waitForIdleSync(); }
    private void await(BooleanSupplier condition, String message) {
        if (!within(10000, condition)) throw new IllegalStateException(message);
    }
    /** Whether the condition (checked on the main thread) holds within {@code ms}. */
    private boolean within(long ms, BooleanSupplier condition) {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) { AtomicReference<Boolean> ready = new AtomicReference<>(); main(() -> ready.set(condition.getAsBoolean())); if (Boolean.TRUE.equals(ready.get())) return true; SystemClock.sleep(40); }
        return false;
    }
    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Class<?> type = target.getClass(); Method method = null;
            while (type != null && method == null) { try { method = type.getDeclaredMethod(name, types); } catch (NoSuchMethodException missing) { type = type.getSuperclass(); } }
            if (method == null) throw new NoSuchMethodException(name);
            method.setAccessible(true); return method.invoke(target, values); }
        catch (java.lang.reflect.InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
    private Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
}
