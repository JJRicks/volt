package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.*;

/**
 * Disposable empty emulator, radio off. The charging pill's states on Home's charging view (the owner's concept B,
 * Nov 19 2026; ChargingPillStatus), on Widgets and Clean, in the themes asked for, at the emulator's current size and
 * the Text size asked for (argument text, default 1). The developer simulator charges as the UT5 MAX; the slowdown is
 * set through ScooterService's test-only testChargeSlowdown (the simulator never slows), and the readings are the
 * fixture's. In order: Charging, Charging to an 85% limit, slowed hot (45 C), slowed cold (11.7 C), slowed for no named
 * reason (28 C), each slowed one without a limit, and back to the limit. The temperature unit is Fahrenheit (argument unit "c" for Celsius).
 * Arguments hot_c (default 45) and amps (default 9.6) set the hot state's battery temperature and the readings' current,
 * e.g. 36 and 1.04 for a charge held low right after a ride (2.0.6).
 *
 * <p>Each state checks: the hero's state; the line shown (Clean's line or its short line) holds one of the state's
 * rungs, the longest that fits on one line where it is the full line, one line with no ellipsis while slowed;
 * TalkBack's description is the full text while slowed and the words themselves otherwise; the words' ink is the
 * state's, 4.5:1 or more on the pill fill sampled from the screen; the icon is the pill's own, as tall as the words
 * call for. While slowed on Clean the pill shows even where the slot is short. A change between states crossfades with
 * Android's animations on and changes at once with them off. Each state is captured, cropped to the pill and the
 * percentage, with a contact sheet per Home style (a JPEG beside them). Sends no scooter command.
 */
public final class ChargingPillValidation extends ValidationInstrumentation {
    private static final String[] STATES = {"charging", "limit", "hot", "cold", "slowed", "back"};
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private String label = "";
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    private String animatorScale;

    @Override public void onCreate(Bundle arguments) {
        args = arguments == null ? new Bundle() : arguments;
        // It checks the pill's crossfade, which the endless-motion hold would draw at once: motion runs as on a phone.
        runEndlessMotion();
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic UT5 Max", "000000000001", 0); p.serial = "Q2345ZZ9ES7777777";
                profiles.update(p); profiles.select(p.id); OnboardingScreen.markDone(getTargetContext());
            });
            animatorScale = shell("settings get global animator_duration_scale").trim();
            verifyStates();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot(new File(dir(), "failure-" + label + ".png"), null); } catch (Throwable ignored) {}
        } finally {
            try { setSlowdown(null); } catch (Throwable ignored) {}
            try { restoreAnimations(); } catch (Throwable ignored) {}
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> { if (service != null && service.simulating()) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void verifyStates() throws Exception {
        String size = args.getString("size_label", "size"), text = args.getString("text", "1");
        boolean fahrenheit = !"c".equals(args.getString("unit", "f"));
        String[] themes = args.getString("themes", "dark,light").split(",");
        for (String style : args.getString("styles", "widgets,clean").split(",")) {
            Map<String, File[]> sheet = new LinkedHashMap<>();
            for (String theme : themes) {
                label = size + (text.equals("1") ? "" : "-text" + text) + "-" + style + "-" + theme;
                launch(style, theme, Float.parseFloat(text), fahrenheit);
                File[] column = new File[STATES.length];
                for (int i = 0; i < STATES.length; i++) column[i] = state(STATES[i], style.equals("clean"), fahrenheit, i > 0);
                motionOff(fahrenheit);
                sheet.put(theme, column);
            }
            contactSheet(size + (text.equals("1") ? "" : "-text" + text) + "-" + style, sheet);
        }
        MainActivity old = activity; main(() -> { service.setSimulation(false); old.finish(); }); SystemClock.sleep(800);
    }

    /** Opens Home on the simulator's charge, then holds it to the fixture's readings. */
    private void launch(String style, String theme, float text, boolean fahrenheit) throws Exception {
        if (activity != null) { MainActivity old = activity; main(() -> { service.setSimulation(false); old.finish(); }); SystemClock.sleep(1200); }
        setSlowdown(null);
        require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                .putBoolean("developer_options", true).putString("theme", theme).putFloat("text_scale", text)
                .putString("temperature_unit", fahrenheit ? "f" : "c")
                .putString("home_style", style).putBoolean("home_style_chosen", true).commit(), "Could not seed preferences");
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().getWidth() > 0, "App did not launch"); service = activity.service();
        SystemClock.sleep(1500); sync();
        main(() -> {
            if (!service.simulating()) require(service.setSimulation(true), "Simulation did not start");
            service.ble.setSimulationModel(""); service.ble.setSimulationCharging(true); service.ble.tickSimulation();
        });
        await(() -> service.ble.ready(), "The simulated scooter is not ready");
        await(() -> field(activity, "screen") != null, "No screen");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home"));
        long end = SystemClock.uptimeMillis() + 15000;
        boolean shown = false;
        while (!shown && SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> ok = new AtomicReference<>();
            main(() -> activity.redraw());
            SystemClock.sleep(400); sync();
            main(() -> { View h = hero(); ok.set(h != null && h.isShown() && field(h, "reveal") == null); });
            shown = Boolean.TRUE.equals(ok.get());
        }
        require(shown, label + ": the charging view did not show on the simulator");
        // From here the fixture's readings: Home no longer follows the simulator's ticks.
        main(() -> {
            ((android.os.Handler) field(activity, "handler")).removeCallbacks((Runnable) field(activity, "pulse"));
            service.unlisten((Runnable) field(activity, "serviceChanged"));
        });
        SystemClock.sleep(1500);
        refresh(readings(62, 9.6, null));
        SystemClock.sleep(600);
    }

    /** One state: set it, check the change's motion, let it settle, check the pill, capture it. */
    private File state(String name, boolean clean, boolean fahrenheit, boolean expectMotion) throws Exception {
        // The limit only for its own states: a slowed pill replaces either, and without the limit's two-line words and
        // second reading the slot is the one where Clean's normal line makes way at large text (the kept line's case).
        Double limit = name.equals("limit") || name.equals("back") ? 85d : null;
        Object[] slowdown = name.equals("hot") ? new Object[] {"HOT", Double.parseDouble(args.getString("hot_c", "45"))} : name.equals("cold") ? new Object[] {"COLD", 11.7}
                : name.equals("slowed") ? new Object[] {"PLAIN", 28.0} : null;
        setSlowdown(slowdown == null ? null : state((String) slowdown[0], (Double) slowdown[1]));
        String kindName = name.equals("charging") ? "CHARGING" : name.equals("limit") || name.equals("back") ? "LIMIT"
                : name.equals("slowed") ? "SLOWED" : name.toUpperCase(Locale.ROOT);
        Map<String, TelemetryHistory.Reading> readings = readings(62, Double.parseDouble(args.getString("amps", "9.6")), limit);
        AtomicReference<String> motion = new AtomicReference<>();
        main(() -> {
            View h = hero();
            Object before = field(h, "pillKind");
            invoke(h, "refresh", new Class<?>[]{Map.class}, readings);
            boolean changed = !String.valueOf(before).equals(kindName);
            Object fade = field(h, "pillFade");
            motion.set(changed ? (fade != null ? "crossfade" : "instant") : "unchanged");
        });
        if (expectMotion && !motion.get().equals("unchanged") && android.animation.ValueAnimator.areAnimatorsEnabled())
            require(motion.get().equals("crossfade"), label + " " + name + ": a change of state crossfades with animations on (" + motion.get() + ")");
        SystemClock.sleep(Math.max(600, 3 * 220)); sync(); SystemClock.sleep(300); sync();
        return checkAndCapture(name, kindName, clean, fahrenheit, slowdown, motion.get());
    }

    private File checkAndCapture(String name, String kindName, boolean clean, boolean fahrenheit, Object[] slowdown, String motion) throws Exception {
        AtomicReference<int[]> geometry = new AtomicReference<>();
        AtomicReference<String> facts = new AtomicReference<>();
        AtomicReference<Integer> ink = new AtomicReference<>();
        main(() -> {
            View h = hero();
            require(h != null && h.isShown(), label + " " + name + ": the charging view is not showing");
            require(String.valueOf(field(h, "pillKind")).equals(kindName), label + " " + name + ": the pill's state is " + field(h, "pillKind"));
            require(field(h, "pillFade") == null && ((Float) field(h, "pillT")) == 1f, label + " " + name + ": the crossfade ended");
            String[] rungs = (String[]) field(h, "pillRungs");
            boolean slowed = slowdown != null;
            TextView chip = (TextView) field(h, "chip"), chipShort = (TextView) field(h, "chipShort");
            TextView shown = chip.getVisibility() == View.VISIBLE ? chip : chipShort != null && chipShort.getVisibility() == View.VISIBLE ? chipShort : null;
            if (slowed) require(shown != null, label + " " + name + ": a slowed pill always shows");
            float density = h.getResources().getDisplayMetrics().density;
            String rung = "line hidden (the slot's rule for the normal pill)";
            if (shown != null) {
                CharSequence words = shown.getText();
                // Clean's short line for a normal state is today's "Charging" (its height rule), not one of the state's rungs.
                boolean normalShort = shown == chipShort && !slowed;
                int index = normalShort ? 0 : Arrays.asList(rungs).indexOf(words.toString());
                require(index >= 0, label + " " + name + ": the pill's words '" + words + "' are one of its rungs " + Arrays.toString(rungs));
                if (shown == chipShort && slowed) {
                    require(index == rungs.length - 1, label + " " + name + ": Clean's short line holds the shortest rung");
                    // The short form stands in only where it is shorter than the line (ChargingFit.plan, as for the
                    // normal pill): never where the line already fits on one line (council P3 on #568).
                    require(chipShort.getMeasuredHeight() < chip.getMeasuredHeight(), label + " " + name
                            + ": the short line shows only where the line is taller (" + chipShort.getMeasuredHeight() + " vs " + chip.getMeasuredHeight() + " px)");
                }
                // Whichever line shows while slowed, its words are the longest rung that fits on one line.
                if ((shown == chip || slowed) && rungs.length > 1) {
                    int room = (Integer) field(h, "pillRoom");
                    int extra = chip.getCompoundPaddingLeft() + chip.getCompoundPaddingRight();
                    for (int i = 0; i < index; i++)
                        require(chip.getPaint().measureText(rungs[i]) + extra > room, label + " " + name + ": a longer rung '" + rungs[i] + "' fits " + room + " px");
                    require(index == rungs.length - 1 || chip.getPaint().measureText(rungs[index]) + extra <= room, label + " " + name + ": the rung fits");
                }
                if (slowed) {
                    require(shown.getLineCount() == 1, label + " " + name + ": a slowed pill is one line (" + shown.getLineCount() + ")");
                    require(shown.getLayout() == null || shown.getLayout().getEllipsisCount(0) == 0, label + " " + name + ": no ellipsis");
                    require(rungs[0].contentEquals(shown.getContentDescription()), label + " " + name + ": TalkBack reads the full text, not '" + shown.getContentDescription() + "'");
                } else {
                    require(shown.getContentDescription() == null, label + " " + name + ": the normal pill is read by its words");
                }
                if (slowed && slowdown[1] != null) {
                    long degrees = Math.round(fahrenheit ? (Double) slowdown[1] * 1.8 + 32 : (Double) slowdown[1]);
                    if (!name.equals("slowed")) require(rungs[0].contains(degrees + "°"), label + " " + name + ": the temperature in the unit setting (" + degrees + "°)");
                    else require(!rungs[0].contains("°"), label + " " + name + ": plain slowed names no temperature");
                }
                Drawable icon = shown.getCompoundDrawablesRelative()[0];
                require(icon != null && icon.getClass().getSimpleName().equals("PillIcon"), label + " " + name + ": the pill's own icon");
                int side = Math.round(shown.getTextSize() * 14 / 12.5f);
                require(Math.abs(icon.getBounds().height() - side) <= 1, label + " " + name + ": the icon (" + icon.getBounds().height() + " px) grows with the words (" + side + " px)");
                ink.set(shown.getCurrentTextColor());
                require(shown.getCurrentTextColor() == (Integer) field(h, "pillToInk"), label + " " + name + ": the words in the state's ink");
                rung = (normalShort ? "Clean's short line" : "rung " + (index + 1) + " of " + rungs.length) + " '" + words + "' (" + (shown == chip ? "line" : "short line")
                        + ", " + Math.round(shown.getWidth() / density) + " dp wide, room " + Math.round(((Integer) field(h, "pillRoom")) / density) + " dp)";
            }
            View percent = (View) field(h, "percent");
            Rect hr = new Rect(), pr = new Rect(), nr = new Rect();
            h.getGlobalVisibleRect(hr);
            boolean pillShown = shown != null && shown.getGlobalVisibleRect(pr);
            percent.getGlobalVisibleRect(nr);
            if (slowed && pillShown) {
                // The kept line sits inside the slot, above the percentage, and the percentage stays in the slot.
                require(pr.top >= hr.top && pr.bottom <= hr.bottom, label + " " + name + ": the pill inside the slot " + pr + " in " + hr);
                require(nr.bottom <= hr.bottom, label + " " + name + ": the percentage inside the slot " + nr + " in " + hr);
                int digitsTop = nr.top + percent.getPaddingTop();
                rung += ", " + Math.round((digitsTop - pr.bottom) / density) + " dp to the percentage's top padding edge";
            }
            int pad = Math.round(12 * density);
            geometry.set(new int[] {hr.left, Math.max(0, pillShown ? pr.top - pad : hr.top), hr.right,
                    Math.max(nr.bottom, pillShown ? pr.bottom : hr.top) + Math.round(8 * density),
                    pillShown ? pr.right - Math.round(5 * density) : -1, pillShown ? pr.centerY() : -1});
            facts.set(label + " " + name + ": state " + kindName + ", " + rung + ", change " + motion);
        });
        File file = new File(dir(), label + "-" + name + ".png");
        String sample = screenshot(file, geometry.get());
        if (ink.get() != null && sample != null) {
            int fill = Integer.parseInt(sample, 16) | 0xFF000000;
            double ratio = contrast(ink.get() | 0xFF000000, fill);
            String line = String.format(Locale.US, "%s; ink #%06X on the sampled fill #%06X: %.2f:1", facts.get(), ink.get() & 0xFFFFFF, fill & 0xFFFFFF, ratio);
            record(line);
            require(ratio >= 4.5, line + ": under 4.5:1");
        } else {
            record(facts.get());
        }
        return file;
    }

    /** With Android's animations off (Remove animations), a change of state is drawn at once. */
    private void motionOff(boolean fahrenheit) throws Exception {
        shell("settings put global animator_duration_scale 0");
        SystemClock.sleep(800);
        AtomicReference<String> after = new AtomicReference<>();
        setSlowdown(state("COLD", 11.7));
        Map<String, TelemetryHistory.Reading> readings = readings(62, 9.6, 85d);
        main(() -> {
            View h = hero();
            invoke(h, "refresh", new Class<?>[]{Map.class}, readings);
            after.set(field(h, "pillKind") + " fade " + (field(h, "pillFade") == null ? "none" : "running") + " t " + field(h, "pillT"));
        });
        require(after.get().equals("COLD fade none t 1.0"), label + ": with animations off the change is instant (" + after.get() + ")");
        record(label + " animations off: a change of state is instant (" + after.get() + ")");
        setSlowdown(null);
        main(() -> invoke(hero(), "refresh", new Class<?>[]{Map.class}, readings));
        restoreAnimations();
    }

    private void restoreAnimations() throws Exception {
        String scale = animatorScale == null || animatorScale.isEmpty() || animatorScale.equals("null") ? "1" : animatorScale;
        shell("settings put global animator_duration_scale " + scale);
        SystemClock.sleep(500);
    }

    // ---- The slowdown hook ----------------------------------------------------------------------------------

    /** A ChargeSlowdown.State, slowed with this cause and battery temperature (its constructor is the app's own). */
    private static Object state(String cause, Double temperatureC) {
        try {
            Class<?> causes = Class.forName("com.jjricks.volt.ChargeSlowdown$Cause");
            Object value = null;
            for (Object c : causes.getEnumConstants()) if (((Enum<?>) c).name().equals(cause)) value = c;
            Class<?> type = Class.forName("com.jjricks.volt.ChargeSlowdown$State");
            Constructor<?> make = type.getDeclaredConstructor(boolean.class, causes, Double.class, boolean.class);
            make.setAccessible(true);
            return make.newInstance(true, value, temperatureC, false);
        } catch (Exception e) { throw new IllegalStateException("ChargeSlowdown.State", e); }
    }

    /** ScooterService's test-only slowdown (null: the live one, which is none on the simulator). */
    private static void setSlowdown(Object state) {
        try {
            Field f = ScooterService.class.getDeclaredField("testChargeSlowdown");
            f.setAccessible(true);
            f.set(null, state);
        } catch (Exception e) { throw new IllegalStateException("testChargeSlowdown", e); }
    }

    // ---- Fixtures and captures --------------------------------------------------------------------------------

    private void refresh(Map<String, TelemetryHistory.Reading> values) {
        main(() -> invoke(hero(), "refresh", new Class<?>[]{Map.class}, values));
        SystemClock.sleep(300); sync();
    }
    private static Map<String, TelemetryHistory.Reading> readings(double percent, double amps, Double limit) {
        long wall = System.currentTimeMillis();
        Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>();
        List<Object[]> rows = new ArrayList<>(Arrays.asList(new Object[][]{{"charging_state", 1d}, {"voltage_v", 52d}, {"current_a", -amps}, {"battery_percent", percent}, {"rated_capacity_ah", 14.5d}}));
        if (limit != null) rows.add(new Object[] {"charge_limit_percent", limit});
        for (Object[] r : rows)
            values.put((String) r[0], new TelemetryHistory.Reading((String) r[0], (String) r[0], "Synthetic fixture", String.valueOf(r[1]), "", (Double) r[1], wall, true));
        return values;
    }
    private View hero() {
        View clean = root().findViewWithTag("clean-home-charging");
        if (clean != null) return clean;
        Object screen = field(activity, "screen");
        return screen instanceof DashboardScreen ? (View) field(screen, "chargingHero") : null;
    }

    /** Saves the screen cropped to {@code g} (left, top, right, bottom), returning the fill sampled at (g[4], g[5]) as hex. */
    private String screenshot(File file, int[] g) throws Exception {
        sync();
        Bitmap full = getUiAutomation().takeScreenshot(); require(full != null, "Screenshot unavailable");
        String sample = null;
        Bitmap out = full;
        if (g != null) {
            if (g[4] >= 0 && g[5] >= 0 && g[4] < full.getWidth() && g[5] < full.getHeight()) sample = String.format(Locale.US, "%06X", full.getPixel(g[4], g[5]) & 0xFFFFFF);
            int left = Math.max(0, g[0]), top = Math.max(0, g[1]), right = Math.min(full.getWidth(), g[2]), bottom = Math.min(full.getHeight(), g[3]);
            out = Bitmap.createBitmap(full, left, top, Math.max(1, right - left), Math.max(1, bottom - top));
        }
        try (FileOutputStream stream = new FileOutputStream(file)) { out.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        if (out != full) out.recycle();
        full.recycle();
        screenshots.add(file.getAbsolutePath());
        TextCensus.write(this, file);
        return sample;
    }

    /** One picture per Home style: the themes across, the states down, at half size, labelled. */
    private void contactSheet(String name, Map<String, File[]> columns) throws Exception {
        float scale = .5f;
        int cw = 0, ch = 0;
        for (File[] col : columns.values()) for (File f : col) {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            cw = Math.max(cw, Math.round(o.outWidth * scale)); ch = Math.max(ch, Math.round(o.outHeight * scale));
        }
        int head = 84, side = 150, gap = 6;
        Bitmap sheet = Bitmap.createBitmap(side + columns.size() * (cw + gap), head + STATES.length * (ch + gap), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sheet);
        c.drawColor(Color.rgb(128, 128, 128));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.WHITE); paint.setTextSize(26); paint.setFakeBoldText(true);
        c.drawText(name, 8, 30, paint);
        int x = side;
        for (Map.Entry<String, File[]> col : columns.entrySet()) {
            c.drawText(col.getKey(), x + 8, 72, paint);
            int y = head;
            for (int r = 0; r < STATES.length; r++) {
                Bitmap b = BitmapFactory.decodeFile(col.getValue()[r].getAbsolutePath());
                Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)), Math.max(1, Math.round(b.getHeight() * scale)), true);
                c.drawBitmap(s, x, y, null);
                if (x == side) c.drawText(STATES[r], 8, y + 34, paint);
                b.recycle(); s.recycle();
                y += ch + gap;
            }
            x += cw + gap;
        }
        // A JPEG, not a capture: its text is the captures' beside it, each with its census.
        File file = new File(dir(), "sheet-" + name + ".jpg");
        try (FileOutputStream out = new FileOutputStream(file)) { sheet.compress(Bitmap.CompressFormat.JPEG, 92, out); }
        sheet.recycle();
    }

    private static double luminance(int c) {
        double[] v = {Color.red(c) / 255.0, Color.green(c) / 255.0, Color.blue(c) / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= .04045 ? v[i] / 12.92 : Math.pow((v[i] + .055) / 1.055, 2.4);
        return .2126 * v[0] + .7152 * v[1] + .0722 * v[2];
    }
    private static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + .05) / (Math.min(la, lb) + .05);
    }

    // ---- Plumbing ---------------------------------------------------------------------------------------------

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
    private void record(String line) { checks.add(line); Log.i("ChargingPillValidation", line); }
    private String shell(String command) throws Exception {
        ParcelFileDescriptor pfd = getUiAutomation().executeShellCommand(command);
        StringBuilder out = new StringBuilder();
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buffer = new byte[1024]; int n;
            while ((n = in.read(buffer)) > 0) out.append(new String(buffer, 0, n, "UTF-8"));
        }
        return out.toString();
    }
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "charging-pill-validation"); d.mkdirs(); return d; }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View) field(activity, "root"); }
    /**
     * A pass through the main thread's queue. Not waitForIdleSync: the charging glow animates without end, so the main
     * thread need never be idle (COMMON.md, Nov 19 2026).
     */
    private void sync() { main(() -> {}); SystemClock.sleep(120); main(() -> {}); }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) { AtomicReference<Boolean> ready = new AtomicReference<>(); main(() -> ready.set(condition.getAsBoolean())); if (Boolean.TRUE.equals(ready.get())) return; SystemClock.sleep(40); }
        throw new IllegalStateException(message);
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
        catch (Exception error) { throw new IllegalStateException("Method " + name + ": " + error, error); }
    }
}
