package com.jjricks.volt.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.LocaleManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;

/**
 * MOCK-UP RENDERS ONLY (branch claude/new-key-mockups, never a sweep step): the "Make a new owner key" route's
 * wording and gate variants, for the owner to pick from. Disposable emulator only; drives setup's own screens from
 * the route list (as NewOwnerKeyValidation does) and never presses the new-key page's Continue, so no key is made.
 * Per language (en, de) and variant (current, A, B, C): the route list with the new route chosen, the new-key page
 * (B and C unticked, then ticked), and C's link and where it leads. Writes PNGs and report.tsv to
 * files/new-key-mockups/.   -e label <size label>
 */
public final class NewKeyMockupRender extends ValidationInstrumentation {
    private static final int PATH = 1, PREPARE = 2, IDENTITY = 7;
    private static final String NEW_ROUTE = "onboarding-path-new_key", LINK = "onboarding-new-copy-instead";
    private static final String[] VARIANTS = {"current", "A", "B", "C"}, LANGS = {"en", "de"};
    private MainActivity activity;
    private Instrumentation.ActivityMonitor bluetoothPrompt;
    private String label = "phone", lang = "en", variant = "current";
    private final StringBuilder report = new StringBuilder("file\tvariant\tscreen\tstate\tlang\tnotes\n");
    private int shots;

    @Override public void onCreate(Bundle args) {
        if (args != null) label = args.getString("label", "phone");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
            require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Physical device refused");
            bluetoothPrompt = addMonitor(new IntentFilter(BluetoothAdapter.ACTION_REQUEST_ENABLE), new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
            for (String l : LANGS) {
                lang = l;
                setLocale(l);
                for (String v : VARIANTS) { variant = v; renderVariant(); }
            }
            setLocale("");
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("screenshots", shots);
            try { write("report-" + label + ".tsv", report.toString()); } catch (Exception ignored) { }
            if (bluetoothPrompt != null) removeMonitor(bluetoothPrompt);
            if (activity != null) try { main(() -> activity.finish()); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void renderVariant() throws Exception {
        if (activity != null) { MainActivity old = activity; main(old::finish); await(old::isDestroyed, "old activity did not close"); }
        main(() -> {
            prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putString("theme", "light").putFloat("text_scale", 1f).commit();
            prefs("onboarding").edit().clear().putInt("step", PATH)
                    .putString("mockup_variant", variant.equals("current") ? "" : variant).commit();
        });
        activity = (MainActivity) startActivitySync(new Intent(Intent.ACTION_MAIN)
                .setClass(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().hasWindowFocus() && setup() != null && step() == PATH && tag(NEW_ROUTE) != null,
                "Setup did not open on the route list");
        main(() -> tag(NEW_ROUTE).performClick());
        waitForIdleSync();
        await(() -> tag(NEW_ROUTE) != null && tag(NEW_ROUTE).isShown(), "route list after choosing");
        main(() -> reveal(tag(NEW_ROUTE)));
        // B and C share A's route list; their lists are not rendered again.
        if (variant.equals("current") || variant.equals("A")) shot("choices", "new-key-selected", detailNote());
        clickText(continueLabel());
        await(() -> step() == IDENTITY && first(root(), android.widget.EditText.class) != null, "Continue did not open the new-key page");
        main(() -> scrollTop());
        boolean gated = variant.equals("B") || variant.equals("C");
        String state = gated ? "unticked" : "default";
        shot("page", state, pageNote());
        if (gated) {
            main(() -> require(!continueButton().isEnabled(), "Continue is enabled before the tick"));
            main(() -> tickCard().performClick());
            waitForIdleSync();
            main(() -> require(continueButton().isEnabled(), "Continue stayed disabled after the tick"));
            main(() -> scrollTop());
            shot("page", "ticked", pageNote());
        } else main(() -> require(continueButton().isEnabled(), "Continue is disabled on an ungated variant"));
        if (variant.equals("C")) {
            main(() -> reveal(tag(LINK)));
            shot("link", "visible", linkNote());
            main(() -> tag(LINK).performClick());
            await(() -> step() == PREPARE, "The link did not open the recommended route's first page");
            main(() -> require("webadb".equals(prefs("onboarding").getString("path", "")), "The link did not choose the recommended route"));
            main(() -> scrollTop());
            shot("link-destination", "prepare", "path=webadb; Back goes to the route list");
        }
    }

    private String detailNote() {
        TextView detail = text(tag(NEW_ROUTE), variantDetail());
        return detail == null ? "detail not found" : "detail lines=" + detail.getLineCount();
    }

    private String variantDetail() {
        return getString(variant.equals("current") ? "onboarding_path_new_key_detail" : "onboarding_mock_path_new_key_detail");
    }

    private String pageNote() {
        TextView heading = tickHeading();
        View cont = continueButton();
        return "tick lines=" + heading.getLineCount() + "; continue enabled=" + cont.isEnabled();
    }

    private String linkNote() {
        TextView link = (TextView) tag(LINK);
        String full = getString("onboarding_mock_new_copy_instead");
        return "rung=" + (link.getText().toString().equals(full) ? "full" : "short") + "; lines=" + link.getLineCount()
                + "; text=" + link.getText();
    }

    private TextView tickHeading() {
        String heading = getString(variant.equals("current") ? "onboarding_new_unowned" : "onboarding_mock_new_unowned");
        TextView found = text(root(), heading);
        require(found != null, "tick heading missing: " + heading);
        return found;
    }

    private View tickCard() { return (View) tickHeading().getParent().getParent(); }

    private String continueLabel() { return getString("onboarding_continue"); }

    private TextView continueButton() {
        TextView found = text(root(), continueLabel());
        require(found != null, "Continue missing");
        return found;
    }

    private String getString(String name) {
        int id = activity.getResources().getIdentifier(name, "string", activity.getPackageName());
        require(id != 0, "no string " + name);
        return activity.getString(id);
    }

    /** Scrolls the page so view is fully on screen, with a little room below it. */
    private void reveal(View view) {
        require(view != null, "nothing to reveal");
        ScrollView scroll = scrollOf(view);
        if (scroll == null) return;
        int top = 0; View v = view;
        while (v != scroll) { top += v.getTop(); v = (View) v.getParent(); }
        int bottom = top + view.getHeight(), pad = (int) (24 * view.getResources().getDisplayMetrics().density);
        if (bottom + pad > scroll.getScrollY() + scroll.getHeight()) scroll.scrollTo(0, bottom + pad - scroll.getHeight());
        if (top < scroll.getScrollY()) scroll.scrollTo(0, Math.max(0, top - pad));
    }

    private void scrollTop() { scrollAllTop(root()); }

    private static void scrollAllTop(View view) {
        if (view instanceof ScrollView) view.scrollTo(0, 0);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) scrollAllTop(((ViewGroup) view).getChildAt(i));
    }

    private static ScrollView scrollOf(View view) {
        ViewParent p = view.getParent();
        while (p != null && !(p instanceof ScrollView)) p = p.getParent();
        return (ScrollView) p;
    }

    private void shot(String screen, String state, String notes) throws Exception {
        SystemClock.sleep(700); waitForIdleSync();
        AtomicReference<Bitmap> image = new AtomicReference<>();
        main(() -> {
            View window = root();
            Bitmap drawn = Bitmap.createBitmap(window.getWidth(), window.getHeight(), Bitmap.Config.ARGB_8888);
            window.draw(new Canvas(drawn)); image.set(drawn);
        });
        String name = variant + "-" + screen + "-" + state + "-" + label + "-" + lang + ".png";
        File file = new File(dir(), name);
        try (FileOutputStream out = new FileOutputStream(file)) { image.get().compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { image.get().recycle(); }
        report.append(name).append('\t').append(variant).append('\t').append(screen).append('\t').append(state).append('\t')
                .append(lang).append('\t').append(notes).append('\n');
        shots++;
    }

    private File dir() {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "new-key-mockups");
        require(dir.mkdirs() || dir.isDirectory(), "Screenshot directory");
        return dir;
    }

    private void write(String name, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(dir(), name))) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    private void setLocale(String tag) {
        LocaleManager manager = getTargetContext().getSystemService(LocaleManager.class);
        manager.setApplicationLocales(tag.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag));
        SystemClock.sleep(800); waitForIdleSync();
    }

    private OnboardingScreen setup() { return (OnboardingScreen) field(activity, "onboarding"); }
    private int step() { OnboardingScreen s = setup(); return s == null ? -1 : (Integer) field(s, "step"); }
    private View root() { return activity.getWindow().getDecorView(); }
    private View tag(String tag) { return root().findViewWithTag(tag); }
    private void clickText(String value) {
        main(() -> { TextView view = text(root(), value); require(view != null && view.isShown() && view.isEnabled(), "Missing or disabled: " + value); view.performClick(); });
        waitForIdleSync();
    }
    private static TextView text(View root, String text) {
        if (root instanceof TextView && ((TextView) root).getText().toString().equals(text)) return (TextView) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { TextView found = text(((ViewGroup) root).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private static <T extends View> T first(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { T found = first(((ViewGroup) root).getChildAt(i), type); if (found != null) return found; }
        return null;
    }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void require(boolean good, String message) { if (!good) throw new AssertionError(message); }
    private interface Action { void run() throws Exception; }
    private void main(Action action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long until = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < until) {
            AtomicReference<Boolean> ready = new AtomicReference<>();
            main(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(80);
        }
        throw new AssertionError(message);
    }
    private static Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
