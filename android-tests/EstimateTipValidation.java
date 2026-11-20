package com.jjricks.volt;

import android.Manifest;
import android.app.Dialog;
import com.jjricks.volt.tests.ValidationInstrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.Layout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Disposable empty emulator, radio off. Home's estimate tip (ChargeEstimateTip; owner, Nov 19 2026: a subtle cue by the
 * charge time to record charges, shown while there are none yet) on the developer simulator's charging UT5 MAX, with no
 * charge recordings saved:
 * <ul>
 *   <li>the tip shows under the charging view's time tile: one line, its words whole (the full or the short rung, or
 *   the icon alone), named "Improve this estimate", 48 dp to touch, its start at the time tile's;</li>
 *   <li>a real tap opens its sheet: "Record your charges", the invite's intro and four points, Record this charge, the
 *   auto-record switch, Charge history &amp; recordings, Not now and Hide this tip; on the simulator Record this charge
 *   starts nothing and the sheet stays (a recording can't start, and the reason shows); Not now closes it, the tip stays;</li>
 *   <li>Hide this tip stores the answer and the tip goes; it stays gone over refreshes; with the answer removed it is back;</li>
 *   <li>a saved recording (a synthesized two-sample file, the kind that teaches no curve, so the service counts this
 *   scooter's own) makes it go; with the file gone it comes back.</li>
 * </ul>
 * Arguments: home=widgets|clean, theme, text_scale, size_label. Mode look (-e mode look): only the tip at rest and its
 * sheet, for renders. An app without the tip (built before it, or a sweep's base app) gets a screenshot of its charging
 * view and passes.
 */
public final class EstimateTipValidation extends ValidationInstrumentation {
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private boolean admitted, clean;
    private String profile, label = "";
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String mode = args.getString("mode", "all");
        try {
            admit(); admitted = true;
            clean = "clean".equals(args.getString("home", "widgets"));
            String theme = args.getString("theme", "dark"), text = args.getString("text_scale", "1");
            label = (clean ? "clean" : "widgets") + "-" + args.getString("size_label", getTargetContext().getResources().getConfiguration().screenWidthDp + "")
                    + "-" + theme + ("1".equals(text) ? "" : "-x" + text);
            seed(theme, text);
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic UT5 MAX", "000000000000", 0); p.serial = "X2538-SYNTHETIC"; profiles.update(p); profile = p.id;
                profiles.select(profile); OnboardingScreen.markDone(getTargetContext());
            });
            launch();
            if (!tipBuilt()) { shot("tip-" + label); checks.add("An app without the estimate tip: its charging view as it is"); }
            else if (mode.equals("look")) look();
            else if (mode.startsWith("charger")) charger(mode.equals("charger-look"), "true".equals(args.getString("recording", "false")));
            else {
                verifyShows();
                verifySheet();
                verifyHide();
                verifySavedRecording();
            }
            assertNoRadio();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (admitted && activity != null) try { main(() -> { if (service != null) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ---- Cases ------------------------------------------------------------------------------------------------

    /** No recordings, charging: the tip shows under the time tile, whole, named and 48 dp to touch. */
    private void verifyShows() throws Exception {
        redrawUntil(this::tipShown, "The estimate tip did not show while charging with no recordings");
        main(() -> {
            require(Boolean.FALSE.equals(service.chargeRecordingsSaved()), "The fixture has recordings: " + service.chargeRecordingsSaved());
            TextView pill = pill();
            String spoken = String.valueOf(pill.getContentDescription());
            require("Improve this estimate".equals(spoken), "The tip's name: " + spoken);
            int rung = (Integer) field(slot(), "rung");
            String shown = pill.getText().toString();
            require(rung == 0 ? shown.equals("Improve this estimate") : rung == 1 ? shown.equals("Improve estimate") : shown.isEmpty(),
                    "Rung " + rung + " shows '" + shown + "'");
            Layout layout = pill.getLayout();
            require(shown.isEmpty() || layout != null && layout.getLineCount() == 1 && layout.getEllipsisCount(0) == 0
                    && layout.getLineWidth(0) <= pill.getWidth() - pill.getTotalPaddingLeft() - pill.getTotalPaddingRight() + 1, "The tip's words are cut or wrapped");
            require(pill.getHeight() >= dp(48) - 1 && pill.getWidth() >= dp(48) - 1, "The tip is under 48 dp to touch: " + pill.getWidth() + "x" + pill.getHeight());
            Rect tile = onScreen((View) field(hero(), "timeTile")), tip = onScreen(pill);
            require(Math.abs(tip.left - tile.left) <= 1 && tip.right <= tile.right + 1, "The tip is not under the time tile: tip " + tip + ", tile " + tile);
            require(tip.top >= tile.bottom - 1, "The tip overlaps the time tile: tip " + tip + ", tile " + tile);
        });
        shot("tip-" + label);
        checks.add("Charging with no recordings: the tip shows under the time tile, one whole line (rung " + rungNow() + "), named in full, 48 dp to touch");
    }

    /** A real tap opens the sheet; Record this charge starts nothing on the simulator and keeps it; Not now closes it. */
    private void verifySheet() throws Exception {
        tapPill();
        View sheet = awaitSheet();
        main(() -> {
            String words = texts(sheet);
            for (String expected : new String[] {"Record your charges", "Volt learns how your scooter charges from real charges.",
                    "Even a partial charge helps.", "100% helps the most.", "near the scooter", "Email to Volt", "Record this charge",
                    "Record charges automatically", "Charge history & recordings", "Not now", "Hide this tip"})
                require(words.contains(expected), "The sheet lacks '" + expected + "': " + words);
            for (String tag : new String[] {ChargeEstimateTip.TAG_RECORD, ChargeEstimateTip.TAG_AUTO, ChargeEstimateTip.TAG_OPEN,
                    ChargeEstimateTip.TAG_NOT_NOW, ChargeEstimateTip.TAG_HIDE})
                require(sheet.findViewWithTag(tag) != null, "The sheet lacks " + tag);
            View hide = sheet.findViewWithTag(ChargeEstimateTip.TAG_HIDE);
            require(hide.getHeight() >= dp(48) - 1, "Hide this tip is under 48 dp to touch");
            textProblems(sheet);
        });
        shot("sheet-" + label);
        // The simulator can't record a charge: the reason shows and nothing starts; the sheet stays for the rest.
        main(() -> {
            require(service.chargeCaptureUnavailable() != null, "A recording could start on the simulator");
            sheet.findViewWithTag(ChargeEstimateTip.TAG_RECORD).performClick();
        });
        settle();
        main(() -> require(!service.isChargeRecording() && sheet.isShown(), "Record this charge started a recording or closed the sheet with none possible"));
        main(() -> sheet.findViewWithTag(ChargeEstimateTip.TAG_NOT_NOW).performClick());
        await(() -> !sheet.isShown(), "Not now did not close the sheet");
        redrawUntil(this::tipShown, "Not now hid the tip");
        main(() -> require(!settings().contains(ChargeEstimateTip.Tip.NO_RECORDINGS.hiddenKey()), "Not now stored an answer"));
        checks.add("A tap opens 'Record your charges' with its intro, four points and controls; Record this charge starts nothing on the "
                + "simulator and the sheet stays; Not now closes it and the tip stays");
    }

    /** Hide this tip: stored, the tip goes and stays gone; the answer removed, it is back. */
    private void verifyHide() throws Exception {
        tapPill();
        View sheet = awaitSheet();
        main(() -> sheet.findViewWithTag(ChargeEstimateTip.TAG_HIDE).performClick());
        await(() -> !sheet.isShown(), "Hide this tip did not close the sheet");
        await(() -> slot().getVisibility() == View.GONE, "Hide this tip left the tip on screen");
        require(settings().getBoolean(ChargeEstimateTip.Tip.NO_RECORDINGS.hiddenKey(), false), "Hide this tip stored no answer");
        for (int i = 0; i < 3; i++) { main(() -> activity.redraw()); settle(); }
        main(() -> require(slot().getVisibility() == View.GONE, "The hidden tip came back on a refresh"));
        shot("hidden-" + label);
        require(settings().edit().remove(ChargeEstimateTip.Tip.NO_RECORDINGS.hiddenKey()).commit(), "Could not remove the answer");
        redrawUntil(this::tipShown, "With the answer removed the tip did not come back");
        checks.add("Hide this tip stores its answer and the tip goes, over refreshes too; without the answer it is back");
    }

    /** A saved recording for this scooter makes the tip go; with it deleted, the tip may come back (it does). */
    private void verifySavedRecording() throws Exception {
        File folder = new File(getTargetContext().getFilesDir(), "charge-recordings");
        File file = new File(folder, UUID.randomUUID().toString() + ".csv");
        long wall = 1735700000000L, mono = 10000000;
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), "UTF-8")) {
            out.write("charge-v1," + profile + ",STOCK," + wall + "," + mono + "\n");
            out.write("s," + wall + "," + mono + ",99.0,52.0,-2.0,-104.0,,1\n");
            out.write("s," + (wall + 10000) + "," + (mono + 10000) + ",100.0,52.0,-2.0,-104.0,,0\nend,COMPLETE\n");
        }
        refreshCurves();
        redrawUntil(() -> slot().getVisibility() == View.GONE && Boolean.TRUE.equals(service.chargeRecordingsSaved()),
                "A saved recording did not make the tip go");
        shot("recorded-" + label);
        require(file.delete(), "Could not delete the fixture recording");
        refreshCurves();
        redrawUntil(this::tipShown, "With the recording deleted the tip did not come back");
        checks.add("A saved recording for this scooter (one that teaches no curve, so the scooter's own are counted) makes the tip go; deleted, it comes back");
    }

    /**
     * The charger tip (item 11): the simulator never records and never runs the far-off rule, so its state is set as the
     * service's validation-only fields allow. It shows before the record tip, named "Record this charger", its
     * words whole; its sheet names the charger the charge is filed under (Save this recording as High-speed while an
     * automatic guess records, else Record this charge as High-speed); Not now keeps it; Hide this tip hides it alone.
     */
    private void charger(boolean lookOnly, boolean recording) throws Exception {
        main(() -> { ScooterService.chargerTipRecordingForTest = recording; ScooterService.chargerTipForTest = ChargeSessionStore.Charger.HIGH_SPEED; });
        try {
            String tag = "charger-" + (recording ? "recording-" : "") + label;
            redrawUntil(() -> ChargeEstimateTip.Tip.OTHER_CHARGER.equals(field(slot(), "tip")) && (tipShown() || clean && !(Boolean) field(slot(), "fits")),
                    "The charger tip did not show");
            if (!tipShown()) {
                // Clean at this size has no room to spare: the tip makes way (K's rule), and the view is as it was.
                shot("tip-" + tag);
                checks.add("Clean at this size has no room to spare: the charger tip makes way");
                return;
            }
            main(() -> {
                TextView pill = pill();
                String spoken = String.valueOf(pill.getContentDescription());
                require("Record this charger".equals(spoken), "The charger tip's name: " + spoken);
                int rung = (Integer) field(slot(), "rung");
                String shown = pill.getText().toString();
                require(rung == 0 ? shown.equals("Record this charger") : rung == 1 ? shown.equals("Record charger") : shown.isEmpty(),
                        "Rung " + rung + " shows '" + shown + "'");
                Layout layout = pill.getLayout();
                require(shown.isEmpty() || layout != null && layout.getLineCount() == 1 && layout.getEllipsisCount(0) == 0, "The charger tip's words are cut or wrapped");
                require(pill.getHeight() >= dp(48) - 1, "The charger tip is under 48 dp to touch");
            });
            shot("tip-" + tag);
            checks.add("The charger tip shows (rung " + rungNow() + "), named in full" + (recording ? ", during an automatic guess" : ""));
            tapPill();
            View[] found = new View[1];
            await(() -> {
                for (Dialog d : (Set<Dialog>) field(activity, "shownDialogs")) {
                    if (d == null || !d.isShowing() || d.getWindow() == null) continue;
                    View sheet = d.getWindow().getDecorView().findViewWithTag(ChargeEstimateTip.TAG_CHARGER_SHEET);
                    if (sheet != null && sheet.isShown()) { found[0] = sheet; return true; }
                }
                return false;
            }, "The charger tip's sheet did not open");
            View sheet = found[0];
            main(() -> {
                String words = texts(sheet);
                String action = recording ? "Save this recording as High-speed" : "Record this charge as High-speed";
                for (String expected : new String[] {"Different charger?", "Charging power doesn't match your recorded charges.", action, "Not now", "Hide this tip"})
                    require(words.contains(expected), "The charger sheet lacks '" + expected + "': " + words);
                require(sheet.findViewWithTag(ChargeEstimateTip.TAG_CHARGER_ACTION).getHeight() >= dp(48) - 1, "Its button is under 48 dp");
                textProblems(sheet);
            });
            shot("sheet-" + tag);
            checks.add("Its sheet names the charger it files under: " + (recording ? "Save this recording as High-speed" : "Record this charge as High-speed"));
            if (lookOnly) return;
            main(() -> sheet.findViewWithTag(ChargeEstimateTip.TAG_NOT_NOW).performClick());
            await(() -> !sheet.isShown(), "Not now did not close the charger sheet");
            if (clean) roomChanges();
            await(() -> !sheet.isShown(), "Not now did not close the charger sheet");
            redrawUntil(() -> tipShown() && ChargeEstimateTip.Tip.OTHER_CHARGER.equals(field(slot(), "tip")), "Not now hid the charger tip");
            tapPill();
            View[] again = new View[1];
            await(() -> {
                for (Dialog d : (Set<Dialog>) field(activity, "shownDialogs")) {
                    if (d == null || !d.isShowing() || d.getWindow() == null) continue;
                    View s2 = d.getWindow().getDecorView().findViewWithTag(ChargeEstimateTip.TAG_CHARGER_SHEET);
                    if (s2 != null && s2.isShown()) { again[0] = s2; return true; }
                }
                return false;
            }, "The charger sheet did not open again");
            main(() -> again[0].findViewWithTag(ChargeEstimateTip.TAG_HIDE).performClick());
            await(() -> !again[0].isShown(), "Hide this tip did not close the charger sheet");
            require(settings().getBoolean(ChargeEstimateTip.Tip.OTHER_CHARGER.hiddenKey(), false), "Hide this tip stored no answer for the charger tip");
            require(!settings().contains(ChargeEstimateTip.Tip.NO_RECORDINGS.hiddenKey()), "It hid the record tip too");
            // Hidden, with no recordings saved: the record tip is next.
            redrawUntil(() -> tipShown() && ChargeEstimateTip.Tip.NO_RECORDINGS.equals(field(slot(), "tip")), "With the charger tip hidden the record tip did not follow");
            checks.add("Not now keeps it; Hide this tip hides the charger tip alone, and the record tip follows");
        } finally {
            main(() -> { ScooterService.chargerTipForTest = null; ScooterService.chargerTipRecordingForTest = false; });
            settings().edit().remove(ChargeEstimateTip.Tip.OTHER_CHARGER.hiddenKey()).commit();
        }
    }

    /**
     * #574 F01: Clean's room changes after the first layout. The charging view loses most of its height (bottom padding),
     * so the tip must make way on the very next layout, then gets it back, and the tip must return whole: a measure that
     * reused the slot's height from before the change would keep either answer stale.
     */
    private void roomChanges() throws Exception {
        int[] before = new int[1];
        main(() -> { View hero = hero(); before[0] = hero.getPaddingBottom();
            hero.setPadding(hero.getPaddingLeft(), hero.getPaddingTop(), hero.getPaddingRight(), before[0] + hero.getHeight() / 2); });
        try {
            await(() -> { boolean[] gone = new boolean[1]; main(() -> gone[0] = !(Boolean) field(slot(), "fits") && slot().getHeight() == 0); return gone[0]; },
                    "With half the room gone the tip did not make way");
            checks.add("Clean: with half the room gone after the first layout, the tip makes way");
        } finally {
            main(() -> { View hero = hero(); hero.setPadding(hero.getPaddingLeft(), hero.getPaddingTop(), hero.getPaddingRight(), before[0]); });
        }
        await(() -> { boolean[] back = new boolean[1]; main(() -> back[0] = (Boolean) field(slot(), "fits") && slot().getHeight() == pill().getHeight() && pill().getHeight() > 0); return back[0]; },
                "With the room back the tip did not return");
        redrawUntil(() -> tipShown() && ChargeEstimateTip.Tip.OTHER_CHARGER.equals(field(slot(), "tip")), "With the room back the charger tip is not shown");
        checks.add("Clean: with the room back, the tip returns whole");
    }

    /** Renders: the tip at rest and its sheet. */
    private void look() throws Exception {
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end && !tipShown()) { main(() -> activity.redraw()); settle(); }
        shot("tip-" + label);
        checks.add("Look: the charging view with the tip " + (tipShown() ? "at rung " + rungNow() : "not shown (" + (clean ? "no room in Clean's slot" : "?") + ")"));
        if (tipShown()) {
            tapPill();
            awaitSheet();
            shot("sheet-" + label);
        }
    }

    // ---- Fixtures and finders -----------------------------------------------------------------------------------

    /** Whether this app's charging view has the tip's slot (a build before it, such as a sweep's base app, has none). */
    private boolean tipBuilt() {
        boolean[] out = new boolean[1];
        main(() -> {
            for (Class<?> type = hero().getClass(); type != null; type = type.getSuperclass())
                try { type.getDeclaredField("tipSlot"); out[0] = true; return; } catch (NoSuchFieldException missing) { }
        });
        return out[0];
    }
    private boolean tipShown() {
        boolean[] out = new boolean[1];
        main(() -> {
            View hero = hero();
            if (hero == null || !hero.isShown()) return;
            View slot = slot();
            TextView pill = pill();
            out[0] = slot.getVisibility() == View.VISIBLE && pill.isShown() && pill.getHeight() > 0 && !Motion.changing(slot) && slot.getAlpha() == 1f;
        });
        return out[0];
    }
    private int rungNow() { int[] r = new int[1]; main(() -> r[0] = (Integer) field(slot(), "rung")); return r[0]; }
    private View slot() { return (View) field(hero(), "tipSlot"); }
    private TextView pill() { return (TextView) field(slot(), "pill"); }
    private void tapPill() throws Exception {
        float[] at = new float[2];
        main(() -> { Rect r = onScreen(pill()); at[0] = r.exactCenterX(); at[1] = r.exactCenterY(); });
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, at[0], at[1], 0);
        MotionEvent lift = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, at[0], at[1], 0);
        try { sendPointerSync(press); SystemClock.sleep(60); sendPointerSync(lift); } finally { press.recycle(); lift.recycle(); }
        settle();
    }
    @SuppressWarnings("unchecked")
    private View awaitSheet() {
        View[] found = new View[1];
        await(() -> {
            for (Dialog d : (Set<Dialog>) field(activity, "shownDialogs")) {
                if (d == null || !d.isShowing() || d.getWindow() == null) continue;
                View sheet = d.getWindow().getDecorView().findViewWithTag(ChargeEstimateTip.TAG_SHEET);
                if (sheet != null && sheet.isShown()) { found[0] = sheet; return true; }
            }
            return false;
        }, "The tip's sheet did not open");
        SystemClock.sleep(Motion.PAGE_MS + 120); settle();
        return found[0];
    }
    private void refreshCurves() {
        main(() -> invoke(service, "refreshChargeCurves", new Class<?>[]{}));
        await(() -> !Boolean.TRUE.equals(field(service, "chargeModelLoading")), "The charge store's refresh did not finish");
    }
    private void seed(String theme, String text) {
        SharedPreferences.Editor edit = settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                .putString("theme", theme).putFloat("text_scale", Float.parseFloat(text));
        if (clean) edit.putString("home_style", "clean").putBoolean("home_style_chosen", true).putBoolean("home_whats_new_shown", true);
        require(edit.commit(), "Could not seed preferences");
    }
    private void launch() throws Exception {
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
        redrawUntil(this::heroAtRest, "The charging view did not show on the simulator");
    }
    private boolean heroAtRest() {
        View hero = hero();
        if (hero == null || !hero.isShown() || field(hero, "reveal") != null) return false;
        if (clean) {
            Object home = cleanHome();
            if (home == null || Boolean.TRUE.equals(invoke(field(home, "transition"), "changing")) || hero.getAlpha() != 1f) return false;
        }
        return true;
    }
    private void redrawUntil(BooleanSupplier condition, String message) throws Exception {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> ok = new AtomicReference<>();
            main(() -> activity.redraw());
            settle(); SystemClock.sleep(160);
            main(() -> ok.set(condition.getAsBoolean()));
            if (Boolean.TRUE.equals(ok.get())) { SystemClock.sleep(Motion.CHANGE_MS + 80); settle(); return; }
        }
        throw new IllegalStateException(message);
    }
    private Object screen() { return field(activity, "screen"); }
    private View hero() {
        if (clean) return root().findViewWithTag("clean-home-charging");
        Object screen = screen();
        return screen == null || !(screen instanceof DashboardScreen) ? null : (View) field(screen, "chargingHero");
    }
    private Object cleanHome() {
        Object screen = screen();
        if (screen != null && screen.getClass().getSimpleName().equals("CleanHomeScreen")) return screen;
        Object pane = field(activity, "homeScreen");
        return pane != null && pane.getClass().getSimpleName().equals("CleanHomeScreen") ? pane : null;
    }
    private static Rect onScreen(View v) { int[] at = new int[2]; v.getLocationOnScreen(at); return new Rect(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight()); }
    private int dp(float value) { return Math.round(value * getTargetContext().getResources().getDisplayMetrics().density); }
    private static String texts(View view) {
        StringBuilder out = new StringBuilder(); ArrayDeque<View> queue = new ArrayDeque<>(); queue.add(view);
        while (!queue.isEmpty()) {
            View v = queue.removeFirst();
            if (!v.isShown()) continue;
            if (v instanceof TextView) out.append(((TextView) v).getText()).append(" | ");
            if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) queue.add(((ViewGroup) v).getChildAt(i));
        }
        return out.toString();
    }
    /** No shown text is cut short or broken inside a word. */
    private void textProblems(View view) {
        ArrayDeque<View> queue = new ArrayDeque<>(); queue.add(view);
        while (!queue.isEmpty()) {
            View v = queue.removeFirst();
            if (!v.isShown()) continue;
            if (v instanceof TextView) {
                TextView t = (TextView) v; Layout layout = t.getLayout(); CharSequence text = t.getText();
                if (layout != null && text.length() > 0) for (int line = 0; line < layout.getLineCount(); line++) {
                    require(layout.getEllipsisCount(line) == 0, "Cut text: " + text);
                    int at = layout.getLineEnd(line);
                    if (line < layout.getLineCount() - 1 && at > 0 && at < text.length())
                        require(!(Character.isLetter(text.charAt(at - 1)) && Character.isLetter(text.charAt(at))), "A word broken across lines: " + text);
                }
            }
            if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) queue.add(((ViewGroup) v).getChildAt(i));
        }
    }

    // ---- Plumbing ---------------------------------------------------------------------------------------------

    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "estimate-tip-validation"); d.mkdirs(); return d; }
    private void shot(String name) throws Exception { settle(); SystemClock.sleep(300); waitForIdleSync(); screenshot(name); }
    private void screenshot(String name) throws Exception {
        File file = new File(dir(), name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
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
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end) { AtomicReference<Boolean> ready = new AtomicReference<>(); main(() -> ready.set(condition.getAsBoolean())); if (Boolean.TRUE.equals(ready.get())) return; SystemClock.sleep(40); }
        throw new IllegalStateException(message);
    }
    private void main(Runnable action) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { action.run(); return; }
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
