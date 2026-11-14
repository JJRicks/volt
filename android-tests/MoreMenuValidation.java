package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
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
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.HubText;
import com.jjricks.volt.ScooterProfiles;

/**
 * Fresh disposable emulator only. Settings (the gear page, More's index before the Hub layout) lines up on every width.
 * Since part 11 (#392) it is one column at every width, as the Layout Plan's settings-page mock-up draws it, with no
 * icon tiles: one card of app choices, then a card for the scooters and the data and one for Volt itself. Every row
 * shares one title edge, is a 48 dp target with a spoken name, and keeps its words whole and uncut. Pass -e wide true
 * or false to say which window class the emulator's size must give (Ui.wideLayout), so a run can't pass on the other.
 */
public final class MoreMenuValidation extends Instrumentation {
    private Bundle arguments;
    private MainActivity activity;
    private int assertions;
    private final ArrayList<String> screenshots = new ArrayList<>();
    /** The Settings cards' rows in the plan's order, by tag (null: the row has no tag; it is found by its menu section). */
    private static final String[][] CARDS = {
            {"home-style-menu", "preference-appearance", "preference-units-menu", "preference_haptics"},
            {"#Scooters", "data-backup"},
            {"settings-updates", "#About", "developer-options-menu"}};

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            require(arguments.containsKey("wide"), "Pass -e wide true or false");
            boolean wide = Boolean.parseBoolean(arguments.getString("wide"));
            // A saved More submenu (the old tab's) must not change what Settings opens on.
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                    .putString("theme", arguments.getString("theme", "dark")).putString("more_section", wide ? "App" : "Scooters").commit(), "Fixture settings failed");
            seed();
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "App did not launch"); settle();
            // The rider's way in: Home's gear (or the dock's All sheet on Widgets), tapped.
            HubTestPages.reach(this, activity, "settings"); settle();
            main(() -> require("settings".equals(HubTestPages.current(activity)), "Settings did not open from Home"));
            Object more = more();
            main(() -> require(Boolean.TRUE.equals(field(activity, "expanded")) == wide, "Expected the " + (wide ? "wide" : "phone") + " window at "
                    + activity.getResources().getConfiguration().screenWidthDp + " dp"));
            oneColumn(more);
            screenshot(wide ? "wide-menu" : "phone-menu");
            aligned(more);
            longTitle(more, wide ? "wide-long-title" : "phone-long-title");
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", assertions); result.putString("screenshots", screenshots.toString());
            result.putBoolean("physical_device_tested", false);
            try { if (activity != null) main(() -> activity.finish()); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /**
     * Settings is one column at this width: no two-pane menu and submenu (More's Panes), it opens on its index whatever
     * submenu the old More tab saved, and its cards share one left and one right edge inside the window.
     */
    private void oneColumn(Object more) {
        main(() -> {
            require(!Boolean.TRUE.equals(field(more, "wide")) && field(more, "menuScroll") == null && field(more, "sectionScroll") == null,
                    "Settings built More's two-pane layout");
            require(classNamed(((com.jjricks.volt.AppScreen) more).view(), "Panes") == null, "Settings holds a two-pane layout");
            require("".equals(field(more, "selectedSection")), "Settings opened on a saved submenu instead of its index");
            require(((View) field(more, "indexView")).isShown(), "Settings' index is hidden");
            List<LinearLayout> cards = cards(more);
            require(cards.size() == CARDS.length, "Settings shows " + cards.size() + " cards, not " + CARDS.length);
            int window = activity.getWindow().getDecorView().getWidth();
            int[] first = bounds(cards.get(0));
            require(first[0] >= 0 && first[1] <= window, "The first card runs past the window: " + first[0] + "-" + first[1] + " in " + window);
            for (LinearLayout card : cards) {
                int[] at = bounds(card);
                require(Math.abs(at[0] - first[0]) <= 1 && Math.abs(at[1] - first[1]) <= 1,
                        "Cards are not one column: " + at[0] + "-" + at[1] + " beside " + first[0] + "-" + first[1]);
            }
            headings(cards);
        });
    }
    /**
     * The plan's headings: none over the first card, "Scooters & data" right above the second and "About" right above
     * the third (part 10's text), each shown whole, above its card and inside the cards' column.
     */
    private void headings(List<LinearLayout> cards) {
        LinearLayout index = (LinearLayout) cards.get(0).getParent();
        View beforeFirst = index.indexOfChild(cards.get(0)) > 0 ? index.getChildAt(index.indexOfChild(cards.get(0)) - 1) : null;
        require(beforeFirst == null || !(beforeFirst instanceof TextView) || !beforeFirst.isShown()
                || !(String.valueOf(beforeFirst.getTag()).startsWith("settings-heading-")), "A heading sits over Settings' first card");
        String[][] expected = {{"settings-heading-scooters", HubText.settingsSectionScooters().render()},
                {"settings-heading-about", HubText.settingsSectionAbout().render()}};
        for (int c = 1; c <= 2; c++) {
            LinearLayout card = cards.get(c);
            int at = index.indexOfChild(card);
            require(at > 0 && index.getChildAt(at - 1) instanceof TextView, "Settings card " + (c + 1) + " has no heading right above it");
            TextView heading = (TextView) index.getChildAt(at - 1);
            String tag = expected[c - 1][0], text = expected[c - 1][1];
            require(tag.equals(heading.getTag()), "Settings card " + (c + 1) + "'s heading has tag " + heading.getTag() + ", not " + tag);
            require(heading.isShown() && text.contentEquals(heading.getText()), "Settings card " + (c + 1) + "'s heading reads \"" + heading.getText() + "\", not \"" + text + "\"");
            int[] h = bounds(heading), k = bounds(card);
            require(h[0] >= k[0] - 1 && h[1] <= k[1] + 1, "The " + text + " heading runs outside the cards' column");
            int[] top = new int[2], cardTop = new int[2];
            heading.getLocationOnScreen(top); card.getLocationOnScreen(cardTop);
            require(top[1] + heading.getHeight() <= cardTop[1] + 1, "The " + text + " heading overlaps its card");
            wholeAndUncut(heading, index, text + " heading");
        }
    }
    /**
     * Every row in every card: in the plan's card and order, no icon tile, one title edge for all, a 48 dp target with a
     * spoken name, the title's words whole (a break only at a space) and nothing cut, the value and chevron inside the row.
     */
    private void aligned(Object more) {
        AtomicReference<String> problem = new AtomicReference<>();
        AtomicReference<Integer> rows = new AtomicReference<>(0);
        main(() -> {
            List<LinearLayout> cards = cards(more);
            for (int c = 0; c < CARDS.length; c++) {
                int last = -1;
                for (String key : CARDS[c]) {
                    View row = key.startsWith("#") ? (View) field(menuRow(more, key.substring(1)), "view") : cards.get(c).findViewWithTag(key);
                    require(row != null && row.getParent() == cards.get(c), "The " + key + " row is not in Settings card " + (c + 1));
                    int at = cards.get(c).indexOfChild(row);
                    require(at > last, "The " + key + " row is out of the plan's order in card " + (c + 1)); last = at;
                }
            }
            Integer titleLeft = null; String first = null; int count = 0; int dp48 = activity.ui().dp(48);
            for (LinearLayout card : cards) for (int i = 0; i < card.getChildCount(); i++) {
                View child = card.getChildAt(i);
                if (!"RowLine".equals(child.getClass().getSimpleName()) || child.getVisibility() != View.VISIBLE) continue;
                Object row = field(child, "row");
                TextView title = (TextView) field(row, "title");
                String label = String.valueOf(title.getText());
                require(field(row, "icon") == null, label + " has an icon tile; the plan's rows have none");
                require(child.getHeight() >= dp48, label + " is " + child.getHeight() + " px tall, under 48 dp");
                Switch toggle = (Switch) field(row, "toggle");
                if (toggle != null) {
                    require(toggle.getWidth() >= dp48 && toggle.getHeight() >= dp48, label + "'s switch is under 48 dp");
                    require(label.contentEquals(toggle.getContentDescription()), label + "'s switch is not spoken by its title");
                } else {
                    require(child.isClickable() && child.hasOnClickListeners() && child.isFocusable(), label + " is not a tappable, focusable row");
                    require(field(row, "chevron") != null && ((View) field(row, "chevron")).isShown(), label + " has no chevron");
                }
                require(child.getImportantForAccessibility() != View.IMPORTANT_FOR_ACCESSIBILITY_NO && label.trim().length() > 0, label + " has no spoken name");
                wholeAndUncut(title, child, label);
                TextView value = (TextView) field(row, "value");
                if (value.getVisibility() == View.VISIBLE) wholeAndUncut(value, child, label + "'s value");
                int[] at = new int[2]; title.getLocationOnScreen(at);
                if (titleLeft == null) { titleLeft = at[0]; first = label; }
                else if (Math.abs(at[0] - titleLeft) > 1 && problem.get() == null)
                    problem.set(label + " starts at title x=" + at[0] + " but " + first + " at x=" + titleLeft);
                count++;
            }
            rows.set(count);
        });
        require(rows.get() >= 9, "Settings shows " + rows.get() + " rows");
        require(problem.get() == null, String.valueOf(problem.get()));
    }
    /**
     * (Was: the wide menu's narrow-label ladder, which stepped a row's icon aside and then dropped to one pane.) With one
     * column and no icons, a title word as wide as the title's whole room stays whole and uncut, wraps at its space, and
     * the row grows to hold it with its chevron still inside the card.
     */
    private void longTitle(Object more, String shot) throws Exception {
        Object row = menuRow(more, "About");
        TextView title = (TextView) field(row, "title");
        View line = (View) field(row, "view");
        int[] room = new int[1];
        main(() -> { View words = (View) title.getParent(); room[0] = words.getWidth() - title.getCompoundPaddingLeft() - title.getCompoundPaddingRight(); });
        require(room[0] > activity.ui().dp(100), "The About row's title has " + room[0] + " px of room");
        String word = word(title, room[0] - 2);
        main(() -> title.setText("Long " + word)); settle();
        main(() -> {
            require(title.getLayout() != null && title.getLayout().getLineCount() >= 2, "A title as wide as its room did not wrap at its space");
            wholeAndUncut(title, line, "The long About title");
            require(line.getHeight() >= title.getHeight(), "The About row did not grow with its title");
            View chevron = (View) field(row, "chevron");
            require(chevron.isShown() && within(chevron, line), "The About row's chevron left the row");
            require(within(line, (View) line.getParent()), "The About row left its card");
        });
        screenshot(shot);
    }
    /** text: laid out, no ellipsis, breaks only at spaces, every line within its width, and inside container on screen. */
    private void wholeAndUncut(TextView text, View container, String label) {
        android.text.Layout layout = text.getLayout(); CharSequence s = text.getText();
        require(layout != null, label + " is not laid out");
        int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        for (int i = 0; i < layout.getLineCount(); i++) {
            require(layout.getEllipsisCount(i) == 0, label + " is cut with an ellipsis: " + s);
            require(layout.getLineWidth(i) <= width + 1, label + " runs past its width: " + s);
            int end = layout.getLineEnd(i);
            if (i + 1 < layout.getLineCount()) require(end > 0 && Character.isWhitespace(s.charAt(end - 1)), label + " breaks a word: " + s);
        }
        require(layout.getLineEnd(layout.getLineCount() - 1) == s.length(), label + " lost text: " + s);
        require(layout.getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 1, label + " is cut at the bottom: " + s);
        require(within(text, container), label + " is outside its row");
    }
    private boolean within(View view, View container) {
        int[] a = new int[2], b = new int[2]; view.getLocationOnScreen(a); container.getLocationOnScreen(b);
        return a[0] >= b[0] - 1 && a[1] >= b[1] - 1 && a[0] + view.getWidth() <= b[0] + container.getWidth() + 1 && a[1] + view.getHeight() <= b[1] + container.getHeight() + 1;
    }
    private int[] bounds(View view) { int[] at = new int[2]; view.getLocationOnScreen(at); return new int[]{at[0], at[0] + view.getWidth()}; }
    /** The shown group cards of Settings' index, top to bottom: the LinearLayouts directly in it that hold rows. */
    private List<LinearLayout> cards(Object more) {
        LinearLayout index = (LinearLayout) field(more, "indexView");
        List<LinearLayout> found = new ArrayList<>();
        for (int i = 0; i < index.getChildCount(); i++) {
            View child = index.getChildAt(i);
            if (!(child instanceof LinearLayout) || !child.isShown()) continue;
            for (int j = 0; j < ((LinearLayout) child).getChildCount(); j++)
                if ("RowLine".equals(((LinearLayout) child).getChildAt(j).getClass().getSimpleName())) { found.add((LinearLayout) child); break; }
        }
        return found;
    }
    /** A section's row on Settings (More's SECTIONS id, e.g. About), which has no tag of its own. */
    private Object menuRow(Object more, String section) {
        String[] sections = (String[]) field(more, "SECTIONS"); Object[] rows = (Object[]) field(more, "menuRows");
        for (int i = 0; i < sections.length; i++) if (section.equals(sections[i]) && rows[i] != null) return rows[i];
        throw new IllegalStateException("No " + section + " row on Settings");
    }
    private View classNamed(View view, String simpleName) {
        if (view.getClass().getSimpleName().equals(simpleName)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View found = classNamed(((ViewGroup) view).getChildAt(i), simpleName); if (found != null) return found; }
        return null;
    }
    /** A single word of repeated letters as wide as px in the title's paint. */
    private String word(TextView title, int px) {
        StringBuilder text = new StringBuilder("W");
        while (title.getPaint().measureText(text.toString() + "m") <= px) text.append('m');
        return text.toString();
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"})
            require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        require(manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled(), "Emulator Bluetooth must stay off");
    }
    private void seed() {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create("Synthetic menu fixture", "A1B2C3D4E5F6", 0);
            profile.serial = "X2538-SYNTHETIC"; profile.mac = "C0:FF:EE:12:34:56"; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile);
        });
        prefs("onboarding").edit().putBoolean("onboarding_done", true).commit();
    }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "more-menu-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        File file = new File(dir, arguments.getString("theme", "dark") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
    private Object more() { Object screen = field(activity, "screen"); require(screen != null && screen.getClass().getSimpleName().equals("MoreScreen"), "More screen is not active"); return screen; }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new IllegalStateException(error.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 10000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        require(condition.getAsBoolean(), message);
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException next) { type = type.getSuperclass(); } }
            throw new NoSuchFieldException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
