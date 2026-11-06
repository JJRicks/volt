package com.jjricks.volt.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.RideStore;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * Empty-emulator-only timing check for app launch and the first Rides open. Never connects Bluetooth.
 * mode=seed writes N finished synthetic journals into an empty app; the runner then ends the process.
 * mode=measure starts cold: it times launch (first frame, the service's start where its ride read runs,
 * the service command that starts a connection) and opening Rides twice, with the longest main-thread
 * message in each window. Results are printed as instrumentation result keys, in milliseconds.
 */
public final class RidesTimingValidation extends Instrumentation {
    private Bundle args;
    private final List<long[]> draws = new ArrayList<>();
    /** Main-thread messages: {start, end}, with labels in the same order. */
    private final List<long[]> messages = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private long messageStart; private String messageLabel;

    @Override public void onCreate(Bundle arguments) {
        args = arguments == null ? new Bundle() : arguments;
        if ("measure".equals(args.getString("mode"))) Looper.getMainLooper().setMessageLogging(line -> {
            long now = SystemClock.uptimeMillis();
            if (line.startsWith(">>>>> Dispatching to ")) { messageStart = now; messageLabel = line.substring(21); }
            else if (line.startsWith("<<<<< Finished") && messageLabel != null) {
                synchronized (messages) { messages.add(new long[]{messageStart, now}); labels.add(messageLabel); }
                messageLabel = null;
            }
        });
        start();
    }

    @Override public void callActivityOnCreate(Activity activity, Bundle icicle) {
        super.callActivityOnCreate(activity, icicle);
        View decor = activity.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnDrawListener(() -> { synchronized (draws) { draws.add(new long[]{SystemClock.uptimeMillis()}); } });
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        MainActivity activity = null;
        try {
            check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"), "Refusing physical phone");
            Context context = getTargetContext();
            BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter();
            check(adapter == null || !adapter.isEnabled(), "Bluetooth must be off");
            if ("seed".equals(args.getString("mode"))) { seed(context, result); result.putString("result", "PASS"); return; }
            check("measure".equals(args.getString("mode")), "mode must be seed or measure");
            File rides = new File(context.getFilesDir(), "rides");
            String[] seeded = rides.list();
            check(seeded != null && seeded.length > 0, "Run mode=seed first");
            result.putInt("rides", seeded.length);
            result.putLong("process_to_measure_ms", SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis());

            long t0 = SystemClock.uptimeMillis();
            // Not startActivitySync: with a long ride history main never goes idle, and that is what this measures.
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            activity = (MainActivity) waitForMonitorWithTimeout(monitor, 60000);
            check(activity != null, "Volt opened");
            long until = SystemClock.uptimeMillis() + 30000;
            while (activity.service() == null && SystemClock.uptimeMillis() < until) SystemClock.sleep(10);
            check(activity.service() != null, "Service ready");
            long serviceReady = SystemClock.uptimeMillis();
            SystemClock.sleep(3000);
            long firstDraw = firstDrawAfter(t0);
            long[] create = message(t0, ": 114"), command = message(t0, ": 115");
            check(create != null && command != null, "Service create and start command seen");
            result.putLong("launch_first_frame_ms", firstDraw - t0);
            result.putLong("launch_service_create_start_ms", create[0] - t0);
            result.putLong("launch_service_create_ms", create[1] - create[0]);
            result.putLong("launch_connect_start_ms", command[0] - t0);
            result.putLong("launch_service_bound_ms", serviceReady - t0);
            result.putString("launch_service_create_before_first_frame", Boolean.toString(create[0] < firstDraw));
            putLongest(result, "launch", t0, t0 + 3000);
            check(activity.service().rides != null, "Ride storage available");

            for (String pass : new String[]{"rides_cold", "rides_warm"}) {
                final MainActivity shown = activity;
                main(() -> invoke(shown, "select", new Class[]{String.class}, "home")); SystemClock.sleep(1500);
                long[] selected = new long[2];
                main(() -> { selected[0] = SystemClock.uptimeMillis(); invoke(shown, "select", new Class[]{String.class}, "rides"); selected[1] = SystemClock.uptimeMillis(); });
                // The rows may arrive after the first frame (skeleton rows while a worker reads the rides). On a busy
                // emulator the background read of 41 one-hour rides took over 20 s; the rows only have to arrive (not gated).
                long rows = waitForRows(field(activity, "screen"), seeded.length, selected[0] + 60000);
                SystemClock.sleep(2500);
                Object screen = field(activity, "screen");
                check(screen.getClass().getSimpleName().equals("RidesScreen"), "Rides is showing");
                check((int) field(screen, "savedCount") == seeded.length && ((android.view.ViewGroup) field(screen, "history")).getChildCount() > 0, "History rows drawn");
                result.putLong(pass + "_select_ms", selected[1] - selected[0]);
                result.putLong(pass + "_busy_ms", busy(selected[1], selected[1] + 2500));
                result.putLong(pass + "_first_row_ms", firstDrawAfter(selected[1]) - selected[0]);
                // The first frame after the real rows were built (equal to first_row when they come at once).
                result.putLong(pass + "_rows_ms", firstDrawAfter(rows - 2) - selected[0]);
                putLongest(result, pass, selected[0], selected[0] + 2500);
            }

            // A second, read-only store on a worker: the cost of one full ride read with a warm page cache.
            long read = SystemClock.uptimeMillis();
            new RideStore(new File(context.getFilesDir(), "rides")).list();
            result.putLong("worker_store_read_and_list_ms", SystemClock.uptimeMillis() - read);
            check(!activity.service().ble.connected() && !activity.service().isRecording(), "No scooter connection or active recording");
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(error));
        } finally {
            if (activity != null) { final MainActivity shown = activity; runOnMainSync(() -> { shown.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); }
            Looper.getMainLooper().setMessageLogging(null);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** N finished journals copied from one template ride, each a day apart, into an otherwise empty app. */
    private void seed(Context context, Bundle result) throws Exception {
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents"})
            check(context.getSharedPreferences(name, 0).getAll().isEmpty(), "Requires empty preferences");
        check(context.getFilesDir().list().length == 0 && context.databaseList().length == 0, "Requires empty private data");
        int count = Integer.parseInt(args.getString("rides", "41")), seconds = Integer.parseInt(args.getString("seconds", "3600"));
        check(count > 0 && count <= 1000 && seconds > 1 && seconds <= 4 * 3600, "Reasonable ride fixture");
        context.getSharedPreferences("app_settings", 0).edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                .putBoolean("map_basemap_enabled", false).putString("theme", args.getString("theme", "dark")).commit();
        runOnMainSync(() -> new ScooterProfiles(context).create("Synthetic timing validation", "000000000000", 0));
        File templateDir = new File(context.getCacheDir(), "timing-template");
        RideStore template = new RideStore(templateDir);
        long wall = System.currentTimeMillis() - (count + 1L) * 86400000L;
        String id = template.start("fixture", "Timing ride", wall, 1000);
        for (int i = 0; i < seconds; i++) {
            long mono = 1000 + i * 1000L; double speed = 20 + 4 * Math.sin(i / 15.0), watts = 600 + 500 * Math.sin(i / 20.0);
            Map<String, RideStore.Sample> row = new LinkedHashMap<>();
            row.put("voltage_v", new RideStore.Sample(50 - i / 400.0, "", "V", 0x72, mono));
            row.put("current_a", new RideStore.Sample(watts / 50, "", "A", 0x72, mono));
            row.put("power_w", new RideStore.Sample(watts, "", "W", 0x72, mono));
            row.put("speed_kmh", new RideStore.Sample(speed, "", "km/h", 0x72, mono));
            row.put("odometer_km", new RideStore.Sample(100 + i * .005, "", "km", 0x72, mono));
            row.put("trip_km", new RideStore.Sample(i * .005, "", "km", 0x72, mono));
            row.put("battery_percent", new RideStore.Sample(90 - i / 60.0, "", "%", 0x72, mono));
            row.put("temperature_c", new RideStore.Sample(21 + i / 600.0, "", "C", 0x72, mono));
            row.put("controller_temperature_c", new RideStore.Sample(30 + i / 400.0, "", "C", 0x72, mono));
            row.put("drive_mode", new RideStore.Sample(3d, "SPORT", "", 0x72, mono));
            template.sample(wall + i * 1000L, mono, row);
        }
        template.stop(wall + seconds * 1000L, 1000 + seconds * 1000L);
        File source = new File(templateDir, id + ".csv");
        List<String> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) { check(line.indexOf('"') < 0, "Template has no quoted fields"); lines.add(line); }
        }
        File rides = new File(context.getFilesDir(), "rides");
        check(rides.mkdirs(), "Ride folder created");
        long bytes = 0;
        for (int k = 0; k < count; k++) {
            File target = new File(rides, UUID.randomUUID() + ".csv");
            try (Writer out = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)) {
                for (String line : lines) {
                    String[] f = line.split(",", -1);
                    if (f.length == 15 && "1".equals(f[0])) { f[3] = Long.toString(Long.parseLong(f[3]) + k * 86400000L); line = String.join(",", f); }
                    out.write(line); out.write('\n');
                }
            }
            bytes += target.length();
        }
        for (File f : templateDir.listFiles()) f.delete(); templateDir.delete();
        RideStore.Summary first = new RideStore(rides).list().get(0);
        check(first.sampleCount == seconds && !first.active, "Seeded journals replay as finished rides");
        result.putInt("seeded_rides", count); result.putLong("seeded_bytes", bytes);
    }

    /** When the history rows were built, polled from this thread while main builds them (2 ms resolution). */
    private static long waitForRows(Object screen, int count, long until) throws Exception {
        while (SystemClock.uptimeMillis() < until) {
            if ((int) field(screen, "savedCount") == count && !((String) field(screen, "historySignature")).isEmpty()) return SystemClock.uptimeMillis();
            SystemClock.sleep(2);
        }
        // Too late: the existing "History rows drawn" check then fails.
        return until;
    }
    private long firstDrawAfter(long time) {
        synchronized (draws) { for (long[] draw : draws) if (draw[0] >= time) return draw[0]; }
        throw new AssertionError("No frame drawn");
    }
    /** The first ActivityThread$H message with this code (": 114" CREATE_SERVICE, ": 115" SERVICE_ARGS). */
    private long[] message(long after, String code) {
        synchronized (messages) {
            for (int i = 0; i < messages.size(); i++)
                if (messages.get(i)[0] >= after && labels.get(i).contains("ActivityThread$H") && labels.get(i).endsWith(code)) return messages.get(i);
        }
        return null;
    }
    /** Main-thread time spent in messages that started in the window: near the window's length means main never rested. */
    private long busy(long from, long to) {
        long total = 0;
        synchronized (messages) { for (long[] m : messages) if (m[0] >= from && m[0] < to) total += m[1] - m[0]; }
        return total;
    }
    private void putLongest(Bundle result, String prefix, long from, long to) {
        long longest = -1; String label = "";
        synchronized (messages) {
            for (int i = 0; i < messages.size(); i++) {
                long[] m = messages.get(i);
                if (m[0] >= from && m[0] < to && m[1] - m[0] > longest) { longest = m[1] - m[0]; label = labels.get(i); }
            }
        }
        result.putLong(prefix + "_longest_main_message_ms", longest);
        result.putString(prefix + "_longest_main_message", label.replaceAll("\\{[0-9a-f]+\\}|@[0-9a-f]+", ""));
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { failure.set(e); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get());
    }
    private static Object field(Object target, String name) {
        try { Class<?> c = target.getClass(); while (c != null) { try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception e) { throw new IllegalStateException(name, e); }
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, values); }
        catch (Exception e) { throw new IllegalStateException(name, e); }
    }
}
