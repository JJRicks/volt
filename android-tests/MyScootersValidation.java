package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.BleController;
import com.jjricks.volt.ChargeSessionStore;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.MoreScreen;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * Fresh emulator only. Real menu actions and local stores; Bluetooth stays disabled.
 *   -e theme dark|light   -e text_scale 1|1.4   -e layout wide|phone (optional: the side rail and More's two
 *   columns must be shown, or the bottom bar and one column; the release sweep's 725 dp steps set it)
 */
public final class MyScootersValidation extends Instrumentation {
    private MainActivity activity;
    private ScooterProfiles.Profile a, b;
    private ActivityMonitor bluetoothPrompt;
    private int checks;
    private boolean admitted;
    private String theme = "dark";
    private float textScale = 1f;
    private String layout = "";
    private File rideSentinel;
    private final byte[] durable = "Synthetic saved ride must survive forgetting its scooter\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Override public void onCreate(Bundle args) {
        if (args != null) { theme = args.getString("theme", "dark"); textScale = Float.parseFloat(args.getString("text_scale", "1")); layout = args.getString("layout", ""); }
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            main(() -> {
                prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                        .putString("theme", theme).putFloat("text_scale", textScale).putString("last_page", "more")
                        .putString("more_section", "Scooters").commit();
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                a = profiles.create("My UT5 MAX", "000000000000", 0);
                a.mac = "C0:FF:EE:00:00:01"; a.serial = "Q2538ZZ9US7777777";
                a.lastConnected = System.currentTimeMillis() - 2 * 60 * 60 * 1000; profiles.update(a);
                b = profiles.create("Campus N65i", "000000000000", 0);
                b.mac = "C0:FF:EE:00:00:02"; b.serial = "Q2213ZZ9US8888888"; profiles.update(b);
                profiles.select(a.id); OnboardingScreen.markDone(getTargetContext());
                prefs("app_settings").edit().putString("range_source_" + b.id, "learned").commit();
                File rides = new File(getTargetContext().getFilesDir(), "rides");
                require(rides.mkdirs() || rides.isDirectory(), "Synthetic rides directory");
                rideSentinel = new File(rides, "preservation.sentinel");
                Files.write(rideSentinel.toPath(), durable);
            });
            activity = (MainActivity) startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClass(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && activity.getWindow().getDecorView().hasWindowFocus(), "Activity/service not ready");
            // Register after launch: an IntentFilter monitor may also match a null-action
            // explicit activity intent and block startActivitySync before it can return.
            bluetoothPrompt = addMonitor(new IntentFilter(BluetoothAdapter.ACTION_REQUEST_ENABLE), new ActivityResult(Activity.RESULT_CANCELED, null), true);
            openMenu();
            expectedLayout();
            visibleCards(); offlineSelectionAndConnection(); savedScannerLabels(); guards(); simulationSwitches(); forgetWithConfirmation(); onboardingCancellation();
            shizukuSetup();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", checks); result.putBoolean("physical_device_tested", false);
            if (bluetoothPrompt != null) removeMonitor(bluetoothPrompt);
            if (admitted && activity != null) try {
                main(() -> {
                    ScooterService s = activity.service();
                    if (s != null) {
                        set(s, "phoneSavingCount", 0); set(s.ble, "mutation", null);
                        require(field(s.ble, "gatt") == null && field(s.ble, "scanner") == null, "Unexpected real transport");
                        s.ble.disconnect();
                    }
                    activity.finish(); ScooterService.setUiVisible(false);
                    getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class));
                });
            } catch (Throwable error) { result.putString("result", "FAIL"); result.putString("cleanup_error", error.toString()); }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Physical device refused");
        require(getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED, "Grant emulator Nearby devices first");
        BluetoothAdapter adapter = getTargetContext().getSystemService(BluetoothManager.class).getAdapter();
        require(adapter != null && !adapter.isEnabled(), "Bluetooth must remain disabled");
        for (String name : new String[]{"profiles", "scooter", "app_settings", "onboarding", "control_evidence"})
            require(prefs(name).getAll().isEmpty(), "Existing preferences refused");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Existing data refused");
    }
    private void openMenu() {
        main(() -> { invoke(activity, "select", new Class<?>[]{String.class}, "more"); invoke(more(), "selectSection", new Class<?>[]{String.class}, "Scooters"); more().refresh(); });
        await(() -> tag("scooters-add") != null && tag("scooters-add").isShown(), "My scooters menu did not open");
    }
    /**
     * -e layout: the activity's window class matches it (Ui.wideLayout's rule). My scooters is a Settings page since part 11
     * (#392): one column at every width (Settings' sub-pages are pages, so More's menu-beside-submenu panes are gone), so
     * the page must be one column in both window classes.
     */
    private void expectedLayout() throws Exception {
        if (layout.isEmpty()) return;
        require(layout.equals("wide") || layout.equals("phone"), "layout must be wide or phone");
        final boolean wide = layout.equals("wide");
        main(() -> {
            require(Boolean.TRUE.equals(field(activity, "expanded")) == wide, "Expected the " + layout + " window layout at "
                    + activity.getResources().getConfiguration().screenWidthDp + " dp and " + textScale + "x text");
            require("settings.scooters".equals(field(activity, "selectedPage")), "My scooters is not its own Settings page");
            require(!Boolean.TRUE.equals(field(more(), "wide")) && field(more(), "menuScroll") == null && field(more(), "sectionScroll") == null,
                    "My scooters built More's two-pane layout at " + activity.getResources().getConfiguration().screenWidthDp + " dp");
        });
    }
    private void visibleCards() throws Exception {
        main(() -> {
            require(tag("scooter-card-" + a.id) != null && tag("scooter-card-" + b.id) != null, "Both same-account scooters must be listed");
            View current = tag("scooter-card-" + a.id), saved = tag("scooter-card-" + b.id);
            require(text(current, "Current").isShown() && text(current, "Offline") != null, "Current offline scooter is not clear");
            require(!text(saved, "Current").isShown() && !text(saved, "Offline").isShown(), "Other scooter status is misleading");
            require(text(current, "Connect").isShown() && !text(current, "Use this scooter").isShown(), "Current scooter lacks its connection action");
            require(text(saved, "Use this scooter").isShown() && !text(saved, "Connect").isShown(), "Other scooter should allow offline selection");
            require(text(current, "Current").getCurrentTextColor() == activity.ui().text, "Current indication should use neutral text");
            require(text(saved, "Model not identified") != null && text(saved, "Model 2213 (unconfirmed)") == null, "Unknown model card exposes a technical identifier");
            require(prefix(current, "Last connected ") != null && prefix(saved, "Last connected ") == null, "Last-connected detail invented or missing");
            require(((TextView)tag("scooters-add")).getText().toString().equals("Add a scooter"), "Primary add label changed");
            require(tag("scooters-add").getHeight() >= dp(56), "Add lost its prominent touch target");
            int[] addPosition = new int[2], listPosition = new int[2];
            tag("scooters-add").getLocationOnScreen(addPosition); tag("scooters-list").getLocationOnScreen(listPosition);
            require(addPosition[1] < listPosition[1], "Add must stay above the scooter list");
            require(text(root(), "Add with owner identity") == null && text(root(), "Run setup again") == null, "Old competing setup entries remain");
            require(tag("scooter-forget-" + a.id) == null && tag("scooter-forget-" + b.id) == null, "Forget should live inside Edit scooter");
            for (String id : new String[]{a.id, b.id}) {
                View card = tag("scooter-card-" + id);
                for (String action : new String[]{id.equals(a.id) ? "connect" : "use", "edit"}) {
                    View button = tag("scooter-" + action + "-" + id);
                    require(button.getWidth() >= dp(48) && button.getHeight() >= dp(48), "Small " + action + " target");
                    int[] p = new int[2], c = new int[2]; button.getLocationOnScreen(p); card.getLocationOnScreen(c);
                    require(p[0] >= c[0] && p[0] + button.getWidth() <= c[0] + card.getWidth(), action + " clips horizontally");
                    if (button instanceof TextView) {
                        TextView value = (TextView) button;
                        require(value.getLayout() != null, "Button text was not laid out");
                        for (int line=0;line<value.getLayout().getLineCount();line++) require(value.getLayout().getEllipsisCount(line) == 0, "Button text truncated");
                    }
                }
            }
        });
        SystemClock.sleep(600); waitForIdleSync(); // Let the outgoing Home snapshot finish its transition.
        screenshot("my-scooters-" + theme + ".png");
    }
    private void offlineSelectionAndConnection() {
        final long[] before = new long[1]; final int prompts = bluetoothPrompt.getHits();
        AtomicReference<View> staleConnect = new AtomicReference<>();
        main(() -> {
            before[0] = activity.service().ble.intentionalDisconnectSequence(); staleConnect.set(tag("scooter-connect-" + a.id));
            set(activity.service().ble, "state", BleController.State.AUTHENTICATING);
            set(activity.service().ble, "wanted", true); more().refresh();
        });
        click("scooter-use-" + b.id);
        await(() -> activity.service().profiles.active().id.equals(b.id), "Use did not select the requested scooter");
        main(() -> {
            require(activity.service().ble.intentionalDisconnectSequence() > before[0], "Switch did not end the previous transport");
            require(activity.service().ble.state() == BleController.State.DISCONNECTED, "Old transport state survived selection");
            require(Boolean.FALSE.equals(field(activity.service().ble, "wanted")), "Offline selection left a reconnect pending");
            require(bluetoothPrompt.getHits() == prompts, "Offline selection requested Bluetooth");
            staleConnect.get().performClick();
            require(activity.service().profiles.active().id.equals(b.id) && bluetoothPrompt.getHits() == prompts, "Stale Connect acted on a different current scooter");
        });
        for (String page : new String[]{"home", "rides", "stats"}) {
            main(() -> invoke(activity, "select", new Class<?>[]{String.class}, page)); waitForIdleSync();
            main(() -> require(activity.service().profiles.active().id.equals(b.id)
                    && activity.service().ble.state() == BleController.State.DISCONNECTED
                    && bluetoothPrompt.getHits() == prompts, "Saved " + page + " required a connection"));
        }
        openMenu(); click("scooter-connect-" + b.id);
        await(() -> bluetoothPrompt.getHits() > prompts, "Explicit Connect did not request Bluetooth when disabled");
        click("scooter-edit-" + a.id);
        AlertDialog rename = dialog();
        main(() -> {
            require(text(rename.getWindow().getDecorView(), "Edit scooter") != null
                    && text(rename.getWindow().getDecorView(), "Forget this scooter") != null, "Edit does not contain name and Forget controls");
            EditText name = first(rename.getWindow().getDecorView(), EditText.class); name.setText(" ");
            rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            require(rename.isShowing() && name.getError() != null && find(a.id).name.equals("My UT5 MAX"), "Empty name was accepted");
            name.setText("Home UT5 MAX"); rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        main(() -> {
            require(find(a.id).name.equals("Home UT5 MAX"), "Rename did not target the tapped scooter");
            require(find(b.id).name.equals("Campus N65i") && activity.service().profiles.active().id.equals(b.id), "Rename changed the selected other scooter");
        });
        click("scooter-use-" + a.id);
        await(() -> activity.service().profiles.active().id.equals(a.id), "Could not select original scooter");
        main(() -> { set(activity.service().ble, "state", BleController.State.AUTHENTICATING); more().refresh();
            require(((TextView)tag("scooter-connect-" + a.id)).getText().toString().equals("Cancel"), "Connecting current scooter lacks Cancel"); });
        click("scooter-connect-" + a.id);
        main(() -> require(activity.service().profiles.active().id.equals(a.id)
                && activity.service().ble.state() == BleController.State.DISCONNECTED, "Cancel changed the profile or retained the transport"));
    }
    private void savedScannerLabels() {
        main(() -> {
            BluetoothAdapter adapter = getTargetContext().getSystemService(BluetoothManager.class).getAdapter();
            ScanResult result = new ScanResult(adapter.getRemoteDevice(b.mac), null, -55, 1L);
            View row = (View) invoke(activity, "scanOption", new Class<?>[]{ScanResult.class, ScooterProfiles.Profile.class, boolean.class}, result, find(b.id), false);
            require(text(row, "Campus N65i") != null && text(row, "Saved") != null && text(row, "Yours") == null,
                    "Another saved scooter's scanner row is not recognized");
            require(row.getContentDescription().toString().contains("saved scooter in My scooters"), "Saved scanner label is inaccessible");
            ScanResult current = new ScanResult(adapter.getRemoteDevice(a.mac), null, -55, 1L);
            View currentRow = (View) invoke(activity, "scanOption", new Class<?>[]{ScanResult.class, ScooterProfiles.Profile.class, boolean.class}, current, find(a.id), false);
            require(text(currentRow, "Home UT5 MAX") != null && text(currentRow, "Yours") != null, "Current scanner row lost its saved identity");
        });
    }
    private void guards() {
        main(() -> {
            ScooterService s = activity.service(); long now = SystemClock.elapsedRealtime();
            s.rides.start(a.id, "Synthetic guard ride", System.currentTimeMillis(), now);
            blocked("ride"); require(s.isRecording(), "Blocked switch ended the ride");
            s.rides.stop(System.currentTimeMillis(), now + 1);
            ChargeSessionStore store = (ChargeSessionStore)field(s, "chargeStore");
            store.start(a.id, ChargeSessionStore.Charger.STOCK, System.currentTimeMillis(), SystemClock.elapsedRealtime());
            blocked("charge"); require(s.isChargeRecording(), "Blocked switch ended the charge"); store.stop(ChargeSessionStore.Stop.STOPPED);
            set(s, "phoneSavingCount", 1); blocked("saving"); set(s, "phoneSavingCount", 0);
            Class<?> type = Class.forName("com.jjricks.volt.BleController$Mutation");
            Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, int.class, long.class); constructor.setAccessible(true);
            set(s.ble, "mutation", constructor.newInstance("lock", 0x40, 1, 77L)); blocked("pending command"); set(s.ble, "mutation", null);
            more().refresh();
        });
    }
    private void blocked(String reason) {
        ScooterService s = activity.service(); long before = s.ble.intentionalDisconnectSequence();
        more().refresh();
        require(!((TextView)tag("scooters-notice")).getText().toString().isEmpty(), "Blocked reason not shown: " + reason);
        tag("scooters-add").performClick(); tag("scooter-use-" + b.id).performClick(); tag("scooter-connect-" + a.id).performClick();
        tag("scooter-edit-" + b.id).performClick(); AlertDialog edit = lastDialog();
        edit.getWindow().getDecorView().findViewWithTag("scooter-forget-" + b.id).performClick();
        require(edit.isShowing() && showingDialogs() == 1, "Blocked Forget opened its confirmation: " + reason);
        edit.dismiss();
        require(s.profiles.all().size() == 2 && s.profiles.active().id.equals(a.id), "Blocked action changed profiles: " + reason);
        require(field(activity, "onboarding") == null && showingDialogs() == 0, "Blocked action opened a flow: " + reason);
        require(s.ble.intentionalDisconnectSequence() == before, "Blocked action disconnected: " + reason);
    }
    private void forgetWithConfirmation() {
        final AlertDialog forget = openForget(b.id);
        main(() -> text(forget.getWindow().getDecorView(), "Cancel").performClick());
        main(() -> require(find(b.id) != null && activity.service().profiles.all().size() == 2, "Cancel forgot the scooter"));
        final AlertDialog accepted = openForget(b.id);
        // A recording beginning after the dialog opened must still block its destructive action.
        main(() -> { set(activity.service(), "phoneSavingCount", 1); text(accepted.getWindow().getDecorView(), "Forget").performClick();
            require(find(b.id) != null && accepted.isShowing(), "Forget confirmation ignored a newly busy service");
            set(activity.service(), "phoneSavingCount", 0);
            // The selection can change while the confirmation is open; decide which transport to cancel at acceptance.
            activity.service().profiles.select(b.id); activity.service().ble.profileSelected();
            set(activity.service().ble, "state", BleController.State.AUTHENTICATING);
            long before = activity.service().ble.intentionalDisconnectSequence();
            text(accepted.getWindow().getDecorView(), "Forget").performClick();
            require(activity.service().ble.intentionalDisconnectSequence() > before
                    && activity.service().ble.state() == BleController.State.DISCONNECTED, "Forget ignored a newly current target"); });
        main(() -> {
            require(find(b.id) == null && find(a.id) != null && activity.service().profiles.active().id.equals(a.id), "Forget removed or selected the wrong scooter");
            require(Arrays.equals(Files.readAllBytes(rideSentinel.toPath()), durable), "Forget changed saved ride files");
            require("learned".equals(prefs("app_settings").getString("range_source_" + b.id, "")), "Forget erased saved per-scooter preferences");
        });
    }
    private AlertDialog openForget(String id) {
        click("scooter-edit-" + id); final AlertDialog edit = dialog();
        main(() -> edit.getWindow().getDecorView().findViewWithTag("scooter-forget-" + id).performClick());
        return dialog();
    }
    private void enableSimulation() {
        main(() -> {
            prefs("app_settings").edit().putBoolean("developer_options", true).commit();
            require(activity.service().setSimulation(true) && activity.service().ble.ready(), "Simulation fixture did not connect");
        });
        await(() -> Boolean.TRUE.equals(field(activity, "displayedSimulation")), "Simulation UI did not refresh");
        openMenu();
    }
    private void simulationSwitches() {
        enableSimulation();
        click("scooter-connect-" + a.id);
        main(() -> require(activity.service().profiles.active().id.equals(a.id)
                && activity.service().ble.state() == BleController.State.DISCONNECTED,
                "Selected simulated scooter did not disconnect"));
        int prompts = bluetoothPrompt.getHits();
        click("scooter-use-" + b.id);
        await(() -> !activity.service().simulating() && activity.service().profiles.active().id.equals(b.id),
                "Switching scooter retained the old simulation");
        openMenu();
        main(() -> require(activity.service().ble.state() == BleController.State.DISCONNECTED && bluetoothPrompt.getHits() == prompts,
                "Switching scooter mistook simulation for a real connection"));
        click("scooter-use-" + a.id);
        await(() -> activity.service().profiles.active().id.equals(a.id), "Simulation switch did not restore original selection");
    }
    private void onboardingCancellation() {
        enableSimulation();
        click("scooters-add");
        await(() -> field(activity, "onboarding") != null, "Add did not enter onboarding");
        main(() -> {
            require(prefs("onboarding").getInt("step", -1) == 1, "Add did not open the path chooser");
            // Locate by route identity so copy edits do not break navigation coverage.
            View recommended = tag("onboarding-path-webadb");
            require(recommended != null && text(recommended, "Use Chrome and a USB cable") != null
                    && text(recommended, "Recommended") != null, "Add does not recommend Chrome/USB");
            android.view.accessibility.AccessibilityNodeInfo choice = recommended.createAccessibilityNodeInfo();
            try { require(choice.isCheckable() && choice.isChecked(), "Add does not default to the recommended browser route"); }
            finally { choice.recycle(); }
            require(!activity.service().simulating() && !activity.service().ble.ready()
                    && !activity.service().ble.connected(), "Add carried a simulated connection into real setup");
            View banner = tag("simulation-banner");
            require((banner == null || !banner.isShown()) && text(root(), "You're connected") == null,
                    "Real setup displays a simulated connected state");
            require(activity.service().profiles.all().size() == 1 && activity.service().profiles.active().id.equals(a.id),
                    "Entering setup from simulation changed existing profiles");
            View cancel = description(root(), "Cancel adding a scooter"); require(cancel != null, "Adding a scooter has no clear Cancel"); cancel.performClick();
        });
        await(() -> field(activity, "onboarding") == null, "Cancel did not leave onboarding");
        openMenu(); click("scooters-add");
        await(() -> field(activity, "onboarding") != null, "Second Add did not open setup");
        main(() -> {
            OnboardingScreen setup = (OnboardingScreen)field(activity, "onboarding");
            setup.back();
        });
        // Back from the Add path chooser cancels this re-run directly.
        await(() -> field(activity, "onboarding") == null, "Back did not leave adding flow");
        main(() -> require(activity.service().profiles.all().size() == 1 && activity.service().profiles.active().id.equals(a.id), "Cancelling setup altered existing scooters"));
    }

    /**
     * G7 coverage of ShizukuSetupActivity, opened through Add's real "Use Shizuku on this phone" route. The
     * emulator has no Shizuku, so the screens reachable are those that need no Shizuku service: step 1
     * (start Shizuku), the cleanup-first screen and its connection check, the busy screens with the
     * countdown ring and the stop dialog, and the import screen. The cleanup record is this test's own
     * marker with no token (nothing can be restored from it), and the busy and import screens are the
     * capture client's in-process state set directly, never a real capture: no Shizuku call reaches a
     * service, no NAVEE app is touched, and the synthetic identity is never shown or imported (Discard
     * drops it). Each screenshot waits until the setup screen is laid out, focused and the only shown
     * window of the process: MainActivity's window stays shown until it stops, and the census lists
     * every window, so a capture before that put the setup screen under dialog[1]/ in some runs.
     */
    private void shizukuSetup() throws Exception {
        require(Build.VERSION.SDK_INT >= 30, "Phone-only setup needs Android 11 or newer for its census");
        boolean shizukuPresent;
        try { getTargetContext().getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0); shizukuPresent = true; }
        catch (PackageManager.NameNotFoundException absent) { shizukuPresent = false; }
        require(!shizukuPresent, "Shizuku is installed on this emulator; the setup census needs it absent");
        SharedPreferences journal = prefs("owner_capture_cleanup");
        require(journal.getAll().isEmpty(), "Existing owner-capture cleanup record refused");
        int preparing = captureConstant("PREPARING"), connecting = captureConstant("CONNECT_NAVEE"), cleaning = captureConstant("CLEANING");
        Constructor<?> identity = Class.forName("com.jjricks.volt.OwnerIdentityParser$Result").getDeclaredConstructor(String.class, int.class);
        identity.setAccessible(true);
        openMenu(); click("scooters-add");
        await(() -> field(activity, "onboarding") != null, "Add did not enter onboarding for Shizuku setup");
        click("onboarding-path-shizuku");
        // The Bluetooth prompt monitor also matches setup's explicit, action-less intent and would block it.
        removeMonitor(bluetoothPrompt);
        ActivityMonitor opened = addMonitor(com.jjricks.volt.ShizukuSetupActivity.class.getName(), null, false);
        Activity setup = null;
        Object client = null;
        try {
            main(() -> {
                TextView next = text(root(), "Continue");
                require(next != null && next.isShown(), "Setup choices lost Continue");
                next.performClick();
            });
            setup = waitForMonitorWithTimeout(opened, 10000);
            require(setup instanceof com.jjricks.volt.ShizukuSetupActivity, "Continue did not open Shizuku setup");
            Activity screen = setup;
            client = field(screen, "capture");
            Object capture = client;
            main(() -> require(!(Boolean) invoke(capture, "available", new Class<?>[]{})
                    && !(Boolean) invoke(capture, "pendingCleanup", new Class<?>[]{})
                    && "".equals(field(capture, "message")) && !(Boolean) field(capture, "busy") && field(capture, "owner") == null,
                    "Shizuku setup did not start without Shizuku, a cleanup record or a capture"));
            setupShot(screen, "start", null, "Phone-only setup", "Start Shizuku", "Shizuku installed", "Install", "Shizuku running", "Open",
                    "Shizuku setup guide", "Back to setup choices");

            require(journal.edit().putBoolean("pending", true).commit(), "Cleanup record fixture failed");
            main(() -> invoke(screen, "render", new Class<?>[]{}));
            setupShot(screen, "cleanup", null, "Finish cleanup first", "NAVEE logging may still be on.", "Install Shizuku",
                    "Check Shizuku connection", "Back");
            main(() -> {
                TextView check = text(setupRoot(screen), "Check Shizuku connection");
                require(check != null && check.isShown(), "Cleanup screen lost its connection check");
                check.performClick();
            });
            setupShot(screen, "cleanup-checked", null, "Finish cleanup first", "Shizuku isn't running");
            require(journal.edit().clear().commit(), "Cleanup record fixture was not removed");

            main(() -> {
                set(capture, "message", ""); set(capture, "busy", true); set(capture, "phase", preparing); set(capture, "captureSinceMs", 0L);
                invoke(screen, "render", new Class<?>[]{});
            });
            setupShot(screen, "preparing", null, "Preparing capture…", "Keep Shizuku running.", "Cancel capture");
            main(() -> { set(capture, "phase", cleaning); invoke(screen, "render", new Class<?>[]{}); });
            setupShot(screen, "restoring", null, "Restoring NAVEE logging…", "Keep Shizuku running.", "Cancel capture");
            // About 60 s left: the ring's spoken seconds drift between runs, which G7 compares by form.
            main(() -> {
                set(capture, "phase", connecting); set(capture, "captureSinceMs", System.nanoTime() / 1000000L - 30000L);
                invoke(screen, "render", new Class<?>[]{});
            });
            setupShot(screen, "connecting", null, "Connect your scooter in NAVEE", "Connect in NAVEE, then return.", "Cancel capture");
            main(screen::onBackPressed);
            setupShot(screen, "stop-dialog", "Stop setup?", "Connect your scooter in NAVEE");
            main(() -> {
                View dialog = otherShownWindow(setupRoot(screen));
                require(dialog != null, "Stop setup dialog vanished");
                View keep = dialog.findViewById(android.R.id.button2);
                require(keep instanceof TextView && "Keep waiting".contentEquals(((TextView) keep).getText()), "Stop setup dialog lost Keep waiting");
                keep.performClick();
            });
            main(() -> {
                TextView cancel = text(setupRoot(screen), "Cancel capture");
                require(cancel != null && cancel.isShown(), "Busy screen lost Cancel capture");
                cancel.performClick();
            });
            setupShot(screen, "stopped", null, "Start Shizuku", "Stopped", "Shizuku installed");
            main(() -> require(!(Boolean) field(capture, "busy") && journal.getAll().isEmpty(), "Cancelling the fixture capture left work behind"));

            main(() -> {
                set(capture, "message", ""); set(capture, "owner", identity.newInstance("000000000000", 0));
                invoke(screen, "render", new Class<?>[]{});
            });
            setupShot(screen, "ready-owner", null, "Ready to import", "Owner key found", "NAVEE restored", "Import owner setup", "Discard");
            main(() -> { set(capture, "owner", identity.newInstance("000000000000", 1)); invoke(screen, "render", new Class<?>[]{}); });
            setupShot(screen, "ready-shared", null, "Ready to import", "Shared-rider key found", "NAVEE restored", "Import owner setup", "Discard");
            main(() -> {
                require(text(setupRoot(screen), "000000000000") == null, "Shizuku setup showed the captured identity");
                TextView discard = text(setupRoot(screen), "Discard");
                require(discard != null && discard.isShown(), "Import screen lost Discard");
                discard.performClick();
            });
            awaitOnMain(() -> field(capture, "owner") == null && setupSteady(screen, null, "Start Shizuku", "Back to setup choices"),
                    "Discard did not return Shizuku setup to step 1", 20000);
            main(() -> {
                TextView leave = text(setupRoot(screen), "Back to setup choices");
                require(leave != null && leave.isShown(), "Step 1 lost Back to setup choices");
                leave.performClick();
            });
            awaitOnMain(() -> screen.isDestroyed() && activity.getWindow().getDecorView().hasWindowFocus(),
                    "Back to setup choices did not return to Volt", 20000);
        } finally {
            removeMonitor(opened);
            addMonitor(bluetoothPrompt);
            journal.edit().clear().commit();
            if (client != null) {
                Object capture = client;
                main(() -> {
                    set(capture, "busy", false); set(capture, "phase", 0); set(capture, "captureSinceMs", 0L);
                    set(capture, "owner", null); set(capture, "message", "");
                });
            }
            if (setup != null && !setup.isDestroyed()) { Activity open = setup; main(open::finish); }
        }
        main(() -> {
            require(field(activity, "onboarding") != null, "Leaving Shizuku setup also left the setup choices");
            ((OnboardingScreen) field(activity, "onboarding")).back();
        });
        await(() -> field(activity, "onboarding") == null, "Back did not leave adding flow after Shizuku setup");
        main(() -> require(activity.service().profiles.all().size() == 1 && activity.service().profiles.active().id.equals(a.id),
                "Shizuku setup altered existing scooters"));
    }
    /** A Shizuku setup screenshot and census, once setupSteady holds; dialogTitle names a dialog that must be up. */
    private void setupShot(Activity screen, String state, String dialogTitle, String... shown) throws Exception {
        awaitOnMain(() -> setupSteady(screen, dialogTitle, shown), "Shizuku setup " + state + " did not settle as the only Volt window", 20000);
        SystemClock.sleep(300); waitForIdleSync();
        awaitOnMain(() -> setupSteady(screen, dialogTitle, shown), "Shizuku setup " + state + " did not stay settled", 4000);
        File dir = new File(getTargetContext().getExternalFilesDir(null), "my-scooters-validation");
        require(dir.mkdirs() || dir.isDirectory(), "Screenshot directory");
        File file = new File(dir, "shizuku-setup-" + theme + "-" + state + ".png");
        Bitmap shot = dialogTitle == null ? null : getUiAutomation().takeScreenshot();
        if (shot == null) {
            AtomicReference<Bitmap> image = new AtomicReference<>();
            main(() -> {
                View window = setupRoot(screen);
                Bitmap drawn = Bitmap.createBitmap(window.getWidth(), window.getHeight(), Bitmap.Config.ARGB_8888);
                window.draw(new Canvas(drawn)); image.set(drawn);
            });
            shot = image.get();
        }
        try (FileOutputStream out = new FileOutputStream(file)) { shot.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { shot.recycle(); }
        // Check and census in one main-thread turn, so nothing can land between them.
        main(() -> {
            require(setupSteady(screen, dialogTitle, shown), "Shizuku setup " + state + " changed before its census");
            TextCensus.write(this, file);
        });
    }
    /**
     * Main thread only. True when the setup window is laid out with every shown text in it, its countdown ring
     * (if any) has spoken its seconds, and it is the only shown window of this process (with dialogTitle, it
     * and one focused dialog holding that title).
     */
    private boolean setupSteady(Activity screen, String dialogTitle, String... shown) {
        View decor = setupRoot(screen);
        if (screen.isFinishing() || !decor.isShown() || !decor.isLaidOut() || decor.isLayoutRequested() || decor.getWidth() == 0) return false;
        for (String value : shown) {
            TextView view = text(decor, value);
            if (view == null || !view.isShown() || !view.isLaidOut() || view.getWidth() == 0) return false;
        }
        if (!countdownSpoken(decor)) return false;
        java.util.List<View> windows = new java.util.ArrayList<>();
        for (View window : android.view.inspector.WindowInspector.getGlobalWindowViews()) if (window.isShown()) windows.add(window);
        if (!windows.contains(decor)) return false;
        if (dialogTitle == null) return windows.size() == 1 && decor.hasWindowFocus();
        if (windows.size() != 2) return false;
        View dialog = windows.get(windows.get(0) == decor ? 1 : 0);
        TextView title = text(dialog, dialogTitle);
        return dialog.isLaidOut() && !dialog.isLayoutRequested() && dialog.hasWindowFocus() && title != null && title.isShown();
    }
    /** False while a shown countdown ring has not drawn yet: it sets its spoken seconds in onDraw. */
    private static boolean countdownSpoken(View view) {
        if (!view.isShown()) return true;
        if ("Countdown".equals(view.getClass().getSimpleName()) && view.getContentDescription() == null) return false;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (!countdownSpoken(((ViewGroup) view).getChildAt(i))) return false;
        return true;
    }
    /** The shown window of this process other than root's, or null. */
    private static View otherShownWindow(View root) {
        for (View window : android.view.inspector.WindowInspector.getGlobalWindowViews()) if (window != root && window.isShown()) return window;
        return null;
    }
    private static View setupRoot(Activity screen) { return screen.getWindow().getDecorView(); }
    private static int captureConstant(String name) throws Exception {
        Field constant = Class.forName("com.jjricks.volt.OwnerCaptureEngine").getDeclaredField(name);
        constant.setAccessible(true);
        return constant.getInt(null);
    }
    /** Like await, with a longer bound for a loaded machine. */
    private void awaitOnMain(BooleanSupplier condition, String message, long timeoutMs) {
        long until = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < until) {
            AtomicReference<Boolean> ready = new AtomicReference<>();
            main(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(40);
        }
        throw new AssertionError(message);
    }

    private void click(String tag) { main(() -> { View view=tag(tag); require(view!=null,"Missing action "+tag); view.performClick(); }); waitForIdleSync(); }
    private MoreScreen more() { return (MoreScreen)field(activity,"screen"); }
    private View root() { return activity.getWindow().getDecorView(); }
    private View tag(String tag) { return root().findViewWithTag(tag); }
    private ScooterProfiles.Profile find(String id) { for(ScooterProfiles.Profile p:activity.service().profiles.all())if(p.id.equals(id))return p;return null; }
    private AlertDialog dialog() { AtomicReference<AlertDialog> found=new AtomicReference<>(); main(()->found.set(lastDialog()));return found.get(); }
    private AlertDialog lastDialog() { List<?> list=(List<?>)field(more(),"dialogs");require(!list.isEmpty(),"Dialog missing");return(AlertDialog)list.get(list.size()-1); }
    private int showingDialogs() { int count=0;for(Object item:(List<?>)field(more(),"dialogs"))if(((AlertDialog)item).isShowing())count++;return count; }
    private TextView text(View root,String text){if(root instanceof TextView&&((TextView)root).getText().toString().equals(text))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=text(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    private TextView prefix(View root,String prefix){if(root instanceof TextView&&((TextView)root).getText().toString().startsWith(prefix))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=prefix(((ViewGroup)root).getChildAt(i),prefix);if(found!=null)return found;}return null;}
    private View description(View root,String text){if(text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=description(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    private <T extends View>T first(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T found=first(((ViewGroup)root).getChildAt(i),type);if(found!=null)return found;}return null;}
    private void screenshot(String name)throws Exception{AtomicReference<Bitmap> image=new AtomicReference<>();main(()->{Bitmap bmp=Bitmap.createBitmap(root().getWidth(),root().getHeight(),Bitmap.Config.ARGB_8888);root().draw(new Canvas(bmp));image.set(bmp);});File dir=new File(getTargetContext().getExternalFilesDir(null),"my-scooters-validation");require(dir.mkdirs()||dir.isDirectory(),"Screenshot directory");try(FileOutputStream out=new FileOutputStream(new File(dir,name))){image.get().compress(Bitmap.CompressFormat.PNG,100,out);}image.get().recycle();TextCensus.write(this,new File(dir,name));}
    private int dp(int value){return Math.round(value*getTargetContext().getResources().getDisplayMetrics().density);}
    private SharedPreferences prefs(String name){return getTargetContext().getSharedPreferences(name,Context.MODE_PRIVATE);}
    private void require(boolean good,String message){checks++;if(!good)throw new AssertionError(message);}
    private interface Action{void run()throws Exception;}
    private void main(Action action){AtomicReference<Throwable> error=new AtomicReference<>();runOnMainSync(()->{try{action.run();}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new AssertionError(error.get());}
    private void await(BooleanSupplier condition,String message){long until=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<until){AtomicReference<Boolean> ready=new AtomicReference<>();main(()->ready.set(condition.getAsBoolean()));if(ready.get())return;SystemClock.sleep(80);}throw new AssertionError(message);}
    private static Object field(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception error){throw new IllegalStateException(error);}}
    private static void set(Object target,String name,Object value){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception error){throw new IllegalStateException(error);}}
    private static Object invoke(Object target,String name,Class<?>[] types,Object...args){try{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}catch(Exception error){throw new IllegalStateException(error);}}
}
