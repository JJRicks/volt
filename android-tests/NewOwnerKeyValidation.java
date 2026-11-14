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
 * through the real screens and stores, under the rule that setup never deletes a made key once any scooter was offered it:
 *   - the route list (the new route second, after the recommended one) and the new-key page, which makes nothing;
 *   - Continue makes one owner key of NAVEE's shape, on disk with its record ("made", in app_settings) and setup's
 *     pointer to it before Connect, and reaching Connect alone ties it to no scooter; Back + Continue reuses it;
 *   - a key never offered (no owner check went out, as when the chooser is cancelled) may be replaced by a typed key,
 *     and Import drops one too, with its record and setup's pointers;
 *   - the real controller's owner-check gate (mayOffer) ties the made key to the first scooter offered it, on disk,
 *     before any owner check is queued, and never for another profile's owner check; a retry then goes straight to
 *     that scooter (connectAddress), and the tie never blocks a typed key's setup of it (hardwareMatch);
 *   - offered to a second scooter while tied, the key is refused before the owner check: nothing queued, no
 *     reconnect, and the new message fully on screen above Try again (rendered with the couldn't-save message too);
 *   - the owner check's rejection, through the real controller's ownerRefused: another scooter's leaves the tie; the
 *     tied scooter's unties it and keeps the key, shows the refusal on screen without scrolling, and "Set up another
 *     way" keeps the key; the new route then reuses it;
 *   - a timeout keeps the key and hides "Set up another way"; a typed key saved afterwards leaves the made key in
 *     place, and returning to the new-key route reuses the made key and drops the typed one;
 *   - Import an owner setup file after a made key offers the file picker, not the made key as an imported file, keeps
 *     an offered key with setup's pointers to it cleared, never renames it with a pending typed name, and the name
 *     goes to the imported profile (onOwnerImported);
 *   - a typed key a scooter accepted (its address saved) is never removed by Make a new owner key;
 *   - the done page's heading, then the backup note above Back up Volt, all fully on screen without scrolling, above Go to Home.
 * Not reached here: a live BluetoothGatt (authenticate() reads the link's address; NewOwnerKeyTest checks that mayOffer
 * comes first and is the only way to the owner check), a failed tie write (NewOwnerKeyTest), and a real Try again's
 * connect (Bluetooth stays off). Nothing reaches a scooter. The done page is entered directly once the profile has the scooter address a real
 * binding saves (FirstConnectionValidation covers the binding itself). Synthetic identities only; none is printed.
 *   -e theme dark|light   -e text_scale 1|1.4   -e label <screen-size label for screenshot names>
 */
public final class NewOwnerKeyValidation extends Instrumentation {
    private static final int PATH = 1, IMPORT = 6, IDENTITY = 7, CONNECT = 9, DONE = 10;
    private static final String NEW_ROUTE = "onboarding-path-new_key";
    private static final String UNOWNED = "Scooter is new or reset";
    private static final String REFUSED = "This scooter already has an owner, so it refused the new key. Remove it from the NAVEE app and try again, or set up another way.";
    private static final String OTHER_WAY = "Set up another way";
    private static final String DONE_NOTE = "Volt made this scooter's owner key, and it's only on this phone. Back up Volt so you can set it up on another phone.";
    private static final String TYPED_KEY = "000000000000";
    private static final String ELSEWHERE = "This new key went to another scooter first. Turn that scooter on nearby and try again.";
    private static final String UNSAVED = "Couldn't save setup, so Volt didn't send the new key. Free up some storage and try again.";
    /** Synthetic scooter addresses: A is offered the made key first, B is another scooter. */
    private static final String A = "C0:FF:EE:00:00:0A", B = "C0:FF:EE:00:00:0B";
    private static final String PENDING = "Imported pending name";
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
            neverOfferedMayGo(made[0]);
            String dropped = importDropsNeverOffered();
            String[] key = offeredAtTransport(dropped);
            elsewhereRefused(key);
            refusalKeeps(key);
            timeoutKeeps(key);
            importAfterMadeKey(key[0]);
            acceptedTypedKept(key[0]);
            doneNote(key[0]);
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

    /**
     * Continue makes one key, on disk with setup's pointer to it before Connect; Connect itself sends nothing here
     * (Bluetooth is off), so nothing is recorded as offered. Back and Continue again reuse the key.
     */
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
            require(p.id.equals(onboarding().getString("generated_profile_id", "")), "Setup does not point at the made key");
            require("made".equals(record(p.id)), "Reaching Connect tied the made key to a scooter, or it has no record");
            require(onDisk("profiles").contains(p.identity) && onDisk("onboarding").contains(p.id)
                    && onDisk("app_settings").contains(recordXml(p.id, "made")),
                    "The made key, its record or setup's pointer to it is not on disk before Connect");
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

    /** A key no owner check ever carried (the chooser cancelled, say) may go: a typed key saved next replaces it. */
    private void neverOfferedMayGo(String madeId) {
        backToRoutes();
        saveTypedKey();
        main(() -> {
            List<ScooterProfiles.Profile> all = profiles().all();
            require(profileById(madeId) == null && all.size() == 1 && TYPED_KEY.equals(all.get(0).identity),
                    "A typed key did not replace a made key that was never offered");
            require(!onboarding().contains("generated_profile_id") && record(madeId) == null,
                    "Setup still points at a removed key, or its record stayed");
        });
        backToRoutes();
    }

    /** A fresh key never offered, then Import: it goes with its record, and setup no longer points at it. */
    private String importDropsNeverOffered() {
        openNewRoute();
        String[] made = new String[1];
        main(() -> {
            List<ScooterProfiles.Profile> all = profiles().all();
            require(all.size() == 1 && !TYPED_KEY.equals(all.get(0).identity) && "made".equals(record(all.get(0).id)),
                    "The new route did not make a fresh, never-offered key");
            made[0] = all.get(0).id;
        });
        backToRoutes();
        click("onboarding-path-backup"); clickText("Continue");
        await(() -> step() == IMPORT && shown("Choose owner file"), "Import did not open after a never-offered made key");
        await(() -> profileById(made[0]) == null, "Import kept a made key no scooter was ever offered");
        main(() -> {
            require(record(made[0]) == null && !onDisk("app_settings").contains("made_owner_key" + made[0]),
                    "Import left the dropped key's record");
            for (String pointer : new String[]{"generated_profile_id", "profile_id", "created_profile_id"})
                require(!made[0].equals(onboarding().getString(pointer, "")), "Setup still points at the dropped key: " + pointer);
            setup().back();
        });
        await(() -> step() == PATH, "Back from Import to the routes");
        return made[0];
    }

    /**
     * The new route makes a fresh key. The real controller's owner-check gate (mayOffer) ties it to the first scooter
     * offered it, on disk, before any owner check is queued, with setup not involved: another profile's owner check
     * leaves the record alone. A retry goes straight to that scooter, and the tie never stops a typed key's setup of it.
     * Bluetooth is off and there is no GATT, so nothing is transmitted.
     */
    private String[] offeredAtTransport(String droppedId) {
        openNewRoute();
        String[] key = new String[2];
        main(() -> {
            List<ScooterProfiles.Profile> all = profiles().all();
            require(all.size() == 1 && !all.get(0).id.equals(droppedId) && !TYPED_KEY.equals(all.get(0).identity),
                    "The new route did not make a fresh key");
            key[0] = all.get(0).id; key[1] = all.get(0).identity;
            ScooterService s = activity.service();
            if (s.ble.state() != BleController.State.DISCONNECTED) s.ble.disconnect();
            require(field(s.ble, "gatt") == null, "A transport is open");
            require("".equals(s.ble.connectAddress(profileById(key[0]))), "A never-offered made key is not found by a scan");
            ScooterProfiles.Profile other = new ScooterProfiles.Profile("someone-else", "Other", "000000000001", 0, "", "", 0);
            set(s.ble, "profile", other);
            require(offer(s, A), "Another profile's owner check was refused");
            require("made".equals(record(key[0])), "Another profile's owner check tied the made key");
            s.ble.disconnect();
            set(s.ble, "profile", profileById(key[0]));
            require(offer(s, A), "The made key's first owner check was refused");
            require(A.equals(record(key[0])), "The first owner check did not tie the made key to its scooter");
            require(onDisk("app_settings").contains(recordXml(key[0], A)), "The tie is not on disk before the owner check");
            require(((java.util.Collection<?>) field(s.ble, "queue")).isEmpty(), "An owner check was queued with no transport");
            ScooterProfiles.Profile p = profileById(key[0]);
            require(p.mac.isEmpty() && A.equals(s.ble.connectAddress(p)), "A retry of the dropped first link does not go straight to its scooter");
            require(profiles().hardwareMatch("someone-else", A, null) == null, "The tie blocks another profile's setup of that scooter");
            s.ble.disconnect();
        });
        return key;
    }

    /**
     * While tied to A, the made key offered to B is refused before the owner check: the tie stays, nothing is queued,
     * no reconnect is wanted, and the message is fully on screen above Try again. The couldn't-save message, which
     * needs a failed write (NewOwnerKeyTest), is rendered through the same failure path.
     */
    private void elsewhereRefused(String[] key) throws Exception {
        drive(s -> {
            require(!offer(s, B), "The made key went to a second scooter while tied to the first");
            require(A.equals(record(key[0])), "A second scooter changed the tie");
            require(((java.util.Collection<?>) field(s.ble, "queue")).isEmpty(), "A key was queued for the second scooter");
            require(!(Boolean) field(s.ble, "wanted"), "The refusal at the second scooter wants a reconnect");
            require(ELSEWHERE.equals(s.ble.message()), "The controller's message: " + s.ble.message());
        });
        await(() -> shown(ELSEWHERE) && shown("Try again"), "Connect did not show the second-scooter refusal");
        main(() -> require(!shown(REFUSED) && !shown(OTHER_WAY), "The second-scooter refusal showed the owner refusal"));
        await(() -> fullyVisible(text(root(), ELSEWHERE)) && bottom(text(root(), ELSEWHERE)) <= top(text(root(), "Try again")),
                "The second-scooter refusal is not fully on screen above Try again");
        shot("elsewhere");
        inject(UNSAVED, false, ConnectionEventLog.Reason.IDENTITY_REJECTED);
        await(() -> shown(UNSAVED) && shown("Try again"), "Connect did not show the couldn't-save message");
        await(() -> fullyVisible(text(root(), UNSAVED)) && bottom(text(root(), UNSAVED)) <= top(text(root(), "Try again")),
                "The couldn't-save message is not fully on screen above Try again");
        shot("unsaved");
        main(() -> require(A.equals(record(key[0])), "Showing a message changed the tie"));
    }

    /** The owner check's rejection: the refusal on screen without scrolling, and Set up another way keeps the key. */
    private void refusalKeeps(String[] key) throws Exception {
        drive(s -> invoke(s.ble, "ownerRefused", new Class<?>[]{String.class, int.class}, B, 1));
        main(() -> require(A.equals(record(key[0])) && profileById(key[0]) != null, "Another scooter's refusal untied the made key"));
        drive(s -> invoke(s.ble, "ownerRefused", new Class<?>[]{String.class, int.class}, A, 1));
        main(() -> {
            require("offered".equals(record(key[0])) && onDisk("app_settings").contains(recordXml(key[0], "offered")),
                    "The tied scooter's refusal did not untie the made key, or kept it unmarked as offered");
            require(profileById(key[0]) != null && key[1].equals(profileById(key[0]).identity), "The refusal deleted the made key");
            require("".equals(activity.service().ble.connectAddress(profileById(key[0]))), "An untied key's retry is not a scan");
        });
        await(() -> shown(REFUSED) && shown(OTHER_WAY) && shown("Try again"), "Connect did not show the refusal");
        await(() -> fullyVisible(text(root(), REFUSED)) && bottom(text(root(), REFUSED)) <= top(text(root(), "Try again")),
                "The refusal is not fully on screen above the footer");
        shot("refused");
        clickText(OTHER_WAY);
        await(() -> step() == PATH && tag(NEW_ROUTE) != null, "Set up another way did not return to the routes");
        main(() -> {
            ScooterProfiles.Profile p = profileById(key[0]);
            require(p != null && key[1].equals(p.identity), "Set up another way deleted a key a scooter may hold");
            require(key[0].equals(onboarding().getString("generated_profile_id", "")) && "offered".equals(record(key[0])),
                    "Set up another way forgot the made key or its offer");
            require(!onboarding().contains("profile_id") && !onboarding().contains("created_profile_id") && !onboarding().contains("connect_tried"),
                    "Set up another way left setup on the refused attempt");
        });
    }

    /**
     * The new route reuses the kept key. A timeout keeps it and offers no Set up another way. A typed key saved
     * afterwards leaves the made key in place; returning to the new route reuses the made key and drops the typed one.
     */
    private void timeoutKeeps(String[] key) {
        openNewRoute();
        main(() -> {
            List<ScooterProfiles.Profile> all = profiles().all();
            require(all.size() == 1 && all.get(0).id.equals(key[0]) && all.get(0).identity.equals(key[1]),
                    "After Set up another way, the new route did not reuse the kept key");
        });
        inject("Timed out", true, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
        await(() -> shown("Timed out") && shown("Try again"), "Connect did not show the timeout");
        main(() -> require(!shown(OTHER_WAY) && !shown(REFUSED), "A timeout showed the refusal"));
        backToRoutes();
        saveTypedKey();
        main(() -> {
            List<ScooterProfiles.Profile> all = profiles().all();
            require(all.size() == 2 && profileById(key[0]) != null && key[1].equals(profileById(key[0]).identity),
                    "Saving a typed key deleted a made key a scooter may hold");
            require(!profiles().active().id.equals(key[0]), "The typed key is not the active scooter");
        });
        backToRoutes();
        openNewRoute();
        main(() -> {
            List<ScooterProfiles.Profile> all = profiles().all();
            require(all.size() == 1 && all.get(0).id.equals(key[0]) && all.get(0).identity.equals(key[1]),
                    "Returning to the new route did not reuse the kept key, or kept the abandoned typed key");
            require(profiles().active().id.equals(key[0]), "The kept key is not the active scooter");
        });
    }

    /** After a made key's timeout, Back to the routes and Import an owner setup file: the file picker, not "Owner key found". */
    private void importAfterMadeKey(String keyId) {
        inject("Timed out", true, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
        await(() -> shown("Timed out"), "Connect did not show the timeout");
        backToRoutes();
        click("onboarding-path-backup"); clickText("Continue");
        await(() -> step() == IMPORT && shown("Choose owner file"), "Import did not offer the file picker after a made key");
        String[] imported = new String[1];
        main(() -> {
            require(!shown("Owner key found"), "Import treated the made key as an imported file");
            ScooterProfiles.Profile made = profileById(keyId);
            require(made != null && "offered".equals(record(keyId)), "Import dropped an offered made key, or its record");
            require(keyId.equals(onboarding().getString("generated_profile_id", "")), "Import forgot the kept key, which the new route reuses");
            require(!keyId.equals(onboarding().getString("profile_id", "")) && !keyId.equals(onboarding().getString("created_profile_id", "")),
                    "Import still has the made key as setup's profile");
            // A name typed for a file still being picked, with setup's profile pointing at the made key (as before
            // the fix): the made key keeps its name, and the name waits for the imported profile.
            String before = made.name;
            onboarding().edit().putString("pending_name", PENDING).putString("profile_id", keyId).commit();
            invoke(setup(), "applyPendingName", new Class<?>[0]);
            require(before.equals(profileById(keyId).name) && PENDING.equals(onboarding().getString("pending_name", null)),
                    "A pending typed name renamed the made key");
            onboarding().edit().remove("profile_id").commit();
            // The picked file applied, as MainActivity does it: a new active profile, then onOwnerImported(true).
            ScooterProfiles.Profile file = profiles().create("Owner file", "000000000002", 0);
            profiles().select(file.id); imported[0] = file.id;
            setup().onOwnerImported(true);
            require(PENDING.equals(profileById(file.id).name) && !onboarding().contains("pending_name"),
                    "The pending typed name did not reach the imported profile");
            require(before.equals(profileById(keyId).name), "Importing renamed the made key");
        });
        waitForIdleSync();
        main(() -> {
            require(step() == IMPORT && profileById(keyId) != null, "Import moved on or dropped the made key");
            // This harness's file is synthetic: it goes, and setup returns to the routes.
            profiles().remove(imported[0]); onboarding().edit().remove("profile_id").commit();
            profiles().select(keyId);
            setup().back();
        });
        await(() -> step() == PATH, "Back from Import to the routes");
    }

    /** A typed key a scooter accepted (its address saved) stays when the new route is chosen again. */
    private void acceptedTypedKept(String keyId) {
        saveTypedKey();
        String[] typed = new String[1];
        main(() -> {
            ScooterProfiles.Profile p = profiles().active();
            require(p != null && TYPED_KEY.equals(p.identity), "The typed key is not the active scooter");
            p.mac = "C0:FF:EE:00:00:08"; profiles().update(p); typed[0] = p.id;
        });
        backToRoutes();
        openNewRoute();
        main(() -> {
            require(profileById(typed[0]) != null, "Make a new owner key removed a typed key a scooter accepted");
            require(profiles().active().id.equals(keyId), "The kept key is not the active scooter");
        });
    }

    /** Once a scooter took the key (its address saved), the done page's note and Back up Volt are on screen unscrolled. */
    private void doneNote(String keptId) throws Exception {
        main(() -> {
            ScooterProfiles.Profile p = profileById(keptId);
            p.mac = "C0:FF:EE:00:00:07"; p.serial = "Q2538ZZ9US7777777"; profiles().update(p);
            invoke(setup(), "go", new Class<?>[]{int.class}, DONE);
        });
        await(() -> step() == DONE && shown(DONE_NOTE) && tag("onboarding-create-backup") != null && tag("onboarding-create-backup").isShown(),
                "The done page lost the new-key note or Back up Volt");
        // The page slides in (Motion.page); judge it once it has settled, with the scroll view never moved.
        await(() -> fullyVisible(text(root(), DONE_NOTE)) && fullyVisible(tag("onboarding-create-backup")) && fullyVisible(text(root(), "Go to Home")),
                "The new-key note, Back up Volt or Go to Home is not fully on screen without scrolling");
        // The pinned note and button must not cover the page's heading either (the hero gives up height in a short window).
        await(() -> fullyVisible(text(root(), "You're connected")), "The done page's heading is not fully on screen without scrolling");
        main(() -> {
            View note = text(root(), DONE_NOTE), backup = tag("onboarding-create-backup"), home = text(root(), "Go to Home");
            android.widget.ScrollView scroll = first(root(), android.widget.ScrollView.class);
            require(scroll == null || scroll.getScrollY() == 0, "The done page was scrolled");
            require(fullyVisible(note) && fullyVisible(backup) && fullyVisible(home), "The done page's footer moved");
            require(bottom(note) <= top(backup) && bottom(backup) <= top(home), "The note, Back up Volt and Go to Home are out of order or overlap");
            require(bottom(text(root(), "You're connected")) <= top(note), "The done page's heading runs under the new-key note");
            require(profileById(keptId) != null && !profileById(keptId).mac.isEmpty(), "The accepted key's profile is missing");
        });
        shot("done");
    }

    private void backToRoutes() {
        for (int i = 0; i < 3; i++) {
            AtomicReference<Integer> now = new AtomicReference<>();
            main(() -> now.set(step()));
            if (now.get() == PATH) break;
            main(() -> setup().back());
            int before = now.get();
            await(() -> step() != before, "Back did not move from step " + before);
        }
        await(() -> step() == PATH && tag(NEW_ROUTE) != null, "Back did not reach the routes");
    }

    private void openNewRoute() {
        click(NEW_ROUTE); clickText("Continue");
        await(() -> step() == IDENTITY && shown(UNOWNED), "The new route did not open");
        clickText("Continue");
        await(() -> step() == CONNECT, "The new-key page's Continue did not reach Connect");
    }

    private void saveTypedKey() {
        click("onboarding-path-identity"); clickText("Continue");
        await(() -> step() == IDENTITY && shown("Save and continue"), "The typed-key page did not open");
        main(() -> {
            EditText identity = (EditText) field(setup(), "identityField");
            require(identity != null && identity.isShown(), "Typed-key field missing");
            identity.setText(TYPED_KEY);
        });
        clickText("Save and continue");
        await(() -> step() == CONNECT, "Saving a typed key did not reach Connect");
    }

    /** Wholly inside the window and every parent's clip, a scroll view's included: on screen without scrolling. */
    private boolean fullyVisible(View view) {
        if (view == null || !view.isShown() || view.getWidth() == 0 || view.getHeight() == 0) return false;
        android.graphics.Rect r = new android.graphics.Rect();
        return view.getGlobalVisibleRect(r) && r.width() == view.getWidth() && r.height() == view.getHeight()
                && r.top >= 0 && r.bottom <= root().getHeight();
    }
    private static int top(View view) { int[] at = new int[2]; view.getLocationOnScreen(at); return at[1]; }
    private static int bottom(View view) { return top(view) + view.getHeight(); }

    /** The app's preferences file as written on disk (this runs in the app's process), to prove a commit. */
    private String onDisk(String name) throws Exception {
        File file = new File(getTargetContext().getDataDir(), "shared_prefs/" + name + ".xml");
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The real controller's failure path, as a finished owner check or timeout reaches it, then a setup refresh. */
    private void inject(String message, boolean retry, ConnectionEventLog.Reason reason) {
        drive(s -> {
            Method failed = BleController.class.getDeclaredMethod("failed", String.class, boolean.class, ConnectionEventLog.Reason.class, int.class);
            failed.setAccessible(true); failed.invoke(s.ble, message, retry, reason, -1);
        });
    }

    /** A setup connection attempt on the active profile that the real controller ends with call, then a setup refresh. */
    private void drive(Step call) {
        main(() -> {
            ScooterService s = activity.service();
            onboarding().edit().putBoolean("connect_tried", true).remove("connect_requested").commit();
            if (s.ble.state() != BleController.State.DISCONNECTED) s.ble.disconnect();
            set(s.ble, "profile", profiles().active()); set(s.ble, "wanted", true);
            set(s.ble, "state", BleController.State.AUTHENTICATING);
            call.run(s);
            require(s.ble.state() == BleController.State.DISCONNECTED, "The failure left the controller " + s.ble.state());
            setup().refresh();
        });
        waitForIdleSync();
    }
    private interface Step { void run(ScooterService s) throws Exception; }

    /** The controller's owner-check gate for the scooter at address: true when the owner key may go. */
    private static boolean offer(ScooterService s, String address) {
        return (Boolean) invoke(s.ble, "mayOffer", new Class<?>[]{String.class}, address);
    }
    /** A made key's record (OnboardingScreen.keyOffered): "made", the tied scooter's address, "offered", or null. */
    private String record(String profileId) { return prefs("app_settings").getString("made_owner_key" + profileId, null); }
    private static String recordXml(String profileId, String value) { return "<string name=\"made_owner_key" + profileId + "\">" + value + "</string>"; }

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
