package com.jjricks.volt.tests;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Instrumentation;
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
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.HubPages;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;

/**
 * Disposable emulator only; no scooter, no radio, nothing sent. Battery &amp; charging's way into Charge history &amp;
 * recordings and its invite to record charges (owner, Nov 17 2026):
 * <ul>
 *   <li>the page shows a Charge history &amp; recordings row of its own, and a rider's tap on it opens that page; the
 *   HISTORY card's bar graph still opens it too;</li>
 *   <li>the banner ("Help improve your charge time estimate") shows at the top of the page with a scooter chosen, and a
 *   tap opens its sheet: the five points (record, any charge helps, 100% helps most, phone nearby, Email to Volt), the
 *   Record charges automatically switch (it writes the same preference as Charge history &amp; recordings'), Record this
 *   charge (with no scooter connected it opens Charge history &amp; recordings with its Record this charge row on screen,
 *   and starts nothing), Charge history &amp; recordings, and Not now (closes the sheet; the banner stays);</li>
 *   <li>the banner's X hides it with motion and stores the answer, and it stays hidden in a new activity; the
 *   "sent" answer Email to Volt stores hides it too; with no answer it is back;</li>
 *   <li>no cut or mid-word-broken text on the banner, the row or the sheet.</li>
 * </ul>
 * Screenshots (-e size_label, -e theme): the page with the banner, the new row, the sheet, and the page after the X. The
 * row and the bar graph are checked on every app; an app without the invite (no hub_charge_invite_title string: built
 * before its strings, or a release sweep's base app) gets the invite's screenshot names of the Battery page and passes.
 */
public final class ChargeInviteValidation extends Instrumentation {
    private static final String KEY = "charge_invite", BANNER = "battery-charge-invite", HIDE = "battery-charge-invite-hide",
            SHEET = "charge-invite-sheet", RECORD = "charge-invite-record", AUTO = "charge-invite-auto", OPEN = "charge-invite-open",
            NOT_NOW = "charge-invite-not-now", CARD = "battery-history-card", LINK = "hub-link-" + HubPages.BATTERY_HISTORY;
    private MainActivity activity;
    private Bundle args;
    private int assertions;
    private String label;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            String theme = args.getString("theme", "light");
            label = args.getString("size_label", getTargetContext().getResources().getConfiguration().screenWidthDp + "") + "-" + theme;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putString("theme", theme).putFloat("text_scale", Float.parseFloat(args.getString("text_scale", "1"))).commit(), "Could not seed preferences");
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic invite scooter", "000000000000", 0);
                p.serial = "X2538-SYNTHETIC"; profiles.update(p); profiles.select(p.id);
                OnboardingScreen.markDone(getTargetContext());
            });
            launch();
            if (!rowBuilt()) baseApp(true);
            else if (!inviteBuilt()) { row(); barGraphStillOpens(); baseApp(false); }
            else {
                row();
                barGraphStillOpens();
                banner();
                sheet();
                dismissed();
                sentAndBack();
            }
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            failure.printStackTrace();
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> activity.finish()); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** The page opens with the banner at its top. */
    private void banner() throws Exception {
        battery();
        main(() -> {
            require(!settings().contains(KEY), "The invite has an answer before anyone gave one");
            View banner = HubTestPages.shownTag(activity, BANNER);
            textProblems(banner);
            String words = texts(banner);
            require(words.contains("Help improve your charge time estimate") && words.contains("Record a few charges and send them to Volt."),
                    "The banner's words: " + words);
            View hide = banner.findViewWithTag(HIDE);
            require(hide != null && hide.isShown() && "Hide this suggestion".contentEquals(hide.getContentDescription())
                    && hide.getWidth() >= dp(48) - 1 && hide.getHeight() >= dp(48) - 1, "The banner's X: shown, named and 48 dp to touch");
            // At the top: above the CHARGING rows and the HISTORY card.
            View card = HubTestPages.shownTag(activity, CARD);
            require(top(banner) < top(card), "The banner is not above the HISTORY card");
        });
        shot("battery-banner-" + label);
        checks.add("Battery & charging: the invite banner at the top of the page, its words whole, its X named and 48 dp");
    }

    /** The page's Charge history & recordings row; a rider's tap on it opens the page. */
    private void row() throws Exception {
        battery();
        View[] row = {null};
        main(() -> {
            row[0] = HubTestPages.shownTag(activity, LINK);
            require(LINK.equals(row[0].getTag()), "Not the link row");
            require(texts(row[0]).contains("Charge history & recordings"), "The row's name: " + texts(row[0]));
            textProblems(row[0]);
            reveal(row[0]);
        });
        shot("battery-row-" + label);
        HubTestPages.tapRow(this, activity, HubPages.BATTERY_HISTORY);
        main(() -> require(HubPages.BATTERY_HISTORY.equals(HubTestPages.current(activity)), "The row did not open Charge history & recordings"));
        HubTestPages.back(this, activity);
        checks.add("Battery & charging: a Charge history & recordings row of its own; a rider's tap on it opens the page");
    }

    /** The HISTORY card (the bar graph) still opens Charge history & recordings. */
    private void barGraphStillOpens() throws Exception {
        battery();
        View[] card = {null};
        main(() -> card[0] = HubTestPages.shownTag(activity, CARD));
        HubTestPages.tapView(this, activity, card[0], "the HISTORY card");
        await(() -> HubPages.BATTERY_HISTORY.equals(HubTestPages.current(activity)), "The HISTORY card's tap did not open Charge history & recordings");
        HubTestPages.back(this, activity);
        checks.add("The HISTORY card's bar graph still opens Charge history & recordings");
    }

    /** The sheet: its points, its switch, Not now, Charge history & recordings and Record this charge. */
    private void sheet() throws Exception {
        openSheet();
        main(() -> {
            View sheet = sheetView();
            String words = texts(sheet);
            for (String part : new String[]{"Help improve your charge time estimate", "Record this charge", "Record charges automatically",
                    "doesn't have to be perfect", "100%", "near the scooter", "Email to Volt", "Charge history & recordings", "Not now"})
                require(words.contains(part), "The sheet does not say \"" + part + "\": " + words);
            textProblems(sheet);
        });
        shot("sheet-" + label);
        // Scrolled to its end too, where the buttons are on a short window.
        main(() -> { View holder = sheetView().getRootView().findViewWithTag(SHEET); View scroll = holder; while (scroll != null && !(scroll instanceof ScrollView)) scroll = scroll.getParent() instanceof View ? (View) scroll.getParent() : null; if (scroll != null) ((ScrollView) scroll).fullScroll(View.FOCUS_DOWN); });
        shot("sheet-end-" + label);
        // The switch is Record charges automatically's own preference, on and off.
        main(() -> { Switch toggle = switchIn(sheetView().findViewWithTag(AUTO)); require(toggle != null && !toggle.isChecked(), "The switch is not off by default"); toggle.performClick(); });
        settle();
        require(settings().getBoolean("charge_auto_record", false), "The sheet's switch did not turn Record charges automatically on");
        main(() -> switchIn(sheetView().findViewWithTag(AUTO)).performClick()); settle();
        require(!settings().getBoolean("charge_auto_record", true), "The sheet's switch did not turn it off again");
        checks.add("The sheet: five points in plain words; its switch is Record charges automatically's own preference, on and off");
        // Not now: the sheet closes, the banner stays, nothing is answered.
        main(() -> sheetView().findViewWithTag(NOT_NOW).performClick()); settle();
        await(() -> sheetDialog() == null, "Not now did not close the sheet");
        main(() -> require(shownOnPage(BANNER) && !settings().contains(KEY), "Not now hid the banner or answered it"));
        checks.add("Not now closes the sheet and keeps the banner");
        // Charge history & recordings.
        openSheet();
        main(() -> sheetView().findViewWithTag(OPEN).performClick()); settle();
        await(() -> sheetDialog() == null && HubPages.BATTERY_HISTORY.equals(HubTestPages.current(activity)), "The sheet's Charge history & recordings did not open the page");
        HubTestPages.back(this, activity);
        checks.add("The sheet's Charge history & recordings opens the page");
        // Record this charge, no scooter connected: the page opens with its Record this charge row on screen; nothing starts.
        openSheet();
        main(() -> sheetView().findViewWithTag(RECORD).performClick()); settle();
        await(() -> sheetDialog() == null && HubPages.BATTERY_HISTORY.equals(HubTestPages.current(activity)), "Record this charge did not open Charge history & recordings");
        SystemClock.sleep(500); settle();
        main(() -> {
            View row = HubTestPages.shownTag(activity, "charge-capture-action");
            ScrollView scroll = scroll(row);
            require(scroll != null, "The Record this charge row is not in the page's scroll");
            Rect seen = new Rect();
            require(row.getGlobalVisibleRect(seen) && seen.height() >= row.getHeight() - 1, "Record this charge did not bring its row fully on screen");
            require(!activity.service().isChargeRecording(), "A recording started with no scooter connected");
            require(!anyDialogWith("charge-confirmation-selector"), "The charger question opened with no recording possible");
            View status = HubTestPages.shownTag(activity, "charge-capture-status");
            require(((TextView) status).getText().length() > 0, "The row's status line does not say why it can't record");
        });
        shot("record-row-" + label);
        HubTestPages.back(this, activity);
        checks.add("Record this charge, no scooter connected: Charge history & recordings opens with its Record this charge row on screen and its reason under it; nothing starts");
    }

    /** The X: the banner goes with motion, the answer is stored, and a new activity keeps it hidden. */
    private void dismissed() throws Exception {
        battery();
        View[] hide = {null};
        main(() -> hide[0] = HubTestPages.shownTag(activity, HIDE));
        HubTestPages.tapView(this, activity, hide[0], "the banner's X");
        await(() -> !shownOnPage(BANNER), "The X did not hide the banner");
        require("dismissed".equals(settings().getString(KEY, "")), "The X did not store its answer");
        shot("battery-dismissed-" + label);
        relaunch();
        battery();
        main(() -> require(!shownOnPage(BANNER), "The banner came back in a new activity after its X"));
        main(() -> HubTestPages.shownTag(activity, LINK));
        checks.add("The X hides the banner and stores it; a new activity keeps it hidden; the row stays");
    }

    /** Email to Volt's answer hides it too; with no answer, it is back. */
    private void sentAndBack() throws Exception {
        require(settings().edit().putString(KEY, "sent").commit(), "Could not store sent");
        relaunch(); battery();
        main(() -> require(!shownOnPage(BANNER), "The banner shows after the recordings were sent"));
        require(settings().edit().putString(KEY, "later").commit(), "Could not store an unknown word");
        relaunch(); battery();
        main(() -> require(shownOnPage(BANNER), "An unknown stored word hid the banner"));
        require(settings().edit().remove(KEY).commit(), "Could not clear the answer");
        checks.add("Recordings sent: hidden; an unknown stored word: shown");
    }

    /** An app without the invite (no hub_charge_invite_title string): the same screenshots of the Battery page, and a pass. */
    private void baseApp(boolean withRow) throws Exception {
        battery();
        if (withRow) shot("battery-row-" + label);
        for (String name : new String[]{"battery-banner-", "sheet-", "sheet-end-", "record-row-", "battery-dismissed-"}) shot(name + label);
        checks.add(withRow ? "This app predates the Charge history & recordings row (a release sweep's base app); the Battery page is captured without it"
                : "This app has no charge recordings invite yet (its strings ship in their own pull request); the Battery page is captured without it");
    }

    // ===== Helpers =====

    /** Whether the app has the row: its HISTORY card has a tag of its own since the row took "hub-link-" (a base app's card has that tag). */
    private boolean rowBuilt() {
        battery();
        boolean[] built = {false};
        main(() -> { Object screen = field(activity, "screen"); View page = screen instanceof com.jjricks.volt.AppScreen ? ((com.jjricks.volt.AppScreen) screen).view() : null; built[0] = page != null && page.findViewWithTag(CARD) != null; });
        return built[0];
    }
    private boolean inviteBuilt() {
        Context app = getTargetContext();
        return app.getResources().getIdentifier("hub_charge_invite_title", "string", app.getPackageName()) != 0;
    }
    private void launch() {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root() != null && root().getWidth() > 0, "App did not launch");
    }
    private void relaunch() {
        MainActivity old = activity;
        main(old::finish);
        await(old::isDestroyed, "The activity did not finish");
        launch();
    }
    private void battery() {
        main(() -> HubTestPages.open(activity, HubPages.HOME)); settle();
        HubTestPages.reach(this, activity, HubPages.BATTERY); settle();
    }
    private void openSheet() throws Exception {
        battery();
        View[] banner = {null};
        main(() -> banner[0] = HubTestPages.shownTag(activity, BANNER));
        HubTestPages.tapView(this, activity, banner[0], "the invite banner");
        await(() -> sheetDialog() != null, "The banner did not open its sheet");
        SystemClock.sleep(500); settle();
    }
    /** The invite sheet the page holds while it shows, else null. Main thread. */
    private AlertDialog sheetDialog() {
        Object controls = HubTestPages.part(field(activity, "screen"), "ControlsScreen");
        if (controls == null) return null;
        AlertDialog d = (AlertDialog) field(controls, "inviteSheet");
        return d != null && d.isShowing() ? d : null;
    }
    private View sheetView() {
        AlertDialog d = sheetDialog(); require(d != null, "The invite sheet is not open");
        View v = d.getWindow().getDecorView().findViewWithTag(SHEET); require(v != null, "Not the invite sheet"); return v;
    }
    /** Whether any of Volt's dialogs on screen holds a view with this tag (OwnDialogs, by reflection). Main thread. */
    @SuppressWarnings("unchecked")
    private boolean anyDialogWith(String tag) {
        try {
            Class<?> own = Class.forName("com.jjricks.volt.OwnDialogs", true, activity.getClassLoader());
            Field list = own.getDeclaredField("DIALOGS"); list.setAccessible(true);
            for (java.lang.ref.WeakReference<android.app.Dialog> ref : (ArrayList<java.lang.ref.WeakReference<android.app.Dialog>>) list.get(null)) {
                android.app.Dialog d = ref.get();
                if (d != null && d.isShowing() && d.getWindow() != null && d.getWindow().getDecorView().findViewWithTag(tag) != null) return true;
            }
            return false;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("OwnDialogs", e); }
    }
    private Switch switchIn(View view) {
        if (view instanceof Switch) return (Switch) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { Switch s = switchIn(((ViewGroup) view).getChildAt(i)); if (s != null) return s; }
        return null;
    }
    private boolean shownOnPage(String tag) {
        Object screen = field(activity, "screen");
        View page = screen instanceof com.jjricks.volt.AppScreen ? ((com.jjricks.volt.AppScreen) screen).view() : null;
        View v = page == null ? null : page.findViewWithTag(tag);
        return v != null && v.isShown();
    }
    private int top(View view) { int[] at = new int[2]; view.getLocationOnScreen(at); return at[1]; }
    private int dp(int value) { return Math.round(value * getTargetContext().getResources().getDisplayMetrics().density); }
    private ScrollView scroll(View view) {
        View parent = view; while (parent != null && !(parent instanceof ScrollView)) parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        return (ScrollView) parent;
    }
    private void reveal(View view) {
        ScrollView parent = scroll(view); if (parent == null) return;
        Rect r = new Rect(); view.getDrawingRect(r); parent.offsetDescendantRectToMyCoords(view, r); parent.scrollTo(0, Math.max(0, r.top - parent.getHeight() / 3));
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
    private String texts(View view) {
        StringBuilder out = new StringBuilder(); ArrayDeque<View> stack = new ArrayDeque<>(); stack.push(view);
        while (!stack.isEmpty()) { View v = stack.pop(); if (v.getVisibility() != View.VISIBLE) continue; if (v instanceof TextView) out.append(((TextView) v).getText()).append(' ');
            if (v.getContentDescription() != null) out.append(v.getContentDescription()).append(' ');
            if (v instanceof ViewGroup) for (int i = ((ViewGroup) v).getChildCount() - 1; i >= 0; i--) stack.push(((ViewGroup) v).getChildAt(i)); }
        return out.toString();
    }
    private void admit() {
        String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hw.contains("ranchu") || hw.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical phone");
        for (String name : new String[]{"app_settings", "profiles"}) require(prefs(name).getAll().isEmpty(), "Refusing existing preferences");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant emulator permissions first: " + permission);
    }
    private void shot(String name) throws Exception { settle(); SystemClock.sleep(900); waitForIdleSync(); screenshot(name); }
    private void screenshot(String name) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "charge-invite-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory missing");
        File file = new File(dir, name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View) field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(160); waitForIdleSync(); }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 10000;
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
