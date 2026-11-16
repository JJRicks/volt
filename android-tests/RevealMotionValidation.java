package com.jjricks.volt.tests;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import com.jjricks.volt.BleController;
import com.jjricks.volt.HubPages;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.ScooterProfiles;

/**
 * Fresh disposable emulator only (refuses a physical phone, existing app data and Bluetooth turned on). Things that come
 * and go on a live page must not pop (the owner's request of 15 Nov: "the 'on but not answering' bluetooth popup just
 * POPs into screen, can it animate nicely"). Each site is shown and hidden the app's own way while every drawn frame
 * (a draw listener) records the site's opacity and where the views around it sit in the window:
 *
 * <ul>
 *   <li><b>Arrival and departure</b> (animator duration scale 1): at least two frames show the site part-way faded,
 *   and at least two show each neighbour that moves part-way to its new place, moving only one way and never past it;
 *   everything is at rest within {@link #SETTLE_MS} (the motion itself is Motion.CHANGE_MS, 220 ms).</li>
 *   <li><b>Live refreshes</b>: with the site resting, the 1 s pulse and repeated redraws move nothing and fade
 *   nothing.</li>
 *   <li><b>Quick reversals</b>: shown then hidden two frames later, hidden then shown, and both in one step: the end
 *   state is the last one asked, fully opaque or gone, with no translation, no suppressed layout and no extra views
 *   left behind.</li>
 *   <li><b>The activity recreated mid-arrival</b> (as a rotation, fold or text-size change rebuilds Home): the new Home
 *   shows the site at rest.</li>
 *   <li><b>Focus</b>: an arrival takes no focus; the view with focus before has it after.</li>
 *   <li><b>Animations off</b> (scale 0): every frame shows the site and its neighbours either before or after, never
 *   between.</li>
 *   <li><b>An instant change mid-departure</b> (a null root, as Home's edit-mode switch is): shown at once two frames
 *   into its departure, it stays shown after the departure would have ended. On Widgets Home also the real way, edit
 *   mode entered and left while the card goes.</li>
 *   <li><b>Turning back</b> (scale 10): hidden mid-arrival and shown mid-departure, no frame changes its opacity by more
 *   than 0.15 or moves a neighbour more than 8 dp from the frame before, per 60 Hz frame of time between them (no jump
 *   to where the first motion was going).</li>
 *   <li><b>Leaving at the end of a scrolled page</b> that still scrolls without it (the text made larger until it
 *   does): the scroll clamps, and no frame draws the views below off their way from start to rest.</li>
 *   <li><b>Taps mid-fade</b> (scale 10, real touches through the window): a view that is leaving takes no tap and
 *   what is under it does. Rides ({@code rides}): Start recording tapped, then tapped again where it was while it fades
 *   out: the ride is still recording and automatic recording is not held (a second tap that reached Start would stop
 *   the ride as the rider's own stop). My scooters ({@code scooters}): another scooter's Use tapped, then its arriving
 *   Connect where Use was, while Use fades out: Connect gets the tap (it is consumed there, so nothing connects) and
 *   Use none. Riding mode's alert banner ({@code riding-alert}): its close button tapped mid-fade closes it at once.
 *   The other parts have no tap check.</li>
 *   <li><b>Per part</b>: {@code night}, a leaving view that scrolls its own content (the Schedule selector, wider than
 *   the screen at large text, scrolled to its end) is drawn fading exactly as on screen ({@code ghost}). {@code battery},
 *   the summary's lines landing one by one as a connection starts come in together once, never holding a line already
 *   shown or a value's change ({@code gather}). {@code onboarding}, the problem line arriving below the fold (a window
 *   half as tall) is scrolled into view gliding from its arrival, to where it ends ({@code into-view}).</li>
 * </ul>
 *
 * In every motion, every frame draws each neighbour within 2 dp of the span from where it started to where it rests.
 * A view that is leaving is GONE from the start and drawn fading by Motion (Motion.leaving says at what opacity); the
 * frames count that as drawn. At rest nothing is still drawn leaving.
 * -e only arrives|pulse|reversals|recreated|off|instant|turns|scrolled|ghost|gather|into-view|taps runs one check.
 * {@code rides} needs the hidden API policy relaxed (a synthetic link, as AutoStartValidation's).
 *
 * A host too slow to draw a motion's frames cannot tell a motion from a pop: a failed measurement in which a frame came
 * more than STARVED_MS late is measured again, and if every try was starved the run ends INCONCLUSIVE (STARVED), not
 * FAIL. A pop draws its frame on time and always fails.
 *
 * Parts (-e part): {@code find} (Home's Find it nearby card, FindNearby entry A; -e home widgets|clean). The card is
 * offered the app's own way: BleController's timeout tally for the saved address reaches two (FindNearby.Timeouts, as
 * two connect timeouts leave it) while a connection is wanted; it goes when the tally starts again (as a connection
 * does). Nothing is sent and Bluetooth stays off. The connection parts run the simulator (memory only, never a
 * command): its dropout script leaves the link Connecting, as a scooter that stops answering does, and the default
 * script brings it back. {@code controls}: Controls › Limits' connection banner. {@code rides}: Ride › Rides'
 * Connect to record. {@code scooters}: Settings › My scooters, the card's "Last connected" line. {@code night}:
 * Ride › Riding mode's Night vision rows, revealed by tapping Scheduled (sunset to sunrise, the time zone's city) and
 * hidden by tapping Off. Sheets (the rows glide and the sheet's height with them, anchored at the bottom):
 * {@code charge-alerts}, the Battery alerts sheet's charge level row (Charge alert on and off, alarm sound on so nothing
 * else changes; a GT3-family fixture, which offers the alerts offline); {@code maintenance-mode}, Maintenance's Add
 * reminder distance box (Either, then Time); {@code maintenance-earlier}, its Earlier date rows (Earlier, then Now).
 * {@code battery}: Battery &amp; charging's pack temperature line on the simulator, the reading withheld and released.
 * {@code riding-alert}: Riding mode's battery alert banner (a synthetic silent charge alert; it fades in over
 * Motion.ALERT_MS, drawn from its first frame, and goes at once). {@code onboarding}: Setup's connect step, the problem
 * line and Try again against Cancel as the link's state changes (Bluetooth stays off; nothing connects). -e mode demo toggles the site every -e hold_ms (1500) for -e
 * cycles (4) and checks nothing, for recordings driven from adb. The animator duration scale is put back as it was.
 */
public final class RevealMotionValidation extends Instrumentation {
    private static final String FIXTURE_NAME = "Synthetic reveal fixture", FIXTURE_IDENTITY = "C1D2E3F4A5B6",
            FIXTURE_SERIAL = "X2538-SYNTHETIC", FIXTURE_MAC = "C0:FF:EE:12:34:58";
    /** At rest within this long after the first frame that moved (the motion is 220 ms; the emulator drops frames). */
    private static final long SETTLE_MS = 600;
    private Bundle arguments;
    private MainActivity activity;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), notes = new ArrayList<>();
    private String scaleBefore, part, home;
    private String profileId;
    private ScooterProfiles.Profile fixture;
    private Site site;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String mode = arguments.getString("mode", "all");
            part = arguments.getString("part", "find");
            home = arguments.getString("home", "widgets");
            require(mode.equals("all") || mode.equals("demo"), "Unknown -e mode " + mode + " (all or demo)");
            require(PARTS.contains(part), "Unknown -e part " + part + " (" + String.join(", ", PARTS) + ")");
            require(home.equals("widgets") || home.equals("clean"), "Unknown -e home " + home + " (widgets or clean)");
            admit();
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("auto_record_rides", false).putBoolean("riding_auto_open", false).putBoolean("phone_gps", false)
                    .putString("theme", arguments.getString("theme", "dark")).putString("units", "mi")
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1")))
                    .putString("home_style", home).putBoolean("home_style_chosen", true).putString("home_style_origin", "install")
                    .commit(), "Fixture settings failed");
            if (part.equals("charge-alerts")) require(settings().edit().putBoolean("notification_prompted", true).commit(), "Alert fixture failed");
            if (part.equals("night")) require(settings().edit().putString("riding_night_vision", "off").putString("riding_night_schedule", "sun")
                    .putString("riding_night_ride_area", "decline").commit(), "Night vision fixture failed");
            seed();
            launch();
            site = site();
            requirePage();
            scaleBefore = shell("settings get global animator_duration_scale").trim();
            try {
                scale(1);
                if (mode.equals("demo")) demo(site);
                else {
                    // -e only <check> runs one check (to show which one a build fails).
                    String only = arguments.getString("only", "");
                    String[] names = {"arrives", "pulse", "reversals", "recreated", "off", "instant", "turns", "scrolled", "ghost", "gather", "into-view", "taps"};
                    require(only.isEmpty() || java.util.Arrays.asList(names).contains(only), "Unknown -e only " + only);
                    boolean alert = part.equals("riding-alert");
                    if (only.isEmpty() || only.equals("arrives")) arrivesAndGoes(site);
                    if (only.isEmpty() || only.equals("pulse")) pulse(site);
                    if (only.isEmpty() || only.equals("reversals")) reversals(site);
                    if (only.isEmpty() || only.equals("recreated")) {
                        if (site.keepsAcrossRecreate && !alert) recreated(site);
                        else notes.add(site.name + ": no rebuilt-activity check (" + (alert ? "riding mode closes with the activity"
                                : part.equals("onboarding") ? "a rebuilt Setup starts its connect step afresh" : "an editor that was not saved opens afresh") + ")");
                    }
                    if (only.isEmpty() || only.equals("off")) animationsOff(site);
                    if (only.isEmpty() || only.equals("instant")) {
                        if (!alert) instantWins(site); else notes.add(site.name + ": no instant-change check (the banner is shown and hidden only one way)");
                    }
                    if (only.isEmpty() || only.equals("turns")) {
                        if (alert) alertTurns(site);
                        else measured(site.name + " turning back", () -> turnsBack(site), () -> { scale(1); site.hide.run(); awaitRest(site, false); });
                    }
                    if (only.isEmpty() || only.equals("scrolled")) {
                        if (alert) notes.add(site.name + ": no scrolled-end check (riding mode does not scroll)");
                        else if (part.equals("battery")) notes.add(site.name + ": no scrolled-end check (the line sits beside the level, so the card keeps its height)");
                        else scrolledEnd(site);
                    }
                    if (part.equals("night") && (only.isEmpty() || only.equals("ghost"))) ghostScroll(site);
                    if (part.equals("battery") && (only.isEmpty() || only.equals("gather"))) gather(site);
                    if (part.equals("onboarding") && (only.isEmpty() || only.equals("into-view"))) intoView(site);
                    // Last: it ends the simulator (and, for Rides, records a ride on a synthetic link).
                    if (only.isEmpty() || only.equals("taps")) taps();
                }
            } finally {
                shell(scaleBefore.isEmpty() || scaleBefore.equals("null") ? "settings delete global animator_duration_scale" : "settings put global animator_duration_scale " + scaleBefore);
            }
            result.putString("result", "PASS");
        } catch (Throwable error) {
            // A host too starved to tell a motion from a pop is reported as such (and still not a pass), never as a pop.
            boolean starved = false;
            for (Throwable cause = error; cause != null && !starved; cause = cause.getCause()) starved = cause instanceof Starved;
            result.putString("result", starved ? "INCONCLUSIVE" : "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("notes", notes);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ===== Sites =====

    /** Something that comes and goes on a live page, the views around it, and the app's own ways to show and hide it. */
    private static final class Site {
        final String name;
        final Supplier<View> subject;
        /** The views that may move when it comes or goes (the next sibling and those after it). */
        final Supplier<List<View>> neighbours;
        final Runnable show, hide;
        /** Views above it that move up as it arrives and back down as it goes (a bottom sheet's title, as the sheet grows). */
        Supplier<List<View>> above = ArrayList::new;
        /** A view in the window it is drawn in (a sheet's), or null for the activity's. */
        Supplier<View> window;
        /** Nothing around it has to move (it comes at the end of its page, or over it). */
        boolean fadeOnly;
        /** Views that fade in as it shows and out as it goes ({@code with}), or the other way round ({@code against}). */
        Supplier<List<View>> with = ArrayList::new, against = ArrayList::new;
        /** Reopens what it is in (a sheet) after the activity is rebuilt; null when the page alone holds it. */
        Runnable reopen;
        /** It keeps its state across a rebuilt activity (an editor that is not saved opens afresh). */
        boolean keepsAcrossRecreate = true;
        Site(String name, Supplier<View> subject, Supplier<List<View>> neighbours, Runnable show, Runnable hide) {
            this.name = name; this.subject = subject; this.neighbours = neighbours; this.show = show; this.hide = hide;
        }
    }

    /** Every measurement of a check had a frame come too late to tell a motion from a pop: inconclusive, not a failure of the app. */
    private static final class Starved extends IllegalStateException { Starved(String message) { super(message); } }
    /** A frame later than this after a change, or while it is still moving, makes the measurement inconclusive. */
    private static final long STARVED_MS = 50;
    /** Inconclusive measurements are measured again this many times before the check reports the host as starved. */
    private static final int STARVED_RETRIES = 3;

    private static final List<String> PARTS = java.util.Arrays.asList("find", "controls", "rides", "night", "scooters",
            "charge-alerts", "maintenance-mode", "maintenance-earlier", "battery", "riding-alert", "onboarding");

    private Site site() {
        switch (part) {
            case "charge-alerts": return chargeAlerts();
            case "maintenance-mode": return maintenance(true);
            case "maintenance-earlier": return maintenance(false);
            case "battery": return battery();
            case "riding-alert": return ridingAlert();
            case "onboarding": return onboarding();
            case "controls": return connection("Controls › Limits' connection banner", HubPages.CONTROLS_LIMITS, "ControlsScreen", "banner");
            case "rides": return connection("Ride › Rides' Connect to record", HubPages.RIDE_RIDES, "RidesScreen", "connectButton");
            case "night": return night();
            case "scooters": return connection("Settings › My scooters' last connected line", HubPages.SETTINGS_SCOOTERS, "MoreScreen", null);
            default: return findCard();
        }
    }

    /** The page the part shows, opened the app's own way (Home is already open for find). */
    private String page() {
        switch (part) {
            case "controls": return HubPages.CONTROLS_LIMITS;
            case "rides": return HubPages.RIDE_RIDES;
            case "night": return HubPages.RIDE_RIDING;
            case "scooters": return HubPages.SETTINGS_SCOOTERS;
            case "maintenance-mode": case "maintenance-earlier": return HubPages.SERVICE_MAINTENANCE;
            case "battery": return HubPages.BATTERY;
            default: return HubPages.HOME;
        }
    }

    /**
     * A view on a page that shows while the simulator's link is Connecting (its dropout script) and goes once it is
     * ready again (the default script).
     */
    private Site connection(String name, String page, String screen, String field) {
        if (onMain(() -> !activity.service().simulating())) main(() -> require(activity.service().setSimulation(true), "The simulator did not start"));
        await(() -> onMain(() -> activity.service().ble.ready()), "The simulator is not ready");
        // My scooters: the fixture scooter's card, its "Last connected" line (null field).
        Supplier<View> subject = field != null ? () -> (View) field(part(screen), field)
                : () -> (View) field(((java.util.Map<?, ?>) field(part(screen), "scooterCards")).get(profileId), "lastSeen");
        return new Site(name, subject, () -> siblingsAfter(subject.get()),
                () -> main(() -> { activity.service().ble.setSimulationScript("dropout"); activity.redraw(); }),
                () -> main(() -> { activity.service().ble.setSimulationScript(""); activity.redraw(); }));
    }


    // ===== C1 sites: sheets, the riding alert, the battery summary and setup =====

    /** The open sheet (MainActivity's controlDialog or the Maintenance page's sheet), or null. */
    private android.app.Dialog sheet() {
        Object dialog = part.startsWith("maintenance") ? field(part("MaintenanceScreen"), "sheet") : field(activity, "controlDialog");
        return dialog instanceof android.app.Dialog && ((android.app.Dialog) dialog).isShowing() ? (android.app.Dialog) dialog : null;
    }
    private View inSheet(String tag) {
        android.app.Dialog sheet = sheet();
        return sheet == null ? null : sheet.getWindow().getDecorView().findViewWithTag(tag);
    }
    /**
     * A view in the sheet by its tag, or else the text the app draws for it (T.words(), as a build before the tag was
     * added finds it: the same view, so a check can show that build's motion rather than a missing view).
     */
    private View inSheetOr(String tag, String words) {
        View tagged = inSheet(tag);
        if (tagged != null || words == null || sheet() == null) return tagged;
        String text;
        try { Method t = Class.forName("com.jjricks.volt.T").getDeclaredMethod(words); t.setAccessible(true); text = String.valueOf(t.invoke(null)); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        return withText(sheet().getWindow().getDecorView(), text);
    }
    private static View withText(View view, String text) {
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = withText(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
    /** The sheet's title row (what rises as the sheet grows), the first row of its content. */
    private List<View> sheetHead(String anyTag) {
        List<View> out = new ArrayList<>();
        View any = inSheet(anyTag);
        android.widget.ScrollView holder = scrollOf(any);
        if (holder != null && holder.getChildAt(0) instanceof ViewGroup) out.add(((ViewGroup) holder.getChildAt(0)).getChildAt(0));
        return out;
    }

    /**
     * Battery alerts' sheet (Controls' Battery alerts row): the charge alert's level slider comes as its switch turns on
     * and goes as it turns off, with its value and the delivery note. The sheet's height glides with it.
     */
    private Site chargeAlerts() {
        Supplier<View> subject = () -> { View slider = inSheet("charge-alert-charge-level"); return slider == null ? null : (View) slider.getParent(); };
        Site site = new Site("Battery alerts sheet's charge level row", subject, () -> siblingsAfter(subject.get()),
                () -> main(() -> chargeSwitch(true)), () -> main(() -> chargeSwitch(false)));
        site.above = () -> sheetHead("charge-alert-charge");
        site.window = subject;
        // Alarm sound on keeps the "choose how to be alerted" note away, so the charge level row is the only arrival.
        site.reopen = () -> main(() -> { if (sheet() == null) invoke(activity, "showChargeAlerts"); sheetSwitch("charge-alert-alarm", true); });
        return site;
    }
    private void chargeSwitch(boolean on) { sheetSwitch("charge-alert-charge", on); }
    private void sheetSwitch(String tag, boolean on) {
        android.widget.Switch toggle = (android.widget.Switch) inSheet(tag);
        require(toggle != null, "The Battery alerts sheet has no " + tag + " switch");
        if (toggle.isChecked() != on) toggle.performClick();
    }

    /**
     * Maintenance's Add reminder sheet. mode: Remind me after Either shows the distance box (and the Either note) above
     * the time box, Time hides them. Otherwise Counting from Earlier shows the date and odometer rows, Now hides them.
     */
    private Site maintenance(boolean mode) {
        Supplier<View> subject = mode ? () -> { View distance = inSheet("maintenance_distance"); return distance == null ? null : (View) distance.getParent(); }
                : () -> { View label = inSheetOr("maintenance_earlier_rows", "maintenanceDate"); return label == null || "maintenance_earlier_rows".equals(label.getTag()) ? label : (View) label.getParent(); };
        Site site = mode
                ? new Site("Add reminder's distance box (Either, then Time)", subject, () -> siblingsAfter(subject.get()),
                        () -> main(() -> tapInSheet("maintenance_mode2")), () -> main(() -> tapInSheet("maintenance_mode1")))
                : new Site("Add reminder's earlier date rows (Earlier, then Now)", subject, () -> siblingsAfter(subject.get()),
                        () -> main(() -> tapInSheet("maintenance_when_earlier", "maintenanceWhenEarlier")), () -> main(() -> tapInSheet("maintenance_when_now", "maintenanceWhenNow")));
        site.above = () -> sheetHead("maintenance_name");
        site.window = subject;
        site.keepsAcrossRecreate = false;
        site.reopen = () -> {
            main(() -> {
                if (sheet() != null) return;
                View toggle = root().findViewWithTag("maintenance_switch");
                if (toggle instanceof android.widget.Switch && !((android.widget.Switch) toggle).isChecked()) toggle.performClick();
            });
            settle();
            main(() -> { if (sheet() == null) { View add = root().findViewWithTag("maintenance_add"); require(add != null, "Maintenance has no Add reminder"); add.performClick(); } });
            await(() -> onMain(() -> sheet() != null), "The Add reminder sheet did not open");
            settle();
            // Time to start from: the distance box and the earlier rows are hidden.
            main(() -> tapInSheet("maintenance_mode1"));
            settle();
        };
        return site;
    }
    private void tapInSheet(String tag) { tapInSheet(tag, null); }
    private void tapInSheet(String tag, String words) {
        View view = inSheetOr(tag, words);
        require(view != null, "The sheet has no " + tag);
        view.performClick();
    }

    /** Battery & charging's summary card, on the simulator: one reading arriving on its own (the pack temperature). */
    private Site battery() {
        if (onMain(() -> !activity.service().simulating())) main(() -> require(activity.service().setSimulation(true), "The simulator did not start"));
        await(() -> onMain(() -> activity.service().ble.ready()), "The simulator is not ready");
        // At rest the pack temperature is withheld (the part's starting point): the line is hidden until offered.
        main(() -> { withheld.clear(); withheld.add("temperature_c"); withhold(); activity.redraw(); });
        Supplier<View> subject = () -> (View) field(part("HubBatterySummary"), "packRow");
        return new Site("Battery & charging's pack temperature line", subject, () -> siblingsAfter(subject.get()),
                () -> main(() -> { withheld.remove("temperature_c"); activity.redraw(); }),
                () -> main(() -> { withheld.add("temperature_c"); activity.redraw(); }));
    }
    /** Readings the simulator keeps but the app does not see (Telemetry.metrics leaves them out): they arrive when released. */
    private final java.util.Set<String> withheld = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void withhold() {
        Object telemetry = field(activity.service().ble, "telemetry");
        Object readings = field(telemetry, "readings");
        if (readings instanceof Withholding) return;
        synchronized (telemetry) { set(telemetry, "readings", new Withholding((java.util.Map) readings, withheld)); }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class Withholding extends java.util.LinkedHashMap {
        final java.util.Set<String> hidden;
        Withholding(java.util.Map from, java.util.Set<String> hidden) { super(from); this.hidden = hidden; }
        @Override public java.util.Collection values() {
            ArrayList out = new ArrayList();
            for (Object entry : entrySet()) if (!hidden.contains(((java.util.Map.Entry) entry).getKey())) out.add(((java.util.Map.Entry) entry).getValue());
            return out;
        }
    }

    /** Riding mode's battery alert banner: a synthetic silent charge alert fires in riding mode; its close button hides it. */
    private Site ridingAlert() {
        // Riding mode hides the system bars: Android's one-time "Viewing full screen" note would take the close tap.
        try { shell("settings put secure immersive_mode_confirmations confirmed"); } catch (Exception error) { throw new IllegalStateException(error); }
        Supplier<View> subject = () -> root().findViewWithTag("riding_alert_banner");
        Site site = new Site("Riding mode's battery alert banner", subject, () -> { List<View> self = new ArrayList<>(); self.add(subject.get()); return self; },
                () -> main(this::fireAlert), () -> main(() -> { View close = root().findViewWithTag("riding_alert_close"); if (close != null && close.isShown()) close.performClick(); }));
        site.fadeOnly = true;
        site.reopen = () -> { main(() -> { if (field(activity, "riding") == null) activity.openRiding(); }); await(() -> onMain(() -> subject.get() != null), "Riding mode did not open"); };
        return site;
    }
    /** A silent 80% charge alert for the fixture, pending as one that fired in sight, taken by riding mode as its banner. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void fireAlert() {
        try {
            Class<?> shown = Class.forName("com.jjricks.volt.ScooterService$AlertShown");
            Class event = Class.forName("com.jjricks.volt.ChargeAlertPolicy$Event");
            java.lang.reflect.Constructor<?> make = shown.getDeclaredConstructor(event, int.class, int.class, ScooterProfiles.Profile.class);
            make.setAccessible(true);
            set(activity.service(), "pendingChargeAlert", make.newInstance(Enum.valueOf(event, "CHARGE"), 80, 80, activity.service().profiles.active()));
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        invoke(activity, "showChargeAlertInRiding");
    }

    /**
     * Setup's connect step: after a try, the problem line and Try again show; connecting hides them and shows Cancel.
     * The controller's state is set as a connection sets it (Bluetooth stays off; nothing connects).
     */
    private Site onboarding() {
        Supplier<View> subject = () -> { View line = statusLine(); return line; };
        Site site = new Site("Setup's connect problem line", subject, () -> new ArrayList<>(),
                () -> main(() -> { setLink(BleController.State.DISCONNECTED); }), () -> main(() -> { setLink(BleController.State.CONNECTING); }));
        site.fadeOnly = true;
        site.with = () -> { List<View> out = new ArrayList<>(); out.add(footerButton(true)); return out; };
        site.against = () -> { List<View> out = new ArrayList<>(); out.add(footerButton(false)); return out; };
        site.keepsAcrossRecreate = false;
        // The starting point: a connection under way (Cancel shown, no problem line).
        site.reopen = () -> main(() -> setLink(BleController.State.CONNECTING));
        return site;
    }
    private void setLink(BleController.State state) {
        set(activity.service().ble, "state", state);
        invoke(field(activity, "onboarding"), "refresh");
    }
    /** The connect step's status line: the row after the checklist (the content's last row). */
    private View statusLine() {
        Object onboarding = field(activity, "onboarding");
        ViewGroup content = onboarding == null ? null : (ViewGroup) field(onboarding, "content");
        return content == null || content.getChildCount() == 0 ? null : content.getChildAt(content.getChildCount() - 1);
    }
    /** The footer's Try again (primary) or Cancel. */
    private View footerButton(boolean primary) {
        Object onboarding = field(activity, "onboarding");
        ViewGroup footer = onboarding == null ? null : (ViewGroup) field(onboarding, "footer");
        return footer == null || footer.getChildCount() < 2 ? null : footer.getChildAt(primary ? 0 : 1);
    }

    /** Ride › Riding mode's "Sunset from" row: tapping Scheduled (sunset to sunrise) shows it, tapping Off hides it. */
    private Site night() {
        Supplier<View> subject = () -> root().findViewWithTag("preference_riding_night_from");
        return new Site("Ride › Riding mode's Night vision schedule rows", subject, () -> siblingsAfter(subject.get()),
                () -> main(() -> nightChoice(2)), () -> main(() -> nightChoice(0)));
    }

    /** Taps a Night vision choice (Off, On, Scheduled): the page's third selector, after brightness and rotation. */
    private void nightChoice(int choice) {
        List<View> selectors = new ArrayList<>();
        collect((View) field(part("HubRidingSettings"), "view"), "SegmentedSelector", selectors);
        require(selectors.size() >= 3, "Ride › Riding mode has " + selectors.size() + " selectors");
        ((View) ((List<?>) field(field(selectors.get(2), "track"), "choices")).get(choice)).performClick();
    }

    private static void collect(View view, String simpleName, List<View> out) {
        if (view.getClass().getSimpleName().equals(simpleName)) { out.add(view); return; }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), simpleName, out);
    }

    /** The open page's screen of this class: the page itself, or one of a stacked page's parts. */
    private Object part(String simpleName) {
        Object screen = field(activity, "screen");
        require(screen != null, "No page is open");
        if (screen.getClass().getSimpleName().equals(simpleName)) return screen;
        if (screen.getClass().getSimpleName().equals("HubPageScreen"))
            for (Object item : (List<?>) field(screen, "parts")) if (item.getClass().getSimpleName().equals(simpleName)) return item;
        // A page that wraps its screen (Maintenance) holds it in the wrapper's captured fields.
        if (screen.getClass().isAnonymousClass())
            for (java.lang.reflect.Field f : screen.getClass().getDeclaredFields())
                if (f.getType().getSimpleName().equals(simpleName)) {
                    try { f.setAccessible(true); return f.get(screen); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
                }
        throw new IllegalStateException("The open page has no " + simpleName + " (" + screen.getClass().getSimpleName() + ")");
    }

    /** Home's Find it nearby card: offered by two timeouts at the saved address while a connection is wanted. */
    private Site findCard() {
        return new Site("Find it nearby card (" + home + " Home)", () -> root().findViewWithTag("home_find_nearby"),
                () -> siblingsAfter(root().findViewWithTag("home_find_nearby")),
                () -> main(() -> { tally(2); activity.redraw(); }),
                () -> main(() -> { tally(0); activity.redraw(); }));
    }

    /** On the main thread: BleController's tally for the fixture's saved address, and a connection wanted. */
    private void tally(int count) {
        Object ble = field(activity.service(), "ble");
        Object timeouts = field(ble, "timeouts");
        set(timeouts, "profileId", profileId);
        set(timeouts, "count", count);
        set(ble, "wanted", true);
    }

    /** The views after this one in its parent, then after its parent in the grandparent (what a size change moves). */
    private static List<View> siblingsAfter(View view) {
        List<View> after = new ArrayList<>();
        for (View at = view; at != null && at.getParent() instanceof ViewGroup && after.size() < 4; at = (View) at.getParent()) {
            ViewGroup parent = (ViewGroup) at.getParent();
            for (int i = parent.indexOfChild(at) + 1; i < parent.getChildCount() && after.size() < 4; i++)
                if (parent.getChildAt(i).getVisibility() == View.VISIBLE && parent.getChildAt(i).getHeight() > 0) after.add(parent.getChildAt(i));
            if (parent instanceof android.widget.ScrollView) break;
        }
        return after;
    }

    // ===== The checks =====

    /**
     * A busy machine can drop a whole motion's frames (a slower remote emulator drew one part-faded frame between 0.07 and 1).
     * A failed measurement in which a frame came more than STARVED_MS late (after the change, or while anything was still
     * moving) could not tell a motion from a pop: it is inconclusive and measured again, up to STARVED_RETRIES times, and
     * if every measurement was starved the check reports the host as starved (INCONCLUSIVE), never as a pop. A pop
     * draws its one changed frame on time, so it is never starved and always fails.
     */
    private void measured(String what, Runnable attempt, Runnable reset) {
        for (int tries = 0; ; tries++) {
            hitchMs = 0;
            try { attempt.run(); return; }
            catch (IllegalStateException error) {
                if (error instanceof Starved || hitchMs <= STARVED_MS) throw error;
                notes.add(what + ": inconclusive (a frame came " + hitchMs + " ms late), measured again: " + error.getMessage());
                if (tries >= STARVED_RETRIES) throw new Starved(what + ": STARVED, inconclusive and not a pop: in all " + (tries + 1)
                        + " measurements a frame came more than " + STARVED_MS + " ms late (the last " + hitchMs + " ms). Last measurement: " + error.getMessage());
                reset.run();
            }
        }
    }

    private void arrivesAndGoes(Site site) {
        measured(site.name + " arrival and departure", () -> {
            if (site == this.site && part.equals("riding-alert")) alertArrivesAndGoesOnce(site); else arrivesAndGoesOnce(site);
        }, () -> { site.hide.run(); awaitRest(site, false); });
    }
    /**
     * After a starved try: until the site rests as asked (a slow host can still be drawing it leaving after SETTLE_MS),
     * at most 5 s; the next try's own checks then start from rest.
     */
    private void awaitRest(Site site, boolean shown) {
        long end = SystemClock.uptimeMillis() + 5000;
        settleFor(SETTLE_MS);
        while (SystemClock.uptimeMillis() < end) {
            try { requireRest(site, shown, "after a starved try"); return; }
            catch (IllegalStateException notYet) { SystemClock.sleep(100); waitForIdleSync(); }
        }
    }

    /** The latest frame of the measurements since it was reset (ms): see measured. */
    private long hitchMs;

    private void arrivesAndGoesOnce(Site site) {
        requireRest(site, false, "before it was offered");
        Frames in = record(site, site.show);
        requireRest(site, true, "after it arrived");
        smooth(in, true, site.name + " arrival");
        Frames out = record(site, site.hide);
        requireRest(site, false, "after it went");
        smooth(out, false, site.name + " departure");
        checks.add(site.name + ": arrives over " + in.alphaFrames() + " part-faded frames while " + in.movers() + " glides " + in.travel()
                + " px over " + in.betweenFrames() + " frames, at rest in " + in.settledMs() + " ms; goes over " + out.alphaFrames()
                + " part-faded frames while it glides back over " + out.betweenFrames() + " frames, at rest in " + out.settledMs() + " ms");
    }

    private void pulse(Site site) {
        site.show.run();
        settleFor(SETTLE_MS);
        requireRest(site, true, "before the pulse");
        Frames frames = new Frames(site);
        frames.start();
        for (int i = 0; i < 6; i++) { main(() -> activity.redraw()); SystemClock.sleep(400); }
        frames.stop();
        View subject = onMain(site.subject::get);
        for (float[] frame : frames.samples) {
            require(frame[1] == 1 && Math.abs(frame[2] - 1) < .001f, site.name + " faded during live refreshes (alpha " + frame[2] + ")");
            for (int n = 0; n < frames.count; n++)
                require(Math.abs(frame[3 + n] - frames.samples.get(0)[3 + n]) <= .5f, site.name + ": a neighbour (" + frames.name(n) + ") moved during live refreshes ("
                        + frames.samples.get(0)[3 + n] + " to " + frame[3 + n] + " px; track " + track(frames, n) + ")");
        }
        require(subject != null, site.name + " is missing");
        checks.add(site.name + ": " + frames.samples.size() + " frames over 2.4 s of the 1 s pulse and six redraws, resting: nothing faded or moved");
        site.hide.run();
        settleFor(SETTLE_MS);
    }

    private void reversals(Site site) {
        int children = onMain(() -> ((ViewGroup) site.subject.get().getParent()).getChildCount());
        // Shown, then hidden two frames into its arrival.
        site.show.run(); frames(2); site.hide.run();
        settleFor(SETTLE_MS);
        requireRest(site, false, "shown then hidden two frames in");
        // Hidden two frames into its departure, then shown again.
        site.show.run(); settleFor(SETTLE_MS);
        site.hide.run(); frames(2); site.show.run();
        settleFor(SETTLE_MS);
        requireRest(site, true, "hidden then shown two frames in");
        // Both in one step (two refreshes before a frame): the last one asked wins, with nothing drawn between.
        main(() -> { site.hide.run(); site.show.run(); site.hide.run(); });
        settleFor(SETTLE_MS);
        requireRest(site, false, "hidden, shown and hidden in one step");
        // Shown, hidden and shown again within a tenth of a second.
        site.show.run(); frames(3); site.hide.run(); frames(3); site.show.run();
        settleFor(SETTLE_MS);
        requireRest(site, true, "shown, hidden and shown within a tenth of a second");
        main(() -> require(((ViewGroup) site.subject.get().getParent()).getChildCount() == children, site.name + ": its parent's children changed (" + children + " before)"));
        site.hide.run(); settleFor(SETTLE_MS);
        requireRest(site, false, "after the reversals");
        checks.add(site.name + ": shown and hidden two or three frames apart, or three times in one step, it ends as last asked: opaque in place or gone, nothing shifted, layout not held, no views left behind");
    }

    private void recreated(Site site) {
        site.show.run(); frames(3);
        activity = recreate();
        requirePage();
        settleFor(SETTLE_MS);
        requireRest(site, true, "in the Home rebuilt mid-arrival");
        site.hide.run(); frames(3);
        activity = recreate();
        requirePage();
        settleFor(SETTLE_MS);
        requireRest(site, false, "in the Home rebuilt mid-departure");
        checks.add(site.name + ": the activity recreated mid-arrival or mid-departure (a rotation, fold or text-size change) shows it at rest, as asked");
    }

    private void animationsOff(Site site) {
        scale(0);
        Frames in = record(site, site.show);
        requireRest(site, true, "with animations off");
        instant(in, site.name + " arrival with animations off");
        Frames out = record(site, site.hide);
        requireRest(site, false, "gone with animations off");
        instant(out, site.name + " departure with animations off");
        scale(1);
        checks.add(site.name + ": with animations off (scale 0), " + in.samples.size() + " and " + out.samples.size() + " frames show it and its neighbours only before or after");
    }

    /**
     * A change without a motion of its own (a null root: Home's edit-mode switch, a re-filter, rebuilt cards) asked
     * while a departure is still fading wins over it. The departure must not hide the view when it ends, after it was
     * asked to show. For Widgets Home also the real way: the card goes, and edit mode is entered and left mid-way.
     */
    private void instantWins(Site site) {
        site.show.run(); settleFor(SETTLE_MS);
        site.hide.run(); frames(2);
        main(() -> {
            try {
                java.lang.reflect.Method reveal = Class.forName("com.jjricks.volt.Motion").getDeclaredMethod("reveal", ViewGroup.class, View.class, boolean.class, View[].class);
                reveal.setAccessible(true);
                reveal.invoke(null, null, site.subject.get(), true, new View[0]);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        });
        site.show.run();
        settleFor(SETTLE_MS);
        requireRest(site, true, "shown at once (no motion of its own) while its departure was still fading");
        settleFor(SETTLE_MS);
        requireRest(site, true, "a while after it was shown at once mid-departure");
        String also = "";
        if (part.equals("find") && home.equals("widgets")) {
            site.hide.run(); frames(2);
            main(() -> { tally(2); invoke(field(activity, "screen"), "toggleEditing"); });
            frames(2);
            main(() -> invoke(field(activity, "screen"), "toggleEditing"));
            settleFor(SETTLE_MS);
            requireRest(site, true, "after edit mode was entered and left while it was going (and it was offered again)");
            settleFor(SETTLE_MS);
            requireRest(site, true, "a while after edit mode was entered and left mid-departure");
            also = "; so does entering and leaving edit mode while it goes";
        }
        site.hide.run(); settleFor(SETTLE_MS);
        requireRest(site, false, "after the instant show");
        checks.add(site.name + ": shown at once (no motion of its own) two frames into its departure, it stays shown after the departure would have ended" + also);
    }

    /**
     * Reversals at a slow animator scale (10), from what is on screen: shown and hidden mid-arrival, hidden and shown
     * mid-departure. No frame jumps to where the first motion was going before turning back.
     */
    private void turnsBack(Site site) {
        scale(10);
        try {
            Frames in = new Frames(site);
            in.start(); frames(2); in.mark();
            site.show.run(); SystemClock.sleep(500); site.hide.run();
            SystemClock.sleep(4000);
            in.stop();
            hitchMs = Math.max(hitchMs, in.starvedMs());
            requireRest(site, false, "hidden mid-arrival at scale 10");
            continuous(in, site.name + " hidden mid-arrival (scale 10)");
            float peak = 0; for (float[] f : in.samples) if (f[1] == 1) peak = Math.max(peak, f[2]);
            require(peak > .1f && peak < .9f, site.name + ": the reversal mid-arrival was not mid-way (opacity peaked at " + peak + ")");
            site.show.run(); settleFor(4000);
            Frames out = new Frames(site);
            out.start(); frames(2); out.mark();
            site.hide.run(); SystemClock.sleep(300); site.show.run();
            SystemClock.sleep(4000);
            out.stop();
            hitchMs = Math.max(hitchMs, out.starvedMs());
            requireRest(site, true, "shown mid-departure at scale 10");
            continuous(out, site.name + " shown mid-departure (scale 10)");
            float low = 1; for (float[] f : out.samples) low = Math.min(low, f[1] == 1 ? f[2] : 0);
            require(low > .1f && low < .9f, site.name + ": the reversal mid-departure was not mid-way (opacity fell to " + low + ")");
            checks.add(site.name + ": at scale 10, hidden mid-arrival (opacity " + String.format(Locale.ROOT, "%.2f", peak) + ") and shown mid-departure (" + String.format(Locale.ROOT, "%.2f", low)
                    + "), it turns back from where it is: " + in.samples.size() + " and " + out.samples.size() + " frames, none changing opacity by more than 0.15 or moving a neighbour more than 8 dp per 60 Hz frame from the frame before");
            site.hide.run(); settleFor(4000);
        } finally {
            scale(1);
        }
    }

    /**
     * Leaving at the end of a scrolled page that still scrolls without it: the page gets shorter and the scroll is
     * clamped. No frame may draw the views below it off their way from where they were to where they rest (a jump by
     * the clamp, then a glide back). The text is made larger (and the activity rebuilt) until the page is long enough.
     */
    private void scrolledEnd(Site site) {
        float[] scales = {1, 1.4f, 1.8f, 2.4f, 3};
        float used = -1;
        for (float textScale : scales) {
            if (textScale != 1) {
                require(settings().edit().putFloat("text_scale", textScale).commit(), "Text size failed");
                activity = recreate();
                requirePage();
            }
            site.show.run(); settleFor(SETTLE_MS);
            requireRest(site, true, "shown on the long page");
            int spare = onMain(() -> {
                View subject = site.subject.get();
                android.widget.ScrollView scroll = scrollOf(subject);
                if (scroll == null) return -1;
                return scroll.getChildAt(0).getHeight() - (scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom()) - subject.getHeight();
            });
            if (spare > 4 * density()) { used = textScale; break; }
            site.hide.run(); settleFor(SETTLE_MS);
        }
        if (used < 0) {
            require(!part.equals("find"), site.name + ": Home never scrolled past the card, even at the largest text");
            notes.add(site.name + ": the page never scrolled past it, even at the largest text; the scrolled-end check had nothing to clamp");
        } else {
            main(() -> { android.widget.ScrollView scroll = scrollOf(site.subject.get()); scroll.scrollTo(0, scroll.getChildAt(0).getHeight()); });
            settle();
            int[] scrolled = new int[1];
            Frames out = new Frames(new Site(site.name, site.subject, () -> lowest(site.subject.get()), site.show, site.hide));
            out.start(); frames(2);
            main(() -> scrolled[0] = scrollOf(site.subject.get()).getScrollY());
            site.hide.run();
            SystemClock.sleep(SETTLE_MS + 300);
            out.stop();
            int clamped = scrolled[0] - onMain(() -> scrollOf(site.subject.get()).getScrollY());
            requireRest(site, false, "after it went from the end of the long page");
            require(clamped > 0, site.name + ": its departure at the end of the page did not clamp the scroll");
            onPath(out, site.name + " departure at the end of a scrolled page (text " + used + ")");
            require(onMain(() -> scrollOf(site.subject.get()).getChildAt(0).getTranslationY()) == 0, site.name + ": the page's content is left offset");
            checks.add(site.name + ": leaving at the end of a page that still scrolls without it (text " + used + "), the scroll clamps by " + clamped
                    + " px and " + out.samples.size() + " frames draw the views below only on their way (track " + track(out, 0) + ")");
        }
        if (used != 1) { require(settings().edit().putFloat("text_scale", 1).commit(), "Text size failed"); activity = recreate(); requirePage(); }
    }

    private static android.widget.ScrollView scrollOf(View view) {
        for (android.view.ViewParent at = view == null ? null : view.getParent(); at != null; at = at.getParent())
            if (at instanceof android.widget.ScrollView) return (android.widget.ScrollView) at;
        return null;
    }

    /** The views after it on the page, and the lowest one the page shows (what a clamped scroll moves). */
    private static List<View> lowest(View subject) {
        List<View> out = siblingsAfter(subject);
        android.widget.ScrollView scroll = scrollOf(subject);
        if (scroll != null && scroll.getChildAt(0) instanceof ViewGroup) {
            ViewGroup content = (ViewGroup) scroll.getChildAt(0);
            for (int i = content.getChildCount() - 1; i >= 0; i--)
                if (content.getChildAt(i).getVisibility() == View.VISIBLE && content.getChildAt(i).getHeight() > 0) {
                    if (!out.contains(content.getChildAt(i))) out.add(content.getChildAt(i));
                    break;
                }
        }
        return out;
    }

    private float density() { return getTargetContext().getResources().getDisplayMetrics().density; }

    private static void invoke(Object target, String name) {
        try {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass())
                for (java.lang.reflect.Method method : type.getDeclaredMethods())
                    if (method.getName().equals(name) && method.getParameterCount() == 0) { method.setAccessible(true); method.invoke(target); return; }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        throw new IllegalStateException("No " + name + "()");
    }

    private void demo(Site site) {
        long hold = Long.parseLong(arguments.getString("hold_ms", "1500"));
        int cycles = Integer.parseInt(arguments.getString("cycles", "4"));
        // battery -e demo gather: the link comes back and health, temperature and charging land 130 ms apart (as gather).
        if (part.equals("battery") && arguments.getString("demo", "").equals("gather")) {
            String[] keys = {"battery_health_percent", "temperature_c", "charging_state"};
            Runnable linkBack = () -> {
                main(() -> { activity.service().ble.setSimulationScript(""); activity.redraw(); });
                for (String key : keys) { SystemClock.sleep(130); main(() -> { withheld.remove(key); activity.redraw(); }); }
            };
            Runnable linkGoes = () -> main(() -> { activity.service().ble.setSimulationScript("dropout"); activity.redraw(); for (String key : keys) withheld.add(key); });
            site = new Site(site.name, site.subject, site.neighbours, linkBack, linkGoes);
            site.hide.run();
        }
        SystemClock.sleep(hold);
        for (int i = 0; i < cycles; i++) {
            site.show.run(); SystemClock.sleep(hold);
            site.hide.run(); SystemClock.sleep(hold);
        }
        checks.add(site.name + " shown and hidden " + cycles + " times for a recording");
    }


    // ===== C1 checks =====

    /** Motion.ALERT_MS: a riding alert's fade in. */
    private static final long ALERT_MS = 150;

    /**
     * Riding mode's alert: never delayed or missed. The first frame drawn after it fires shows it in its place (only its
     * opacity rises, over at least two part-faded frames, to opaque within ALERT_MS and a frame or two); it never moves.
     * Closed, it is gone in the first frame after, with no fade.
     */
    private void alertArrivesAndGoesOnce(Site site) {
        requireRest(site, false, "before it fired");
        Frames in = record(site, site.show);
        requireRest(site, true, "after it fired");
        int first = -1;
        for (int i = 0; i < in.samples.size(); i++) if (in.samples.get(i)[0] >= in.changedAt && in.samples.get(i)[1] == 1) { first = i; break; }
        require(first >= 0, site.name + " was never drawn");
        int next = -1;
        for (int i = 0; i < in.samples.size(); i++) if (in.samples.get(i)[0] >= in.changedAt) { next = i; break; }
        require(first == next, site.name + " was not drawn in the first frame after it fired (frame " + (first - next) + " after)");
        float place = in.samples.get(first)[3];
        for (int i = first; i < in.samples.size(); i++) require(Math.abs(in.samples.get(i)[3] - place) <= 1, site.name + " moved while it faded in (" + place + " to " + in.samples.get(i)[3] + " px)");
        require(in.alphaFrames() >= 2, site.name + " popped in: " + in.alphaFrames() + " frames drew it part-way faded (alpha track " + alphaTrack(in) + ")");
        long opaque = -1; float previous = 0;
        for (int i = first; i < in.samples.size(); i++) {
            float alpha = in.samples.get(i)[2];
            require(alpha >= previous - .001f, site.name + "'s opacity fell while it faded in (" + alphaTrack(in) + ")");
            previous = alpha;
            if (opaque < 0 && alpha >= .999f) opaque = (long) (in.samples.get(i)[0] - in.samples.get(first)[0]);
        }
        require(opaque >= 0 && opaque <= ALERT_MS + 120, site.name + " took " + opaque + " ms to be opaque (alpha track " + alphaTrack(in) + ")");
        Frames out = record(site, site.hide);
        requireRest(site, false, "after it was closed");
        for (float[] f : out.samples) require(f[0] < out.changedAt || f[1] == 0 || f[2] >= .999f, site.name + " faded as it went (it should go at once; alpha track " + alphaTrack(out) + ")");
        int gone = -1;
        for (int i = 0; i < out.samples.size(); i++) if (out.samples.get(i)[0] >= out.changedAt) { gone = i; break; }
        require(gone >= 0 && out.samples.get(gone)[1] == 0, site.name + " was still drawn in the first frame after it was closed");
        checks.add(site.name + ": drawn in its place in the first frame after it fired, opaque in " + opaque + " ms over " + in.alphaFrames()
                + " part-faded frames without moving; gone in the first frame after it was closed");
    }

    /**
     * At scale 10 (a 1.5 s fade), closed mid-fade it is gone in the next frame; fired again it fades up from nothing,
     * never jumping, while it already sits in its place.
     */
    private void alertTurns(Site site) {
        scale(10);
        try {
            Frames frames = new Frames(site);
            frames.start(); frames(2);
            site.show.run(); SystemClock.sleep(500);
            float mid = onMain(() -> opacity(site.subject.get()));
            require(mid > .05f && mid < .95f, site.name + ": not mid-fade half a second in at scale 10 (opacity " + mid + ")");
            frames.mark();
            site.hide.run();
            frames(2);
            requireRest(site, false, "closed mid-fade at scale 10");
            site.show.run(); SystemClock.sleep(2500);
            frames.stop();
            requireRest(site, true, "fired again at scale 10");
            int after = -1;
            for (int i = 0; i < frames.samples.size(); i++) if (frames.samples.get(i)[0] >= frames.changedAt) { after = i; break; }
            require(after >= 0 && frames.samples.get(after)[1] == 0, site.name + " was still drawn in the frame after it was closed mid-fade");
            // From the second arrival on: rising continuously from nothing.
            int again = -1;
            for (int i = after; i < frames.samples.size(); i++) if (frames.samples.get(i)[1] == 1) { again = i; break; }
            require(again >= 0, site.name + " did not come back");
            require(frames.samples.get(again)[2] < .3f, site.name + " came back already part-shown (" + frames.samples.get(again)[2] + ")");
            for (int i = again + 1; i < frames.samples.size(); i++) {
                float[] a = frames.samples.get(i - 1), b = frames.samples.get(i);
                float late = Math.max(1, (b[0] - a[0]) / 16.7f);
                require(b[2] - a[2] <= .15f * late && b[2] >= a[2] - .001f, site.name + "'s opacity snapped from " + a[2] + " to " + b[2] + " coming back (alpha track " + alphaTrack(frames) + ")");
            }
            checks.add(site.name + ": at scale 10, closed mid-fade (opacity " + String.format(Locale.ROOT, "%.2f", mid) + ") it is gone in the next frame; fired again it rises from nothing without a jump");
            site.hide.run(); settle();
        } finally {
            scale(1);
        }
    }

    /** At scale 10, a real tap on the banner's close button mid-fade closes it: it takes taps from its first frame. */
    private void closeMidFade() {
        scale(10);
        try {
            site.show.run(); SystemClock.sleep(300);
            View close = onMain(() -> root().findViewWithTag("riding_alert_close"));
            float fading = onMain(() -> opacity(site.subject.get()));
            require(fading > .05f && fading < .95f, "Riding alert: not mid-fade at the tap (opacity " + fading + ")");
            tap(centre(close));
            SystemClock.sleep(300); waitForIdleSync();
            requireRest(site, false, "after its close was tapped mid-fade");
            require(onMain(() -> field(activity.service(), "ridingAlert")) == null, "Riding alert: the tap mid-fade did not close the alert");
            checks.add("Riding alert: its close button tapped mid-fade (opacity " + String.format(Locale.ROOT, "%.2f", fading) + ", scale 10) closes it at once");
        } finally {
            scale(1);
        }
    }

    /**
     * A leaving view that scrolls its own content (Night vision's Schedule selector, wider than the screen at large text,
     * scrolled to its end) is drawn fading as it was on screen: the ghost Motion draws over the page matches a snapshot of
     * the selector at its scroll. Drawn without its scroll, its choices would jump sideways as it fades.
     */
    private void ghostScroll(Site site) {
        float[] scales = {1, 1.4f, 1.8f, 2.4f, 3};
        float used = -1;
        for (float textScale : scales) {
            if (textScale != 1) { require(settings().edit().putFloat("text_scale", textScale).commit(), "Text size failed"); activity = recreate(); requirePage(); }
            site.show.run(); settleFor(SETTLE_MS);
            boolean wide = onMain(() -> { View selector = root().findViewWithTag("preference-selector-riding_night_schedule");
                return selector != null && selector.isShown() && ((ViewGroup) selector).getChildAt(0).getWidth() > selector.getWidth(); });
            if (wide) { used = textScale; break; }
            site.hide.run(); settleFor(SETTLE_MS);
        }
        require(used > 0, site.name + ": the Schedule selector never overflowed, even at the largest text");
        int[] result = onMain(() -> {
            View selector = root().findViewWithTag("preference-selector-riding_night_schedule");
            selector.scrollTo(((ViewGroup) selector).getChildAt(0).getWidth(), 0);
            return new int[]{selector.getScrollX()};
        });
        require(result[0] > 0, site.name + ": the Schedule selector did not scroll");
        settle();
        float differs = onMain(() -> {
            try {
                View selector = root().findViewWithTag("preference-selector-riding_night_schedule");
                ViewGroup page = (ViewGroup) scrollOf(selector).getChildAt(0);
                Method capture = Class.forName("com.jjricks.volt.Motion").getDeclaredMethod("capture", View.class);
                capture.setAccessible(true);
                android.graphics.Bitmap expected = (android.graphics.Bitmap) capture.invoke(null, selector);
                Class<?> ghostType = Class.forName("com.jjricks.volt.Motion$Ghost");
                java.lang.reflect.Constructor<?> make = ghostType.getDeclaredConstructor(View.class, ViewGroup.class, float.class);
                make.setAccessible(true);
                android.graphics.drawable.Drawable ghost = (android.graphics.drawable.Drawable) make.newInstance(selector, page, 1f);
                ghost.setBounds(0, 0, page.getWidth(), page.getHeight());
                android.graphics.Bitmap drawn = android.graphics.Bitmap.createBitmap(page.getWidth(), page.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                ghost.draw(new android.graphics.Canvas(drawn));
                int[] at = new int[2], base = new int[2];
                selector.getLocationInWindow(at); page.getLocationInWindow(base);
                int x0 = at[0] - base[0], y0 = at[1] - base[1], bad = 0, all = 0;
                for (int y = 0; y < expected.getHeight(); y += 2) for (int x = 0; x < expected.getWidth(); x += 2) {
                    if (x0 + x < 0 || y0 + y < 0 || x0 + x >= drawn.getWidth() || y0 + y >= drawn.getHeight()) continue;
                    int a = expected.getPixel(x, y), b = drawn.getPixel(x0 + x, y0 + y);
                    all++;
                    if (Math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) > 12 || Math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) > 12
                            || Math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) > 12 || Math.abs(android.graphics.Color.alpha(a) - android.graphics.Color.alpha(b)) > 12) bad++;
                }
                expected.recycle(); drawn.recycle();
                return all == 0 ? 1f : bad / (float) all;
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        });
        require(differs < .01f, site.name + ": the ghost of the Schedule selector scrolled " + result[0] + " px (text " + used + ") does not match it on screen ("
                + String.format(Locale.ROOT, "%.1f", differs * 100) + "% of pixels differ): it would jump as it fades");
        checks.add(site.name + ": its Schedule selector, scrolled " + result[0] + " px at text " + used + ", is drawn fading exactly as on screen ("
                + String.format(Locale.ROOT, "%.2f", differs * 100) + "% of pixels differ)");
        main(() -> root().findViewWithTag("preference-selector-riding_night_schedule").scrollTo(0, 0));
        site.hide.run(); settleFor(SETTLE_MS);
        if (used != 1) { require(settings().edit().putFloat("text_scale", 1).commit(), "Text size failed"); activity = recreate(); requirePage(); }
    }

    /**
     * Battery & charging as a connection starts: the readings land one by one (the level first, then health, the pack
     * temperature and the charging state over 0.4 s). The three lines that arrive come in together, in one glide, about
     * GATHER_MS (700 ms) after the first live reading, rather than popping in one after another. A value on a line already
     * shown (the level) changes at once meanwhile, and the lines go at once when the link does.
     */
    private void gather(Site site) {
        String[] keys = {"battery_health_percent", "temperature_c", "charging_state"};
        Object summary = part("HubBatterySummary");
        View health = onMain(() -> (View) field(summary, "healthRow")), pack = onMain(() -> (View) field(summary, "packRow")), state = onMain(() -> (View) field(summary, "state"));
        android.widget.TextView level = onMain(() -> (android.widget.TextView) field(summary, "level"));
        Site rows = new Site("Battery & charging's health, pack temperature and charging lines", () -> health, () -> lowest(health), site.show, site.hide);
        rows.with = () -> { List<View> out = new ArrayList<>(); out.add(pack); out.add(state); return out; };
        measured(rows.name + " coming in together", () -> {
            main(() -> { withheld.clear(); activity.service().ble.setSimulationScript(""); activity.redraw(); });
            settleFor(SETTLE_MS * 2);
            main(() -> require(health.isShown() && pack.isShown() && state.isShown(), "Battery & charging does not show health, temperature and charging on the simulator"));
            // The link goes: they go at once (never held).
            main(() -> {
                activity.service().ble.setSimulationScript("dropout"); activity.redraw();
                require(health.getVisibility() == View.GONE && pack.getVisibility() == View.GONE && state.getVisibility() == View.GONE, "The lines waited to go when the link went");
            });
            main(() -> { for (String key : keys) withheld.add(key); });
            settleFor(SETTLE_MS);
            Frames frames = new Frames(rows);
            frames.start(); frames(2); frames.mark();
            long t0 = SystemClock.uptimeMillis();
            main(() -> { activity.service().ble.setSimulationScript(""); activity.redraw(); });
            String[] seen = new String[1];
            for (int i = 0; i < keys.length; i++) {
                SystemClock.sleep(Math.max(0, t0 + 130 * (i + 1) - SystemClock.uptimeMillis()));
                String key = keys[i];
                main(() -> {
                    withheld.remove(key); activity.redraw();
                    if (key.equals("temperature_c")) {
                        // A value change on a shown line, mid-gathering: on screen at once.
                        try {
                            Method put = Class.forName("com.jjricks.volt.Telemetry").getDeclaredMethod("putSimulationSample", String.class, String.class, Double.class, long.class);
                            put.setAccessible(true);
                            put.invoke(field(activity.service().ble, "telemetry"), "battery_percent", "57 %", 57d, SystemClock.elapsedRealtime());
                        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                        activity.redraw();
                        seen[0] = level.getText().toString();
                    }
                });
            }
            SystemClock.sleep(SETTLE_MS + 900);
            frames.stop();
            // The readings land on the test's own schedule, so a page idle between them owes no frame: only a wait while
            // a line was on its way in (drawn, not yet opaque) says the host was starved.
            hitchMs = Math.max(hitchMs, frames.fadingGapMs());
            require(seen[0] != null && seen[0].contains("57"), "Battery & charging held the level's new value while lines were gathering (it read " + seen[0] + ")");
            int[] firstShown = {-1, -1, -1};
            for (int i = 0; i < frames.samples.size(); i++) {
                if (firstShown[0] < 0 && frames.samples.get(i)[1] == 1 && frames.samples.get(i)[2] > 0) firstShown[0] = i;
                for (int n = 0; n < 2; n++) if (firstShown[n + 1] < 0 && frames.partners.get(i)[n] > 0) firstShown[n + 1] = i;
            }
            for (int n = 0; n < 3; n++) require(firstShown[n] >= 0, "Battery & charging: line " + n + " never came in");
            long at = (long) (frames.samples.get(firstShown[0])[0] - frames.changedAt);
            require(firstShown[0] == firstShown[1] && firstShown[1] == firstShown[2], "Battery & charging: the lines came in one by one (first drawn at frames "
                    + Arrays.toString(firstShown) + ", " + at + " ms after the link came back)");
            require(at >= Math.min(400, 130 * keys.length), "Battery & charging: the lines came in " + at + " ms after the link came back, before the last reading landed");
            require(at <= 1200, "Battery & charging: the lines waited " + at + " ms to come in");
            require(frames.alphaFrames() >= 2 && frames.partnerFrames(0) >= 2 && frames.partnerFrames(1) >= 2, "Battery & charging: the gathered lines popped in (alpha track " + alphaTrack(frames) + ")");
            checks.add(rows.name + ": landing over 0.4 s as a connection starts, they come in together " + at + " ms after the first live reading, over "
                    + frames.alphaFrames() + " part-faded frames; the level's new value showed at once meanwhile, and they went at once with the link");
        }, () -> { main(() -> { withheld.clear(); activity.redraw(); }); settleFor(SETTLE_MS); });
        // Back to the part's starting point: the pack temperature withheld.
        main(() -> { withheld.clear(); withheld.add("temperature_c"); activity.redraw(); });
        settleFor(SETTLE_MS);
    }

    /**
     * Setup's problem line, at a text size where it lands below the fold: the page scrolls to where the line will be once
     * it is in, gliding from the start of its arrival, and ends with all of it in view (above the footer). The scroll
     * moves one way, over at least two frames, and never stops short to jump again.
     */
    private void intoView(Site site) {
        String sizeBefore;
        try { sizeBefore = shell("wm size"); } catch (Exception error) { throw new IllegalStateException(error); }
        java.util.regex.Matcher physical = java.util.regex.Pattern.compile("Physical size: (\\d+)x(\\d+)").matcher(sizeBefore);
        java.util.regex.Matcher override = java.util.regex.Pattern.compile("Override size: (\\d+)x(\\d+)").matcher(sizeBefore);
        boolean overridden = override.find();
        java.util.regex.Matcher size = overridden ? override : physical.find() ? physical : null;
        require(size != null, "No screen size (" + sizeBefore.trim() + ")");
        int width = Integer.parseInt(size.group(1)), height = Integer.parseInt(size.group(2));
        // Setup fits a tall phone even at the largest text: the check runs in a window half as tall (a phone held
        // sideways, a split screen), where the problem line lands below the fold.
        try {
            try { shell("wm size " + width + "x" + (height / 2)); } catch (Exception error) { throw new IllegalStateException(error); }
            settleFor(SETTLE_MS * 2);
            intoViewAt(site);
        } finally {
            try { shell(overridden ? "wm size " + width + "x" + height : "wm size reset"); } catch (Exception error) { throw new IllegalStateException(error); }
            settleFor(SETTLE_MS * 2);
        }
    }
    private void intoViewAt(Site site) {
        float[] scales = {1, 1.4f, 1.8f, 2.4f, 3};
        float used = -1;
        for (float textScale : scales) {
            if (textScale != 1) { require(settings().edit().putFloat("text_scale", textScale).commit(), "Text size failed"); activity = recreate(); requirePage(); }
            site.hide.run(); settleFor(SETTLE_MS);
            main(() -> { android.widget.ScrollView scroll = scrollOf(statusLine()); scroll.scrollTo(0, 0); });
            settle();
            site.show.run(); settleFor(SETTLE_MS);
            // Below the fold by where it lies in the page, not by whether the page scrolled (a build that never scrolls fails below).
            boolean below = onMain(() -> { View line = statusLine(); android.widget.ScrollView scroll = scrollOf(line);
                int[] a = new int[2], b = new int[2]; line.getLocationOnScreen(a); scroll.getLocationOnScreen(b);
                int bottomInPage = a[1] - b[1] + scroll.getScrollY() + line.getHeight();
                return bottomInPage > scroll.getHeight() - scroll.getPaddingBottom(); });
            site.hide.run(); settleFor(SETTLE_MS);
            if (below) { used = textScale; break; }
        }
        if (used < 0) { notes.add(site.name + ": the line never landed below the fold, even at the largest text; nothing to scroll to"); return; }
        main(() -> scrollOf(statusLine()).scrollTo(0, 0));
        settle();
        Site watch = new Site(site.name, site.subject, () -> { List<View> out = new ArrayList<>(); out.add(statusLine()); return out; }, site.show, site.hide);
        Frames frames = record(watch, site.show);
        int[] end = onMain(() -> { View line = statusLine(); android.widget.ScrollView scroll = scrollOf(line);
            int[] a = new int[2], b = new int[2]; line.getLocationOnScreen(a); scroll.getLocationOnScreen(b);
            return new int[]{a[1], a[1] + line.getHeight(), b[1], b[1] + scroll.getHeight() - scroll.getPaddingBottom(), scroll.getScrollY()}; });
        require(end[0] >= end[2] - 1 && end[1] <= end[3] + 1, site.name + ": after its arrival it is not all in view (" + end[0] + " to " + end[1] + " px; the page shows " + end[2] + " to " + end[3] + ")");
        require(end[4] > 0, site.name + ": the page did not scroll to it");
        float from = Float.NaN, to = frames.last()[3]; int between = 0;
        for (float[] f : frames.samples) if (f[1] == 1 && f[0] >= frames.changedAt) { from = f[3]; break; }
        require(!Float.isNaN(from), site.name + " was never drawn");
        float previous = from;
        for (float[] f : frames.samples) {
            if (f[1] == 0) continue;
            require((f[3] - previous) * Math.signum(to - from) >= -1, site.name + ": the scroll turned back (" + track(frames, 0) + ")");
            if (f[3] > Math.min(from, to) + 1 && f[3] < Math.max(from, to) - 1) between++;
            previous = f[3];
        }
        require(between >= 2, site.name + ": the page jumped to it (" + between + " frames between; track " + track(frames, 0) + ")");
        checks.add(site.name + ": below the fold at text " + used + ", the page glides " + Math.round(Math.abs(to - from)) + " px to it over " + between
                + " frames from its arrival and ends with all of it in view (scroll " + end[4] + ")");
        site.hide.run(); settleFor(SETTLE_MS);
        require(settings().edit().putFloat("text_scale", 1).commit(), "Text size failed"); activity = recreate(); requirePage();
    }

    // ===== Taps mid-fade =====

    private void taps() {
        if (part.equals("rides")) startTwice();
        else if (part.equals("scooters")) useThenConnect();
        else if (part.equals("riding-alert")) closeMidFade();
        else notes.add(site.name + ": no tap check for this part");
    }

    /**
     * Ride › Rides: Start recording tapped twice, the second tap mid-fade where Start was. A second tap that reaches
     * Start stops the ride it just started (toggleRecording), and as the rider's own stop it holds automatic recording
     * until the next connection.
     */
    private void startTwice() {
        Object ble = onMain(() -> activity.service().ble);
        if (onMain(() -> activity.service().simulating())) main(() -> activity.service().setSimulation(false));
        await(() -> onMain(() -> activity.service().ble.state() == BleController.State.DISCONNECTED), "The simulator's link did not end");
        FakeLink link = new FakeLink((BleController) ble);
        main(link::attach);
        await(() -> onMain(() -> activity.service().ble.ready()), "The synthetic link is not ready");
        main(() -> activity.redraw());
        settle();
        Object rides = part("RidesScreen");
        View start = onMain(() -> (View) field(rides, "recordButton")), card = onMain(() -> (View) field(rides, "recordCard"));
        main(() -> { link.status(); require(start.isShown() && start.isEnabled(), "Start recording is not offered on the synthetic link"); });
        require(!onMain(() -> activity.service().isRecording()), "A ride is already recording");
        float[] at = centre(start);
        scale(10);
        try {
            main(link::status);
            tap(at);
            await(() -> onMain(() -> activity.service().isRecording()), "The first tap on Start recording did not start a ride");
            SystemClock.sleep(250);
            float fading = onMain(() -> drawn(card));
            require(fading > .05f && fading < .95f, "Rides: the record card is not mid-fade at the second tap (opacity " + fading + ")");
            tap(at);
            SystemClock.sleep(1500);
            waitForIdleSync();
            boolean recording = onMain(() -> activity.service().isRecording());
            boolean held = onMain(() -> (Boolean) field(field(activity.service(), "autoStart"), "recordHeld"));
            require(recording, "Rides: a second tap where Start recording was, mid-fade (opacity " + fading + "), stopped the ride it had just started");
            require(!held, "Rides: a second tap mid-fade held automatic recording (the rider's own stop)");
            checks.add("Rides: Start recording tapped, then tapped again where it was while its card faded out (opacity " + String.format(Locale.ROOT, "%.2f", fading)
                    + ", scale 10): still recording, automatic recording not held");
        } finally {
            scale(1);
            main(() -> { if (activity.service().isRecording()) activity.service().stopRide(); });
            await(() -> onMain(() -> !activity.service().isRecording() && !activity.service().phoneSaving()), "The ride did not stop");
            main(() -> ((BleController) ble).disconnect());
            settle();
        }
    }

    /**
     * Settings › My scooters: another scooter's Use tapped, then its Connect, arriving where Use was, while Use fades out.
     * Connect must get the tap. A listener on Connect consumes it (nothing connects; Bluetooth stays off) and records it;
     * one on Use records anything that reaches Use and lets it through.
     */
    private void useThenConnect() {
        if (onMain(() -> activity.service().simulating())) main(() -> activity.service().setSimulation(false));
        String[] second = new String[1];
        main(() -> {
            ScooterProfiles profiles = activity.service().profiles;
            ScooterProfiles.Profile other = profiles.create(FIXTURE_NAME + " two", "C1D2E3F4A5B7", 0);
            other.serial = "X2538-SYNTHETIC-2"; other.mac = "C0:FF:EE:12:34:59"; profiles.update(other);
            // Creating a scooter makes it current: the fixture scooter stays current, the new one offers Use.
            profiles.select(profileId);
            second[0] = other.id;
            activity.redraw();
        });
        settle();
        Object screen = part("MoreScreen");
        Object card = onMain(() -> ((java.util.Map<?, ?>) field(screen, "scooterCards")).get(second[0]));
        require(card != null, "My scooters has no card for the second scooter");
        View use = onMain(() -> (View) field(card, "use")), connect = onMain(() -> (View) field(card, "connect"));
        main(() -> require(use.isShown() && !connect.isShown(), "The second scooter's card does not offer Use (Use visibility " + use.getVisibility() + ", shown "
                + use.isShown() + "; Connect visibility " + connect.getVisibility() + "; current " + activity.service().profiles.active().id.equals(second[0]) + ", simulating " + activity.service().simulating() + ")"));
        List<String> reachedUse = new CopyOnWriteArrayList<>(), reachedConnect = new CopyOnWriteArrayList<>();
        float[] onUse = centre(use);
        scale(10);
        try {
            main(() -> use.setOnTouchListener((v, event) -> { reachedUse.add(MotionEvent.actionToString(event.getActionMasked())); return false; }));
            tap(onUse);
            await(() -> onMain(() -> second[0].equals(activity.service().profiles.active().id)), "Use did not make the second scooter current");
            reachedUse.clear();
            main(() -> connect.setOnTouchListener((v, event) -> { reachedConnect.add(MotionEvent.actionToString(event.getActionMasked())); return true; }));
            SystemClock.sleep(250);
            float fading = onMain(() -> drawn(use));
            require(fading > .05f && fading < .95f, "My scooters: Use is not mid-fade at the tap on Connect (opacity " + fading + ")");
            float[] onConnect = centre(connect);
            require(onMain(() -> { int[] w = new int[2]; use.getLocationOnScreen(w); return onConnect[0] >= w[0] && onConnect[0] <= w[0] + use.getWidth()
                    && onConnect[1] >= w[1] && onConnect[1] <= w[1] + use.getHeight(); }), "My scooters: Connect's centre is not where Use is drawn fading");
            tap(onConnect);
            SystemClock.sleep(300);
            waitForIdleSync();
            require(reachedUse.isEmpty(), "My scooters: the fading Use (opacity " + fading + ") took the tap on Connect (" + reachedUse + ")");
            require(reachedConnect.contains("ACTION_DOWN") && reachedConnect.contains("ACTION_UP"), "My scooters: Connect did not get the tap (" + reachedConnect + ")");
            checks.add("My scooters: Use tapped, then the arriving Connect where Use was while Use faded out (opacity " + String.format(Locale.ROOT, "%.2f", fading)
                    + ", scale 10): Connect got the tap, Use none");
        } finally {
            main(() -> { use.setOnTouchListener(null); connect.setOnTouchListener(null); });
            scale(1);
        }
    }

    private static Method leavingMethod;
    private static boolean leavingLooked;
    /** Motion.leaving: the opacity a leaving view is drawn at, or -1 (also on a build without it). */
    private static float leaving(View view) {
        if (view == null) return -1;
        if (!leavingLooked) {
            leavingLooked = true;
            try { leavingMethod = Class.forName("com.jjricks.volt.Motion").getDeclaredMethod("leaving", View.class); leavingMethod.setAccessible(true); }
            catch (ReflectiveOperationException absent) { leavingMethod = null; }
        }
        if (leavingMethod == null) return -1;
        try { return (Float) leavingMethod.invoke(null, view); } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    /** The opacity a view is drawn at: leaving, or shown (alpha x transition alpha), or 0. */
    private static float drawn(View view) {
        float leaving = leaving(view);
        if (leaving >= 0) return leaving;
        return view.isShown() && view.getHeight() > 0 ? view.getAlpha() * (Build.VERSION.SDK_INT >= 29 ? view.getTransitionAlpha() : 1) : 0;
    }
    private float[] centre(View view) {
        return onMain(() -> { int[] at = new int[2]; view.getLocationOnScreen(at); return new float[]{at[0] + view.getWidth() / 2f, at[1] + view.getHeight() / 2f}; });
    }
    /** A real tap through the window (its hit test decides who gets it). */
    private void tap(float[] at) {
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, at[0], at[1], 0);
        press.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        sendPointerSync(press); press.recycle();
        SystemClock.sleep(40);
        MotionEvent lift = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, at[0], at[1], 0);
        lift.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        sendPointerSync(lift); lift.recycle();
    }

    /** A synthetic authenticated link (AutoStartValidation's): status and motion pushes, writes acknowledged. */
    private final class FakeLink {
        final BleController ble;
        final byte[] state = new byte[44];
        final Handler handler = new Handler(Looper.getMainLooper());
        BluetoothGatt gatt; BluetoothGattCharacteristic writer, notifier; BluetoothGattCallback callbacks;
        FakeLink(BleController ble) { this.ble = ble; state[1] = 3; state[2] = 0; state[3] = 1; state[6] = 2; state[7] = 1; state[19] = 3; state[25] = 50; state[31] = 100; state[33] = 0; state[34] = 3; }
        void attach() { try {
            BluetoothDevice device = getTargetContext().getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(fixture.mac);
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe"); Field unsafe = unsafeType.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
            gatt = (BluetoothGatt) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe.get(null), BluetoothGatt.class);
            Class<?> iface = Class.forName("android.bluetooth.IBluetoothGatt");
            InvocationHandler transport = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) { if (method.getName().equals("equals")) return proxy == args[0]; if (method.getName().equals("hashCode")) return System.identityHashCode(proxy); return "RevealFixtureGatt"; }
                if (method.getName().equals("writeCharacteristic")) { set(gatt, "mDeviceBusy", false); handler.postDelayed(() -> callbacks.onCharacteristicWrite(gatt, writer, 0), 10); return 0; }
                Class<?> type = method.getReturnType(); return type == int.class ? 0 : type == boolean.class ? false : type == long.class ? 0L : null;
            };
            set(gatt, "mService", Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, transport));
            set(gatt, "mClientRegistered", true); set(gatt, "mDeviceBusy", false); set(gatt, "mDeviceBusyLock", new Object()); set(gatt, "mStateLock", new Object()); set(gatt, "mDevice", device); set(gatt, "mServices", new CopyOnWriteArrayList<BluetoothGattService>());
            BluetoothGattService gattService = new BluetoothGattService(BleController.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY); set(gattService, "mDevice", device);
            writer = new BluetoothGattCharacteristic(BleController.WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
            notifier = new BluetoothGattCharacteristic(BleController.NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0); gattService.addCharacteristic(writer); gattService.addCharacteristic(notifier);
            callbacks = (BluetoothGattCallback) field(ble, "callbacks"); set(ble, "profile", fixture); set(ble, "gatt", gatt); set(ble, "writer", writer); set(ble, "serial", fixture.serial);
            set(ble, "authenticated", true); set(ble, "discovered", true); set(ble, "subscribed", true); set(ble, "wanted", false); set(ble, "state", BleController.State.READY);
            status(); motion(0, 0);
        } catch (Exception error) { throw new IllegalStateException("Synthetic GATT unavailable; relax hidden_api_policy on the disposable emulator", error); } }
        void status() { notifyPacket(0x70, 0, state); }
        void motion(int driving, int kmh) { notifyPacket(0x91, 0, new byte[]{0, (byte) driving, (byte) kmh}); }
        void notifyPacket(int cmd, int status, byte[] payload) {
            byte[] packet = new byte[payload.length + 9]; packet[0] = 0x55; packet[1] = (byte) 0xaa; packet[3] = (byte) cmd; packet[4] = (byte) (payload.length + 1); packet[5] = (byte) status;
            System.arraycopy(payload, 0, packet, 6, payload.length); int sum = 0; for (int i = 0; i < packet.length - 3; i++) sum += packet[i] & 255;
            packet[packet.length - 3] = (byte) sum; packet[packet.length - 2] = (byte) 0xfe; packet[packet.length - 1] = (byte) 0xfd;
            handler.postDelayed(() -> callbacks.onCharacteristicChanged(gatt, notifier, packet), 10);
        }
    }

    // ===== Frames =====

    /**
     * Every drawn frame: [ms since start, shown 0/1, opacity (alpha x transition alpha), each neighbour's top on the screen
     * px (those above it first)]; and the opacity each view that fades with it is drawn at ({@link #partners}).
     */
    private final class Frames {
        final List<float[]> samples = new ArrayList<>(), partners = new ArrayList<>();
        final Site site;
        List<View> watched, fading;
        int count, aboveCount, withCount;
        /** When the change was asked (ms since start), or -1; the samples before {@code stopIndex} are drawn frames. */
        long changedAt = -1;
        int stopIndex = Integer.MAX_VALUE;
        private boolean run;
        private long started;
        private android.view.ViewTreeObserver.OnDrawListener tick;
        private android.view.ViewTreeObserver observer;
        Frames(Site site) { this.site = site; }
        /** Samples each frame as it draws (what reaches the screen, after layout, pre-draw and animation). */
        void start() {
            main(() -> {
                List<View> above = site.above.get();
                watched = new ArrayList<>(above);
                watched.addAll(site.neighbours.get());
                aboveCount = above.size();
                count = watched.size();
                fading = new ArrayList<>(site.with.get());
                withCount = fading.size();
                fading.addAll(site.against.get());
                started = SystemClock.uptimeMillis();
                run = true;
                tick = () -> { if (run) sample(); };
                observer = windowOf(site).getViewTreeObserver();
                observer.addOnDrawListener(tick);
                sample();
            });
        }
        /** Just before the change is asked. */
        void mark() { changedAt = SystemClock.uptimeMillis() - started; }
        private void sample() {
            View subject = site.subject.get();
            float[] frame = new float[3 + count];
            frame[0] = SystemClock.uptimeMillis() - started;
            frame[1] = subject != null && drawnAtAll(subject) ? 1 : 0;
            frame[2] = subject == null ? 0 : opacity(subject);
            // The screen position includes every translation (the view's own, the scroll content's, a sheet's) and the window's place.
            for (int n = 0; n < count; n++) { int[] at = new int[2]; watched.get(n).getLocationOnScreen(at); frame[3 + n] = at[1]; }
            samples.add(frame);
            float[] faded = new float[fading.size()];
            for (int n = 0; n < faded.length; n++) faded[n] = drawnAtAll(fading.get(n)) ? opacity(fading.get(n)) : 0;
            partners.add(faded);
        }
        void stop() { main(() -> { run = false; if (observer.isAlive()) observer.removeOnDrawListener(tick); stopIndex = samples.size(); sample(); }); }
        /**
         * The longest wait for a drawn frame after the change was asked: for its first frame, and between frames while
         * anything was still on its way (a view at rest owes no frame). 0 without a change.
         */
        long starvedMs() {
            if (changedAt < 0) return 0;
            float[] end = last(); long worst = 0;
            for (int i = 1; i < Math.min(stopIndex, samples.size()); i++) {
                float[] a = samples.get(i - 1), b = samples.get(i);
                if (b[0] < changedAt) continue;
                boolean first = a[0] < changedAt;
                if (!first && same(a, end) && Arrays.equals(partners.get(i - 1), partners.get(partners.size() - 1))) continue;
                worst = Math.max(worst, (long) (b[0] - Math.max(a[0], changedAt)));
            }
            return worst;
        }
        /** The longest wait for a frame while the subject was drawn not yet opaque, or a partner part-way faded (ms). */
        long fadingGapMs() {
            long worst = 0;
            for (int i = 1; i < Math.min(stopIndex, samples.size()); i++) {
                float[] a = samples.get(i - 1);
                boolean fading = a[1] == 1 && a[2] < .96f;
                for (float f : partners.get(i - 1)) fading |= f > 0 && f < .96f;
                if (fading) worst = Math.max(worst, (long) (samples.get(i)[0] - a[0]));
            }
            return worst;
        }
        /** Frames that drew partner n part-way faded. */
        int partnerFrames(int n) { int c = 0; for (float[] f : partners) if (f[n] > .04f && f[n] < .96f) c++; return c; }
        /** Frames that drew the subject part-way faded. */
        int alphaFrames() { int n = 0; for (float[] f : samples) if (f[1] == 1 && f[2] > .04f && f[2] < .96f) n++; return n; }
        /** The neighbour that moved furthest, and how far (px). */
        int mover() {
            int best = -1; float most = 0;
            for (int n = 0; n < count; n++) { float d = Math.abs(last()[3 + n] - samples.get(0)[3 + n]); if (d > most) { most = d; best = n; } }
            return best;
        }
        String name(int n) { return watched.get(n).getClass().getSimpleName() + (watched.get(n).getTag() == null ? "" : " " + watched.get(n).getTag()); }
        String movers() { int n = mover(); return n < 0 ? "nothing" : watched.get(n).getClass().getSimpleName() + (watched.get(n).getTag() == null ? "" : " " + watched.get(n).getTag()); }
        int travel() { int n = mover(); return n < 0 ? 0 : Math.round(Math.abs(last()[3 + n] - samples.get(0)[3 + n])); }
        /** Frames that drew the furthest-moving neighbour strictly between where it started and where it ended. */
        int betweenFrames() {
            int n = mover(); if (n < 0) return 0;
            float a = samples.get(0)[3 + n], b = last()[3 + n], lo = Math.min(a, b) + 1, hi = Math.max(a, b) - 1; int c = 0;
            for (float[] f : samples) if (f[3 + n] > lo && f[3 + n] < hi) c++;
            return c;
        }
        float[] last() { return samples.get(samples.size() - 1); }
        /** From the first frame that differs from the first to the last frame that differs from the last (ms). */
        long settledMs() {
            float[] first = samples.get(0), end = last(); long from = -1, to = -1;
            for (float[] f : samples) {
                if (from < 0 && !same(f, first)) from = (long) f[0];
                if (!same(f, end)) to = (long) f[0];
            }
            return from < 0 || to < 0 ? 0 : Math.max(0, to - from);
        }
        private boolean same(float[] a, float[] b) {
            if (a[1] != b[1] || Math.abs(a[2] - b[2]) > .001f) return false;
            for (int n = 3; n < a.length; n++) if (Math.abs(a[n] - b[n]) > .5f) return false;
            return true;
        }
    }

    /** Records frames while {@code change} runs, until everything has rested for a while. */
    private Frames record(Site site, Runnable change) {
        Frames frames = new Frames(site);
        frames.start();
        frames(2);
        frames.mark();
        change.run();
        SystemClock.sleep(SETTLE_MS + 300);
        frames.stop();
        hitchMs = Math.max(hitchMs, frames.starvedMs());
        return frames;
    }

    /** Drawn: shown, or drawn leaving by Motion. */
    private static boolean drawnAtAll(View view) { return leaving(view) >= 0 || view.isShown() && view.getHeight() > 0; }
    /** The opacity it is drawn at: leaving, or its alpha x transition alpha (its own; a fading parent counts too). */
    private static float opacity(View view) {
        float leaving = leaving(view);
        if (leaving >= 0) return leaving;
        float alpha = 1;
        for (View at = view; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null)
            alpha *= at.getAlpha() * (Build.VERSION.SDK_INT >= 29 ? at.getTransitionAlpha() : 1);
        return alpha;
    }

    /** A real motion: part-faded frames, every moving neighbour gliding one way to its place, at rest in time. */
    private void smooth(Frames frames, boolean arriving, String what) {
        require(frames.samples.size() >= 8, what + ": only " + frames.samples.size() + " frames were drawn");
        require(frames.alphaFrames() >= 2, what + " popped: " + frames.alphaFrames() + " frames drew it part-way faded (alpha track " + alphaTrack(frames) + ")");
        for (int n = 0; n < frames.fading.size(); n++)
            require(frames.partnerFrames(n) >= 2, what + ": " + frames.fading.get(n).getClass().getSimpleName() + " (which " + (n < frames.withCount == arriving ? "arrives" : "goes")
                    + " with it) popped: " + frames.partnerFrames(n) + " frames drew it part-way faded");
        onPath(frames, what);
        int moved = 0;
        for (int n = 0; n < frames.count; n++) {
            float from = frames.samples.get(0)[3 + n], to = frames.last()[3 + n], d = to - from;
            if (Math.abs(d) <= 2) continue;
            moved++;
            // What is below it makes room downward; in a bottom sheet that grows, what is above it rises instead.
            boolean down = arriving != n < frames.aboveCount;
            require(down ? d > 0 : d < 0, what + ": " + frames.name(n) + " (" + (n < frames.aboveCount ? "above" : "below") + " it) moved the wrong way (" + from + " to " + to + "; track " + track(frames, n) + "; all " + allTracks(frames) + ")");
            int between = 0; float previous = from;
            for (float[] f : frames.samples) {
                float y = f[3 + n];
                require((y - previous) * Math.signum(d) >= -1, what + ": a neighbour turned back (" + previous + " to " + y + ")");
                require((y - to) * Math.signum(d) <= 1, what + ": a neighbour went past its place (" + y + ", resting at " + to + ")");
                if (y > Math.min(from, to) + 1 && y < Math.max(from, to) - 1) between++;
                previous = y;
            }
            require(between >= 2, what + ": the content below jumped (" + between + " frames between " + from + " and " + to + " px; track " + track(frames, n) + ")");
        }
        require(moved > 0 || frames.site.fadeOnly, what + ": nothing around it moved (it should make or give back room)");
        require(frames.settledMs() <= SETTLE_MS, what + " took " + frames.settledMs() + " ms to rest");
        notes.add(what + ": alpha " + alphaTrack(frames) + "; below " + track(frames, frames.mover()));
    }

    /**
     * Every frame shows every neighbour on its way from where it started to where it rests, within 2 dp: none jumps
     * out of that span for a frame (as a scroll clamped before the motion's first frame would make it) and glides back.
     */
    private void onPath(Frames frames, String what) {
        float slack = 2 * density();
        for (int n = 0; n < frames.count; n++) {
            float from = frames.samples.get(0)[3 + n], to = frames.last()[3 + n];
            for (float[] f : frames.samples)
                require(f[3 + n] >= Math.min(from, to) - slack && f[3 + n] <= Math.max(from, to) + slack, what + ": a frame drew "
                        + frames.name(n) + " off its way from " + from + " to " + to + " px (" + f[3 + n] + " at " + (long) f[0] + " ms; track " + track(frames, n) + ")");
        }
    }

    /**
     * Between any two frames drawn one after another, the subject's opacity changes by at most 0.15 and each neighbour
     * moves at most 8 dp per 60 Hz frame of time between them (a busy emulator that draws every 40 ms gets 2.4 frames'
     * worth): nothing snaps. At scale 10 the fastest part of the curve moves about 20 px a frame, a snap hundreds.
     */
    private void continuous(Frames frames, String what) {
        float step = 8 * density();
        for (int i = 1; i < frames.samples.size(); i++) {
            float[] a = frames.samples.get(i - 1), b = frames.samples.get(i);
            float late = Math.max(1, (b[0] - a[0]) / 16.7f);
            float alphaA = a[1] == 1 ? a[2] : 0, alphaB = b[1] == 1 ? b[2] : 0;
            require(Math.abs(alphaB - alphaA) <= .15f * late, what + ": its opacity snapped from " + alphaA + " to " + alphaB + " at " + (long) b[0]
                    + " ms (alpha track " + alphaTrack(frames) + ")");
            for (int n = 3; n < a.length; n++)
                require(Math.abs(b[n] - a[n]) <= step * late, what + ": " + frames.name(n - 3) + " snapped from " + a[n] + " to " + b[n] + " px at "
                        + (long) b[0] + " ms (track " + track(frames, n - 3) + ")");
        }
    }

    /** No motion: every frame shows the subject and each neighbour either as before or as after. */
    private void instant(Frames frames, String what) {
        float[] first = frames.samples.get(0), end = frames.last();
        for (float[] f : frames.samples) {
            require(f[1] == 0 || Math.abs(f[2] - 1) < .001f, what + ": a frame drew it part-faded (" + f[2] + ")");
            for (int n = 3; n < f.length; n++)
                require(Math.abs(f[n] - first[n]) <= .5f || Math.abs(f[n] - end[n]) <= .5f, what + ": a frame drew a neighbour between (" + f[n] + ")");
        }
    }

    /** The site at rest, shown (opaque, in place) or gone; its parent lays out; focus where it was. */
    private void requireRest(Site site, boolean shown, String when) {
        main(() -> {
            View subject = site.subject.get();
            require(subject != null, site.name + " is missing " + when);
            if (shown) {
                require(subject.getVisibility() == View.VISIBLE && subject.isShown() && subject.getHeight() > 0, site.name + " is not shown " + when);
                require(subject.getAlpha() == 1 && (Build.VERSION.SDK_INT < 29 || subject.getTransitionAlpha() == 1), site.name + " is left part-faded " + when + " (" + subject.getAlpha() + ")");
                require(subject.getTranslationX() == 0 && subject.getTranslationY() == 0, site.name + " is left shifted " + when);
                require(!subject.isFocused() && !subject.hasFocus(), site.name + " took focus " + when);
                require(subject.getImportantForAccessibility() != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, site.name + " is hidden from accessibility " + when);
            } else {
                require(subject.getVisibility() == View.GONE, site.name + " is still there " + when + " (visibility " + subject.getVisibility() + ")");
                require(leaving(subject) < 0, site.name + " is still drawn leaving " + when);
                if (Build.VERSION.SDK_INT >= 29) require(subject.getTransitionAlpha() == 1, site.name + " keeps a transition alpha " + when);
            }
            ViewGroup parent = (ViewGroup) subject.getParent();
            if (Build.VERSION.SDK_INT >= 29) require(!parent.isLayoutSuppressed(), site.name + "'s page holds its layout " + when);
            for (View after : siblingsAfter(subject)) require(after.getTranslationY() == 0 && after.getAlpha() == 1, "A view below " + site.name + " is left shifted or faded " + when);
            View focus = windowOf(site).findFocus();
            require(focus == null || !isInside(focus, subject), site.name + " holds focus " + when);
        });
    }

    private static boolean isInside(View view, View container) {
        for (View at = view; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) if (at == container) return true;
        return false;
    }

    private static String alphaTrack(Frames frames) {
        StringBuilder out = new StringBuilder();
        for (float[] f : frames.samples) if (f[1] == 1 && f[2] < .999f || out.length() > 0 && out.length() < 200) out.append(String.format(Locale.ROOT, "%.2f ", f[1] == 1 ? f[2] : 0));
        return out.toString().trim();
    }

    private static String allTracks(Frames frames) {
        StringBuilder out = new StringBuilder();
        for (int n = 0; n < frames.count; n++) out.append(frames.name(n)).append(": ").append(track(frames, n)).append(" | ");
        return out.toString();
    }

    private static String track(Frames frames, int n) {
        if (n < 0) return "(none)";
        StringBuilder out = new StringBuilder();
        float previous = Float.NaN;
        for (float[] f : frames.samples) { if (f[3 + n] != previous) out.append(Math.round(f[3 + n])).append(' '); previous = f[3 + n]; }
        return out.toString().trim();
    }

    // ===== Fixture =====

    private void scale(float value) {
        try { shell("settings put global animator_duration_scale " + (value == (int) value ? String.valueOf((int) value) : String.valueOf(value))); }
        catch (Exception error) { throw new IllegalStateException(error); }
        await(() -> ValueAnimator.getDurationScale() == value, "The animator duration scale did not reach the app (" + ValueAnimator.getDurationScale() + ", wanted " + value + ")");
        settle();
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence", "onboarding"})
            require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        require(manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled(), "Emulator Bluetooth must stay off");
    }
    private void seed() {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create(FIXTURE_NAME, FIXTURE_IDENTITY, 0);
            profile.serial = FIXTURE_SERIAL; profile.mac = FIXTURE_MAC; profile.lastConnected = System.currentTimeMillis() - 60000;
            // Battery alerts are offered offline for a saved model without a charge limit.
            if (part.equals("charge-alerts")) { profile.serial = "Q2345-SYNTHETIC"; profile.advertisedPid = "24182"; profile.advertisedPidWall = System.currentTimeMillis(); }
            profiles.update(profile);
            profiles.select(profile.id);
            profileId = profile.id;
            fixture = profile;
        });
        if (part.equals("onboarding"))
            // Setup's connect step for the fixture scooter, after one try (Bluetooth stays off: nothing connects).
            require(prefs("onboarding").edit().putInt("step", 9).putString("path", "identity").putString("profile_id", profileId)
                    .putBoolean("connect_tried", true).commit(), "Setup fixture failed");
        else prefs("onboarding").edit().putBoolean("onboarding_done", true).commit();
    }
    private void launch() {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null, "App did not launch");
        settle();
    }
    private MainActivity recreate() {
        Instrumentation.ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        MainActivity was = activity;
        main(was::recreate);
        Activity next = waitForMonitorWithTimeout(monitor, 10000);
        removeMonitor(monitor);
        require(next instanceof MainActivity && next != was, "The activity was not recreated");
        MainActivity now = (MainActivity) next;
        await(() -> now.service() != null, "The recreated activity has no service");
        return now;
    }
    private void requireHome() {
        String wanted = home.equals("clean") ? "CleanHomeScreen" : "DashboardScreen";
        await(() -> onMain(() -> HubPages.HOME.equals(field(activity, "selectedPage")) && field(activity, "screen") != null
                && field(activity, "screen").getClass().getSimpleName().equals(wanted)), "The " + home + " Home is not shown");
        settle();
    }

    /** The part's page is open and laid out: Home for find, otherwise its page, opened as a Hub link opens it. */
    private void requirePage() {
        if (part.equals("find")) { requireHome(); return; }
        if (part.equals("onboarding")) {
            await(() -> onMain(() -> field(activity, "onboarding") != null && root().isLaidOut()), "Setup is not shown");
            settle();
            if (site.reopen != null) { site.reopen.run(); settle(); }
            return;
        }
        String page = page();
        if (!onMain(() -> page.equals(field(activity, "selectedPage")))) main(() -> activity.open(page));
        await(() -> onMain(() -> page.equals(field(activity, "selectedPage")) && field(activity, "screen") != null
                && !field(activity, "screen").getClass().getSimpleName().equals("LoadingScreen")), "The page " + page + " is not shown");
        settle();
        if (site.reopen != null) { site.reopen.run(); settle(); }
        main(() -> {
            View subject = site.subject.get();
            require(subject != null && subject.getParent() instanceof View && ((View) subject.getParent()).isLaidOut(), "The page " + page + " is not laid out around its subject");
        });
    }

    // ===== Plumbing =====

    private View root() { return activity.getWindow().getDecorView(); }
    /** The decor of the window the site is drawn in. */
    private View windowOf(Site site) {
        View inWindow = site.window == null ? null : site.window.get();
        return inWindow != null ? inWindow.getRootView() : root();
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void settleFor(long ms) { SystemClock.sleep(ms); waitForIdleSync(); }
    /** Waits until this many more frames have been drawn. */
    private void frames(int count) {
        java.util.concurrent.CountDownLatch drawn = new java.util.concurrent.CountDownLatch(count);
        main(() -> {
            Choreographer.FrameCallback[] tick = new Choreographer.FrameCallback[1];
            tick[0] = t -> { drawn.countDown(); if (drawn.getCount() > 0) Choreographer.getInstance().postFrameCallback(tick[0]); };
            Choreographer.getInstance().postFrameCallback(tick[0]);
        });
        try { drawn.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
    }
    private String shell(String command) throws Exception {
        StringBuilder out = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))))) {
            for (String line; (line = reader.readLine()) != null; ) out.append(line).append('\n');
        }
        return out.toString();
    }
    private void main(Runnable task) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { task.run(); return; }
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new IllegalStateException(error.get());
    }
    private <T> T onMain(Supplier<T> task) {
        AtomicReference<T> value = new AtomicReference<>();
        main(() -> value.set(task.get()));
        return value.get();
    }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(20);
        require(condition.getAsBoolean(), message);
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private static Field find(Object target, String name) throws NoSuchFieldException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException next) { /* the superclass */ }
        }
        throw new NoSuchFieldException(name);
    }
    private Object field(Object target, String name) {
        try { return find(target, name).get(target); } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private void set(Object target, String name, Object value) {
        try { find(target, name).set(target, value); } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
