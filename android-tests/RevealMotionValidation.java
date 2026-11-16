package com.jjricks.volt.tests;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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
 *   than 0.15 or moves a neighbour more than 8 dp from the frame before (no jump to where the first motion was
 *   going).</li>
 *   <li><b>Leaving at the end of a scrolled page</b> that still scrolls without it (the text made larger until it
 *   does): the scroll clamps, and no frame draws the views below off their way from start to rest.</li>
 * </ul>
 *
 * In every motion, every frame draws each neighbour within 2 dp of the span from where it started to where it rests.
 * -e only arrives|pulse|reversals|recreated|off|instant|turns|scrolled runs one check.
 *
 * Parts (-e part): {@code find} (Home's Find it nearby card, FindNearby entry A; -e home widgets|clean). The card is
 * offered the app's own way: BleController's timeout tally for the saved address reaches two (FindNearby.Timeouts, as
 * two connect timeouts leave it) while a connection is wanted; it goes when the tally starts again (as a connection
 * does). Nothing is sent and Bluetooth stays off. The connection parts run the simulator (memory only, never a
 * command): its dropout script leaves the link Connecting, as a scooter that stops answering does, and the default
 * script brings it back. {@code controls}: Controls › Limits' connection banner. {@code rides}: Ride › Rides'
 * Connect to record. {@code scooters}: Settings › My scooters, the card's "Last connected" line. {@code night}: Ride › Riding mode's Night vision rows, revealed by tapping Scheduled (sunset to
 * sunrise, the time zone's city) and hidden by tapping Off. -e mode demo toggles the site every -e hold_ms (1500) for -e
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
    private Site site;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String mode = arguments.getString("mode", "all");
            part = arguments.getString("part", "find");
            home = arguments.getString("home", "widgets");
            require(mode.equals("all") || mode.equals("demo"), "Unknown -e mode " + mode + " (all or demo)");
            require(java.util.Arrays.asList("find", "controls", "rides", "night", "scooters").contains(part), "Unknown -e part " + part + " (find, controls, rides, night or scooters)");
            require(home.equals("widgets") || home.equals("clean"), "Unknown -e home " + home + " (widgets or clean)");
            admit();
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("auto_record_rides", false).putBoolean("riding_auto_open", false)
                    .putString("theme", arguments.getString("theme", "dark")).putString("units", "mi")
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1")))
                    .putString("home_style", home).putBoolean("home_style_chosen", true).putString("home_style_origin", "install")
                    .commit(), "Fixture settings failed");
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
                    String[] names = {"arrives", "pulse", "reversals", "recreated", "off", "instant", "turns", "scrolled"};
                    require(only.isEmpty() || java.util.Arrays.asList(names).contains(only), "Unknown -e only " + only);
                    if (only.isEmpty() || only.equals("arrives")) arrivesAndGoes(site);
                    if (only.isEmpty() || only.equals("pulse")) pulse(site);
                    if (only.isEmpty() || only.equals("reversals")) reversals(site);
                    if (only.isEmpty() || only.equals("recreated")) recreated(site);
                    if (only.isEmpty() || only.equals("off")) animationsOff(site);
                    if (only.isEmpty() || only.equals("instant")) instantWins(site);
                    if (only.isEmpty() || only.equals("turns")) turnsBack(site);
                    if (only.isEmpty() || only.equals("scrolled")) scrolledEnd(site);
                }
            } finally {
                shell(scaleBefore.isEmpty() || scaleBefore.equals("null") ? "settings delete global animator_duration_scale" : "settings put global animator_duration_scale " + scaleBefore);
            }
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
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
        Site(String name, Supplier<View> subject, Supplier<List<View>> neighbours, Runnable show, Runnable hide) {
            this.name = name; this.subject = subject; this.neighbours = neighbours; this.show = show; this.hide = hide;
        }
    }

    private Site site() {
        switch (part) {
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
     * A busy machine can drop a whole motion's frames (a pool emulator drew one part-faded frame between 0.07 and 1).
     * When a frame of the failed measurement came more than 100 ms late, it is measured once more.
     */
    private void arrivesAndGoes(Site site) {
        hitchMs = 0;
        try { arrivesAndGoesOnce(site); }
        catch (IllegalStateException error) {
            if (hitchMs <= 100) throw error;
            notes.add(site.name + ": measured again after a frame came " + hitchMs + " ms late: " + error.getMessage());
            site.hide.run(); settleFor(SETTLE_MS);
            arrivesAndGoesOnce(site);
        }
    }

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
                require(Math.abs(frame[3 + n] - frames.samples.get(0)[3 + n]) <= .5f, site.name + ": a neighbour moved during live refreshes");
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
            in.start(); frames(2);
            site.show.run(); SystemClock.sleep(500); site.hide.run();
            SystemClock.sleep(4000);
            in.stop();
            requireRest(site, false, "hidden mid-arrival at scale 10");
            continuous(in, site.name + " hidden mid-arrival (scale 10)");
            float peak = 0; for (float[] f : in.samples) if (f[1] == 1) peak = Math.max(peak, f[2]);
            require(peak > .1f && peak < .9f, site.name + ": the reversal mid-arrival was not mid-way (opacity peaked at " + peak + ")");
            site.show.run(); settleFor(4000);
            Frames out = new Frames(site);
            out.start(); frames(2);
            site.hide.run(); SystemClock.sleep(300); site.show.run();
            SystemClock.sleep(4000);
            out.stop();
            requireRest(site, true, "shown mid-departure at scale 10");
            continuous(out, site.name + " shown mid-departure (scale 10)");
            float low = 1; for (float[] f : out.samples) low = Math.min(low, f[1] == 1 ? f[2] : 0);
            require(low > .1f && low < .9f, site.name + ": the reversal mid-departure was not mid-way (opacity fell to " + low + ")");
            checks.add(site.name + ": at scale 10, hidden mid-arrival (opacity " + String.format(Locale.ROOT, "%.2f", peak) + ") and shown mid-departure (" + String.format(Locale.ROOT, "%.2f", low)
                    + "), it turns back from where it is: " + in.samples.size() + " and " + out.samples.size() + " frames, none changing opacity by more than 0.15 or moving a neighbour more than 8 dp from the frame before");
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
        SystemClock.sleep(hold);
        for (int i = 0; i < cycles; i++) {
            site.show.run(); SystemClock.sleep(hold);
            site.hide.run(); SystemClock.sleep(hold);
        }
        checks.add(site.name + " shown and hidden " + cycles + " times for a recording");
    }

    // ===== Frames =====

    /** Every drawn frame: [ms since start, shown 0/1, opacity (alpha x transition alpha), each neighbour's top in the window px]. */
    private final class Frames {
        final List<float[]> samples = new ArrayList<>();
        final Site site;
        List<View> watched;
        int count;
        private boolean run;
        private long started;
        private android.view.ViewTreeObserver.OnDrawListener tick;
        private android.view.ViewTreeObserver observer;
        Frames(Site site) { this.site = site; }
        /** Samples each frame as it draws (what reaches the screen, after layout, pre-draw and animation). */
        void start() {
            main(() -> {
                watched = site.neighbours.get();
                count = watched.size();
                started = SystemClock.uptimeMillis();
                run = true;
                tick = () -> { if (run) sample(); };
                observer = root().getViewTreeObserver();
                observer.addOnDrawListener(tick);
                sample();
            });
        }
        private void sample() {
            View subject = site.subject.get();
            float[] frame = new float[3 + count];
            frame[0] = SystemClock.uptimeMillis() - started;
            boolean shown = subject != null && subject.isShown() && subject.getHeight() > 0;
            frame[1] = shown ? 1 : 0;
            frame[2] = subject == null ? 0 : subject.getAlpha() * (Build.VERSION.SDK_INT >= 29 ? subject.getTransitionAlpha() : 1);
            // The window position includes every translation, the view's own and the scroll content's.
            for (int n = 0; n < count; n++) { int[] at = new int[2]; watched.get(n).getLocationInWindow(at); frame[3 + n] = at[1]; }
            samples.add(frame);
        }
        void stop() { main(() -> { run = false; if (observer.isAlive()) observer.removeOnDrawListener(tick); sample(); }); }
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
        change.run();
        SystemClock.sleep(SETTLE_MS + 300);
        frames.stop();
        return frames;
    }

    /** A real motion: part-faded frames, every moving neighbour gliding one way to its place, at rest in time. */
    private void smooth(Frames frames, boolean arriving, String what) {
        // Frames are drawn only when something changes: count only the gaps while it was still moving.
        for (int i = 1; i < frames.samples.size(); i++)
            if (!frames.same(frames.samples.get(i - 1), frames.last()) && !frames.same(frames.samples.get(i - 1), frames.samples.get(0)))
                hitchMs = Math.max(hitchMs, (long) (frames.samples.get(i)[0] - frames.samples.get(i - 1)[0]));
        require(frames.samples.size() >= 8, what + ": only " + frames.samples.size() + " frames were drawn");
        require(frames.alphaFrames() >= 2, what + " popped: " + frames.alphaFrames() + " frames drew it part-way faded (alpha track " + alphaTrack(frames) + ")");
        onPath(frames, what);
        int moved = 0;
        for (int n = 0; n < frames.count; n++) {
            float from = frames.samples.get(0)[3 + n], to = frames.last()[3 + n], d = to - from;
            if (Math.abs(d) <= 2) continue;
            moved++;
            require(arriving ? d > 0 : d < 0, what + ": a neighbour moved the wrong way (" + from + " to " + to + ")");
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
        require(moved > 0, what + ": nothing below it moved (it should make or give back room)");
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
     * moves at most 8 dp (allowing more only for a frame that came more than 100 ms late): nothing snaps.
     */
    private void continuous(Frames frames, String what) {
        float step = 8 * density();
        for (int i = 1; i < frames.samples.size(); i++) {
            float[] a = frames.samples.get(i - 1), b = frames.samples.get(i);
            float late = Math.max(1, (b[0] - a[0]) / 100f);
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
                if (Build.VERSION.SDK_INT >= 29) require(subject.getTransitionAlpha() == 1, site.name + " keeps a transition alpha " + when);
            }
            ViewGroup parent = (ViewGroup) subject.getParent();
            if (Build.VERSION.SDK_INT >= 29) require(!parent.isLayoutSuppressed(), site.name + "'s page holds its layout " + when);
            for (View after : siblingsAfter(subject)) require(after.getTranslationY() == 0 && after.getAlpha() == 1, "A view below " + site.name + " is left shifted or faded " + when);
            require(root().findFocus() == null || !isInside(root().findFocus(), subject), site.name + " holds focus " + when);
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
            profile.serial = FIXTURE_SERIAL; profile.mac = FIXTURE_MAC; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile);
            profiles.select(profile.id);
            profileId = profile.id;
        });
        prefs("onboarding").edit().putBoolean("onboarding_done", true).commit();
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
        String page = page();
        if (!onMain(() -> page.equals(field(activity, "selectedPage")))) main(() -> activity.open(page));
        await(() -> onMain(() -> page.equals(field(activity, "selectedPage")) && field(activity, "screen") != null
                && !field(activity, "screen").getClass().getSimpleName().equals("LoadingScreen")), "The page " + page + " is not shown");
        settle();
        main(() -> {
            View subject = site.subject.get();
            require(subject != null && subject.getParent() instanceof View && ((View) subject.getParent()).isLaidOut(), "The page " + page + " is not laid out around its subject");
        });
    }

    // ===== Plumbing =====

    private View root() { return activity.getWindow().getDecorView(); }
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
