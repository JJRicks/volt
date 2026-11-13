package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.app.LocaleManager;
import android.app.UiAutomation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.*;

/**
 * Disposable empty emulator, radio off. The charging view's one time tile (owner's design, Nov 12 2026) on synthetic
 * readings fed to the charging hero with the service's own updates unhooked, as ChargeAlertValidation does. Sends no
 * scooter command.
 *
 * Default mode: one reading without an alert (no dots, no switching); an alert's two readings with the alert first;
 * real taps and swipes switch; an abandoned swipe and a vertical move do not; rapid taps, hiding mid-switch, a
 * re-parent (fold) mid-switch and a rotation mid-switch all end at rest on the last choice; the choice survives
 * hiding the view and an activity restart, per scooter; a scooter with a charge limit; reduced motion; TalkBack's
 * node and one announcement; slowed frames of one tap and one held drag saved as crops.
 * Mode layout: every theme, text size (1x, 1.4x) and language (English, German, both Portuguese) at the emulator's
 * current size: labels whole, the dots clear of the label and the state pill, one height for both readings.
 * Mode restart: run after a default pass on the same install (no reinstall): the saved choices survive a new process.
 */
public final class ChargeTimeTileValidation extends Instrumentation {
    private static final int LIMIT = 0, FULL = 1;
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private boolean admitted;
    private String a, b, ut5, label = "";
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    private final ChargeAlertPolicy.Settings alert70 = new ChargeAlertPolicy.Settings(true, 70, false, 20, true, false);

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String mode = args.getString("mode", "all");
        try {
            if (mode.equals("restart")) restartCheck();
            else {
                admit(); admitted = true;
                seed(args.getString("theme", "dark"), args.getString("text_scale", "1"));
                main(() -> {
                    ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                    ScooterProfiles.Profile pa = profiles.create("Synthetic GT3 Max A", "000000000001", 0); pa.serial = "Q2345ZZ9ES7777777";
                    pa.advertisedPid = "24182"; pa.advertisedPidWall = System.currentTimeMillis(); profiles.update(pa); a = pa.id;
                    ScooterProfiles.Profile pb = profiles.create("Synthetic GT3 Max B", "000000000002", 0); pb.serial = "Q2345ZZ9ES7777778";
                    pb.advertisedPid = "24182"; pb.advertisedPidWall = System.currentTimeMillis(); profiles.update(pb); b = pb.id;
                    ScooterProfiles.Profile pu = profiles.create("Synthetic UT5 MAX", "000000000000", 0); pu.serial = "X2538-SYNTHETIC"; profiles.update(pu); ut5 = pu.id;
                    profiles.select(a); OnboardingScreen.markDone(getTargetContext());
                });
                launch();
                if (mode.equals("layout")) verifyLayoutMatrix();
                else {
                    verifyOneReading();
                    verifyAlertReadings();
                    verifyGestures();
                    verifyInterruptions();
                    verifyPerScooterAndRestart();
                    verifyLimitScooter();
                    verifyReducedMotion();
                    verifyAccessibility();
                    verifyFrames();
                    verifyRotation();
                }
                assertNoRadio();
            }
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            try { scale("1"); } catch (Throwable ignored) {}
            try { getUiAutomation().setRotation(UiAutomation.ROTATION_UNFREEZE); } catch (Throwable ignored) {}
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> { activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ---- Cases ------------------------------------------------------------------------------------------------

    private void verifyOneReading() throws Exception {
        hero(50, null, 2);
        main(() -> {
            require(!two() && tile().isShown(), "Without an alert the tile does not show one reading");
            require(face(FULL).getVisibility() == View.VISIBLE && face(LIMIT).getVisibility() == View.GONE, "One reading: the 100% face alone");
            require(!tile().isClickable() && !tile().isFocusable() && tile().getContentDescription() == null, "One reading: the tile became a control");
            require(String.valueOf(value(FULL).getContentDescription()).startsWith(String.valueOf(caption(FULL).getContentDescription())),
                    "One reading: the value does not speak its name: " + value(FULL).getContentDescription());
            require(power().getParent() == tile().getParent(), "Power is not beside the time");
        });
        shot("one-reading");
        tap();
        main(() -> require(!two() && face(FULL).getVisibility() == View.VISIBLE && pref(a) == null, "A tap on one reading switched or saved something"));
        checks.add("No alert, no limit: one reading (time to 100%), no dots, not a control, a tap does nothing");
    }

    private void verifyAlertReadings() throws Exception {
        main(() -> require(service.setChargeAlerts(alert70), "Could not save the alert"));
        hero(50, null, 2);
        main(() -> {
            require(two() && shownReading() == LIMIT && atRest(), "With an alert the tile does not open on the alert's time");
            require(String.valueOf(caption(LIMIT).getContentDescription()).contains("70"), "The alert reading is not named for 70%: " + caption(LIMIT).getContentDescription());
            require(!"—".contentEquals(value(LIMIT).getText()) && value(LIMIT).length() > 0 && value(FULL).length() > 0, "A reading has no value");
            require(face(FULL).getVisibility() == View.INVISIBLE, "The other reading is drawn at rest");
            require(power().getParent() == tile().getParent(), "The two readings took a second row");
            require(pref(a) == null, "Opening the view saved a choice");
        });
        shot("alert-default");
        checks.add("An alert ahead: one row, power beside one tile holding the alert's time (shown first) and the time to 100%");
    }

    private void verifyGestures() throws Exception {
        tap();
        awaitRest();
        main(() -> require(shownReading() == FULL && "full".equals(pref(a)), "A tap did not switch to 100% and save it"));
        swipe(-.8f, false);
        awaitRest();
        main(() -> require(shownReading() == LIMIT && "limit".equals(pref(a)), "A swipe to the left did not switch back"));
        swipe(.8f, false);
        awaitRest();
        main(() -> require(shownReading() == FULL, "A swipe to the right did not switch"));
        swipe(-.14f, true);  // about a third of the travel, held still, then let go
        awaitRest();
        main(() -> require(shownReading() == FULL && "full".equals(pref(a)), "An abandoned swipe switched"));
        verticalPass();
        awaitRest();
        main(() -> require(shownReading() == FULL, "Moving up or down over the tile switched it"));
        tap(); awaitRest();
        main(() -> require(shownReading() == LIMIT, "Tap back failed"));
        checks.add("A tap and a swipe either way switch and save; a swipe let go short, and an up-down move over the tile, do not");
    }

    private void verifyInterruptions() throws Exception {
        int start = readingNow();
        for (int i = 0; i < 7; i++) { tapQuick(); SystemClock.sleep(35); }
        awaitRest();
        main(() -> {
            require(shownReading() != start && cleanRest(), "Seven fast taps did not end cleanly on the other reading");
            require(ChargeTimeReadingsPref(pref(a)) == shownReading(), "The saved choice is not the shown one after fast taps");
        });
        // Hidden mid-switch (charging stops reporting), then shown again.
        int before = readingNow();
        scale("6");
        tapQuick(); SystemClock.sleep(200);
        main(() -> require(!atRest(), "The slowed switch was not running"));
        hero(50, null, 0);
        main(() -> require(cleanRest() && state("target") == other(before), "Hiding mid-switch left a half state"));
        scale("1");
        hero(50, null, 2);
        main(() -> require(shownReading() == other(before) && cleanRest(), "Shown again, the tile lost the choice"));
        // Re-parented mid-switch (a fold hands the header to another pane).
        int now = readingNow();
        scale("6");
        tapQuick(); SystemClock.sleep(200);
        main(() -> {
            require(!atRest(), "The slowed switch was not running");
            View hero = hero(); ViewGroup parent = (ViewGroup) hero.getParent(); int index = parent.indexOfChild(hero);
            ViewGroup.LayoutParams params = hero.getLayoutParams(); parent.removeView(hero); parent.addView(hero, index, params);
            require(cleanRest() && shownReading() == other(now), "A re-parent mid-switch left a half state");
        });
        scale("1");
        hero(50, null, 2);
        // A fresh estimate mid-switch: label and value stay together on each face.
        scale("6");
        tapQuick(); SystemClock.sleep(150);
        hero(52, null, 2);
        main(() -> require(!atRest(), "The slowed switch ended before the fresh estimate"));
        scale("1");
        awaitRest();
        main(() -> require(cleanRest() && String.valueOf(value(LIMIT).getContentDescription()).startsWith(String.valueOf(caption(LIMIT).getContentDescription())),
                "A fresh estimate mid-switch separated a label from its value"));
        checks.add("Fast taps, hiding, a re-parent and a fresh estimate mid-switch all end with one reading drawn, at rest on the last choice");
    }

    private void verifyPerScooterAndRestart() throws Exception {
        if (readingNow() != FULL) { tap(); awaitRest(); }
        main(() -> require("full".equals(pref(a)), "A's choice was not saved"));
        choose(b);
        main(() -> require(service.setChargeAlerts(alert70), "Could not save B's alert"));
        hero(50, null, 2);
        main(() -> require(two() && shownReading() == LIMIT && pref(b) == null, "B inherited A's choice"));
        choose(a);
        hero(50, null, 2);
        main(() -> require(shownReading() == FULL, "A lost its choice after B"));
        relaunch();
        hero(50, null, 2);
        main(() -> require(shownReading() == FULL && cleanRest(), "A's choice did not survive an activity restart"));
        // The alert reached: one reading; the choice waits for two again.
        hero(75, null, 2);
        main(() -> require(!two() && face(FULL).isShown() && "full".equals(pref(a)), "A reached alert changed the saved choice"));
        hero(50, null, 2);
        main(() -> require(two() && shownReading() == FULL, "The choice did not come back with two readings"));
        main(() -> require(service.setChargeAlerts(alert70.charge(false, 70)), "Could not turn A's alert off"));
        hero(50, null, 2);
        main(() -> require(!two() && "full".equals(pref(a)), "Turning the alert off lost the choice"));
        main(() -> require(service.setChargeAlerts(alert70), "Could not restore A's alert"));
        hero(50, null, 2);
        main(() -> require(two() && shownReading() == FULL, "The alert back on did not restore the choice"));
        checks.add("The choice is per scooter (B opens on its alert, A keeps 100%), survives an activity restart, and waits while only one reading exists");
    }

    private void verifyLimitScooter() throws Exception {
        choose(ut5);
        main(() -> require(settings().edit().putString("charge_alerts_" + ut5, alert70.encode()).commit(), "Could not seed an alert on the limit model"));
        hero(50, 80d, 2);
        main(() -> {
            require(two() && shownReading() == LIMIT, "A charge limit below 100% does not open on the limit");
            require(String.valueOf(caption(LIMIT).getContentDescription()).contains("80") && !String.valueOf(caption(LIMIT).getContentDescription()).contains("70"),
                    "The limit reading is not the scooter's 80%: " + caption(LIMIT).getContentDescription());
            require(String.valueOf(caption(FULL).getContentDescription()).contains("100"), "The other reading is not the time to 100%: " + caption(FULL).getContentDescription());
        });
        shot("limit-80");
        hero(50, 100d, 2);
        main(() -> require(!two(), "A limit at 100% shows two readings"));
        main(() -> settings().edit().remove("charge_alerts_" + ut5).commit());
        choose(a); hero(50, null, 2);
        checks.add("A scooter limit below 100% is the first reading (an alert saved on it is not used); at 100% one reading");
    }

    private void verifyReducedMotion() throws Exception {
        scale("0");
        int before = readingNow();
        tap();
        main(() -> require(cleanRest() && shownReading() == other(before) && field(tile(), "motion") == null, "Reduced motion still animated"));
        swipe(-.8f, false);
        main(() -> require(cleanRest() && shownReading() == before, "Reduced motion: a swipe did not switch at once"));
        scale("1");
        checks.add("With animations off the switch is instant, by tap and by swipe");
    }

    private void verifyAccessibility() throws Exception {
        if (readingNow() != LIMIT) { tap(); awaitRest(); }
        reveal();
        main(() -> {
            AccessibilityNodeInfo node = tile().createAccessibilityNodeInfo();
            require(node.isClickable() && node.isFocusable() && node.isVisibleToUser(), "The tile is not a reachable control");
            boolean click = false; for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) click |= action.getId() == AccessibilityNodeInfo.ACTION_CLICK;
            require(click, "The tile offers no click action");
            String spoken = String.valueOf(node.getContentDescription());
            require(spoken.equals(String.valueOf(value(LIMIT).getContentDescription())), "The tile does not speak the shown reading: " + spoken);
            for (int r : new int[] {LIMIT, FULL}) require(face(r).getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, "A face is read on its own");
        });
        ArrayList<String> heard = new ArrayList<>();
        String[] names = new String[2];
        main(() -> { names[0] = String.valueOf(caption(LIMIT).getContentDescription()); names[1] = String.valueOf(caption(FULL).getContentDescription()); });
        UiAutomation automation = getUiAutomation();
        automation.setOnAccessibilityEventListener(event -> {
            if (event.getEventType() != AccessibilityEvent.TYPE_ANNOUNCEMENT) return;
            String text = String.valueOf(event.getText());
            if (text.contains(names[0]) || text.contains(names[1])) synchronized (heard) { heard.add(text); }
        });
        SystemClock.sleep(300);
        try {
            main(() -> tile().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
            awaitRest(); SystemClock.sleep(400);
            String expected = callString(() -> String.valueOf(value(FULL).getContentDescription()));
            synchronized (heard) {
                require(heard.size() == 1 && heard.get(0).contains(expected.substring(0, Math.min(12, expected.length()))),
                        "Switching did not announce the new reading once: " + heard + " expected " + expected);
            }
            heard.clear();
            hero(50, null, 2); hero(51, null, 2);
            SystemClock.sleep(300);
            synchronized (heard) { require(heard.isEmpty(), "Fresh values announced again: " + heard); }
        } finally { automation.setOnAccessibilityEventListener(null); }
        main(() -> require(String.valueOf(tile().getContentDescription()).equals(String.valueOf(value(FULL).getContentDescription())), "After switching the tile speaks the old reading"));
        checks.add("TalkBack: one focusable node speaking the shown reading, click action switches, one announcement per switch and none for fresh values, faces hidden");
        // The catalog follow-up names the switch: a labelled click, a "Switch time" action and the other time as state.
        int switchId = getTargetContext().getResources().getIdentifier("charge_time_switch", "id", getTargetContext().getPackageName());
        if (switchId != 0) {
            int before = readingNow();
            main(() -> {
                AccessibilityNodeInfo node = tile().createAccessibilityNodeInfo();
                CharSequence clickLabel = null, switchLabel = null;
                for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                    if (action.getId() == AccessibilityNodeInfo.ACTION_CLICK) clickLabel = action.getLabel();
                    if (action.getId() == switchId) switchLabel = action.getLabel();
                }
                require(clickLabel != null && clickLabel.length() > 0 && switchLabel != null && switchLabel.length() > 0,
                        "The switch is not named as a click and an action: " + clickLabel + " / " + switchLabel);
                if (Build.VERSION.SDK_INT >= 30) require(String.valueOf(node.getStateDescription()).contains(String.valueOf(caption(other(before)).getContentDescription())),
                        "The tile does not say there is another time: " + node.getStateDescription());
                require(tile().performAccessibilityAction(switchId, null), "The switch action was refused");
            });
            awaitRest();
            main(() -> require(shownReading() == other(before), "The switch action did not switch"));
            main(() -> tile().performAccessibilityAction(switchId, null)); awaitRest();
            checks.add("TalkBack names the switch (labelled click and a Switch time action) and the other time as state; the action switches");
        } else checks.add("No Switch time action in this build: the catalog follow-up adds it");
    }

    private void verifyFrames() throws Exception {
        if (readingNow() != LIMIT) { tap(); awaitRest(); }
        reveal();
        Rect box = callRect(() -> { int[] at = new int[2]; tile().getLocationOnScreen(at); return new Rect(at[0], at[1], at[0] + tile().getWidth(), at[1] + tile().getHeight()); });
        int[] heights = new int[1]; main(() -> heights[0] = tile().getHeight());
        scale("10");
        tapQuick();
        long end = SystemClock.uptimeMillis() + 2600; int n = 0; boolean steadyHeight = true;
        while (SystemClock.uptimeMillis() < end && n < 40) {
            Bitmap frame = screencap();
            saveCrop(frame, box, String.format(Locale.ROOT, "frames/tap-%02d", n++));
            int[] h = new int[1]; main(() -> h[0] = tile().getHeight()); steadyHeight &= h[0] == heights[0];
        }
        awaitRest();
        require(steadyHeight, "The tile's height changed during the switch");
        // A held drag at about a third of the way (slow scale keeps nothing else moving).
        int[] at = new int[2]; main(() -> tile().getLocationOnScreen(at));
        float density = getTargetContext().getResources().getDisplayMetrics().density;
        float x = at[0] + box.width() / 2f, y = at[1] + box.height() / 2f;
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, down, x, y);
        for (int i = 1; i <= 8; i++) send(MotionEvent.ACTION_MOVE, down, x - i * 5 * density, y);
        SystemClock.sleep(250);
        saveCrop(screencap(), box, "frames/drag-held-40dp");
        main(() -> require(Boolean.TRUE.equals(field(tile(), "dragging")) && !atRest(), "The held drag is not dragging"));
        for (int i = 9; i <= 16; i++) send(MotionEvent.ACTION_MOVE, down, x - i * 5 * density, y);
        SystemClock.sleep(200);
        saveCrop(screencap(), box, "frames/drag-held-80dp");
        send(MotionEvent.ACTION_UP, down, x - 80 * density, y);
        SystemClock.sleep(150);
        saveCrop(screencap(), box, "frames/drag-released");
        awaitRest();
        scale("1");
        checks.add("Frames of one slowed tap (" + n + ") and a held drag saved under frames/; the tile's height stayed " + heights[0] + " px throughout");
    }

    private void verifyRotation() throws Exception {
        int before = readingNow();
        scale("6");
        tapQuick(); SystemClock.sleep(150);
        getUiAutomation().setRotation(UiAutomation.ROTATION_FREEZE_90);
        SystemClock.sleep(2500); settle();
        scale("1");
        freeze(); hero(50, null, 2);
        main(() -> require(cleanRest() && shownReading() == other(before) && ChargeTimeReadingsPref(pref(a)) == other(before), "A rotation mid-switch did not end on the choice"));
        shot("rotated");
        getUiAutomation().setRotation(UiAutomation.ROTATION_FREEZE_0);
        SystemClock.sleep(1500); settle(); freeze(); hero(50, null, 2);
        main(() -> require(cleanRest() && shownReading() == other(before), "Rotating back lost the choice"));
        checks.add("A rotation mid-switch rebuilds the screen at rest on the choice");
    }

    /** Every theme, text size and language at this size: labels whole, dots clear, one height for both readings. */
    private void verifyLayoutMatrix() throws Exception {
        String size = args.getString("size_label", "size");
        for (String locale : new String[] {"", "de", "pt-PT", "pt-BR"}) {
            for (String theme : new String[] {"dark", "light"}) for (String text : new String[] {"1", "1.4"}) {
                label = size + "-" + (locale.isEmpty() ? "en" : locale) + "-" + theme + "-" + text;
                MainActivity old = activity; main(old::finish); SystemClock.sleep(800); settle();
                seed(theme, text); setLocale(locale); launch();
                main(() -> require(service.setChargeAlerts(alert70), "Could not save the alert"));
                hero(50, null, 2);
                if (readingNow() != LIMIT) { tap(); awaitRest(); }
                assertLayout("limit");
                int[] limitBox = boxes();
                shot(label + "-limit");
                main(() -> tile().performClick()); awaitRest();
                assertLayout("full");
                int[] fullBox = boxes();
                require(Arrays.equals(limitBox, fullBox), label + ": the tile or power moved between readings " + Arrays.toString(limitBox) + " vs " + Arrays.toString(fullBox));
                shot(label + "-full");
                hero(50, null, 0.03);  // under ChargingEstimate.MINIMUM_ETA_CURRENT_A: the Low current pill on both faces
                main(() -> require(tags(FULL).isShown(), label + ": no state pill at low current"));
                assertLayout("full-low");
                shot(label + "-full-low");
                main(() -> tile().performClick()); awaitRest();
                assertLayout("limit-low");
                main(() -> require(service.setChargeAlerts(alert70.charge(false, 70)), "Could not turn the alert off"));
                hero(50, null, 2);
                assertLayout("one");
                shot(label + "-one");
                main(() -> require(service.setChargeAlerts(alert70), "Could not restore the alert"));
                checks.add(label + ": labels whole, dots clear of label and pill, both readings one height, power still");
            }
        }
        MainActivity old = activity; main(old::finish); SystemClock.sleep(800);
        setLocale("");
    }

    /** No broken or tiny text in the hero's tiles; the dots inside the card and clear of the caption's text and the pill. */
    private void assertLayout(String what) {
        main(() -> {
            ArrayList<String> problems = new ArrayList<>();
            ArrayDeque<View> stack = new ArrayDeque<>(); stack.push((View) field(hero(), "tiles"));
            float sp = getTargetContext().getResources().getDisplayMetrics().scaledDensity;
            while (!stack.isEmpty()) {
                View v = stack.pop();
                if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) stack.push(((ViewGroup) v).getChildAt(i));
                if (!(v instanceof TextView) || !v.isShown()) continue;
                TextView t = (TextView) v;
                TextFit.collectNarrowTextProblems(t, "charge time tile", problems);
                // The state pill keeps main's 11 sp chip style (tagChip), unchanged by the tile; everything else has a 12 sp floor.
                boolean pill = t == tags(LIMIT) || t == tags(FULL);
                if (t.length() > 0 && t.getTextSize() / sp < (pill ? 11 : 12) - .01f) problems.add("tiny text: " + t.getText() + " " + t.getTextSize() / sp);
            }
            require(problems.isEmpty(), label + " " + what + ": " + problems);
            if (!two()) return;
            View tile = tile();
            float r = activity.ui().dp(6) / 2f; int gap = activity.ui().dp(9); int width = Math.round(r * 2) + gap;
            boolean rtl = tile.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float left = rtl ? tile.getPaddingLeft() : tile.getWidth() - tile.getPaddingRight() - width;
            int shown = shownReading();
            TextView caption = caption(shown);
            Rect cap = TextFit.relativeBounds(caption, tile);
            android.text.Layout layout = caption.getLayout();
            float y = cap.top + caption.getTotalPaddingTop() + layout.getLineBaseline(0)
                    + (caption.getPaint().getFontMetrics().ascent + caption.getPaint().getFontMetrics().descent) / 2f;
            RectF dots = new RectF(left, y - r, left + width, y + r);
            require(dots.left >= 0 && dots.right <= tile.getWidth() && dots.top >= 0 && dots.bottom <= tile.getHeight(), label + " " + what + ": dots outside the card " + dots);
            for (int line = 0; line < layout.getLineCount(); line++) {
                if (layout.getLineEnd(line) == layout.getLineStart(line)) continue;
                float lineLeft = cap.left + caption.getTotalPaddingLeft() + layout.getLineLeft(line), lineRight = cap.left + caption.getTotalPaddingLeft() + layout.getLineRight(line);
                RectF text = new RectF(lineLeft, cap.top + caption.getTotalPaddingTop() + layout.getLineTop(line), lineRight, cap.top + caption.getTotalPaddingTop() + layout.getLineBottom(line));
                require(!RectF.intersects(inflate(dots, r), text), label + " " + what + ": dots touch the label '" + caption.getText() + "' " + dots + " " + text);
            }
            TextView pill = tags(shown);
            if (pill.isShown()) require(!RectF.intersects(inflate(dots, r), new RectF(TextFit.relativeBounds(pill, tile))), label + " " + what + ": dots touch the state pill");
        });
    }

    // ---- Restart mode -----------------------------------------------------------------------------------------

    private void restartCheck() throws Exception {
        main(() -> {
            for (ScooterProfiles.Profile p : new ScooterProfiles(getTargetContext()).all()) {
                if (p.name.equals("Synthetic GT3 Max A")) a = p.id;
                if (p.name.equals("Synthetic GT3 Max B")) b = p.id;
            }
            require(a != null && b != null, "Run the default mode first on this install");
            new ScooterProfiles(getTargetContext()).select(a);
        });
        launch();
        main(() -> require(service.setChargeAlerts(alert70), "Could not save the alert"));
        hero(50, null, 2);
        int expected = ChargeTimeReadingsPref(pref(a));
        main(() -> require(two() && shownReading() == expected && expected == FULL, "A's choice did not survive a new process: " + pref(a)));
        choose(b); hero(50, null, 2);
        main(() -> require(shownReading() == LIMIT, "B's default changed after a new process"));
        checks.add("In a new process A opens on its saved 100% and B on its alert");
    }

    // ---- Fixtures -------------------------------------------------------------------------------------------

    private void seed(String theme, String text) {
        require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                .putString("theme", theme).putFloat("text_scale", Float.parseFloat(text)).commit(), "Could not seed preferences");
    }
    private void setLocale(String tag) throws Exception {
        if (Build.VERSION.SDK_INT < 33) { require(tag.isEmpty(), "Languages need API 33"); return; }
        main(() -> getTargetContext().getSystemService(LocaleManager.class)
                .setApplicationLocales(tag.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag)));
        SystemClock.sleep(1200); settle();
    }
    private void launch() throws Exception {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().getWidth() > 0, "App did not launch"); service = activity.service();
        // The first screens settle (service bound, scooter state read) before the fixture takes over.
        SystemClock.sleep(2500); settle();
        freeze();
    }
    private void relaunch() throws Exception {
        MainActivity old = activity;
        main(old::finish);
        SystemClock.sleep(800); settle();
        launch();
    }
    /** Home, with the activity's own refreshes unhooked so only the fixture readings reach the hero. */
    private void freeze() throws Exception {
        await(() -> field(activity, "screen") != null, "No screen");
        main(() -> {
            invoke(activity, "select", new Class<?>[]{String.class}, "home");
            ((android.os.Handler) field(activity, "handler")).removeCallbacks((Runnable) field(activity, "pulse"));
            service.unlisten((Runnable) field(activity, "serviceChanged"));
            invoke(activity, "cancelDeferredBodyRefresh");
            // Saving the choice is a settings change, which redraws Home from the app's own (empty) readings: in the
            // app that is one more refresh with the same readings (the mid-switch estimate case below covers it).
            ((SharedPreferences) field(activity, "preferences")).unregisterOnSharedPreferenceChangeListener(
                    (SharedPreferences.OnSharedPreferenceChangeListener) field(activity, "settingsChanged"));
        });
        settle();
    }
    private void choose(String id) throws Exception {
        main(() -> { service.profiles.select(id); service.ble.profileSelected(); });
        settle();
        main(() -> invoke(activity, "redraw"));  // the frozen app does not refresh by itself: Home sees the new scooter and rebuilds
        SystemClock.sleep(600); settle(); freeze();
        main(() -> require(id.equals(field(screen(), "profileId")), "The dashboard did not follow the scooter"));
    }
    /** Fresh synthetic charging readings; amps 0 means not charging (the hero hides). */
    private void hero(double percent, Double limit, double amps) throws Exception {
        long wall = System.currentTimeMillis();
        Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>();
        for (Object[] r : new Object[][]{{"charging_state", amps > 0 ? 1d : 0d}, {"voltage_v", 52d}, {"current_a", -amps}, {"battery_percent", percent}, {"rated_capacity_ah", 14.5d}})
            values.put((String) r[0], new TelemetryHistory.Reading((String) r[0], (String) r[0], "Synthetic fixture", String.valueOf(r[1]), "", (Double) r[1], wall, true));
        if (limit != null) values.put("charge_limit_percent", new TelemetryHistory.Reading("charge_limit_percent", "charge_limit_percent", "Synthetic fixture", limit + " %", "%", limit, wall, true));
        main(() -> invoke(hero(), "refresh", new Class<?>[]{Map.class}, values));
        settle(); SystemClock.sleep(amps > 0 ? 420 : 320); settle();
        boolean[] stray = new boolean[1];
        main(() -> stray[0] = amps > 0 && !Boolean.TRUE.equals(field(hero(), "shown")));
        if (stray[0]) {  // a refresh of the app's own (empty) readings landed after the fixture's: once more, and say so
            Log.w("ChargeTimeTileValidation", "a stray refresh hid the fixture; refreshing again");
            main(() -> invoke(hero(), "refresh", new Class<?>[]{Map.class}, values));
            settle(); SystemClock.sleep(420); settle();
        }
        if (amps > 0) main(() -> {
            View h = hero(); StringBuilder chain = new StringBuilder();
            for (View v = h; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) chain.append(v.getClass().getSimpleName()).append(':').append(v.getVisibility()).append(' ');
            require(h.isShown() && tile().isShown(), "The charging view did not show for the fixture readings: shown=" + field(h, "shown")
                    + " attached=" + h.isAttachedToWindow() + " charging=" + service.chargingEstimate(values).charging + " chain=" + chain);
        });
    }

    // ---- Touch ------------------------------------------------------------------------------------------------

    private float[] centre() throws Exception {
        reveal();
        float[] c = new float[2];
        main(() -> { int[] at = new int[2]; tile().getLocationOnScreen(at); c[0] = at[0] + tile().getWidth() / 2f; c[1] = at[1] + tile().getHeight() / 2f; });
        return c;
    }
    private void tap() throws Exception { tapQuick(); settle(); }
    private void tapQuick() throws Exception {
        float[] c = centre(); long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, down, c[0], c[1]); SystemClock.sleep(40); send(MotionEvent.ACTION_UP, down, c[0], c[1]);
        waitForIdleSync();
    }
    /** A sideways swipe of share x the tile's width; abandoned: it stops, holds still, then lifts. */
    private void swipe(float share, boolean abandoned) throws Exception {
        float[] c = centre(); int[] w = new int[1]; main(() -> w[0] = tile().getWidth());
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, down, c[0], c[1]);
        for (int i = 1; i <= 10; i++) { send(MotionEvent.ACTION_MOVE, down, c[0] + share * w[0] * i / 10f, c[1]); SystemClock.sleep(12); }
        if (abandoned) { for (int i = 0; i < 12; i++) { send(MotionEvent.ACTION_MOVE, down, c[0] + share * w[0], c[1]); SystemClock.sleep(25); } }
        send(MotionEvent.ACTION_UP, down, c[0] + share * w[0], c[1]);
        settle();
    }
    /** A finger moving down across the tile and lifting on it: a scroll or nothing, never a tap. */
    private void verticalPass() throws Exception {
        float[] c = centre(); float density = getTargetContext().getResources().getDisplayMetrics().density;
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, down, c[0], c[1] - 10 * density);
        for (int i = 1; i <= 6; i++) { send(MotionEvent.ACTION_MOVE, down, c[0] + i * density, c[1] - 10 * density + i * 4 * density); SystemClock.sleep(16); }
        send(MotionEvent.ACTION_UP, down, c[0] + 6 * density, c[1] + 14 * density);
        settle();
    }
    private void send(int action, long down, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        try { sendPointerSync(e); } finally { e.recycle(); }
    }
    private void reveal() throws Exception {
        main(() -> {
            View view = tile(), parent = view;
            while (parent != null && !(parent instanceof android.widget.ScrollView)) parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
            if (parent == null) return;
            Rect r = new Rect(); view.getDrawingRect(r); ((ViewGroup) parent).offsetDescendantRectToMyCoords(view, r);
            parent.scrollTo(0, Math.max(0, r.bottom - parent.getHeight() + activity.ui().dp(24)));
        });
        settle();
    }

    // ---- Tile state -------------------------------------------------------------------------------------------

    private Object screen() { return field(activity, "screen"); }
    private View hero() { return (View) field(screen(), "chargingHero"); }
    private View tile() { return (View) field(hero(), "timeTile"); }
    private View power() { return (View) ((TextView) field(hero(), "power")).getParent(); }
    private View face(int r) { return ((View[]) field(tile(), "faces"))[r]; }
    private TextView caption(int r) { return ((TextView[]) field(tile(), "captions"))[r]; }
    private TextView value(int r) { return ((TextView[]) field(tile(), "values"))[r]; }
    private TextView tags(int r) { return ((TextView[]) field(tile(), "tags"))[r]; }
    private boolean two() { return Boolean.TRUE.equals(field(tile(), "two")); }
    private int state(String name) { return (Integer) field(field(tile(), "state"), name); }
    private boolean atRest() { return Boolean.TRUE.equals(invoke(field(tile(), "state"), "atRest")); }
    private int shownReading() { return two() ? state("resting") : FULL; }
    private int readingNow() { int[] r = new int[1]; main(() -> r[0] = shownReading()); return r[0]; }
    private static int other(int r) { return r == FULL ? LIMIT : FULL; }
    private static int ChargeTimeReadingsPref(String stored) { return "full".equals(stored) ? FULL : LIMIT; }
    /** At rest: no animator, exactly the shown face drawn, whole and in place; the other hidden. */
    private boolean cleanRest() {
        if (!atRest() || field(tile(), "motion") != null) return false;
        int shown = shownReading();
        View on = face(shown), off = face(other(shown));
        boolean otherHidden = two() ? off.getVisibility() == View.INVISIBLE : off.getVisibility() == View.GONE;
        return on.getVisibility() == View.VISIBLE && on.getAlpha() == 1f && on.getTranslationX() == 0f && otherHidden;
    }
    private void awaitRest() { await(this::cleanRest, "The tile did not come to rest"); }
    private int[] boxes() {
        int[] out = new int[6];
        main(() -> {
            Rect t = TextFit.relativeBounds(tile(), root()), p = TextFit.relativeBounds(power(), root());
            out[0] = t.left; out[1] = t.top; out[2] = t.height(); out[3] = p.left; out[4] = p.top; out[5] = p.height();
        });
        return out;
    }
    private String pref(String profile) { return settings().getString("charge_time_reading_" + profile, null); }
    private static RectF inflate(RectF r, float by) { return new RectF(r.left - by, r.top - by, r.right + by, r.bottom + by); }

    // ---- Plumbing ---------------------------------------------------------------------------------------------

    private void scale(String value) throws Exception {
        shell("settings put global animator_duration_scale " + value);
        float target = Float.parseFloat(value);
        await(() -> android.animation.ValueAnimator.getDurationScale() == target, "Animator scale did not reach " + value);
    }
    private String shell(String command) throws Exception {
        ParcelFileDescriptor pipe = getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe)) {
            StringBuilder out = new StringBuilder(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) >= 0) out.append(new String(buffer, 0, n, "UTF-8"));
            return out.toString();
        }
    }
    private Bitmap screencap() throws Exception {
        ParcelFileDescriptor pipe = getUiAutomation().executeShellCommand("screencap -p");
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe)) {
            Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(in);
            require(bitmap != null, "screencap returned no image");
            return bitmap;
        }
    }
    private void saveCrop(Bitmap frame, Rect box, String name) throws Exception {
        File file = new File(dir(), name + ".png"); require(file.getParentFile().isDirectory() || file.getParentFile().mkdirs(), "Frame directory missing");
        Rect clip = new Rect(box); clip.inset(-activity.ui().dp(8), -activity.ui().dp(8));
        require(clip.intersect(0, 0, frame.getWidth(), frame.getHeight()), "Tile is off screen");
        Bitmap part = Bitmap.createBitmap(frame, clip.left, clip.top, clip.width(), clip.height());
        try (FileOutputStream out = new FileOutputStream(file)) { part.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { part.recycle(); frame.recycle(); }
        TextCensus.writeView(this, file, tile());  // the crop shows the tile: census its text as drawn (both faces mid-switch)
    }
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "charge-time-tile-validation"); d.mkdirs(); return d; }
    private void shot(String name) throws Exception { settle(); SystemClock.sleep(500); waitForIdleSync(); screenshot(name); }
    private void screenshot(String name) throws Exception {
        File file = new File(dir(), name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
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
    private interface Call<T> { T get(); }
    private String callString(Call<String> call) { AtomicReference<String> out = new AtomicReference<>(); main(() -> out.set(call.get())); return out.get(); }
    private Rect callRect(Call<Rect> call) { AtomicReference<Rect> out = new AtomicReference<>(); main(() -> out.set(call.get())); return out.get(); }
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
    private Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
    private Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Class<?> type = target.getClass(); Method method = null;
            while (type != null && method == null) { try { method = type.getDeclaredMethod(name, types); } catch (NoSuchMethodException missing) { type = type.getSuperclass(); } }
            if (method == null) throw new NoSuchMethodException(name);
            method.setAccessible(true); return method.invoke(target, values); }
        catch (java.lang.reflect.InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
}
