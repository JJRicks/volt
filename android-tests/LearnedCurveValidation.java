package com.jjricks.volt.tests;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleUnaryOperator;
import com.jjricks.volt.ChargeSessionStore;
import com.jjricks.volt.HubPages;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;

/**
 * Disposable emulator only; no scooter, no radio, nothing sent. Charge history &amp; recordings' "What Volt has learned"
 * (owner's 2.0.1 item 13): a card per learned charger, drawn from synthesized charge recordings written into the app's
 * own charge folder, one state at a time:
 * <ul>
 *   <li>{@code empty}: nothing recorded: one card, the empty state, which points at recording a charge;</li>
 *   <li>{@code partial}: one stock recording 17–50%: learned from 18%, no data below or above;</li>
 *   <li>{@code joined}: stock 17–50% and 45–100% (to full): learned 18–100%, both recordings agree 46–50%, a finish tick;</li>
 *   <li>{@code two}: those and two high-speed recordings (20–100%, 30–100%): two cards, stock first;</li>
 *   <li>{@code mixed}: stock recordings that leave an inside gap and a stretch they disagree on: every legend entry.</li>
 * </ul>
 * Each card: its tag, words whole (no cut text, no mid-word break), the chart measured, inside the screen, and one
 * TalkBack description that names the learned span and its gaps. Screenshots (-e size_label, -e theme, -e text_scale;
 * -e states a comma list). An app without the
 * section (no more_learned_label string: built before its strings) gets the same screenshot names of the page and passes.
 */
public final class LearnedCurveValidation extends ValidationInstrumentation {
    private static final long WALL = System.currentTimeMillis() - 7 * 86_400_000L, MONO = 1_000_000L;
    private MainActivity activity;
    private Bundle args;
    private int assertions;
    private String label, profile;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            String theme = args.getString("theme", "light");
            label = args.getString("size_label", getTargetContext().getResources().getConfiguration().screenWidthDp + "") + "-" + theme
                    + ("1".equals(args.getString("text_scale", "1")) ? "" : "-text" + args.getString("text_scale"));
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putString("theme", theme).putFloat("text_scale", Float.parseFloat(args.getString("text_scale", "1"))).commit(), "Could not seed preferences");
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic learned curve scooter", "000000000000", 0);
                p.serial = "X2538-SYNTHETIC"; profiles.update(p); profiles.select(p.id); profile = p.id;
                OnboardingScreen.markDone(getTargetContext());
            });
            launch();
            boolean built = built();
            for (String state : args.getString("states", "empty,partial,joined,two,mixed").split(",")) {
                seed(state.trim());
                reload();
                page();
                if (built) state(state.trim());
                else checks.add("This app has no What Volt has learned section yet (its strings ship in their own pull request); " + state + " captured without it");
                shotSection("learned-" + state.trim() + "-" + label);
            }
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            failure.printStackTrace();
            if (activity != null) try { screenshot("failure-" + label); } catch (Throwable ignored) {}
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> activity.finish()); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ===== The states =====

    /** The section's cards for a state, and their words. */
    private void state(String state) {
        String[] expected;
        switch (state) {
            case "empty": expected = new String[]{"empty"}; break;
            case "two": expected = new String[]{"stock", "high_speed"}; break;
            default: expected = new String[]{"stock"};
        }
        main(() -> {
            View section = HubTestPages.shownTag(activity, tag("SECTION_TAG"));
            ViewGroup cards = (ViewGroup) section;
            List<String> tags = new ArrayList<>();
            for (int i = 0; i < cards.getChildCount(); i++) tags.add(String.valueOf(cards.getChildAt(i).getTag()));
            List<String> want = new ArrayList<>();
            for (String key : expected) want.add(tag("TAG") + ":" + key);
            require(tags.equals(want), state + ": cards " + tags + ", expected " + want);
            textProblems(section);
            int screen = root().getWidth();
            for (int i = 0; i < cards.getChildCount(); i++) {
                View card = cards.getChildAt(i);
                CharSequence spoken = card.getContentDescription();
                require(spoken != null && spoken.length() > 0 && card.isFocusable(), state + ": a card without one TalkBack description");
                int[] at = new int[2]; card.getLocationOnScreen(at);
                require(at[0] >= 0 && at[0] + card.getWidth() <= screen, state + ": a card runs off the screen");
                View plot = card.findViewWithTag("learned-curve-plot");
                require(plot != null && plot.getHeight() > 0 && plot.getWidth() > 0, state + ": the chart is not measured");
                View legend = card.findViewWithTag("learned-curve-legend");
                String words = spoken.toString();
                switch (state) {
                    case "empty":
                        require(legend.getVisibility() != View.VISIBLE, "Empty: a legend with nothing learned");
                        require(words.contains("Record"), "Empty: the empty state does not point at recording a charge: " + words);
                        break;
                    case "partial":
                        require(words.contains("18") && words.contains("50") && words.contains("below") && words.contains("above"), "Partial: the gaps in words: " + words);
                        require(card.findViewWithTag("learned-curve-key-provisional") != null && card.findViewWithTag("learned-curve-key-robust") == null, "Partial: legend");
                        break;
                    case "joined":
                        if (i == 0) {
                            require(words.contains("18") && words.contains("100") && words.contains("full") && !words.contains("above"), "Joined: learned to full: " + words);
                            require(card.findViewWithTag("learned-curve-key-robust") != null && card.findViewWithTag("learned-curve-key-finish") != null, "Joined: agreeing and finish in the legend");
                        }
                        break;
                    case "mixed":
                        require(card.findViewWithTag("learned-curve-key-disagreeing") != null && card.findViewWithTag("learned-curve-key-none") != null, "Mixed: every kind in the legend");
                        require(words.contains("disagree"), "Mixed: the disagreeing stretch in words: " + words);
                        break;
                    default: break;
                }
            }
        });
        checks.add(state + ": " + String.join(" + ", expected) + " card(s), words whole, chart measured, inside the screen, one TalkBack description");
    }

    /** The synthesized recordings for a state, in the app's own charge folder (a disposable emulator's fresh install). */
    private void seed(String state) throws Exception {
        File folder = new File(getTargetContext().getFilesDir(), "charge-recordings");
        File[] old = folder.listFiles();
        if (old != null) for (File file : old) require(file.delete(), "Could not clear " + file.getName());
        ChargeSessionStore store = new ChargeSessionStore(folder);
        int day = 0;
        switch (state) {
            case "empty": break;
            case "partial": record(store, ChargeSessionStore.Charger.STOCK, STOCK, 17, 50, 1, day++); break;
            case "two":
                record(store, ChargeSessionStore.Charger.HIGH_SPEED, FAST, 20, 100, 1, day++);
                record(store, ChargeSessionStore.Charger.HIGH_SPEED, FAST, 30, 100, 1.05, day++);
                // and the joined stock pair:
            case "joined":
                record(store, ChargeSessionStore.Charger.STOCK, STOCK, 17, 50, 1, day++);
                record(store, ChargeSessionStore.Charger.STOCK, STOCK, 45, 100, 1, day++);
                break;
            case "mixed":
                record(store, ChargeSessionStore.Charger.STOCK, STOCK, 10, 30, 1, day++);
                record(store, ChargeSessionStore.Charger.STOCK, STOCK, 34, 60, 1, day++);
                record(store, ChargeSessionStore.Charger.STOCK, STOCK, 50, 70, 3, day++);
                record(store, ChargeSessionStore.Charger.STOCK, STOCK, 64, 100, 1, day++);
                break;
            default: throw new IllegalArgumentException("Unknown state " + state);
        }
    }

    /** A charger model: amps and pack volts by battery level, and the pack's rated Ah (as the JVM tests' models). */
    private static final class Model {
        final double ratedAh; final DoubleUnaryOperator amps, volts;
        Model(double ratedAh, DoubleUnaryOperator amps, DoubleUnaryOperator volts) { this.ratedAh = ratedAh; this.amps = amps; this.volts = volts; }
        double minutes(int level) { return ratedAh * .01 / amps.applyAsDouble(level) * 60; }
    }
    /** About 100 W in bulk, then a long taper from 85%. */
    private static final Model STOCK = new Model(10, soc -> soc < 85 ? 2.4 : 2.4 * Math.max(.18, 1 - (soc - 85) / 18), soc -> 38 + 4 * Math.min(soc, 90) / 100);
    /** About 450 W in bulk, then a taper from 80%. */
    private static final Model FAST = new Model(20, soc -> soc < 80 ? 10.7 : 10.7 * Math.max(.12, 1 - (soc - 80) / 22), soc -> 38.5 + 4.5 * Math.min(soc, 85) / 100);

    /** One recording from start to end, each percent slower times the model's minutes, a sample every ten seconds; to 100%: complete. */
    private void record(ChargeSessionStore store, ChargeSessionStore.Charger charger, Model model, int start, int end, double slower, int day) throws Exception {
        long wall = WALL + day * 86_400_000L;
        store.start(profile, charger, wall, MONO);
        long t = 0;
        for (int level = start; level < end; level++) {
            long span = (long) (model.minutes(level) * slower * 60000);
            for (long at = 0; at < span; at += 10000, t += 10000) sample(store, model, wall, t, level, false);
        }
        boolean complete = end == 100;
        if (complete) sample(store, model, wall, t, 100, true);
        else for (int i = 0; i < 3; i++, t += 10000) sample(store, model, wall, t, end, false);
        store.stop(complete ? ChargeSessionStore.Stop.COMPLETE : ChargeSessionStore.Stop.STOPPED);
    }
    private void sample(ChargeSessionStore store, Model model, long wall, long t, int level, boolean completion) throws Exception {
        long mono = MONO + t; double amps = model.amps.applyAsDouble(Math.min(level, 99)), volts = model.volts.applyAsDouble(level);
        ChargeSessionStore.Frame frame = new ChargeSessionStore.Frame(wall + t, mono, value(completion ? 0 : 1, mono), value(level, mono), value(volts, mono), value(-amps, mono), value(23, mono));
        require(completion ? store.sampleCompletion(frame) : store.sample(frame), "Synthesized sample not saved at " + level + "%");
        assertions--; // one per sample would drown the count
    }
    private static ChargeSessionStore.Value value(double v, long mono) { return new ChargeSessionStore.Value(v, mono, 0x72); }

    /** The service reads the folder again (its refreshChargeCurves), as after a recording is saved. */
    private void reload() throws Exception {
        Object service = activity.service();
        await(() -> !Boolean.TRUE.equals(field(service, "chargeModelLoading")), 30000, "The charge model did not finish loading");
        main(() -> {
            try { Method refresh = service.getClass().getDeclaredMethod("refreshChargeCurves"); refresh.setAccessible(true); refresh.invoke(service); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("refreshChargeCurves", e); }
        });
        await(() -> !Boolean.TRUE.equals(field(service, "chargeModelLoading")), 30000, "The charge model did not reload");
        settle();
    }

    // ===== Helpers =====

    /** The page, opened again so it reads the reloaded curves; the section scrolled to the top of the screen. */
    private void page() {
        main(() -> HubTestPages.open(activity, HubPages.HOME)); settle();
        HubTestPages.reach(this, activity, HubPages.BATTERY_HISTORY); settle();
        SystemClock.sleep(400); settle();
    }
    private void shotSection(String name) throws Exception {
        main(() -> {
            Object screen = field(activity, "screen");
            View page = screen instanceof com.jjricks.volt.AppScreen ? ((com.jjricks.volt.AppScreen) screen).view() : null;
            View section = page == null ? null : page.findViewWithTag(tagOrNull("SECTION_TAG"));
            if (section == null) return;
            ScrollView parent = scroll(section); if (parent == null) return;
            Rect r = new Rect(); section.getDrawingRect(r); parent.offsetDescendantRectToMyCoords(section, r);
            parent.scrollTo(0, Math.max(0, r.top - dp(56)));
        });
        settle(); SystemClock.sleep(900); waitForIdleSync();
        screenshot(name);
    }
    private String tag(String name) { String tag = tagOrNull(name); require(tag != null, "LearnedCurveCard." + name + " missing"); return tag; }
    private String tagOrNull(String name) {
        try {
            Class<?> card = Class.forName("com.jjricks.volt.LearnedCurveCard", true, getTargetContext().getClassLoader());
            Field field = card.getDeclaredField(name); field.setAccessible(true); return (String) field.get(null);
        } catch (ReflectiveOperationException e) { return null; }
    }
    private boolean built() {
        Context app = getTargetContext();
        return app.getResources().getIdentifier("more_learned_label", "string", app.getPackageName()) != 0;
    }
    private void launch() {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root() != null && root().getWidth() > 0, 10000, "App did not launch");
    }
    private int dp(int value) { return Math.round(value * getTargetContext().getResources().getDisplayMetrics().density); }
    private ScrollView scroll(View view) {
        View parent = view; while (parent != null && !(parent instanceof ScrollView)) parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        return (ScrollView) parent;
    }
    /** Every visible text under view: not ellipsized, and each line break falls between words. */
    private void textProblems(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view; Layout layout = text.getLayout(); CharSequence value = text.getText();
            if (layout != null && value.length() > 0) for (int line = 0; line < layout.getLineCount(); line++) {
                require(layout.getEllipsisCount(line) == 0, "Cut text: " + value);
                int end = layout.getLineEnd(line);
                if (line < layout.getLineCount() - 1 && end > 0 && end < value.length())
                    require(!(Character.isLetterOrDigit(value.charAt(end - 1)) && Character.isLetterOrDigit(value.charAt(end))), "Mid-word break in: " + value);
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) textProblems(((ViewGroup) view).getChildAt(i));
    }
    private void admit() {
        String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hw.contains("ranchu") || hw.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical phone");
        for (String name : new String[]{"app_settings", "profiles"}) require(prefs(name).getAll().isEmpty(), "Refusing existing preferences");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant emulator permissions first: " + permission);
    }
    private void screenshot(String name) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "learned-curve-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory missing");
        File file = new File(dir, name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View) field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(160); waitForIdleSync(); }
    private void await(BooleanSupplier condition, long ms, String message) {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) { AtomicReference<Boolean> ready = new AtomicReference<>(); main(() -> ready.set(condition.getAsBoolean())); if (Boolean.TRUE.equals(ready.get())) return; SystemClock.sleep(60); }
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
}
