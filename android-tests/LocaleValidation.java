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
import com.jjricks.volt.HubPages;
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
 *   -e locale en-XA|ar-XB|<tag>|en  (default en-XA; en, the source, is a manual baseline, not a sweep step)   -e rtl true|false  (a shipped language; default false)
 *   -e theme dark|light   -e text_scale 1|1.4   -e variant <name>   -e walk pages|scooter  (default pages)
 *
 * -e walk scooter: the states the page walk never reaches, since it has no scooter and records no ride.
 * A synthetic profile with seeded maintenance reminders (written straight to app_settings, never the
 * simulator's in-memory document): Scooter › Service's Maintenance row ("1 due", then "5 reminders") and the
 * Maintenance page with a DUE, SOON, SNOOZED, OK and waiting reminder. Then, on the developer simulator
 * (ModelHomeValidation's way), Home with every Quick access shortcut and Setting tile in batches of 8
 * (each batch as one Quick access widget and four two-tile Setting tiles widgets, as Home's default). Then riding mode: connected,
 * the red recording pill (a synthetic ride started in the service's own RideStore, as
 * AppDataMaintenanceValidation does), the same pill reconnecting and not connected after the simulator
 * stops (reconnecting is BleController's recoverable-loss flag set by reflection: the emulator has no
 * scooter to lose), each at both halves of the pill's time/problem alternation, the plain pill's
 * Not connected and Reconnecting, and Ride saved after the rider's stop; an automatic stop (the service's own
 * stop path) must close riding mode (#349). Each riding screen is asserted to show its state (the pill's look and text), and
 * Android's one-time "Viewing full screen" notice is marked confirmed first and must not cover any riding screen.
 * Setting tile text that ends in an ellipsis is reported as truncated_tiles (TextFit skips ellipsized lines).
 * Never connects Bluetooth.
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
    private String locale, profileId, immersivePrevious;
    private boolean immersiveSet;
    private boolean pseudo, rtl, scooterWalk;
    private final ArrayList<String> screenshots = new ArrayList<>();
    private final List<String> unextracted = new ArrayList<>(), glued = new ArrayList<>(), unfilled = new ArrayList<>(),
            fit = new ArrayList<>(), direction = new ArrayList<>(), truncated = new ArrayList<>();
    private int screens;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        locale = arguments.getString("locale", "en-XA");
        try {
            pseudo = locale.equals("en-XA") || locale.equals("ar-XB");
            require(pseudo || LANGUAGE_TAG.matcher(locale).matches(), "locale must be en-XA, ar-XB, en or a shipped language tag");
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
            result.putInt("fit_problems", fit.size()); result.putInt("direction_problems", direction.size()); result.putInt("truncated_tiles", truncated.size());
            result.putStringArrayList("screenshots", screenshots); result.putBoolean("physical_device_tested", false); result.putBoolean("report_only", true);
            if (activity != null) try { main(() -> { ScooterService service = activity.service();
                if (service != null) { if (service.isRecording()) service.stopRide(); service.setSimulation(false); } }); } catch (Throwable ignored) { }
            if (activity != null) try { main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            if (localeSet) try { setLocale(""); } catch (Throwable ignored) { }
            if (immersiveSet) try { restoreImmersiveNotice(); } catch (Throwable ignored) { }
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
    /** MaintenanceStore.PREFIX (package-private in the app) and the tag of Service's Maintenance row (HubListScreen.tag). */
    private static final String MAINTENANCE_PREFIX = "maintenance_v1_", MAINTENANCE_ROW = "hub-row-" + HubPages.SERVICE_MAINTENANCE;
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

    /**
     * Scooter › Service's Maintenance row with a check due and without, then the Maintenance page. No simulator: it keeps
     * its own document. The row was the Scooter tab's Reminders row until the Hub layout (#385) gave maintenance a page
     * under Service.
     */
    private void walkMaintenance() throws Exception {
        servicePage();
        showRow(); check("scooter-maintenance-due");
        require(settings().edit().putString(MAINTENANCE_PREFIX + profileId, maintenanceDocument(false)).commit(), "Maintenance fixture failed");
        main(() -> activity.rebuild()); settle(); servicePage();
        showRow(); check("scooter-maintenance-count");
        require(settings().edit().putString(MAINTENANCE_PREFIX + profileId, maintenanceDocument(true)).commit(), "Maintenance fixture failed");
        main(() -> activity.rebuild()); settle(); servicePage();
        // The Maintenance row's own action, as a tap would run it.
        main(() -> require(activity.getWindow().getDecorView().findViewWithTag(MAINTENANCE_ROW).performClick(), "The Maintenance row did not act"));
        await(() -> HubPages.SERVICE_MAINTENANCE.equals(HubTestPages.current(activity)), "The Maintenance page did not open"); settle();
        check("maintenance-page");
        main(() -> invoke(activity, "navigateBack", new Class<?>[0]));
        await(() -> HubPages.SERVICE.equals(HubTestPages.current(activity)), "The Maintenance page did not close"); settle();
    }
    private void servicePage() { main(() -> HubTestPages.open(activity, HubPages.SERVICE)); settle(); }

    /** Scrolls Service so the Maintenance row sits in the upper third, clear of the bottom bar, for the screenshot. */
    private void showRow() {
        main(() -> {
            View row = activity.getWindow().getDecorView().findViewWithTag(MAINTENANCE_ROW);
            require(row != null && row.isShown(), "Scooter › Service has no Maintenance row");
            View scroll = row;
            while (scroll != null && !(scroll instanceof android.widget.ScrollView)) scroll = scroll.getParent() instanceof View ? (View) scroll.getParent() : null;
            if (scroll == null) return;
            int top = 0;
            for (View v = row; v != scroll; v = (View) v.getParent()) top += v.getTop();
            scroll.scrollTo(0, Math.max(0, top - scroll.getHeight() / 3));
        });
        settle();
    }

    /** Every Quick access shortcut and Setting tile, 8 at a time, on the simulator's live values; then the truncation regression check. */
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
                // Two tiles per widget, as Home's default Setting tiles (the ~90 dp tile the _short rungs are budgeted for).
                for (int part = 0; part < shown.size(); part += 2) {
                    DashboardConfig.Widget tiles = new DashboardConfig.Widget("shortcuts");
                    tiles.quickActions.clear(); tiles.quickActions.addAll(shown.subList(part, Math.min(shown.size(), part + 2))); config.widgets.add(tiles);
                }
                config.save(); invoke(screen, "render", new Class<?>[0]);
            });
            settle();
            main(() -> { activity.service().ble.tickSimulation(); activity.redraw(); });
            check("home-tiles-" + batch);
        }
        requireTruncationFound();
    }

    /** Riding mode's status pill: connected, recording, the recording pill's link problems, the plain pill's, and Ride saved. */
    private void walkRiding() throws Exception {
        ScooterService service = activity.service();
        suppressImmersiveNotice();
        main(() -> activity.openRiding()); settle();
        requireRiding();
        requirePill("connected"); check("riding-connected");
        startRide(service);
        requirePill("recording"); check("riding-recording");
        main(() -> require(service.setSimulation(false), "Simulation did not stop")); settle();
        requireRiding();
        setLinkLoss(service, true);
        checkBothHalves("riding-recording-reconnecting", "reconnecting");
        setLinkLoss(service, false);
        checkBothHalves("riding-recording-not-connected", "not-connected");
        main(() -> { service.stopRide(); activity.redraw(); });
        require(!service.isRecording(), "The rider's stop did not end the recording");
        settle(); requirePill("saved"); check("riding-saved");
        // The confirmation shows 4 s after the rider's stop; then the plain connection pill.
        SystemClock.sleep(4500); main(() -> activity.redraw()); settle();
        requirePill("plain-not-connected"); check("riding-not-connected");
        setLinkLoss(service, true);
        requirePill("plain-reconnecting"); check("riding-reconnecting");
        setLinkLoss(service, false);
        startRide(service);
        main(() -> { invoke(service, "stopRide", new Class<?>[]{String.class}, RideStore.STOP_AUTO_PARKED); activity.redraw(); });
        require(!service.isRecording() && service.rides.lastStopAutomatic(), "The automatic stop did not end the recording");
        // An automatic end closes riding mode at once (#349), so its pill never shows Ride saved there now.
        await(() -> { Object now = field(activity, "screen"); return now == null || !"RidingScreen".equals(now.getClass().getSimpleName()); },
                "An automatic end should close riding mode");
        settle();
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
     * Where the recording pill and its link problem don't both fit, it alternates every PILL_HALF_MS on the elapsedRealtime
     * clock (RecordingPill.timeHalf): the time in even halves, the problem in odd ones. One screen early in each half,
     * each asserted to show that half (or both pieces at once, where they fit); with the system's animations off the pill
     * holds the problem, so the time screen is skipped.
     */
    private void checkBothHalves(String label, String state) throws Exception {
        for (String half : new String[]{"time", "problem"}) {
            if (half.equals("time") && !android.animation.ValueAnimator.areAnimatorsEnabled()) continue;
            long wanted = half.equals("time") ? 0 : 1;
            while (true) {
                long now = SystemClock.elapsedRealtime();
                if (Math.floorMod(now / PILL_HALF_MS, 2L) == wanted && now % PILL_HALF_MS < 300) break;
                SystemClock.sleep(Math.max(1, Math.min(100, PILL_HALF_MS - now % PILL_HALF_MS)));
            }
            main(() -> activity.redraw()); waitForIdleSync();
            requirePill(state + "-" + half);
            check(label + "-" + half, state + "-" + half);
        }
    }

    /**
     * A Setting tile's name and value are one line each that end in an ellipsis when too long (QuickAccessGrid), and
     * TextFit skips ellipsized lines, so a truncated tile would count as fitting. Every shown text in a
     * setting-tile:<id> view that ellipsizes is a finding of its own.
     */
    private static void collectTruncatedTiles(View view, String label, List<String> found) {
        if (!view.isShown()) return;
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith("setting-tile:")) {
            for (TextView text : texts(view)) {
                android.text.Layout layout = text.getLayout();
                if (text.length() == 0 || layout == null) continue;
                for (int line = 0; line < layout.getLineCount(); line++)
                    if (layout.getEllipsisCount(line) > 0) { found.add(label + " tag=" + view.getTag() + ": " + text.getText()); break; }
            }
            return;
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collectTruncatedTiles(((ViewGroup) view).getChildAt(i), label, found);
    }

    /** Regression check for collectTruncatedTiles: an overlong name on a shown Setting tile must be found, then the tiles render again. */
    private void requireTruncationFound() {
        String overlong = "Overlong regression label that cannot fit one Setting tile line";
        main(() -> {
            View tile = findTagPrefix(activity.getWindow().getDecorView(), "setting-tile:");
            require(tile != null, "No Setting tile to check truncation on");
            List<TextView> lines = texts(tile);
            require(!lines.isEmpty(), "A Setting tile shows no text");
            lines.get(0).setText(overlong);
        });
        settle();
        main(() -> {
            List<String> scratch = new ArrayList<>();
            collectTruncatedTiles(activity.getWindow().getDecorView(), "regression", scratch);
            boolean caught = false; for (String line : scratch) caught |= line.endsWith(overlong);
            require(caught, "An overlong Setting tile name was not reported as truncated");
            invoke(field(activity, "screen"), "render", new Class<?>[0]);
        });
        settle();
    }

    private static View findTagPrefix(View view, String prefix) {
        if (!view.isShown()) return null;
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith(prefix)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View found = findTagPrefix(((ViewGroup) view).getChildAt(i), prefix); if (found != null) return found; }
        return null;
    }

    /** The riding pill now: [look, shown text, spoken description]. */
    private String[] pill() {
        AtomicReference<String[]> now = new AtomicReference<>();
        main(() -> {
            Object screen = field(activity, "screen");
            Object look = field(screen, "pillLook");
            CharSequence desc = ((View) field(screen, "statusPill")).getContentDescription();
            now.set(new String[]{String.valueOf(look), String.valueOf(((TextView) field(screen, "status")).getText()), desc == null ? "" : desc.toString()});
        });
        return now.get();
    }

    /** Whether text is this app string in the current language, its arguments read as a clock time (RidingFocus.clockText). */
    private boolean shows(String text, String name) { return text.matches(pattern(name, clock())); }

    /** The pill's clock in the current language: riding_clock_minutes or riding_clock_hours over plain digits. */
    private String clock() { return "(?:" + pattern("riding_clock_minutes", "\\d{1,2}") + "|" + pattern("riding_clock_hours", "\\d{1,2}") + ")"; }

    /** A regex for an app string, every argument (%1$s, %2$s, ...) matched by argument. */
    private String pattern(String name, String argument) {
        int id = getTargetContext().getResources().getIdentifier(name, "string", getTargetContext().getPackageName());
        require(id != 0, "No string " + name);
        String marker = "\u0001", format = activity.getString(id, marker, marker, marker);
        StringBuilder pattern = new StringBuilder();
        String[] parts = format.split(marker, -1);
        for (int i = 0; i < parts.length; i++) { if (i > 0) pattern.append(argument); pattern.append(Pattern.quote(parts[i])); }
        return pattern.toString();
    }

    private boolean showsAny(String text, String... names) { for (String name : names) if (shows(text, name)) return true; return false; }

    /**
     * Each named riding snapshot must have reached its state, so a regression fails the step instead of taking duplicate
     * screenshots. state: connected, recording, reconnecting-time/-problem, not-connected-time/-problem, saved, saved-auto,
     * plain-not-connected, plain-reconnecting. A wide pill may show the time and the problem at once (the both-pieces rungs).
     */
    private void requirePill(String state) {
        String[] now = pill(); String look = now[0], text = now[1], desc = now[2];
        String where = "riding " + state + ": pill " + look + " \"" + text + "\" (" + desc + ")";
        String[] reconnecting = {"riding_status_recording_reconnecting", "riding_status_recording_reconnecting_short"},
                notConnected = {"riding_status_recording_not_connected", "riding_status_recording_not_connected_short"};
        boolean recordingOnly = shows(text, "riding_status_recording") || text.matches(clock());
        switch (state) {
            case "connected": require(look.equals("STATUS") && !recordingOnly, where); break;
            case "recording": require(look.equals("RECORDING") && recordingOnly, where); break;
            case "reconnecting-time": require(look.equals("RECORDING_PROBLEM") && (recordingOnly || showsAny(text, reconnecting)), where); break;
            case "reconnecting-problem": require(look.equals("RECORDING_PROBLEM") && (shows(text, "riding_status_reconnecting") || showsAny(text, reconnecting)), where); break;
            case "not-connected-time": require(look.equals("RECORDING_PROBLEM") && (recordingOnly || showsAny(text, notConnected)), where); break;
            case "not-connected-problem": require(look.equals("RECORDING_PROBLEM") && (shows(text, "riding_status_not_connected_seen_compact") || showsAny(text, notConnected)), where); break;
            case "saved": require(look.equals("SAVED") && showsAny(text, "riding_status_ride_saved", "riding_status_ride_saved_short")
                    && !desc.contains(activity.getString(getTargetContext().getResources().getIdentifier("riding_status_ride_saved_auto", "string", getTargetContext().getPackageName()))), where); break;
            case "saved-auto": require(look.equals("SAVED") && showsAny(text, "riding_status_ride_saved_auto", "riding_status_ride_saved_auto_short", "riding_status_ride_saved_short")
                    && desc.contains(activity.getString(getTargetContext().getResources().getIdentifier("riding_status_ride_saved_auto", "string", getTargetContext().getPackageName()))), where); break;
            case "plain-not-connected": require(look.equals("STATUS") && showsAny(text, "riding_status_not_connected_seen", "riding_status_not_connected_seen_short", "riding_status_not_connected_seen_compact"), where); break;
            case "plain-reconnecting": require(look.equals("STATUS") && shows(text, "riding_status_reconnecting"), where); break;
            default: throw new IllegalArgumentException(state);
        }
    }
    /**
     * Riding mode hides the system bars, and Android then shows its one-time "Viewing full screen" notice over the
     * top of the screen with a scrim (System UI's ImmersiveModeConfirmation), which hid the pill in every riding
     * screenshot. Marking it confirmed, as tapping Got it would, keeps it away; the previous value comes back at the end.
     */
    private void suppressImmersiveNotice() throws Exception {
        immersivePrevious = shell("settings get secure immersive_mode_confirmations").trim();
        immersiveSet = true;
        shell("settings put secure immersive_mode_confirmations confirmed");
        require("confirmed".equals(shell("settings get secure immersive_mode_confirmations").trim()), "Could not mark the full-screen notice confirmed");
    }

    private void restoreImmersiveNotice() throws Exception {
        if (immersivePrevious == null || immersivePrevious.isEmpty() || immersivePrevious.equals("null")) shell("settings delete secure immersive_mode_confirmations");
        else shell("settings put secure immersive_mode_confirmations " + immersivePrevious);
    }

    /** The screenshot just taken must not have the full-screen notice over it: no visible System UI window of it. */
    private void requireNoImmersiveNotice(String label) throws Exception {
        String windows = shell("dumpsys window windows");
        for (String block : windows.split("\\n  Window #")) {
            if (!block.contains("ImmersiveModeConfirmation")) continue;
            require(!block.contains("isOnScreen=true") && !block.contains("isVisible=true"),
                    label + ": Android's \"Viewing full screen\" notice covers the screenshot");
        }
    }

    private String shell(String command) throws Exception {
        android.os.ParcelFileDescriptor output = getUiAutomation().executeShellCommand(command);
        StringBuilder text = new StringBuilder();
        try (java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) > 0) text.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
        }
        return text.toString();
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
    private void check(String label) throws Exception { check(label, null); }

    /** As check(label); pillState (an alternation half) is asserted again right after the capture, taken without the usual settling. */
    private void check(String label, String pillState) throws Exception {
        screens++;
        screenshot(label, pillState);
        if (label.startsWith("riding-")) requireNoImmersiveNotice(label);
        main(() -> {
            View root = activity.getWindow().getDecorView();
            if (rtl && root.getLayoutDirection() != View.LAYOUT_DIRECTION_RTL) direction.add(label + ": the window lays out left to right");
            collectTruncatedTiles(root, label, truncated);
            for (TextView text : texts(root)) {
                TextFit.collectNarrowTextProblems(text, label + " " + where(text), fit);
                if (!locale.equals("en-XA") || !text.isShown()) continue;
                String value = text.getText().toString();
                // A template's pseudo group holds its arguments' own groups ("[»[Ðîšçöññéçţéð]« · »[9:31 [ÞṀ]]«]"):
                // pieces are glued when more than one group stands at the top level, and every group, nested
                // ones included, is pseudo text.
                if (topLevelGroups(value) > 1) glued.add(label + " " + where(text) + ": " + value);
                if (UNFILLED.matcher(value).find()) unfilled.add(label + " " + where(text) + ": " + value);
                if (PLAIN_WORD.matcher(withoutGroups(value)).find()) unextracted.add(label + " " + where(text) + ": " + value);
            }
        });
    }

    /** Pseudo groups not inside another group. */
    private static int topLevelGroups(String value) {
        int depth = 0, count = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '[') { if (depth == 0 && value.indexOf(']', i) > i) count++; depth++; }
            else if (c == ']' && depth > 0) depth--;
        }
        return count;
    }

    /** The text with every pseudo group removed, innermost first, so nested groups go too. */
    private static String withoutGroups(String value) {
        String previous;
        do { previous = value; value = PSEUDO_GROUP.matcher(value).replaceAll(" "); } while (!value.equals(previous));
        return value;
    }

    private void writeReport() throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("locale ").append(locale).append(", ").append(screens).append(" screens (report only)\n");
        section(out, "Not extracted (plain English outside [..])", unextracted);
        section(out, "Glued pieces (more than one [..] group)", glued);
        section(out, "Unfilled placeholders", unfilled);
        section(out, "Text fit", fit);
        section(out, "Truncated Setting tile text (one line, ends in an ellipsis; TextFit skips ellipsized lines)", truncated);
        section(out, "Layout direction", direction);
        try (FileOutputStream file = new FileOutputStream(new File(folder(), variant() + "-report.txt"))) { file.write(out.toString().getBytes(StandardCharsets.UTF_8)); }
    }

    private static void section(StringBuilder out, String title, List<String> lines) {
        out.append('\n').append(title).append(": ").append(lines.size()).append('\n');
        for (String line : lines) out.append("  ").append(line.replace('\n', '|')).append('\n');
    }

    private File screenshot(String label, String pillState) throws Exception {
        if (pillState == null) { settle(); SystemClock.sleep(300); settle(); }
        Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        if (pillState != null) requirePill(pillState);
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
