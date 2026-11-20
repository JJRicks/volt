package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
 * Disposable empty emulator, radio off. Every model's photo as the owner reviews the set (owner, Nov 17 2026: every NAVEE
 * scooter "positioned and sized exactly like the ut5 max"):
 * <ul>
 *   <li>sheet: one picture of every model Volt names (the UT5 MAX first, then by name), each photo in a square card the way
 *       Home's parked card shows it (ScooterPhotos.show into a FIT_CENTER ImageView), with its name and Model ID, in the
 *       theme given (light or dark);</li>
 *   <li>folded: every folded charging picture (ChargeFolded), two across, each at the same share of its card's width
 *       with its tyres on one line, as the charging view places them;</li>
 *   <li>home: Home in Widgets and Clean on the developer simulator, parked, as the UT5 MAX, GT3 Max, GT5 Max and N65i;</li>
 *   <li>identity: Service › Compatibility as the NT5 Max 25431 (its name shared with 25432): the identity card's title
 *       carries the Model ID beside the name, while Home's header shows the name alone (council review of #547, F02).</li>
 * </ul>
 * Without {@code -e mode look} it also requires every scooter in NAVEE's model catalog (the maintainer's copy, 17 November
 * 2026) to be named and pictured, the P50 named only (the catalog has no picture of it). {@code look} only takes the
 * pictures, so the same file run on an older build gives the before pictures. Sends no scooter command.
 */
public final class ModelPhotosValidation extends ValidationInstrumentation {
    /** Every scooter Model ID in the catalog. */
    private static final String[] CATALOG = {"27452", "25463", "25093", "26432", "26202", "26192", "26122", "26232", "26342", "26112", "26142",
            "25431", "25852", "24492", "26582", "25432", "26572", "25732", "25472", "25382", "25462", "25452", "25172", "25362", "25092", "25292",
            "24432", "25192", "25182", "24422", "24162", "25042", "25052", "25062", "24412", "24382", "24372", "24362", "23263", "24222", "24172",
            "23452", "24012", "24182", "24022", "24032", "23531", "23262", "23261", "23342", "23341", "23222", "23221", "23272", "23271", "23282",
            "23281", "23292", "23291", "22131", "23141", "23142"};
    private static final String NO_PICTURE = "23531";
    /** Home's sample models: "" is the simulator's UT5 MAX. */
    private static final String[][] HOME = {{"", "ut5max"}, {"24182", "gt3max"}, {"25462", "gt5max"}, {"22131", "n65i"}};
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("UT5 MAX", "000000000000", 0); p.serial = "X2538-SYNTHETIC";
                p.lastConnected = 1767225600000L; profiles.update(p); profiles.select(p.id); OnboardingScreen.markDone(getTargetContext());
            });
            String theme = args.getString("theme", "light"), size = args.getString("size_label", "412");
            for (String style : args.getString("styles", "widgets,clean").split(",")) {
                launch(theme, style);
                if (style.equals(args.getString("styles", "widgets,clean").split(",")[0]) && !"false".equals(args.getString("sheet"))) { sheet(size + "-" + theme); folded(size + "-" + theme); }
                main(() -> require(service.setSimulation(true), "Simulation did not start"));
                for (String[] model : HOME) {
                    main(() -> { service.ble.setSimulationModel(model[0]); service.ble.setSimulationScene(ScooterSimulation.Scene.PARKED); service.ble.tickSimulation(); });
                    await(() -> service.ble.ready(), "The simulated scooter is not ready");
                    main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home"));
                    settle();
                    main(() -> { View scroll = (View) field(activity, "scroll"); if (scroll != null) scroll.scrollTo(0, 0); activity.redraw(); });
                    settle(); SystemClock.sleep(900); settle();
                    if (!look()) main(() -> require(photoKey(service.modelDescriptor(service.profiles.active())).equals(model[1]),
                            "Home's model is " + model[1] + ": " + photoKey(service.modelDescriptor(service.profiles.active()))));
                    screenshot("home-" + size + "-" + theme + "-" + style + "-" + model[1]);
                }
                if (!look() && style.equals(args.getString("styles", "widgets,clean").split(",")[0])) identity(size + "-" + theme);
                main(() -> { service.ble.setSimulationModel(""); require(service.setSimulation(false), "Simulation did not stop"); });
                settle();
            }
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> { if (service != null && service.simulating()) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private boolean look() { return "look".equals(args.getString("mode")); }

    private void launch(String theme, String style) throws Exception {
        if (activity != null) { MainActivity old = activity; main(old::finish); SystemClock.sleep(800); settle(); }
        require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                .putBoolean("developer_options", true).putString("theme", theme).putString("units", "km")
                .putString("home_style", style).putBoolean("home_style_chosen", true).putBoolean("home_whats_new_shown", true).commit(), "Could not seed preferences");
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().getWidth() > 0, "App did not launch"); service = activity.service();
        SystemClock.sleep(1500); settle();
    }

    /**
     * The contact sheet: every named model's photo in a square card on the theme's surface, three across at the window's
     * width, drawn off screen into one tall picture.
     */
    @SuppressWarnings("unchecked")
    private void sheet(String label) throws Exception {
        List<String[]> models = new ArrayList<>();
        Map<String, String[]> known = (Map<String, String[]>) callStatic("com.jjricks.volt.ModelRegistry", "knownModels", new Class<?>[0]);
        for (Map.Entry<String, String[]> e : known.entrySet()) models.add(new String[]{e.getKey(), e.getValue()[0]});
        Collections.sort(models, (a, b) -> { int n = a[1].compareToIgnoreCase(b[1]); return n != 0 ? n : a[0].compareTo(b[0]); });
        models.add(0, new String[]{"25382", "UT5 MAX"});
        if (!look()) {
            Set<String> listed = new HashSet<>(); for (String[] m : models) listed.add(m[0]);
            for (String pid : CATALOG) require(listed.contains(pid), "The catalog's " + pid + " is not named");
        }
        AtomicReference<Bitmap> drawn = new AtomicReference<>();
        AtomicReference<View> rendered = new AtomicReference<>();
        main(() -> {
            Ui ui = activity.ui();
            float dp = activity.getResources().getDisplayMetrics().density;
            int width = root().getWidth(), pad = Math.round(16 * dp), gap = Math.round(8 * dp), columns = 3;
            int cell = (width - 2 * pad - (columns - 1) * gap) / columns;
            LinearLayout page = new LinearLayout(activity); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(ui.bg);
            page.setPadding(pad, pad, pad, pad);
            TextView title = new TextView(activity); title.setTextColor(ui.text); title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setText("Model photos: " + models.size() + " models, " + (ui.dark ? "dark" : "light")); page.addView(title);
            LinearLayout row = null; int shown = 0;
            for (int i = 0; i < models.size(); i++) {
                String[] m = models.get(i);
                if (i % columns == 0) {
                    row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = gap; page.addView(row, lp);
                }
                LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
                FrameLayout frame = new FrameLayout(activity);
                GradientDrawable surface = new GradientDrawable(); surface.setColor(ui.surface); surface.setCornerRadius(14 * dp); frame.setBackground(surface);
                ImageView photo = new ImageView(activity); photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                DeviceDescriptor d = DeviceDescriptor.simulated(m[0].equals("25382") ? "" : m[0], 0, null);
                boolean has = (Boolean) callStatic("com.jjricks.volt.ScooterPhotos", "show", new Class<?>[]{ImageView.class, DeviceDescriptor.class, int.class}, photo, d, 2);
                if (has) shown++;
                if (!look()) require(has != m[0].equals(NO_PICTURE), m[1] + " (" + m[0] + ") " + (has ? "has a photo" : "has no photo"));
                frame.addView(photo, new FrameLayout.LayoutParams(cell, cell));
                card.addView(frame, new LinearLayout.LayoutParams(cell, cell));
                TextView name = new TextView(activity); name.setTextColor(ui.text); name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); name.setText(m[1]);
                name.setGravity(Gravity.CENTER_HORIZONTAL); name.setMaxLines(1);
                TextView id = new TextView(activity); id.setTextColor(ui.muted); id.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                id.setText(m[0] + " · " + photoKey(d)); id.setGravity(Gravity.CENTER_HORIZONTAL); id.setMaxLines(1);
                card.addView(name, new LinearLayout.LayoutParams(cell, LinearLayout.LayoutParams.WRAP_CONTENT));
                card.addView(id, new LinearLayout.LayoutParams(cell, LinearLayout.LayoutParams.WRAP_CONTENT));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(cell, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i % columns != 0) cp.leftMargin = gap;
                row.addView(card, cp);
            }
            page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            page.layout(0, 0, page.getMeasuredWidth(), page.getMeasuredHeight());
            Bitmap bitmap = Bitmap.createBitmap(page.getMeasuredWidth(), page.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
            page.draw(new Canvas(bitmap)); drawn.set(bitmap); rendered.set(page);
            checks.add(label + ": " + models.size() + " models, " + shown + " with a photo, cells " + Math.round(cell / dp) + " dp");
        });
        File file = new File(dir(), "sheet-" + label + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) { drawn.get().compress(Bitmap.CompressFormat.PNG, 100, out); } finally { drawn.get().recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.writeView(this, file, rendered.get());
    }

    /** Compatibility's identity card names a model with its Model ID; Home's header keeps the name alone. */
    private void identity(String label) throws Exception {
        main(() -> { service.ble.setSimulationModel("25431"); service.ble.tickSimulation(); });
        await(() -> service.ble.ready(), "The simulated NT5 Max is not ready");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home")); settle();
        main(() -> { activity.redraw(); }); settle(); SystemClock.sleep(600); settle();
        main(() -> { List<String> shown = texts(root()); require(shown.contains("NT5 Max") && !shown.contains("NT5 Max \u00b7 25431"), "Home names the NT5 Max alone: " + shown); });
        HubTestPages.reach(this, activity, HubPages.SERVICE_COMPATIBILITY); settle(); SystemClock.sleep(600); settle();
        main(() -> require(texts(root()).contains("NT5 Max \u00b7 25431"), "The compatibility card does not show NT5 Max · 25431: " + texts(root())));
        screenshot("identity-" + label + "-nt5max-25431");
        checks.add(label + ": Compatibility shows NT5 Max · 25431; Home shows NT5 Max");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home")); settle();
    }
    private static List<String> texts(View view) {
        List<String> out = new ArrayList<>();
        if (view instanceof TextView && view.isShown()) out.add(String.valueOf(((TextView) view).getText()));
        if (view instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) out.addAll(texts(((android.view.ViewGroup) view).getChildAt(i)));
        return out;
    }

    /** The folded charging pictures: each cut-out at 80% of its card's width, tyres on the card's floor (ChargeFolded.place). */
    private void folded(String label) throws Exception {
        String[] keys = {"st3pro", "n65i", "gt3max", "gt5max", "xt5ultra", "g5pro", "gt3", "gt3pro", "gt5pro", "k100max", "n65iii", "v25series",
                "v40series", "xt5max", "xt5pro"};
        AtomicReference<Bitmap> drawn = new AtomicReference<>();
        AtomicReference<View> rendered = new AtomicReference<>();
        main(() -> {
            Ui ui = activity.ui();
            float dp = activity.getResources().getDisplayMetrics().density;
            int width = root().getWidth(), pad = Math.round(16 * dp), gap = Math.round(8 * dp), columns = 2;
            int cellW = (width - 2 * pad - (columns - 1) * gap) / columns, cellH = Math.round(cellW * .62f);
            LinearLayout page = new LinearLayout(activity); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(ui.bg);
            page.setPadding(pad, pad, pad, pad);
            TextView title = new TextView(activity); title.setTextColor(ui.text); title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setText("Folded charging pictures (" + (ui.dark ? "dark" : "light") + ")"); page.addView(title);
            LinearLayout row = null; int shown = 0; List<Bitmap> cuts = new ArrayList<>();
            for (int i = 0; i < keys.length; i++) {
                if (i % columns == 0) {
                    row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = gap; page.addView(row, lp);
                }
                int id = activity.getResources().getIdentifier(keys[i] + "_folded", "drawable", activity.getPackageName());
                if (!look()) require(id != 0, "No folded picture for " + keys[i]);
                Bitmap cut = id == 0 ? null : android.graphics.BitmapFactory.decodeResource(activity.getResources(), id);
                Object model = callStatic("com.jjricks.volt.ChargeFolded", "forKey", new Class<?>[]{String.class}, keys[i]);
                int tyreY = cut == null ? 0 : model == null ? cut.getHeight() : (Integer) field(model, "tyreY");
                if (cut != null) { shown++; cuts.add(cut); }
                LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
                // The cut-out at 80% of the card's width, centred, its tyres on the card's floor line (ChargeFolded.place).
                View picture = new View(activity) {
                    final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
                    final android.graphics.Paint floor = new android.graphics.Paint();
                    @Override protected void onDraw(Canvas canvas) {
                        float w = getWidth(), floorY = getHeight() - 14 * dp;
                        floor.setColor(ui.muted); floor.setStrokeWidth(dp);
                        canvas.drawLine(8 * dp, floorY, w - 8 * dp, floorY, floor);
                        if (cut == null) return;
                        float scale = .8f * w / cut.getWidth(), l = (w - cut.getWidth() * scale) / 2, t = floorY - tyreY * scale;
                        canvas.drawBitmap(cut, null, new android.graphics.RectF(l, t, l + cut.getWidth() * scale, t + cut.getHeight() * scale), paint);
                    }
                };
                GradientDrawable surface = new GradientDrawable(); surface.setColor(ui.surface); surface.setCornerRadius(14 * dp); picture.setBackground(surface);
                card.addView(picture, new LinearLayout.LayoutParams(cellW, cellH));
                TextView name = new TextView(activity); name.setTextColor(ui.text); name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                name.setText(keys[i] + "_folded"); name.setMaxLines(1);
                card.addView(name, new LinearLayout.LayoutParams(cellW, LinearLayout.LayoutParams.WRAP_CONTENT));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(cellW, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i % columns != 0) cp.leftMargin = gap;
                row.addView(card, cp);
            }
            page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            page.layout(0, 0, page.getMeasuredWidth(), page.getMeasuredHeight());
            Bitmap bitmap = Bitmap.createBitmap(page.getMeasuredWidth(), page.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
            page.draw(new Canvas(bitmap)); drawn.set(bitmap); rendered.set(page);
            for (Bitmap cut : cuts) cut.recycle(); // drawn; the census below reads only the view tree's text
            checks.add(label + ": " + shown + " folded pictures");
        });
        File file = new File(dir(), "folded-" + label + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) { drawn.get().compress(Bitmap.CompressFormat.PNG, 100, out); } finally { drawn.get().recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.writeView(this, file, rendered.get());
    }

    private String photoKey(DeviceDescriptor d) { return (String) callStatic("com.jjricks.volt.ModelRegistry", "photo", new Class<?>[]{DeviceDescriptor.class}, d); }

    // ---- Plumbing ---------------------------------------------------------------------------------------------

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
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "model-photos-validation"); d.mkdirs(); return d; }
    private void screenshot(String name) throws Exception {
        settle(); SystemClock.sleep(400); waitForIdleSync();
        File file = new File(dir(), name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
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
    /** A package-private static in the app (ScooterPhotos, ModelRegistry), by reflection so this file builds on older versions too. */
    private Object callStatic(String className, String name, Class<?>[] types, Object... values) {
        try { Method method = Class.forName(className).getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(null, values); }
        catch (java.lang.reflect.InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
}
