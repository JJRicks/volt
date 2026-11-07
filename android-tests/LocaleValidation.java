package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.app.LocaleManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.jjricks.volt.DashboardConfig;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.QuickAccessActions;
import com.jjricks.volt.RideStore;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * Pseudo-locale and shipped-language layout checks (plan sections 6.2 and 7.2 step 6), skeleton: B0b walks
 * the five main pages and the More sections, B17 the setup pages; later batches add the sheets, BackupActivity
 * and ShizukuSetupActivity. Report only: the result is PASS whenever the walk completes, and the findings are
 * counted in the result bundle and listed in locale-validation/<variant>-report.txt. Needs API 33 or later.
 * en-XA and ar-XB need the pseudo APK (VOLT_PSEUDO_LOCALES=1 build.sh); with the release APK it says the
 * pseudo resources did not resolve. Any other tag is a shipped language (status beta or stable in
 * l10n/lang/<tag>.tsv) on the normal app APK; the walk fails unless the app's l10n_tag string reads that tag.
 *
 *   -e locale en-XA|ar-XB|<tag>  (default en-XA)   -e rtl true|false  (a shipped language; default false)
 *   -e theme dark|light   -e text_scale 1|1.4   -e variant <name>   -e walk pages|scooter  (default pages)
 *
 * -e walk scooter: the states the page walk never reaches, since it has no scooter and records no ride.
 * A synthetic profile with seeded maintenance reminders (written straight to app_settings, never the
 * simulator's in-memory document): the Scooter tab's Reminders row ("1 due", then "5 reminders") and the
 * Maintenance page with a DUE, SOON, SNOOZED, OK and waiting reminder. Then, on the developer simulator
 * (ModelHomeValidation's way), Home with every Quick access shortcut and Setting tile in batches of 8
 * (each batch as one Quick access widget and two Setting tiles widgets). Then riding mode: connected,
 * the red recording pill (a synthetic ride started in the service's own RideStore, as
 * AppDataMaintenanceValidation does), the same pill reconnecting and not connected after the simulator
 * stops (reconnecting is BleController's recoverable-loss flag set by reflection: the emulator has no
 * scooter to lose), each at both halves of the pill's time/problem alternation, the plain pill's
 * Not connected and Reconnecting, and Ride saved after the rider's stop and after an automatic one
 * (the service's own stop path). Never connects Bluetooth.
 *
 * The language is set from inside the app process with LocaleManager.setApplicationLocales, as the
 * Language row will, and cleared again at the end. Views are found by tag or field only, never by text.
 *   en-XA: text outside aapt's [..] pseudo groups that still has two plain letters was not extracted;
 *          more than one [..] group in one view means pieces were glued; a visible %1$s means a template
 *          got no arguments; plus TextFit's fit checks.
 *   ar-XB: every window root must lay out right to left; plus TextFit's fit checks.
 *   <tag>: TextFit's fit checks; with -e rtl true (rtl in l10n/lang/<tag>.tsv) also ar-XB's direction check.
 */
public final class LocaleValidation extends Instrumentation {
    private static final String[] PAGES = {"home", "stats", "controls", "rides", "more"};
    /** OnboardingScreen's steps WELCOME (0) to DONE (10), in order, as screenshot labels. */
    private static final String[] SETUP_STEPS = {"welcome", "path", "prepare", "usb", "computer", "helper",
            "import", "identity", "nearby", "connect", "done"};
    private static final String[] MORE_SECTIONS = {"App", "Scooters", "Security", "Lab", "Data", "About", "Developer"};
    private static final Pattern PSEUDO_GROUP = Pattern.compile("\\[[^\\[\\]]*\\]");
    private static final Pattern PLAIN_WORD = Pattern.compile("[A-Za-z]{2,}");
    private static final Pattern UNFILLED = Pattern.compile("%\\d+\\$[sdf]");
    private static final Pattern LANGUAGE_TAG = Pattern.compile("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*");
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted, localeSet;
    private String locale, profileId;
    private boolean pseudo, rtl, scooterWalk;
    private final ArrayList<String> screenshots = new ArrayList<>();
    private final List<String> unextracted = new ArrayList<>(), glued = new ArrayList<>(), unfilled = new ArrayList<>(),
            fit = new ArrayList<>(), direction = new ArrayList<>();
    private int screens;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        locale = arguments.getString("locale", "en-XA");
        try {
            pseudo = locale.equals("en-XA") || locale.equals("ar-XB");
            require(pseudo || LANGUAGE_TAG.matcher(locale).matches() && !locale.equals("en"), "locale must be en-XA, ar-XB or a shipped language tag");
            rtl = locale.equals("ar-XB") || !pseudo && Boolean.parseBoolean(arguments.getString("rtl", "false"));
            String walk = arguments.getString("walk", "pages");
            require(walk.equals("pages") || walk.equals("scooter"), "walk must be pages or scooter");
            scooterWalk = walk.equals("scooter");
            if (Build.VERSION.SDK_INT < 33) {
                result.putString("result", "PASS"); result.putString("skipped", "LocaleManager needs API 33");
                return;
            }
            admit(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("theme", arguments.getString("theme", "dark"))
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1"))).commit(), "Fixture settings failed");
            main(() -> OnboardingScreen.markDone(getTargetContext()));
            if (scooterWalk) seedScooter();
            setLocale(locale); localeSet = true;
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.getWindow().getDecorView().isAttachedToWindow() && activity.getWindow().getDecorView().getWidth() > 0, "App did not launch");
            settle();
            Locale applied = activity.getResources().getConfiguration().getLocales().get(0);
            if (pseudo) {
                String expected = locale.equals("en-XA") ? "XA" : "XB";
                require(expected.equals(applied.getCountry()), "the activity did not get " + locale);
                int label = getTargetContext().getResources().getIdentifier("app_name", "string", getTargetContext().getPackageName());
                require(label != 0 && !"Volt".equals(activity.getString(label)), "the pseudo-locale resources did not resolve; install the pseudo APK (VOLT_PSEUDO_LOCALES=1 build.sh)");
            } else {
                require(Locale.forLanguageTag(locale).getLanguage().equals(applied.getLanguage()), "the activity did not get " + locale + " (it has " + applied.toLanguageTag() + ")");
                int tag = getTargetContext().getResources().getIdentifier("l10n_tag", "string", getTargetContext().getPackageName());
                String resolved = tag == 0 ? null : activity.getString(tag);
                require(locale.equals(resolved), "the " + locale + " resources did not resolve: l10n_tag reads " + resolved
                        + "; is " + locale + " shipped (res/values-<qual>/, beta or stable) in this APK?");
            }
            if (scooterWalk) {
                walkMaintenance(); walkHomeTiles(); walkRiding();
            } else {
                for (String page : PAGES) {
                    select(page); check("page-" + page);
                }
                select("more");
                for (String section : MORE_SECTIONS) {
                    main(() -> invoke(field(activity, "screen"), "selectSection", new Class<?>[]{String.class}, section)); settle();
                    check("more-" + section.toLowerCase(Locale.ROOT));
                }
                main(() -> invoke(field(activity, "screen"), "selectSection", new Class<?>[]{String.class}, "")); settle();
                walkSetup();
            }
            writeReport();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
        } finally {
            result.putString("locale", locale); result.putBoolean("pseudo", pseudo); result.putInt("screens", screens);
            result.putString("walk", scooterWalk ? "scooter" : "pages");
            result.putInt("unextracted", unextracted.size()); result.putInt("glued", glued.size()); result.putInt("unfilled", unfilled.size());
            result.putInt("fit_problems", fit.size()); result.putInt("direction_problems", direction.size());
            result.putStringArrayList("screenshots", screenshots); result.putBoolean("physical_device_tested", false); result.putBoolean("report_only", true);
            if (activity != null) try { main(() -> { ScooterService service = activity.service();
                if (service != null) { if (service.isRecording()) service.stopRide(); service.setSimulation(false); } }); } catch (Throwable ignored) { }
            if (activity != null) try { main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            if (localeSet) try { setLocale(""); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /**
     * B17: every setup page on the computer-capture route, opened as "Run setup again" resumes them
     * (the saved step, then a rebuild), so no button is tapped and nothing connects or imports. Setup is
     * marked done again afterwards.
     */
    private void walkSetup() throws Exception {
        for (int setupStep = 0; setupStep < SETUP_STEPS.length; setupStep++) {
            final int savedStep = setupStep;
            require(getTargetContext().getSharedPreferences(OnboardingScreen.PREFS, Context.MODE_PRIVATE).edit().clear()
                    .putBoolean("rerun", true).putInt("step", savedStep).putString("path", "capture").commit(), "Setup fixture failed");
            main(() -> activity.rebuild()); settle();
            require(field(activity, "onboarding") != null, "Setup walk: setup did not open at step " + savedStep);
            check("setup-" + SETUP_STEPS[savedStep]);
        }
        main(() -> { OnboardingScreen.markDone(getTargetContext()); activity.rebuild(); }); settle();
    }

    // ---- -e walk scooter -----------------------------------------------------------------------

    private static final long DAY = 86_400_000L;
    /** MaintenanceStore.PREFIX and MaintenanceScreen.ROW (package-private in the app). */
    private static final String MAINTENANCE_PREFIX = "maintenance_v1_", MAINTENANCE_ROW = "maintenance_reminders";
    /** RecordingPill.ALTERNATE_MS: an alternating recording pill shows the time, then the link problem, this long each. */
    private static final long PILL_HALF_MS = 3000;

    /** A synthetic scooter (never connected) with maintenance reminders on, before the app starts. */
    private void seedScooter() {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create("Synthetic locale scooter", "0F1E2D3C4B5A", 0);
            profile.mac = "C0:FF:EE:00:00:07"; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile);
            profileId = profile.id;
        });
        require(settings().edit().putString(MAINTENANCE_PREFIX + profileId, maintenanceDocument(true)).commit(), "Maintenance fixture failed");
    }

    /**
     * MaintenanceStore's JSON: with due, a DUE brakes check (20 days into 14), a SOON tire pressure one (12 of 14), a
     * SNOOZED bolts one, an OK lights one, a battery one SOON by distance (250 of 300 km, or 30 days) and a clean-and-dry
     * one waiting for its first odometer reading; without, the DUE one is done again today.
     */
    private static String maintenanceDocument(boolean due) {
        long now = System.currentTimeMillis();
        long brakesBase = due ? now - 20 * DAY : now - DAY;
        return "{\"v\":1,\"on\":true,\"distance\":{\"lastRawKm\":250.0,\"lastWall\":" + (now - DAY) + ",\"bits\":32,\"accKm\":250.0,\"resetWall\":0,\"floorRawKm\":0.0},\"reminders\":["
                + reminder("r1", "brakes", "null", 14, 0, true, 0, brakesBase, 0, "{\"t\":\"done\",\"wall\":" + brakesBase + ",\"accKm\":0.0}") + ","
                + reminder("r2", "tire_pressure", "null", 14, 0, true, 0, now - 12 * DAY, 0, "") + ","
                + reminder("r3", "bolts_folding", "null", 0, 1, false, 0, now - 40 * DAY, now + 3 * DAY, "{\"t\":\"snoozed\",\"wall\":" + (now - DAY) + ",\"accKm\":250.0}") + ","
                + reminder("r4", "lights", "null", 30, 0, true, 0, now - 2 * DAY, 0, "") + ","
                + reminder("r5", "battery_storage", "300.0", 30, 0, true, 0, now - 5 * DAY, 0, "") + ","
                + reminder("r6", "clean_dry", "482.8032", 0, 0, false, Double.NaN, now - 3 * DAY, 0, "") + "]}";
    }

    private static String reminder(String id, String task, String km, int days, int months, boolean notify, double baseKm, long baseWall, long snooze, String event) {
        String events = "{\"t\":\"added\",\"wall\":" + baseWall + (Double.isNaN(baseKm) ? "" : ",\"accKm\":" + baseKm) + "}" + (event.isEmpty() ? "" : "," + event);
        return "{\"id\":\"" + id + "\",\"task\":\"" + task + "\",\"name\":\"\",\"km\":" + km + ",\"days\":" + days + ",\"months\":" + months
                + ",\"notify\":" + notify + (Double.isNaN(baseKm) ? "" : ",\"baseAccKm\":" + baseKm) + ",\"baseWall\":" + baseWall
                + ",\"snoozeUntil\":" + snooze + ",\"events\":[" + events + "]}";
    }

    /** The Scooter tab's Reminders row with a check due and without, then the Maintenance page. No simulator: it keeps its own document. */
    private void walkMaintenance() throws Exception {
        select("controls");
        showRow(); check("scooter-maintenance-due");
        require(settings().edit().putString(MAINTENANCE_PREFIX + profileId, maintenanceDocument(false)).commit(), "Maintenance fixture failed");
        main(() -> activity.rebuild()); settle(); select("controls");
        showRow(); check("scooter-maintenance-count");
        require(settings().edit().putString(MAINTENANCE_PREFIX + profileId, maintenanceDocument(true)).commit(), "Maintenance fixture failed");
        main(() -> activity.rebuild()); settle(); select("controls");
        // The Reminders row's own action (ControlsScreen.showMaintenance), as a tap would run it.
        main(() -> invoke(field(activity, "screen"), "showMaintenance", new Class<?>[0]));
        await(() -> field(field(activity, "screen"), "maintenance") != null, "The Maintenance page did not open"); settle();
        check("maintenance-page");
        main(() -> invoke(field(activity, "screen"), "closeMaintenance", new Class<?>[0]));
        await(() -> field(field(activity, "screen"), "maintenance") == null, "The Maintenance page did not close"); settle();
    }

    /** Scrolls the Scooter tab so the Reminders row sits in the upper third, clear of the bottom bar, for the screenshot. */
    private void showRow() {
        main(() -> {
            View row = activity.getWindow().getDecorView().findViewWithTag(MAINTENANCE_ROW);
            require(row != null && row.isShown(), "The Scooter tab has no Reminders row");
            View scroll = row;
            while (scroll != null && !(scroll instanceof android.widget.ScrollView)) scroll = scroll.getParent() instanceof View ? (View) scroll.getParent() : null;
            if (scroll == null) return;
            int top = 0;
            for (View v = row; v != scroll; v = (View) v.getParent()) top += v.getTop();
            scroll.scrollTo(0, Math.max(0, top - scroll.getHeight() / 3));
        });
        settle();
    }

    /** Every Quick access shortcut and Setting tile, 8 at a time, on the simulator's live values. */
    private void walkHomeTiles() throws Exception {
        main(() -> require(activity.service().setSimulation(true), "Simulation did not start")); settle();
        await(() -> activity.service().ble.ready(), "The simulator did not connect");
        select("home");
        List<String> ids = new ArrayList<>();
        for (QuickAccessActions.Entry entry : QuickAccessActions.catalog()) ids.add(entry.id);
        for (int start = 0, batch = 1; start < ids.size(); start += 8, batch++) {
            List<String> shown = ids.subList(start, Math.min(ids.size(), start + 8));
            main(() -> {
                Object screen = field(activity, "screen");
                DashboardConfig config = (DashboardConfig) field(screen, "config");
                config.widgets.clear();
                DashboardConfig.Widget quick = new DashboardConfig.Widget("quick");
                quick.quickActions.clear(); quick.quickActions.addAll(shown); config.widgets.add(quick);
                for (int part = 0; part < shown.size(); part += 4) {
                    DashboardConfig.Widget tiles = new DashboardConfig.Widget("shortcuts");
                    tiles.quickActions.clear(); tiles.quickActions.addAll(shown.subList(part, Math.min(shown.size(), part + 4))); config.widgets.add(tiles);
                }
                config.save(); invoke(screen, "render", new Class<?>[0]);
            });
            settle();
            main(() -> { activity.service().ble.tickSimulation(); activity.redraw(); });
            check("home-tiles-" + batch);
        }
    }

    /** Riding mode's status pill: connected, recording, the recording pill's link problems, the plain pill's, and Ride saved. */
    private void walkRiding() throws Exception {
        ScooterService service = activity.service();
        main(() -> activity.openRiding()); settle();
        requireRiding();
        check("riding-connected");
        startRide(service);
        check("riding-recording");
        main(() -> require(service.setSimulation(false), "Simulation did not stop")); settle();
        requireRiding();
        setLinkLoss(service, true);
        checkBothHalves("riding-recording-reconnecting");
        setLinkLoss(service, false);
        checkBothHalves("riding-recording-not-connected");
        main(() -> { service.stopRide(); activity.redraw(); });
        require(!service.isRecording(), "The rider's stop did not end the recording");
        check("riding-saved");
        // The confirmation shows 4 s after the rider's stop; then the plain connection pill.
        SystemClock.sleep(4500); main(() -> activity.redraw());
        check("riding-not-connected");
        setLinkLoss(service, true);
        check("riding-reconnecting");
        setLinkLoss(service, false);
        startRide(service);
        main(() -> { invoke(service, "stopRide", new Class<?>[]{String.class}, RideStore.STOP_AUTO_PARKED); activity.redraw(); });
        require(!service.isRecording() && service.rides.lastStopAutomatic(), "The automatic stop did not end the recording");
        check("riding-saved-auto");
        main(() -> invoke(activity, "closeRiding", new Class<?>[0])); settle();
    }

    private void requireRiding() {
        main(() -> require("RidingScreen".equals(field(activity, "screen").getClass().getSimpleName()), "Riding mode is not open"));
    }

    /** A recording 12 min 34 s old, in the service's own RideStore (ScooterService.startRide refuses on the simulator). */
    private void startRide(ScooterService service) {
        main(() -> {
            long wall = System.currentTimeMillis(), mono = SystemClock.elapsedRealtime(), age = 754_000;
            try { service.rides.start(profileId, "Synthetic locale ride", wall - age, Math.max(0, mono - age)); }
            catch (java.io.IOException error) { throw new IllegalStateException(error); }
            activity.redraw();
        });
        settle();
        require(service.isRecording(), "The synthetic ride did not start");
    }

    /** BleController's recoverable connection loss: Reconnecting with no scooter to reconnect to. */
    private void setLinkLoss(ScooterService service, boolean lost) {
        main(() -> { setField(service.ble, "recoverableConnectionLoss", lost); activity.redraw(); });
        settle();
    }

    /**
     * Where the recording pill and its link problem don't both fit, it alternates every PILL_HALF_MS (on the same
     * elapsedRealtime clock): one screen early in each half, so each half's text is checked.
     */
    private void checkBothHalves(String label) throws Exception {
        for (String half : new String[]{"a", "b"}) {
            long phase = SystemClock.elapsedRealtime() % PILL_HALF_MS;
            SystemClock.sleep(PILL_HALF_MS - phase + 100);
            main(() -> activity.redraw());
            check(label + "-" + half);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; } catch (NoSuchFieldException e) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception e) { throw new IllegalStateException("Field " + name, e); }
    }

    private void setLocale(String tag) {
        LocaleManager manager = getTargetContext().getSystemService(LocaleManager.class);
        manager.setApplicationLocales(tag.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag));
        SystemClock.sleep(500); waitForIdleSync();
    }

    /** One screen: screenshot and census, then the fit, extraction and direction checks over every window. */
    private void check(String label) throws Exception {
        screens++;
        screenshot(label);
        main(() -> {
            View root = activity.getWindow().getDecorView();
            if (rtl && root.getLayoutDirection() != View.LAYOUT_DIRECTION_RTL) direction.add(label + ": the window lays out left to right");
            for (TextView text : texts(root)) {
                TextFit.collectNarrowTextProblems(text, label + " " + where(text), fit);
                if (!locale.equals("en-XA") || !text.isShown()) continue;
                String value = text.getText().toString();
                Matcher groups = PSEUDO_GROUP.matcher(value);
                int count = 0;
                while (groups.find()) count++;
                if (count > 1) glued.add(label + " " + where(text) + ": " + value);
                if (UNFILLED.matcher(value).find()) unfilled.add(label + " " + where(text) + ": " + value);
                if (PLAIN_WORD.matcher(PSEUDO_GROUP.matcher(value).replaceAll(" ")).find()) unextracted.add(label + " " + where(text) + ": " + value);
            }
        });
    }

    private void writeReport() throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("locale ").append(locale).append(", ").append(screens).append(" screens (report only)\n");
        section(out, "Not extracted (plain English outside [..])", unextracted);
        section(out, "Glued pieces (more than one [..] group)", glued);
        section(out, "Unfilled placeholders", unfilled);
        section(out, "Text fit", fit);
        section(out, "Layout direction", direction);
        try (FileOutputStream file = new FileOutputStream(new File(folder(), variant() + "-report.txt"))) { file.write(out.toString().getBytes(StandardCharsets.UTF_8)); }
    }

    private static void section(StringBuilder out, String title, List<String> lines) {
        out.append('\n').append(title).append(": ").append(lines.size()).append('\n');
        for (String line : lines) out.append("  ").append(line.replace('\n', '|')).append('\n');
    }

    private File screenshot(String label) throws Exception {
        settle(); SystemClock.sleep(300); settle();
        Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        File file = new File(folder(), variant() + "-" + label + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
        return file;
    }

    private File folder() {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "locale-validation");
        require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        return dir;
    }

    private String variant() { return arguments.getString("variant", locale).replaceAll("[^a-zA-Z0-9_.-]", ""); }

    private static String where(View view) { return view.getTag() instanceof String ? "tag=" + view.getTag() : view.getClass().getSimpleName(); }

    private static List<TextView> texts(View view) {
        List<TextView> found = new ArrayList<>();
        if (!view.isShown()) return found;
        if (view instanceof TextView) found.add((TextView) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) found.addAll(texts(((ViewGroup) view).getChildAt(i)));
        return found;
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"})
            require(getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty(), "Refusing existing private preferences");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be disabled before validation");
    }

    private void select(String page) { main(() -> invoke(activity, "select", new Class<?>[]{String.class}, page)); settle(); }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 8000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> ok = new AtomicReference<>(false); runOnMainSync(() -> ok.set(condition.getAsBoolean()));
            if (ok.get()) return; SystemClock.sleep(40);
        }
        throw new IllegalStateException(message);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static Object field(Object target, String name) { try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException("Field " + name, e); } }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) { try { Class<?> type = target.getClass(); while (type != null) { try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (NoSuchMethodException e) { type = type.getSuperclass(); } } throw new NoSuchMethodException(name); } catch (Exception e) { throw new IllegalStateException("Method " + name, e); } }
}
