package com.jjricks.volt.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * Riding mode's chips holding readings beyond drive mode, temperature and the clock (a tester's request). Halo's three chips
 * and Trace's two are set to named readings (Average and Max speed side by side, odometer, current, battery, trip, ride time)
 * on the simulator, at 344 dp, a split-screen half, sideways, wide, large text and night vision. At each it checks that every
 * chip shown is whole, carries its name or icon unless it names itself, that Average and Max never stand on the same icon
 * alone, and that each chip's spoken text names its reading and value. Halo's row is then measured at every width from roomy
 * to nothing, so it lands on each rung: names shorten, then turn into icons, and only then does the last chip go. Screenshots
 * go to the evidence folder. Simulator only.
 */
public final class RidingChipChoicesValidation extends Instrumentation {
    private MainActivity activity;
    private String profileId, immersivePrevious;
    private File evidence;
    private final List<String> notes = new ArrayList<>();

    @Override public void onCreate(Bundle args) { start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
            if (!(hw.contains("ranchu") || hw.contains("goldfish"))) throw new IllegalStateException("emulator only");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "riding-chip-choices");
            if (!evidence.isDirectory() && !evidence.mkdirs()) throw new IllegalStateException("evidence folder");
            immersivePrevious = shell("settings get secure immersive_mode_confirmations").trim();
            shell("settings put secure immersive_mode_confirmations confirmed");
            shell("wm density 480");
            if (!prefs().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                    .putString("units", "mi").commit())
                throw new IllegalStateException("settings");
            main(() -> {
                OnboardingScreen.markDone(getTargetContext());
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic chip scooter", "0F1E2D3C4B5A", 0);
                p.mac = "C0:FF:EE:00:00:08"; p.lastConnected = System.currentTimeMillis() - 60000; profiles.update(p);
                profileId = p.id;
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && activity.getWindow().getDecorView().getWidth() > 0, "launch"); settle();
            main(() -> { if (!activity.service().setSimulation(true)) throw new IllegalStateException("simulation"); }); settle();
            await(() -> activity.service().ble.ready(), "simulation ready");
            main(() -> activity.openRiding()); settle();
            await(() -> "RidingScreen".equals(field(activity, "screen").getClass().getSimpleName()), "riding did not open");
            run();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(error));
        } finally {
            try { java.nio.file.Files.write(new File(evidence, "notes.txt").toPath(), notes); } catch (Exception ignored) { }
            if (activity != null) try { main(() -> { invoke(activity, "closeRiding"); activity.service().setSimulation(false); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            try {
                prefs().edit().remove("text_scale").remove("riding_night_vision").remove("riding_layout_v1_" + profileId).commit();
                shell("wm size reset"); shell("wm density reset");
                shell(immersivePrevious == null || immersivePrevious.isEmpty() || immersivePrevious.equals("null") ? "settings delete secure immersive_mode_confirmations" : "settings put secure immersive_mode_confirmations " + immersivePrevious);
            } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private static final String SPEEDS = halo("average_speed", "max_speed", "odometer");
    private static final String ELECTRIC = halo("current", "battery", "trip");
    private static final String ICONS = halo("average_speed", "trip", "clock");
    private static final String TRACE = "{\"v\":1,\"frame\":\"trace\",\"frames\":{\"trace\":{\"spots\":{\"chip_1\":\"max_speed\",\"chip_2\":\"ride_time\"}}}}";

    private static String halo(String a, String b, String c) {
        return "{\"v\":1,\"frame\":\"halo\",\"frames\":{\"halo\":{\"spots\":{\"chip_1\":\"" + a + "\",\"chip_2\":\"" + b + "\",\"chip_3\":\"" + c + "\"}}}}";
    }

    private void run() throws Exception {
        int[] phone = {344, 882}, split = {411, 440}, sideways = {882, 344}, wide = {850, 1100};
        // The default Halo, unchanged: drive mode, temperature and the clock, for comparing with a build without this change.
        show("halo-default-344", "{\"v\":1,\"frame\":\"halo\",\"frames\":{}}", phone, 1f, false);
        show("halo-speeds-344", SPEEDS, phone, 1f, false);
        show("halo-electric-344", ELECTRIC, phone, 1f, false);
        show("halo-icons-344", ICONS, phone, 1f, false);
        show("halo-speeds-344-large", SPEEDS, phone, 1.5f, false);
        show("halo-speeds-344-night", SPEEDS, phone, 1f, true);
        show("halo-speeds-split", SPEEDS, split, 1f, false);
        show("halo-speeds-sideways", SPEEDS, sideways, 1f, false);
        show("halo-speeds-wide", SPEEDS, wide, 1f, false);
        show("halo-speeds-wide-large", SPEEDS, wide, 1.5f, false);
        show("trace-344", TRACE, phone, 1f, false);
        show("trace-344-large", TRACE, phone, 1.5f, false);
        show("trace-split", TRACE, split, 1f, false);
        show("trace-sideways", TRACE, sideways, 1f, false);
        show("trace-night", TRACE, phone, 1f, true);
        // Km: the same readings in the other units.
        prefs().edit().putString("units", "km").commit();
        show("halo-speeds-344-km", SPEEDS, phone, 1f, false);
        prefs().edit().putString("units", "mi").commit();
        sweep("speeds", SPEEDS, phone);
        sweep("icons", ICONS, phone);
    }

    private void setup(String layout, int[] window, float text, boolean night) throws Exception {
        shell("wm size " + (window[0] * 3) + "x" + (window[1] * 3));
        if (!prefs().edit().putFloat("text_scale", text).putString("riding_night_vision", night ? "on" : "off")
                .putString("riding_layout_v1_" + profileId, layout).commit()) throw new IllegalStateException("prefs");
        SystemClock.sleep(1200);
        await(() -> activity.getWindow().getDecorView().getWidth() == window[0] * 3, "window width");
        main(() -> activity.rebuild());
        settle();
        await(() -> "RidingScreen".equals(field(activity, "screen").getClass().getSimpleName()), "riding gone");
        for (int i = 0; i < 3; i++) { main(() -> { activity.service().ble.tickSimulation(); activity.redraw(); }); SystemClock.sleep(400); }
        settle(); SystemClock.sleep(500); settle();
    }

    private void show(String label, String layout, int[] window, float text, boolean night) throws Exception {
        setup(layout, window, text, night);
        String[] verdict = new String[1];
        main(() -> verdict[0] = check(label));
        notes.add(verdict[0]);
        android.util.Log.i("VoltChips", verdict[0]);
        shot(label);
    }

    /** Every chip shown is whole and identifiable, and says its name and value. */
    private String check(String label) {
        StringBuilder out = new StringBuilder(label + ":");
        List<?> chips = (List<?>) field(field(activity, "screen"), "chips");
        require(!chips.isEmpty(), label + ": no chips");
        ViewGroup group = (ViewGroup) ((View) chips.get(0)).getParent();
        int used = 0, shown = 0, iconOnlySpeeds = 0;
        boolean stacked = group instanceof LinearLayout && ((LinearLayout) group).getOrientation() == LinearLayout.VERTICAL;
        for (Object chip : chips) {
            View v = (View) chip;
            if (v.getVisibility() != View.VISIBLE) { out.append(" [").append(field(chip, "content")).append(" gone]"); continue; }
            shown++;
            String content = (String) field(chip, "content");
            TextView value = (TextView) field(chip, "text"), name = (TextView) field(chip, "name");
            View icon = (View) field(chip, "icon");
            float needed = value.getPaint().measureText(value.getText().toString());
            require(value.getWidth() >= Math.floor(needed), label + ": " + content + " value cut: '" + value.getText() + "' " + value.getWidth() + " < " + needed);
            boolean nameShown = name.getVisibility() == View.VISIBLE, iconShown = icon != null && icon.getVisibility() == View.VISIBLE;
            if (nameShown) {
                float n = name.getPaint().measureText(name.getText().toString());
                require(name.getWidth() >= Math.floor(n) && name.getLineCount() <= 1, label + ": " + content + " name cut: '" + name.getText() + "'");
                require(name.getTextSize() >= value.getTextSize() * 0.8f, label + ": " + content + " name is tiny");
            }
            boolean self = content.equals("drive_mode") || content.equals("battery_temperature") || content.equals("clock");
            if (!self) require(nameShown || iconShown, label + ": " + content + " shows a bare value '" + value.getText() + "'");
            if (!self) require(!(nameShown && iconShown), label + ": " + content + " shows both name and icon");
            if (!nameShown && iconShown && (content.equals("average_speed") || content.equals("max_speed"))) iconOnlySpeeds++;
            CharSequence spoken = v.getContentDescription();
            require(spoken != null && spoken.toString().contains(value.getText().toString()) || "—".contentEquals(value.getText()),
                    label + ": " + content + " spoken '" + spoken + "' lacks its value '" + value.getText() + "'");
            if (!self) require(spoken.toString().toLowerCase(Locale.ROOT).contains(fullName(content)), label + ": " + content + " spoken '" + spoken + "' lacks its name");
            int[] at = new int[2], in = new int[2];
            v.getLocationOnScreen(at); group.getLocationOnScreen(in);
            require(at[0] >= in[0] - 1 && at[0] + v.getWidth() <= in[0] + group.getWidth() + 1, label + ": " + content + " sticks out of its group");
            used += stacked ? 0 : v.getWidth() + margins(v);
            out.append(" [").append(content).append(' ').append(nameShown ? "'" + name.getText() + "' " : "").append(iconShown ? "icon " : "")
                    .append("'").append(value.getText()).append("' ").append(v.getWidth()).append("px spoken='").append(spoken).append("']");
        }
        require(shown > 0, label + ": every chip gone");
        require(iconOnlySpeeds < 2, label + ": Average and Max speed both on the speedometer alone");
        if (!stacked) require(used <= group.getWidth(), label + ": the row needs " + used + " of " + group.getWidth());
        return out.toString();
    }

    private static String fullName(String content) {
        switch (content) {
            case "average_speed": return "average speed";
            case "max_speed": return "max speed";
            case "odometer": return "odometer";
            case "current": return "current";
            case "battery": return "battery";
            case "trip": return "trip";
            case "ride_time": return "ride time";
            default: return content.replace('_', ' ');
        }
    }

    /**
     * Halo's real row measured at every width from roomy to nothing: every chip kept fits whole, keeps its name or icon, and
     * the row passes through its rungs in order (names shorten, then become icons, and a chip goes only after that).
     */
    private void sweep(String label, String layout, int[] window) throws Exception {
        setup(layout, window, 1f, false);
        String[] verdict = new String[1];
        main(() -> {
            List<?> chips = (List<?>) field(field(activity, "screen"), "chips");
            ViewGroup row = (ViewGroup) ((View) chips.get(0)).getParent();
            int unbounded = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            row.measure(unbounded, unbounded);
            int natural = 0;
            for (int i = 0; i < row.getChildCount(); i++) if (row.getChildAt(i).getVisibility() != View.GONE) natural += row.getChildAt(i).getMeasuredWidth() + margins(row.getChildAt(i));
            int lastCount = Integer.MAX_VALUE, shortAt = -1, iconAt = -1, dropAt = -1, fullAt = -1;
            for (int width = natural + 40; width >= 0; width--) {
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), unbounded);
                int used = 0, count = 0;
                boolean anyFull = false, anyShort = false, anyIcon = false;
                for (int i = 0; i < row.getChildCount(); i++) {
                    View chip = row.getChildAt(i);
                    if (chip.getVisibility() == View.GONE) continue;
                    count++;
                    used += chip.getMeasuredWidth() + margins(chip);
                    Object c = chips.get(i);
                    String content = (String) field(c, "content");
                    TextView value = (TextView) field(c, "text"), name = (TextView) field(c, "name");
                    View icon = (View) field(c, "icon");
                    float needed = value.getPaint().measureText(value.getText().toString());
                    require(value.getMeasuredWidth() >= Math.floor(needed), label + " width " + width + ": '" + value.getText() + "' cut");
                    boolean self = content.equals("drive_mode") || content.equals("battery_temperature") || content.equals("clock");
                    boolean nameShown = name.getVisibility() != View.GONE, iconShown = icon != null && icon.getVisibility() != View.GONE;
                    if (nameShown) require(name.getMeasuredWidth() >= Math.floor(name.getPaint().measureText(name.getText().toString())), label + " width " + width + ": name cut");
                    if (!self) {
                        require(nameShown || iconShown, label + " width " + width + ": " + content + " bare");
                        String[] names = (String[]) field(c, "names");
                        if (nameShown && names.length > 1 && !names[0].equals(names[1])) {
                            if (name.getText().toString().equals(names[0])) anyFull = true; else anyShort = true;
                        }
                        if (iconShown) anyIcon = true;
                    }
                }
                require(used <= width, label + " width " + width + ": chips and gaps need " + used);
                require(count <= lastCount, label + " width " + width + ": a chip came back on a narrower row");
                if (count < lastCount && lastCount != Integer.MAX_VALUE && dropAt < 0) dropAt = width;
                if (anyFull && fullAt < 0) fullAt = width;
                if (anyShort && count == 3 && shortAt < 0) shortAt = width;
                if (anyIcon && count == 3 && iconAt < 0) iconAt = width;
                lastCount = count;
            }
            row.requestLayout();
            require(fullAt > 0 && shortAt > 0 && dropAt > 0 && shortAt > dropAt, label + ": rungs out of order full=" + fullAt + " short=" + shortAt + " drop=" + dropAt);
            if (label.equals("icons")) require(iconAt > 0 && iconAt < shortAt && iconAt > dropAt, label + ": icons out of order icon=" + iconAt + " short=" + shortAt + " drop=" + dropAt);
            if (label.equals("speeds")) require(iconAt < 0, "speeds: Average and Max must keep their names (shared speedometer), odometer has no icon");
            verdict[0] = label + " sweep: natural " + natural + " full until " + fullAt + " short at " + shortAt + " icons at " + iconAt + " first drop at " + dropAt;
        });
        notes.add(verdict[0]);
        android.util.Log.i("VoltChips", verdict[0]);
    }

    private static int margins(View child) {
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
        return p.leftMargin + p.rightMargin;
    }

    private void shot(String name) throws Exception {
        waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("screenshot");
        File image = new File(evidence, name + ".png");
        try (FileOutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { bitmap.recycle(); }
        TextCensus.write(this, image);
    }
    private String shell(String command) throws Exception {
        StringBuilder out = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))))) {
            for (String line; (line = r.readLine()) != null; ) out.append(line).append('\n');
        }
        return out.toString();
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private SharedPreferences prefs() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        Throwable[] thrown = new Throwable[1];
        runOnMainSync(() -> { try { task.run(); } catch (Throwable t) { thrown[0] = t; } });
        if (thrown[0] != null) throw new AssertionError(thrown[0].toString(), thrown[0]);
    }
    private void await(BooleanSupplier c, String m) { long end = SystemClock.uptimeMillis() + 10000; while (!c.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(40); if (!c.getAsBoolean()) throw new IllegalStateException(m); }
    private static Object field(Object target, String name) { try { Class<?> t = target.getClass(); while (t != null) { try { Field f = t.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { t = t.getSuperclass(); } } throw new IllegalStateException(name); } catch (IllegalAccessException e) { throw new IllegalStateException(e); } }
    private static void invoke(Object target, String name) { try { Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); } }
}
