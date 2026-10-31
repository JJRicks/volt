package com.jjricks.volt.tests;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.*;

/**
 * Disposable empty emulator, radio disabled. Battery alert rows and sheet (model gating offline and on the
 * simulator), then delivery through the service's own notices and alarm, fed by a policy stepped with
 * synthetic readings. Sends no scooter command. Mode notifications-off runs without POST_NOTIFICATIONS.
 */
public final class ChargeAlertValidation extends Instrumentation {
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private boolean admitted, notificationsOff;
    private String ut5, gt3;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            notificationsOff = "notifications-off".equals(args.getString("mode", ""));
            admit(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                    .putString("theme", args.getString("theme", "dark")).putFloat("text_scale", Float.parseFloat(args.getString("text_scale", "1"))).commit(), "Could not seed preferences");
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile a = profiles.create("Synthetic UT5 MAX", "000000000000", 0); a.serial = "X2538-SYNTHETIC"; profiles.update(a); ut5 = a.id;
                ScooterProfiles.Profile b = profiles.create("Synthetic GT3 Max", "000000000001", 0); b.serial = "Q2345ZZ9ES7777777";
                b.advertisedPid = "24182"; b.advertisedPidWall = System.currentTimeMillis(); profiles.update(b); gt3 = b.id;
                profiles.select(ut5); OnboardingScreen.markDone(getTargetContext());
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && root().getWidth() > 0, "App did not launch"); service = activity.service();
            if (notificationsOff) verifyNotificationsOff();
            else {
                verifyOfflineGating();
                verifySimulatorGating();
                verifySheet();
                verifyWatchNotice();
                verifyPausedNotice();
                verifyAlarmOutOfSight(true);
                verifyAlarmOutOfSight(false);
                verifyInSight();
            }
            assertNoRadio();
            for (String key : settings().getAll().keySet()) require(!key.equals("charge_alert_state_" + ut5), "The UT5 MAX tracked a charge session: " + key);
            checks.add("A scooter with a native charge limit never tracks or saves a charge alert session");
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", failure.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (admitted && activity != null) try { main(() -> { if (service != null) { service.stopChargeAlarm(); service.setSimulation(false); } activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void verifyOfflineGating() throws Exception {
        controls();
        main(() -> require(!shown("charge-alert-charge-row") && !shown("charge-alert-low-row") && !service.chargeAlertsOffered(), "The UT5 MAX offline shows battery alerts"));
        choose(gt3); controls();
        main(() -> {
            require(service.chargeAlertsOffered() && shown("charge-alert-charge-row") && shown("charge-alert-low-row"), "The GT3 Max offline hides battery alerts");
            require("Off".contentEquals(value("charge-alert-charge-value")) && "Off".contentEquals(value("charge-alert-low-value")), "Alerts are not off by default");
            View row = root().findViewWithTag("charge-alert-charge-row"); reveal(row);
        });
        shot("rows-off");
        checks.add("Offline, the saved model decides: the UT5 MAX (charge limit) has no alert rows, the GT3 Max shows both, off by default");
    }

    private void verifySimulatorGating() throws Exception {
        choose(ut5);
        main(() -> require(service.setSimulation(true), "Simulation did not start"));
        String[][] models = {{"", "0"}, {"24431", "0"}, {"39991", "1"}};
        for (String[] model : models) {
            main(() -> { service.ble.setSimulationModel(model[0]); service.ble.setSimulationCharging(true); });
            controls();
            boolean expected = model[1].equals("1");
            main(() -> require(shown("charge-alert-charge-row") == expected && service.chargeAlertsOffered() == expected,
                    "Simulated model " + (model[0].isEmpty() ? "UT5 MAX" : model[0]) + (expected ? " hides" : " shows") + " battery alerts while charging"));
            if (expected) { main(() -> reveal(root().findViewWithTag("charge-alert-charge-row"))); shot("simulated-unknown-charging"); }
        }
        main(() -> require(!service.chargeAlertWatching(), "Sample readings armed a watch"));
        main(() -> { service.ble.setSimulationModel(""); require(service.setSimulation(false), "Simulation did not stop"); });
        checks.add("On the simulator, rows follow the model while charging: hidden for the UT5 MAX and a charge-limit model, shown for an unknown one; sample readings never watch");
    }

    private void verifySheet() throws Exception {
        choose(gt3); controls();
        main(() -> ((View) root().findViewWithTag("charge-alert-charge-row")).performClick()); settle();
        main(() -> {
            View sheet = sheet();
            require(texts(sheet).contains("Battery alerts") && texts(sheet).contains("Alerts stay on for every charge until you turn them off"), "Sheet heading missing: " + texts(sheet));
            for (String tag : new String[]{"charge-alert-charge", "charge-alert-low", "charge-alert-notify", "charge-alert-alarm"})
                require(!((Switch) sheet.findViewWithTag(tag)).isChecked(), tag + " is on by default");
            require(!sheet.findViewWithTag("charge-alert-charge-level").isShown() && !sheet.findViewWithTag("charge-alert-low-level").isShown(), "Levels show while alerts are off");
            require(!sheet.findViewWithTag("charge-alert-permission").isShown(), "Permission banner shows with notifications allowed");
            require(texts(sheet).contains("No restrictions") && texts(sheet).contains("up to 12 hours"), "Background and battery-saver hints missing");
            ((View) ((View) sheet.findViewWithTag("charge-alert-charge")).getParent()).performClick();
        }); settle();
        main(() -> {
            View sheet = sheet();
            require(((Switch) sheet.findViewWithTag("charge-alert-charge")).isChecked() && sheet.findViewWithTag("charge-alert-charge-level").isShown(), "Charge alert did not turn on with its level");
            require(sheet.findViewWithTag("charge-alert-delivery-note").isShown() && texts(sheet).contains("Turn on Notify me or Alarm sound to get alerts."), "No delivery hint without a delivery");
            require(texts(sheet).contains("80%"), "Default level is not 80%");
            require(service.chargeAlerts().charge && service.chargeAlerts().chargeAt == 80 && !service.chargeAlerts().notify && !service.chargeAlerts().alarm, "Charge alert was not saved alone");
        });
        shot("sheet-no-delivery");
        main(() -> ((Switch) sheet().findViewWithTag("charge-alert-notify")).performClick()); settle();
        main(() -> require(!sheet().findViewWithTag("charge-alert-delivery-note").isShown() && service.chargeAlerts().notify, "Notify me did not save or clear the hint"));
        tapEnd("charge-alert-charge-level", true);
        await(() -> service.chargeAlerts().chargeAt == 100, "Dragging to the end did not choose Full");
        main(() -> {
            View sheet = sheet();
            ((Switch) sheet.findViewWithTag("charge-alert-low")).performClick();
            ((Switch) sheet.findViewWithTag("charge-alert-alarm")).performClick();
        }); settle();
        main(() -> {
            View sheet = sheet();ChargeAlertPolicy.Settings a = service.chargeAlerts();
            require(a.charge && a.chargeAt == 100 && a.low && a.lowBelow == 20 && a.notify && a.alarm, "Sheet choices did not save: " + a.encode());
            require(texts(sheet).contains("Full") && texts(sheet).contains("Below 20%") && sheet.findViewWithTag("charge-alert-low-level").isShown(), "Levels not shown as Full / Below 20%");
            require(("charge=1;at=100;low=1;below=20;notify=1;alarm=1").equals(settings().getString("charge_alerts_" + gt3, "")), "Per-scooter preference not stored");
            require(!settings().contains("charge_alerts_" + ut5), "Another scooter received these alerts");
        });
        shot("sheet-configured");
        main(() -> ((AlertDialog) field(activity, "controlDialog")).dismiss()); settle();
        main(() -> {
            require("Full".contentEquals(value("charge-alert-charge-value")) && "Below 20%".contentEquals(value("charge-alert-low-value")), "Rows don't show the saved levels");
            reveal(root().findViewWithTag("charge-alert-low-row"));
        });
        shot("rows-configured");
        tapEnd("charge-alert-charge-row", false);
        checks.add("The sheet saves each choice at once per scooter, defaults to 80% and 20% with everything off, asks for a delivery, and the rows show Full / Below 20%");
        main(() -> { AlertDialog d = (AlertDialog) field(activity, "controlDialog"); if (d != null) d.dismiss(); });settle();
    }

    /** The watch notice: same id and channel, the live level, a 0–target bar, Stop watching; the hold keeps BLE out of recording. */
    private void verifyWatchNotice() {
        main(() -> {
            ChargeAlertPolicy policy = watched(new ChargeAlertPolicy.Settings(true, 80, false, 20, true, false), 42);
            require((Boolean) invoke(service, "chargeAlertHold") && service.workAllowed(), "A watched charge does not hold the connection");
            Notification n = (Notification) invoke(service, "notification");
            require("Charging to 80%".equals(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)))
                    && "42% now · alert at 80%".equals(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT))), "Watch notice wording: " + n.extras);
            require(n.extras.getInt(Notification.EXTRA_PROGRESS_MAX) == 80 && n.extras.getInt(Notification.EXTRA_PROGRESS) == 42 && !n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
                    "Watch notice progress is not 42 of 80");
            require("scooter".equals(n.getChannelId()) && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0 && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0, "Watch notice is not the quiet ongoing connection notice");
            require(n.actions != null && n.actions.length == 1 && "Stop watching".contentEquals(n.actions[0].title), "Watch notice lacks Stop watching");
            require((Long) invoke(service, "chargeAlertDeadline") == policy.watchDeadline() && policy.watchDeadline() - SystemClock.elapsedRealtime() <= ChargeAlertPolicy.HOLD_MS, "The hold is not bounded by 12 hours");
            policy.step(SystemClock.elapsedRealtime(), System.currentTimeMillis(), true, true, ChargeAlertPolicy.Link.RECONNECTING, null, 0, null, 0);
            n = (Notification) invoke(service, "notification");
            require("Last seen 42% · alert at 80%".equals(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT))), "A stale level passes for live: " + n.extras.getCharSequence(Notification.EXTRA_TEXT));
            // BLE: the watch keeps the link and battery reads without the recording flag.
            BleController ble = service.ble; long until = SystemClock.elapsedRealtime() + 60_000;
            ble.watchBattery(until); ble.visibility(false, false, 0);
            require((Boolean) invoke(ble, "workAllowed") && !(Boolean) field(ble, "recording"), "BLE watch is not allowed work, or counts as recording");
            ble.watchBattery(0);
            require(!(Boolean) invoke(ble, "workAllowed"), "BLE work outlives the watch");
            ble.visibility(true, false, 0);
            reset();
        });
        checks.add("The watch notice is the quiet connection notice: Charging to 80%, 42% now · alert at 80% on a 0–80 bar, Last seen when stale, Stop watching; the hold is bounded at 12 h and never counts as a BLE recording");
    }

    private void verifyPausedNotice() {
        main(() -> {
            watched(new ChargeAlertPolicy.Settings(true, 80, false, 20, true, false), 50);
            ScooterService.setUiVisible(false); // out of sight: the next step sees no connection and pauses
        });
        await(() -> active(77) != null, "No paused notice after losing the connection");
        main(() -> {
            StatusBarNotification paused = active(77);
            require("Charge alert paused".equals(String.valueOf(paused.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE)))
                    && "Volt lost the connection to Synthetic GT3 Max. Open Volt to keep watching this charge.".equals(String.valueOf(paused.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT))),
                    "Paused notice wording");
            require(!(Boolean) invoke(service, "chargeAlertHold"), "A paused watch still holds");
            ScooterService.setUiVisible(true);
        });
        await(() -> active(77) == null, "Opening Volt did not clear the paused notice");
        main(this::reset);
        checks.add("Losing the connection out of sight posts one Charge alert paused notice, releases the hold, and opening Volt clears it");
    }

    /** keep: Notify me on (the notice stays after the alarm); off: alarm only (a minimal notice carrying Stop). */
    private void verifyAlarmOutOfSight(boolean keep) throws Exception {
        AtomicReference<Notification> posted = new AtomicReference<>();
        main(() -> {
            // Out of sight first, so the injected watch isn't stepped against the absent connection and paused.
            ScooterService.setUiVisible(false);
            ChargeAlertPolicy policy = watched(new ChargeAlertPolicy.Settings(true, 80, false, 20, keep, true), 79);
            long now = SystemClock.elapsedRealtime();
            ChargeAlertPolicy.Event event = policy.step(now, System.currentTimeMillis(), true, true, ChargeAlertPolicy.Link.CONNECTED, 1d, now, 81d, now);
            require(event == ChargeAlertPolicy.Event.CHARGE, "Policy did not fire at 81%");
            invoke(service, "deliverChargeAlert", new Class<?>[]{ChargeAlertPolicy.Event.class, ScooterProfiles.Profile.class}, event, service.profiles.active());
            invoke(service, "updateChargeAlerts");
            require(service.chargeAlarmPlaying(), "The alarm did not start");
            require(service.workAllowed() && service.backgroundDeadlineMs() == 0, "The sounding alarm does not keep Volt running");
        });
        await(() -> active(76) != null, "No alert notification");
        main(() -> {
            Notification n = active(76).getNotification(); posted.set(n);
            require("charge_alerts".equals(n.getChannelId()), "Alert is not on the Battery alerts channel");
            require("Charged to 80%".equals(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)))
                    && "Synthetic GT3 Max is at 81%.".equals(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT))), "Alert wording: " + n.extras);
            require(n.actions != null && n.actions.length == 1 && "Stop".contentEquals(n.actions[0].title) && Notification.CATEGORY_ALARM.equals(n.category), "Sounding alert lacks Stop");
            require(n.getGroupAlertBehavior() == Notification.GROUP_ALERT_SUMMARY, "The notice would sound over the alarm");
            NotificationChannel channel = getTargetContext().getSystemService(NotificationManager.class).getNotificationChannel("charge_alerts");
            require(channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_HIGH && "Battery alerts".contentEquals(channel.getName()), "Battery alerts channel importance or name");
            Notification fg = (Notification) invoke(service, "notification");
            require("Charged to 80%".equals(String.valueOf(fg.extras.getCharSequence(Notification.EXTRA_TITLE))) && fg.actions != null && "Stop".contentEquals(fg.actions[0].title), "Connection notice lacks the alarm's Stop");
            boolean alarmUsage = false;
            for (AudioPlaybackConfiguration c : getTargetContext().getSystemService(AudioManager.class).getActivePlaybackConfigurations())
                alarmUsage |= c.getAudioAttributes().getUsage() == AudioAttributes.USAGE_ALARM;
            require(alarmUsage, "No playback with USAGE_ALARM");
        });
        shot(keep ? "alarm-notify" : "alarm-only");
        posted.get().actions[0].actionIntent.send();
        await(() -> !service.chargeAlarmPlaying(), "Stop did not end the alarm");
        if (keep) {
            await(() -> active(76) != null && active(76).getNotification().actions == null, "Notify me notice was not kept without Stop");
        } else await(() -> active(76) == null, "Alarm-only notice outlived the alarm");
        // Opening Volt stops a sounding alarm too.
        main(() -> {
            invoke(service, "startChargeAlarm");
            require(service.chargeAlarmPlaying(), "Alarm did not restart");
            ScooterService.setUiVisible(true);
            require(!service.chargeAlarmPlaying(), "Opening Volt did not stop the alarm");
            getTargetContext().getSystemService(NotificationManager.class).cancel(76);
            reset();
        });
        checks.add(keep ? "Out of sight with Notify me and Alarm sound: a silent high-importance Battery alerts notice with Stop while USAGE_ALARM plays; Stop ends it and keeps the notice; opening Volt also stops the alarm"
                : "Alarm sound only: a minimal notice carrying Stop while it sounds, removed with the alarm");
    }

    private void verifyInSight() throws Exception {
        main(() -> {
            ChargeAlertPolicy policy = watched(new ChargeAlertPolicy.Settings(false, 80, true, 20, true, true), 30);
            long now = SystemClock.elapsedRealtime();
            policy.step(now, System.currentTimeMillis(), true, true, ChargeAlertPolicy.Link.CONNECTED, 0d, now, 25d, now);
            ChargeAlertPolicy.Event event = policy.step(now + 1000, System.currentTimeMillis(), true, true, ChargeAlertPolicy.Link.CONNECTED, 0d, now + 1000, 19d, now + 1000);
            require(event == ChargeAlertPolicy.Event.LOW, "Low alert did not fire at 19%");
            invoke(service, "deliverChargeAlert", new Class<?>[]{ChargeAlertPolicy.Event.class, ScooterProfiles.Profile.class}, event, service.profiles.active());
            require(service.chargeAlarmPlaying() && active(76) == null, "In sight, the alert posted a notification instead of a dialog");
        });
        await(() -> { Object d = field(activity, "chargeAlertDialog"); return d != null && ((AlertDialog) d).isShowing(); }, "No in-app alert dialog");
        main(() -> {
            AlertDialog d = (AlertDialog) field(activity, "chargeAlertDialog");String text = texts(d.getWindow().getDecorView());
            require(text.contains("Battery low") && text.contains("Synthetic GT3 Max is at 19%."), "Dialog wording: " + text);
            require("Stop alarm".contentEquals(d.getButton(AlertDialog.BUTTON_POSITIVE).getText()), "Dialog lacks Stop alarm");
        });
        shot("in-app-low");
        main(() -> ((AlertDialog) field(activity, "chargeAlertDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        await(() -> !service.chargeAlarmPlaying(), "Stop alarm did not stop it");
        main(this::reset);
        checks.add("In sight, an alert is a dialog (Battery low · Synthetic GT3 Max is at 19%.) whose Stop alarm ends the sound; no notification is posted");
    }

    private void verifyNotificationsOff() throws Exception {
        main(() -> require(!getTargetContext().getSystemService(NotificationManager.class).areNotificationsEnabled(), "Notifications are allowed in this mode"));
        choose(gt3); controls();
        main(() -> ((View) root().findViewWithTag("charge-alert-charge-row")).performClick()); settle();
        main(() -> {
            View sheet = sheet();
            ((Switch) sheet.findViewWithTag("charge-alert-charge")).performClick();((Switch) sheet.findViewWithTag("charge-alert-notify")).performClick();
        }); settle();
        main(() -> {
            View sheet = sheet();View banner = sheet.findViewWithTag("charge-alert-permission");
            require(banner.isShown() && texts(banner).contains("Notifications are off for Volt, so alerts work only while Volt is open.") && texts(banner).contains("Allow"), "No notifications-off banner: " + texts(sheet));
            reveal(banner);
        });
        shot("sheet-notifications-off");
        main(() -> ((AlertDialog) field(activity, "controlDialog")).dismiss()); settle();
        main(() -> {
            View row = root().findViewWithTag("charge-alert-charge-row");
            require(texts(row).contains("Notifications are off"), "Row does not say notifications are off: " + texts(row));
            reveal(row);
            watched(new ChargeAlertPolicy.Settings(true, 80, false, 20, true, true), 40);
            require(!(Boolean) invoke(service, "chargeAlertHold"), "Volt would hold in the background with no visible notification");
            reset();
        });
        shot("rows-notifications-off");
        checks.add("With notifications off the sheet says alerts work only while Volt is open and offers Allow; the row says so; nothing holds in the background");
    }

    /** Injects a policy for the selected scooter, stepped once with fresh synthetic charging readings. */
    private ChargeAlertPolicy watched(ChargeAlertPolicy.Settings choice, int percent) {
        ChargeAlertPolicy policy = new ChargeAlertPolicy(choice, null);long now = SystemClock.elapsedRealtime();
        policy.step(now, System.currentTimeMillis(), true, true, ChargeAlertPolicy.Link.CONNECTED, 1d, now, (double) percent, now);
        setField(service, "alertPolicy", policy); setField(service, "alertProfile", service.profiles.active().id); setField(service, "alertNoticeKey", "");
        return policy;
    }
    /** Back to the saved settings for the selected scooter. */
    private void reset() { service.stopChargeAlarm(); setField(service, "alertProfile", ""); setField(service, "alertPolicy", null); setField(service, "pendingChargeAlert", null); }
    private StatusBarNotification active(int id) {
        for (StatusBarNotification n : getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications()) if (n.getId() == id) return n;
        return null;
    }
    private void choose(String id) { main(() -> { service.profiles.select(id); service.ble.profileSelected(); }); settle(); }
    private boolean shown(String tag) { View v = root().findViewWithTag(tag); return v != null && v.isShown(); }
    private CharSequence value(String tag) { return ((TextView) root().findViewWithTag(tag)).getText(); }
    private View sheet() { AlertDialog d = (AlertDialog) field(activity, "controlDialog"); require(d != null && d.isShowing(), "Battery alerts sheet is not open"); View v = d.getWindow().getDecorView().findViewWithTag("charge-alert-sheet"); require(v != null, "Not the Battery alerts sheet"); return d.getWindow().getDecorView(); }
    /** A real tap: the far end of a slider (Full), or the middle of a row in the window it lives in. */
    private void tapEnd(String tag, boolean inSheet) throws Exception {
        int[] at = new int[2];
        main(() -> {
            View v = inSheet ? sheet().findViewWithTag(tag) : root().findViewWithTag(tag);
            require(v != null && v.isShown(), tag + " is not shown");
            v.getLocationOnScreen(at);at[0] += inSheet ? v.getWidth() - v.getPaddingRight() - 2 : v.getWidth() / 2;at[1] += v.getHeight() / 2;
        });
        long when = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(when, when, MotionEvent.ACTION_DOWN, at[0], at[1], 0), up = MotionEvent.obtain(when, when + 80, MotionEvent.ACTION_UP, at[0], at[1], 0);
        try { sendPointerSync(down); SystemClock.sleep(80); sendPointerSync(up); } finally { down.recycle(); up.recycle(); }
        settle();
        if (!inSheet) { main(() -> sheet()); }
    }
    private void controls() {
        main(() -> { settings().edit().putString("scooter_section", "").commit(); invoke(activity, "select", new Class<?>[]{String.class}, "home"); }); settle();
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "controls")); settle();
        main(() -> { if (service.simulating()) service.ble.tickSimulation(); activity.redraw(); }); settle();
    }
    private void reveal(View view) {
        View parent = view; while (parent != null && !(parent instanceof android.widget.ScrollView)) parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        if (parent == null) return; Rect r = new Rect(); view.getDrawingRect(r); ((ViewGroup) parent).offsetDescendantRectToMyCoords(view, r); parent.scrollTo(0, Math.max(0, r.top - parent.getHeight() / 3));
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
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"}) require(prefs(name).getAll().isEmpty(), "Refusing existing preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing app data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant emulator Nearby permission");
        require(notificationsOff != (getTargetContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
                notificationsOff ? "Revoke notifications for this mode" : "Grant notifications");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Disable emulator Bluetooth first");
    }
    private void assertNoRadio() {
        main(() -> {
            for (String name : new String[]{"gatt", "writer", "scanner", "mutation"}) require(field(service.ble, name) == null, "Validation touched " + name);
            require(((Collection<?>) field(service.ble, "queue")).isEmpty(), "Validation queued radio bytes");
            require(!service.isRecording() && !service.isChargeRecording(), "Alerts started a recording");
        });
    }
    private void shot(String label) throws Exception { settle(); SystemClock.sleep(900); waitForIdleSync(); screenshot(label); }
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
    private void setField(Object target, String name, Object value) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); } catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }
    private Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
    private Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, values); }
        catch (java.lang.reflect.InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "charge-alert-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory missing");
        File file = new File(dir, label + ".png"); android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
    }
}
