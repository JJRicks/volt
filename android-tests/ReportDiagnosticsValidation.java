package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.ConnectionEventLog;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.ScooterProfiles;

/**
 * Fresh disposable emulator only. Both report emails (More › Report an issue and the compatibility
 * checker's Email to Volt) end on the email sheet: Include diagnostics is ticked by default, Preview
 * shows the exact file, and Open email attaches it through the private provider with a read grant
 * for that intent only. The file holds the simulator's command and connection events but none of the
 * fixture's serial, MAC, owner key or name; unticking sends the plain email and is remembered. Each
 * email's file is its own snapshot at its own URI: a later report never changes or reaches an earlier
 * one, and old snapshots are pruned. Every email is intercepted in-process and never sent.
 */
public final class ReportDiagnosticsValidation extends Instrumentation {
    private static final String MODEL = "39991", ADDRESS = "volt-reports@jjricks.com";
    private static final String FIXTURE_NAME = "Synthetic diagnostics fixture", FIXTURE_IDENTITY = "A1B2C3D4E5F6",
            FIXTURE_SERIAL = "X2538SYNTHETIC0042", FIXTURE_MAC = "C0:FF:EE:12:34:56";
    private static final String NOTE = "Adds a diagnostics file: app and scooter details, and recent connection and control events. Never your owner key, PIN, serial, location or names.";
    private Bundle arguments;
    private MainActivity activity;
    private int assertions;
    private final ArrayList<String> screenshots = new ArrayList<>();
    private final AtomicReference<Intent> started = new AtomicReference<>();
    private Uri attachedUri, firstUri;
    private String firstFile;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                    .putString("theme", arguments.getString("theme", "dark")).commit(), "Fixture settings failed");
            seed();
            // Intercept, never start, the email app and the chooser.
            addMonitor(new ActivityMonitor() {
                @Override public ActivityResult onStartActivity(Intent intent) {
                    String action = intent.getAction();
                    if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SENDTO.equals(action) && !Intent.ACTION_CHOOSER.equals(action)) return null;
                    started.set(intent); return new ActivityResult(0, null);
                }
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "App did not launch"); settle();
            main(() -> require(activity.service().setSimulation(true), "Simulation did not start")); settle();
            await(() -> activity.service().ble.ready(), "Simulated scooter did not connect");
            commandsAndConnections();
            main(() -> activity.service().ble.setSimulationModel(MODEL)); settle();
            await(() -> activity.service().ble.ready(), "Simulated model did not connect");
            settle();

            provider();
            reportIssue();
            checkerEmail();
            pruning();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", assertions); result.putString("screenshots", screenshots.toString());
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            try { if (activity != null) main(() -> activity.finish()); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** The simulator's own control paths write the command log; two connection events stand in for a real session. */
    private void commandsAndConnections() {
        main(() -> {
            Object ble = activity.service().ble;
            require(activity.service().ble.set("lock", 1) && activity.service().ble.set("lock", 0), "The simulator refused a lock change");
            require(!activity.service().ble.set("logo_light", 1), "An unmapped control was admitted on the UT5 MAX simulator");
            ConnectionEventLog log = (ConnectionEventLog) field(ble, "diagnostics");
            long wall = System.currentTimeMillis(), boot = SystemClock.elapsedRealtime();
            log.record(new ConnectionEventLog.Record(wall, boot, ConnectionEventLog.Reason.CONNECT_ATTEMPT, -1, true, false, ConnectionEventLog.Phase.SCANNING, -1, -1));
            log.record(new ConnectionEventLog.Record(wall + 1, boot + 1, ConnectionEventLog.Reason.CONNECT_TIMEOUT, -1, true, false, ConnectionEventLog.Phase.AUTHENTICATING, -1, -1));
        });
        settle();
    }

    /** Private, read-only, and grantable: the manifest entry the attachment relies on. */
    private void provider() {
        ProviderInfo info = getTargetContext().getPackageManager().resolveContentProvider(getTargetContext().getPackageName() + ".diagnostics", 0);
        require(info != null && !info.exported && info.grantUriPermissions && info.readPermission == null, "The diagnostics provider is exported or not grantable");
    }

    /** Report an issue: sheet, Preview, the attachment by default, then unticked (plain email), remembered and ticked again. */
    private void reportIssue() throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        main(() -> { View link = root().findViewWithTag("more-report-issue"); link.requestRectangleOnScreen(new Rect(0, 0, link.getWidth(), link.getHeight()), true); });
        settle(); started.set(null);
        main(() -> root().findViewWithTag("more-report-issue").performClick()); settle();
        sheetDefaults();
        screenshot("report-issue-sheet");
        String previewed = preview("report-issue-preview");
        main(() -> sheet("email-open").performClick()); settle();
        String file = attached("Report an issue");
        require(file.equals(previewed), "Preview did not show the exact attached file");
        require(file.contains("\"command_log\": {") && file.contains("\"key\": \"lock\"") && file.contains("\"outcome\": \"readback_same\"")
                && file.contains("\"key\": \"logo_light\"") && file.contains("\"outcome\": \"refused\"") && file.contains("\"type\": \"session\"")
                && file.contains("\"simulated\": true") && file.contains("\"reason\": \"CONNECT_TIMEOUT\"") && file.contains("\"readings\": [")
                && file.contains("\"model\": \"" + Build.MANUFACTURER + " " + Build.MODEL + "\""), "The file lacks the logs, readings or phone");
        noSecrets(file, "Report an issue's file");
        firstUri = attachedUri; firstFile = file;
        require(!sheetShowing() && settings().getAll().get("report_diagnostics") == null, "Open email did not close the sheet, or stored a choice nobody made");

        // Unticked: the plain email, and the choice is remembered.
        started.set(null);
        main(() -> root().findViewWithTag("more-report-issue").performClick()); settle();
        main(() -> sheet("diagnostics-row").performClick()); settle();
        require(!checked() && Boolean.FALSE.equals(settings().getAll().get("report_diagnostics")), "Unticking did not untick or was not remembered");
        screenshot("report-issue-unticked");
        main(() -> sheet("email-open").performClick()); settle();
        Intent plain = started.get(); require(plain != null, "Unticked, Open email opened nothing");
        if (Intent.ACTION_CHOOSER.equals(plain.getAction())) plain = plain.getParcelableExtra(Intent.EXTRA_INTENT);
        require(plain.getParcelableExtra(Intent.EXTRA_STREAM) == null && plain.getClipData() == null
                && (plain.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0, "Unticked, the email still carried the file");
        String[] to = plain.getStringArrayExtra(Intent.EXTRA_EMAIL);
        require(to != null && ADDRESS.equals(to[0]) && plain.getStringExtra(Intent.EXTRA_TEXT).startsWith("What happened"), "The plain email changed");
        started.set(null);
        main(() -> root().findViewWithTag("more-report-issue").performClick()); settle();
        require(!checked(), "The unticked choice was not remembered");
        main(() -> sheet("diagnostics-row").performClick()); settle();
        require(checked() && Boolean.TRUE.equals(settings().getAll().get("report_diagnostics")), "Ticking again was not remembered");
        main(() -> { android.app.Dialog d = sheetDialog(); d.cancel(); }); settle();
        require(started.get() == null && !sheetShowing(), "Closing the sheet sent something");
    }

    /** Email to Volt after a finished check: the same sheet, with the report in the file; unticked, the report alone. */
    private void checkerEmail() throws Exception {
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "Compatibility")); settle();
        main(() -> byText(root(), "Run check").performClick());
        await(() -> { AtomicReference<Boolean> running = new AtomicReference<>(); main(() -> running.set(activity.service().ble.discovering())); return running.get(); }, "The check did not start");
        long end = SystemClock.uptimeMillis() + 40000;
        while (SystemClock.uptimeMillis() < end) { AtomicReference<Boolean> running = new AtomicReference<>(true); main(() -> running.set(activity.service().ble.discovering())); if (!running.get()) break; SystemClock.sleep(200); }
        await(() -> { AtomicReference<Boolean> drawn = new AtomicReference<>(false); main(() -> drawn.set(root().findViewWithTag("compatibility-email") != null)); return drawn.get(); }, "No Email to Volt after the check");
        for (int round = 0; round < 2; round++) {
            boolean include = round == 0;
            started.set(null);
            main(() -> root().findViewWithTag("compatibility-email").performClick()); settle();
            main(() -> ((android.widget.EditText) sheet("model-search")).setText("gt3 pro")); settle();
            main(() -> sheet("model-gt3pro").performClick()); settle();
            main(() -> sheet("model-next").performClick()); settle();
            await(() -> sheet("drive-mode-skip") != null || sheet("email-open") != null, "Neither the drive-mode step nor the email sheet followed");
            if (sheet("drive-mode-skip") != null) main(() -> sheet("drive-mode-skip").performClick());
            await(() -> sheet("email-open") != null, "The email sheet did not open after the picker");
            require(started.get() == null, "The checker's email opened before Open email");
            if (include) {
                sheetDefaults();
                // The drive-mode step fades out while the sheet rises: let both finish before the screenshot.
                SystemClock.sleep(1200); settle();
                screenshot("checker-sheet");
                String previewed = preview("checker-preview");
                require(previewed.contains("\"compatibility_report\": {") && previewed.contains("\"format\": \"volt-compat-report-v1\""), "The preview lacks the compatibility report");
                main(() -> sheet("email-open").performClick()); settle();
                String file = attached("Email to Volt");
                require(file.equals(previewed), "The checker's preview is not the attached file");
                Intent mail = mail();
                require(("Volt compatibility report · Model " + MODEL).equals(mail.getStringExtra(Intent.EXTRA_SUBJECT))
                        && mail.getStringExtra(Intent.EXTRA_TEXT).startsWith("Scooter model, from the owner: GT3 Pro\n")
                        && mail.getStringExtra(Intent.EXTRA_TEXT).contains("\"format\": \"volt-compat-report-v1\""), "The report email changed");
                noSecrets(file, "the checker's file"); noSecrets(mail.getStringExtra(Intent.EXTRA_TEXT), "the report email");
                laterReport(file);
            } else {
                main(() -> sheet("diagnostics-row").performClick()); settle();
                require(!checked(), "Unticking did not untick in the checker's sheet");
                main(() -> sheet("email-open").performClick()); settle();
                Intent mail = mail();
                require(mail.getParcelableExtra(Intent.EXTRA_STREAM) == null && mail.getClipData() == null
                        && mail.getStringExtra(Intent.EXTRA_TEXT).contains("\"format\": \"volt-compat-report-v1\""), "Unticked, the report email still carried the file");
            }
            require(!sheetShowing() && "sent".equals(settings().getString("report_invite_" + MODEL, "")), "A sent report did not close or persist");
            settings().edit().remove("report_invite_" + MODEL).commit();
        }
    }

    /** The sheet as it opens: ticked by default, the full label on one line, the consent line, Preview. */
    private void sheetDefaults() {
        require(sheetShowing() && started.get() == null, "No email sheet before the email app");
        main(() -> {
            TextView label = (TextView) sheet("diagnostics-label"), note = (TextView) sheet("diagnostics-note"), preview = (TextView) sheet("diagnostics-preview");
            require(((CheckBox) sheet("diagnostics-include")).isChecked() && sheet("diagnostics-include").isEnabled(), "Include diagnostics is not ticked by default");
            require("Include diagnostics".contentEquals(label.getText()) && label.getLineCount() == 1 && label.getLayout() != null && label.getLayout().getEllipsisCount(0) == 0,
                    "The label is not the full one on one line: " + label.getText());
            require(NOTE.contentEquals(note.getText()) && note.isShown(), "The consent line is missing: " + note.getText());
            require(preview.isShown() && "Preview".contentEquals(preview.getText()) && preview.getHeight() >= dp(48), "Preview is missing or too small");
            require(byText(sheetRoot(), "To " + ADDRESS) != null, "The sheet does not say where the email goes");
        });
    }

    /** Preview: the exact file, readable and scrollable; returns its text after scrolling to the end and closing. */
    private String preview(String label) throws Exception {
        main(() -> sheet("diagnostics-preview").performClick()); settle();
        AtomicReference<String> text = new AtomicReference<>();
        main(() -> {
            android.app.Dialog d = (android.app.Dialog) field(activity, "diagnosticsPreview");
            require(d != null && d.isShowing(), "Preview did not open");
            View root = d.getWindow().getDecorView();
            TextView body = (TextView) root.findViewWithTag("diagnostics-preview-text"); ScrollView scroll = (ScrollView) root.findViewWithTag("diagnostics-preview-scroll");
            require(body != null && body.isShown() && body.getTextSize() >= sp(11) && scroll.getChildAt(0).getHeight() > scroll.getHeight(), "The preview is not a readable, scrolling file");
            text.set(String.valueOf(body.getText()));
        });
        require(text.get().startsWith("{\n  \"format\": \"volt-diagnostics-v1\""), "The preview is not the diagnostics file");
        screenshot(label);
        main(() -> ((ScrollView) ((android.app.Dialog) field(activity, "diagnosticsPreview")).getWindow().getDecorView().findViewWithTag("diagnostics-preview-scroll")).fullScroll(View.FOCUS_DOWN));
        settle(); screenshot(label + "-end");
        main(() -> ((android.app.Dialog) field(activity, "diagnosticsPreview")).dismiss()); settle();
        require(sheetShowing() && started.get() == null, "Closing the preview closed the sheet or sent something");
        return text.get();
    }

    /** The intercepted email with its attachment: addressed, granted read-only to its receiver, and readable through the provider. */
    private String attached(String flow) throws Exception {
        Intent mail = mail();
        String[] to = mail.getStringArrayExtra(Intent.EXTRA_EMAIL);
        require(Intent.ACTION_SEND.equals(mail.getAction()) && to != null && to.length == 1 && ADDRESS.equals(to[0]), flow + ": not an ACTION_SEND to the alias");
        Uri uri = mail.getParcelableExtra(Intent.EXTRA_STREAM);
        require(uri != null && "content".equals(uri.getScheme()) && (getTargetContext().getPackageName() + ".diagnostics").equals(uri.getAuthority())
                && uri.getPath() != null && uri.getPath().matches("/[0-9a-f]{32}/volt-diagnostics\\.json"), flow + ": the attachment is not one of the provider's snapshots: " + uri);
        ClipData clip = mail.getClipData();
        require(clip != null && clip.getItemCount() == 1 && uri.equals(clip.getItemAt(0).getUri()), flow + ": the grant does not name the attachment");
        int flags = mail.getFlags();
        require((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0 && (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0
                && (flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0 && (flags & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) == 0, flow + ": the grant is wider than one read");
        require("application/json".equals(mail.getType()) && mail.getSelector() != null && "mailto".equals(mail.getSelector().getData().getScheme()), flow + ": not limited to email apps");
        try (Cursor c = getTargetContext().getContentResolver().query(uri, null, null, null, null)) {
            require(c != null && c.moveToFirst() && "volt-diagnostics.json".equals(c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME))) && c.getLong(c.getColumnIndex(OpenableColumns.SIZE)) > 0,
                    flow + ": the provider does not name the file");
        }
        boolean writable = true;
        try { getTargetContext().getContentResolver().openOutputStream(uri).close(); } catch (Exception refused) { writable = false; }
        require(!writable, flow + ": the attachment is writable");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = getTargetContext().getContentResolver().openInputStream(uri)) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); }
        String file = new String(out.toByteArray(), StandardCharsets.UTF_8);
        require(file.startsWith("{\n  \"format\": \"volt-diagnostics-v1\""), flow + ": the attachment is not volt-diagnostics-v1");
        attachedUri = uri;
        return file;
    }

    /**
     * Report B after report A: B is a new snapshot at a new URI, A's URI still yields exactly A's bytes, and A's
     * grant (that one URI, no prefix) names nothing B's URI shares. Paths beside a real snapshot are refused.
     */
    private void laterReport(String second) throws Exception {
        require(firstUri != null && attachedUri != null && !firstUri.equals(attachedUri), "The second report reused the first report's URI: " + attachedUri);
        require(!second.equals(firstFile) && second.contains("\"compatibility_report\": {") && !firstFile.contains("\"compatibility_report\": {"), "The two reports' files are not distinct");
        require(read(firstUri).equals(firstFile), "After the second report, the first report's URI no longer yields the first file");
        require(read(attachedUri).equals(second), "The second report's URI does not yield the second file");
        String authority = getTargetContext().getPackageName() + ".diagnostics", firstId = firstUri.getPathSegments().get(0);
        for (String path : new String[]{"volt-diagnostics.json", firstId, firstId + "/other.json", firstId.toUpperCase(Locale.ROOT) + "/volt-diagnostics.json",
                firstId.substring(1) + "/volt-diagnostics.json", firstId + "/volt-diagnostics.json/x", "../" + firstId + "/volt-diagnostics.json",
                "0123456789abcdef0123456789abcdef/volt-diagnostics.json"}) {
            Uri bad = Uri.parse("content://" + authority + "/" + path);
            boolean served;
            try (InputStream in = getTargetContext().getContentResolver().openInputStream(bad)) { served = in != null; } catch (Exception refused) { served = false; }
            require(!served, "The provider served a path that is not a snapshot: " + bad);
        }
        Uri withQuery = firstUri.buildUpon().appendQueryParameter("x", "1").build();
        boolean served;
        try (InputStream in = getTargetContext().getContentResolver().openInputStream(withQuery)) { served = in != null; } catch (Exception refused) { served = false; }
        require(!served, "The provider served a snapshot URI with a query");
    }

    /** Snapshots older than a day, and all but the newest ten, are pruned on the next write; the new one stays. */
    private void pruning() throws Exception {
        File dir = new File(getTargetContext().getCacheDir(), "diagnostics");
        require(dir.isDirectory(), "No snapshot folder");
        // Start from just these fixtures; the reports above are checked already.
        for (File f : dir.listFiles()) require(f.delete(), "Could not clear " + f.getName());
        long now = System.currentTimeMillis();
        String stale = "00000000000000000000000000000000";
        File old = new File(dir, stale + ".json");
        try (FileOutputStream out = new FileOutputStream(old)) { out.write('{'); }
        require(old.setLastModified(now - 25L * 60 * 60 * 1000), "Could not age a snapshot");
        for (int i = 0; i < 12; i++) {
            File f = new File(dir, String.format(Locale.ROOT, "%032x", 0x100 + i) + ".json");
            try (FileOutputStream out = new FileOutputStream(f)) { out.write('{'); }
            require(f.setLastModified(now - (20 - i) * 60_000L), "Could not date a snapshot");
        }
        File leftover = new File(dir, "volt-diagnostics.json");
        try (FileOutputStream out = new FileOutputStream(leftover)) { out.write('{'); }
        Class<?> provider = Class.forName("com.jjricks.volt.DiagnosticsProvider");
        Method write = provider.getDeclaredMethod("write", Context.class, String.class); write.setAccessible(true);
        Uri fresh = (Uri) write.invoke(null, getTargetContext(), "{\"fresh\": true}");
        require("{\"fresh\": true}".equals(read(fresh)), "The fresh snapshot is not readable after pruning");
        String[] names = dir.list(); int snapshots = 0;
        for (String name : names) if (name.matches("[0-9a-f]{32}\\.json")) snapshots++;
        require(!old.exists() && !leftover.exists(), "A day-old snapshot or the old fixed file survived pruning");
        require(snapshots == 11, "Pruning kept " + snapshots + " snapshots, not the newest ten plus the fresh one");
        for (int i = 0; i < 12; i++)
            require(new File(dir, String.format(Locale.ROOT, "%032x", 0x100 + i) + ".json").exists() == (i >= 2), "Pruning dropped a newer snapshot or kept an older one (" + i + ")");
    }

    private String read(Uri uri) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = getTargetContext().getContentResolver().openInputStream(uri)) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    private Intent mail() {
        long end = SystemClock.uptimeMillis() + 10000;
        while (started.get() == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        Intent mail = started.get(); require(mail != null, "No email was opened");
        if (Intent.ACTION_CHOOSER.equals(mail.getAction())) mail = mail.getParcelableExtra(Intent.EXTRA_INTENT);
        return mail;
    }

    private void noSecrets(String text, String where) {
        for (String secret : new String[]{FIXTURE_NAME, FIXTURE_IDENTITY, FIXTURE_SERIAL, FIXTURE_MAC, FIXTURE_MAC.replace(":", "")})
            require(!text.toUpperCase(Locale.ROOT).contains(secret.toUpperCase(Locale.ROOT)), "A fixture secret (" + secret + ") reached " + where);
        // The file also leaves out the simulator's serial reading ("SIMULATED SCOOTER") and every raw payload.
        if (text.startsWith("{\n  \"format\": \"volt-diagnostics-v1\""))
            for (String withheld : new String[]{"\"SIMULATED SCOOTER\"", "scooter_serial", "battery_serial_raw", "raw_diagnostic_payloads"})
                require(!text.contains(withheld), "Withheld data (" + withheld + ") reached " + where);
    }
    private boolean checked() { AtomicReference<Boolean> on = new AtomicReference<>(false); main(() -> on.set(((CheckBox) sheet("diagnostics-include")).isChecked())); return on.get(); }
    private android.app.Dialog sheetDialog() { return (android.app.Dialog) field(activity, "reportDialog"); }
    private boolean sheetShowing() { AtomicReference<Boolean> open = new AtomicReference<>(false); main(() -> open.set(sheetDialog() != null && sheetDialog().isShowing())); return open.get(); }
    private View sheetRoot() { android.app.Dialog d = sheetDialog(); return d == null ? null : d.getWindow().getDecorView(); }
    /** The view with this tag in the showing report sheet or drive-mode step. */
    private View sheet(String tag) {
        AtomicReference<View> found = new AtomicReference<>();
        Runnable look = () -> { android.app.Dialog d = sheetDialog(); found.set(d != null && d.isShowing() ? d.getWindow().getDecorView().findViewWithTag(tag) : null); };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) look.run(); else main(look);
        return found.get();
    }
    private int dp(int value) { return Math.round(value * getTargetContext().getResources().getDisplayMetrics().density); }
    private float sp(int value) { return value * getTargetContext().getResources().getDisplayMetrics().scaledDensity; }
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
            ScooterProfiles.Profile profile = profiles.create(FIXTURE_NAME, FIXTURE_IDENTITY, 0);
            profile.serial = FIXTURE_SERIAL; profile.mac = FIXTURE_MAC; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile);
        });
        prefs("onboarding").edit().putBoolean("onboarding_done", true).commit();
    }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "report-diagnostics-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        File file = new File(dir, arguments.getString("theme", "dark") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
    }
    private View root() { return (View) field(activity, "root"); }
    private Object more() { Object screen = field(activity, "screen"); require(screen != null && screen.getClass().getSimpleName().equals("MoreScreen"), "More screen is not active"); return screen; }
    private TextView byText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText()) && view.isShown()) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView found = byText(((ViewGroup) view).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
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
