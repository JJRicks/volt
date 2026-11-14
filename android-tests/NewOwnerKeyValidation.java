package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
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
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.BleController;
import com.jjricks.volt.ConnectionEventLog;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * Disposable emulator only: fresh app data, Bluetooth off, Nearby devices granted. Setup's "Make a new owner key" route
 * through the real screens and stores:
 *   - the route list (the new route second, after the recommended one) and the new-key page, which makes nothing;
 *   - Continue makes one owner key of NAVEE's shape, records it as sent at Connect, and Back + Continue reuses it;
 *   - the owner check's rejection, injected into the real controller, shows the refusal and "Set up another way",
 *     which deletes the refused key;
 *   - a timeout keeps the key and hides "Set up another way"; a typed key saved afterwards leaves the made key in
 *     place, and returning to the new-key route reuses the made key and drops the typed one;
 *   - the done page's backup note, above Back up Volt.
 * Nothing reaches a scooter. The done page is entered directly once the profile has the scooter address a real
 * binding saves (FirstConnectionValidation covers the binding itself). Synthetic identities only; none is printed.
 *   -e theme dark|light   -e text_scale 1|1.4   -e label <screen-size label for screenshot names>
 */
public final class NewOwnerKeyValidation extends Instrumentation {
    private static final int PATH = 1, IDENTITY = 7, CONNECT = 9, DONE = 10;
    private static final String NEW_ROUTE = "onboarding-path-new_key";
    private static final String UNOWNED = "Scooter is new or reset";
    private static final String REFUSED = "This scooter already has an owner, so it refused the new key. Remove it from the NAVEE app and try again, or set up another way.";
    private static final String OTHER_WAY = "Set up another way";
    private static final String DONE_NOTE = "Volt made this scooter's owner key, and it's only on this phone. Back up Volt so you can set it up on another phone.";
    private MainActivity activity;
    private ActivityMonitor bluetoothPrompt;
    private int checks, shots;
    private boolean admitted;
    private String theme = "dark", label = "phone";
    private float textScale = 1f;

    @Override public void onCreate(Bundle args) {
        if (args != null) {
            theme = args.getString("theme", "dark"); label = args.getString("label", "phone");
            textScale = Float.parseFloat(args.getString("text_scale", "1"));
        }
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            main(() -> {
                prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                        .putString("theme", theme).putFloat("text_scale", textScale).commit();
                prefs("onboarding").edit().putInt("step", PATH).commit();
            });
            activity = (MainActivity) startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClass(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && root().hasWindowFocus() && setup() != null, "Setup not ready");
            // After launch: an IntentFilter monitor may also match the explicit launch intent and block it.
            bluetoothPrompt = addMonitor(new IntentFilter(BluetoothAdapter.ACTION_REQUEST_ENABLE), new ActivityResult(Activity.RESULT_CANCELED, null), true);
            routeList();
            String[] made = makeAndReuse();
            refusal();
            String kept = timeoutKeeps(made[0]);
            doneNote(kept);
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", checks); result.putInt("screenshots", shots); result.putBoolean("physical_device_tested", false);
            if (bluetoothPrompt != null) removeMonitor(bluetoothPrompt);
            if (admitted && activity != null) try {
                main(() -> {
                    ScooterService s = activity.service();
                    if (s != null) {
                        require(field(s.ble, "gatt") == null && field(s.ble, "scanner") == null, "A real transport was started");
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
        require(getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED,
                "Grant emulator Nearby devices first");
        BluetoothAdapter adapter = getTargetContext().getSystemService(BluetoothManager.class).getAdapter();
        require(adapter != null && !adapter.isEnabled(), "Emulator Bluetooth must stay off");
        for (String name : new String[]{"profiles", "scooter", "app_settings", "onboarding"})
            require(prefs(name).getAll().isEmpty(), "Existing preferences refused");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0, "Existing app data refused");
    }

    /** The route list offers the new route second, and choosing it makes nothing until the new-key page's Continue. */
    private void routeList() throws Exception {
        await(() -> step() == PATH && tag(NEW_ROUTE) != null, "Setup did not open on the route list");
        main(() -> {
            List<String> order = new ArrayList<>(); routes(root(), order);
            require(order.size() >= 2 && "onboarding-path-webadb".equals(order.get(0)) && NEW_ROUTE.equals(order.get(1)),
                    "The new route is not second, after the recommended route: " + order);
            View option = tag(NEW_ROUTE);
            require(text(option, "Make a new owner key") != null && text(option, "New or reset scooter · no computer") != null,
                    "New route copy");
            option.performClick();
        });
        waitForIdleSync();
        main(() -> {
            AccessibilityNodeInfo node = tag(NEW_ROUTE).createAccessibilityNodeInfo();
            try { require(node.isCheckable() && node.isChecked(), "Choosing the new route did not select it"); } finally { node.recycle(); }
        });
        shot("routes");
        clickText("Continue");
        await(() -> step() == IDENTITY && shown(UNOWNED), "Continue did not open the new-key page");
        main(() -> {
            require(shown("Make a new owner key"), "New-key heading");
            require(shown("The NAVEE app can't add this scooter while it has Volt's key. To switch back, release it in Volt first."),
                    "New-key page lost the NAVEE note");
            require(first(root(), EditText.class) != null, "New-key page lost the name field");
            require(profiles().all().isEmpty(), "Opening the new-key page made a key");
        });
        shot("new-key");
    }

    /** Continue makes one key and marks it sent at Connect; Back and Continue again reuse it. */
    private String[] makeAndReuse() {
        clickText("Continue");
        await(() -> step() == CONNECT, "The new-key page's Continue did not reach Connect");
        String[] made = new String[2];
        main(() -> {
            List<ScooterProfiles.Profile> all = profiles().all();
            require(all.size() == 1, "Continue made " + all.size() + " profiles");
            ScooterProfiles.Profile p = all.get(0);
            require(p.role == 0 && p.identity.matches("88[0-9A-F]{10}"), "The made key is not an owner key of NAVEE's shape");
            require(p.mac.isEmpty() && p.serial.isEmpty() && p.nameDefault, "The made key's profile is not a fresh, unnamed scooter");
            require(p.id.equals(profiles().active().id), "The made key is not the active scooter");
            require(p.id.equals(onboarding().getString("generated_profile_id", "")) && onboarding().getBoolean("generated_sent", false),
                    "Connect did not record the made key as sent");
            made[0] = p.id; made[1] = p.identity;
        });
        main(() -> setup().back());
        await(() -> step() == IDENTITY && shown(UNOWNED), "Back from Connect did not return to the new-key page");
        clickText("Continue");
        await(() -> step() == CONNECT, "Continue again did not reach Connect");
        main(() -> {
            List<ScooterProfiles.Profile> all = profiles().all();
            require(all.size() == 1 && all.get(0).id.equals(made[0]) && all.get(0).identity.equals(made[1]),
                    "Continue again made a second key instead of reusing the first");
        });
        return made;
    }

    /** The owner check's rejection: the refusal copy and Set up another way, which deletes the refused key. */
    private void refusal() throws Exception {
        inject(BleController.OWNER_REJECTED, false, ConnectionEventLog.Reason.AUTHENTICATION_FAILED);
        await(() -> shown(REFUSED) && shown(OTHER_WAY) && shown("Try again"), "Connect did not show the refusal");
        shot("refused");
        clickText(OTHER_WAY);
        await(() -> step() == PATH && tag(NEW_ROUTE) != null, "Set up another way did not return to the routes");
        main(() -> {
            require(profiles().all().isEmpty(), "Set up another way kept the refused key");
            require(!onboarding().contains("generated_profile_id") && !onboarding().contains("generated_sent")
                    && !onboarding().contains("profile_id") && !onboarding().contains("created_profile_id"),
                    "Set up another way left setup pointing at the deleted key");
        });
    }

    /**
     * A timeout keeps the key (the scooter may hold it) and offers no Set up another way. A typed key saved afterwards
     * leaves the made key in place; returning to the new route reuses the made key and drops the typed one.
     */
    private String timeoutKeeps(String refusedId) {
        click(NEW_ROUTE); clickText("Continue");
        await(() -> step() == IDENTITY && shown(UNOWNED), "The new route did not open again");
        clickText("Continue");
        await(() -> step() == CONNECT, "The second key's Continue did not reach Connect");
        String[] kept = new String[2];
        main(() -> {
            List<ScooterProfiles.Profile> all = profiles().all();
            require(all.size() == 1 && !all.get(0).id.equals(refusedId), "After a refusal, setup did not make a fresh key");
            kept[0] = all.get(0).id; kept[1] = all.get(0).identity;
        });
        inject("Timed out", true, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
        await(() -> shown("Timed out") && shown("Try again"), "Connect did not show the timeout");
        main(() -> require(!shown(OTHER_WAY) && !shown(REFUSED), "A timeout offered to delete a key the scooter may hold"));
        main(() -> setup().back());
        await(() -> step() == IDENTITY && shown(UNOWNED), "Back from Connect after a timeout");
        main(() -> setup().back());
        await(() -> step() == PATH && tag("onboarding-path-identity") != null, "Back to the routes after a timeout");
        click("onboarding-path-identity"); clickText("Continue");
        await(() -> step() == IDENTITY && shown("Save and continue"), "The typed-key page did not open");
        main(() -> {
            EditText identity = (EditText) field(setup(), "identityField");
            require(identity != null && identity.isShown(), "Typed-key field missing");
            identity.setText("000000000000");
        });
        clickText("Save and continue");
        await(() -> step() == CONNECT, "Saving a typed key did not reach Connect");
        main(() -> {
            List<ScooterProfiles.Profile> all = profiles().all();
            require(all.size() == 2 && profileById(kept[0]) != null && kept[1].equals(profileById(kept[0]).identity),
                    "Saving a typed key deleted a made key the scooter may hold");
            require(!profiles().active().id.equals(kept[0]), "The typed key is not the active scooter");
        });
        main(() -> setup().back());
        await(() -> step() == IDENTITY, "Back from Connect to the typed-key page");
        main(() -> setup().back());
        await(() -> step() == PATH, "Back to the routes from the typed-key page");
        click(NEW_ROUTE); clickText("Continue");
        await(() -> step() == IDENTITY && shown(UNOWNED), "The new route did not open a third time");
        clickText("Continue");
        await(() -> step() == CONNECT, "Returning to the new route did not reach Connect");
        main(() -> {
            List<ScooterProfiles.Profile> all = profiles().all();
            require(all.size() == 1 && all.get(0).id.equals(kept[0]) && all.get(0).identity.equals(kept[1]),
                    "Returning to the new route did not reuse the kept key, or kept the abandoned typed key");
            require(profiles().active().id.equals(kept[0]), "The kept key is not the active scooter");
        });
        return kept[0];
    }

    /** Once a scooter took the key (its address saved), the done page says the key is only on this phone. */
    private void doneNote(String keptId) throws Exception {
        main(() -> {
            ScooterProfiles.Profile p = profileById(keptId);
            p.mac = "C0:FF:EE:00:00:07"; p.serial = "Q2538ZZ9US7777777"; profiles().update(p);
            invoke(setup(), "go", new Class<?>[]{int.class}, DONE);
        });
        await(() -> step() == DONE && shown(DONE_NOTE) && tag("onboarding-create-backup") != null && tag("onboarding-create-backup").isShown(),
                "The done page lost the new-key note or Back up Volt");
        main(() -> {
            View note = text(root(), DONE_NOTE), backup = tag("onboarding-create-backup");
            int[] a = new int[2], b = new int[2]; note.getLocationOnScreen(a); backup.getLocationOnScreen(b);
            require(a[1] + note.getHeight() <= b[1], "The new-key note is not above Back up Volt");
            require(profileById(keptId) != null && !profileById(keptId).mac.isEmpty(), "The accepted key's profile is missing");
        });
        shot("done");
    }

    /** The real controller's failure path, as a finished owner check or timeout reaches it, then a setup refresh. */
    private void inject(String message, boolean retry, ConnectionEventLog.Reason reason) {
        main(() -> {
            ScooterService s = activity.service();
            onboarding().edit().putBoolean("connect_tried", true).remove("connect_requested").commit();
            if (s.ble.state() != BleController.State.DISCONNECTED) s.ble.disconnect();
            set(s.ble, "profile", profiles().active()); set(s.ble, "wanted", true);
            set(s.ble, "state", BleController.State.AUTHENTICATING);
            Method failed = BleController.class.getDeclaredMethod("failed", String.class, boolean.class, ConnectionEventLog.Reason.class, int.class);
            failed.setAccessible(true); failed.invoke(s.ble, message, retry, reason, -1);
            require(s.ble.state() == BleController.State.DISCONNECTED, "The injected failure left the controller " + s.ble.state());
            setup().refresh();
        });
        waitForIdleSync();
    }

    private void shot(String state) throws Exception {
        SystemClock.sleep(350); waitForIdleSync();
        AtomicReference<Bitmap> image = new AtomicReference<>();
        main(() -> {
            View window = root();
            Bitmap drawn = Bitmap.createBitmap(window.getWidth(), window.getHeight(), Bitmap.Config.ARGB_8888);
            window.draw(new Canvas(drawn)); image.set(drawn);
        });
        File dir = new File(getTargetContext().getExternalFilesDir(null), "new-owner-key-validation");
        require(dir.mkdirs() || dir.isDirectory(), "Screenshot directory");
        File file = new File(dir, label + "-" + theme + "-" + state + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) { image.get().compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { image.get().recycle(); }
        main(() -> TextCensus.write(this, file));
        shots++;
    }

    private OnboardingScreen setup() { return (OnboardingScreen) field(activity, "onboarding"); }
    private int step() { OnboardingScreen s = setup(); return s == null ? -1 : (Integer) field(s, "step"); }
    private ScooterProfiles profiles() { return activity.service().profiles; }
    private ScooterProfiles.Profile profileById(String id) { for (ScooterProfiles.Profile p : profiles().all()) if (p.id.equals(id)) return p; return null; }
    private SharedPreferences onboarding() { return prefs("onboarding"); }
    private View root() { return activity.getWindow().getDecorView(); }
    private View tag(String tag) { return root().findViewWithTag(tag); }
    private boolean shown(String value) { TextView v = text(root(), value); return v != null && v.isShown() && v.getWidth() > 0; }
    private void click(String tag) { main(() -> { View view = tag(tag); require(view != null, "Missing " + tag); view.performClick(); }); waitForIdleSync(); }
    private void clickText(String value) {
        main(() -> { TextView view = text(root(), value); require(view != null && view.isShown() && view.isEnabled(), "Missing or disabled: " + value); view.performClick(); });
        waitForIdleSync();
    }
    private static void routes(View view, List<String> out) {
        Object t = view.getTag();
        if (t instanceof String && ((String) t).startsWith("onboarding-path-")) out.add((String) t);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) routes(((ViewGroup) view).getChildAt(i), out);
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
    private void require(boolean good, String message) { checks++; if (!good) throw new AssertionError(message); }
    private interface Action { void run() throws Exception; }
    private void main(Action action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long until = SystemClock.elapsedRealtime() + 10000;
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
    private static void set(Object target, String name, Object value) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
