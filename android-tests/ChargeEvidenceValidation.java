package com.jjricks.volt;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.Instrumentation;
import android.bluetooth.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import com.jjricks.volt.tests.TextCensus;

/**
 * Disposable emulator only; every packet goes to an in-process GATT stand-in, never a radio, and the email is
 * intercepted, never sent. Report diagnostics' charge evidence, end to end on the real service: a Scheduled charging
 * start armed on a locked (fake) UT5 MAX, the start passing, charging flapping, the scooter's own schedule switch going
 * off with no command, then a Scheduled charging test whose charging readings stop while the link stays up. The file
 * comes from Report an issue; the result's "answers" say where it answers each of the seven questions. Then the stores
 * are filled to their caps and Preview opens on that worst-case file (run at 344 dp): time to open, to scroll to the
 * end, and the longest main-thread stall, with no "isn't responding".
 */
public final class ChargeEvidenceValidation extends Instrumentation {
    private static final String NAME = "Synthetic evidence fixture", IDENTITY = "A1B2C3D4E5F6", SERIAL = "Q2538ZZ9US7777777", MAC = "C0:FF:EE:00:25:38";
    private Bundle args;
    private MainActivity activity;
    private ScooterService service;
    private BleController ble;
    private FakeLink link;
    private String profileId;
    private int checks;
    private boolean admitted;
    private long start, testWall;
    private final ArrayList<String> answers = new ArrayList<>(), timings = new ArrayList<>(), screenshots = new ArrayList<>();
    private final AtomicReference<Intent> started = new AtomicReference<>();

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true; seed();
            addMonitor(new ActivityMonitor() {
                @Override public ActivityResult onStartActivity(Intent intent) {
                    String action = intent.getAction();
                    if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SENDTO.equals(action) && !Intent.ACTION_CHOOSER.equals(action)) return null;
                    started.set(intent); return new ActivityResult(0, null);
                }
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, 10_000, "App did not launch"); settle();
            service = activity.service();
            attach();
            scheduledStart();
            scheduleTest();
            String file = report();
            questions(file);
            worstCase();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", failure.toString()); failure.printStackTrace();
            try { if (activity != null) screenshot("failure"); } catch (Throwable ignored) { }
        } finally {
            result.putInt("assertions", checks); result.putStringArrayList("answers", answers); result.putStringArrayList("timings", timings);
            result.putStringArrayList("screenshots", screenshots); result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (admitted && activity != null) try { main(() -> { if (ble != null) require(field(ble, "scanner") == null, "Unexpected Bluetooth scan"); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Physical device refused");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant emulator Nearby permission first");
        BluetoothAdapter adapter = getTargetContext().getSystemService(BluetoothManager.class).getAdapter();
        require(adapter != null && !adapter.isEnabled(), "Bluetooth must remain off");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"}) require(prefs(name).getAll().isEmpty(), "Existing preferences refused");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Existing app data refused");
        require(!new File(getTargetContext().getNoBackupFilesDir(), "charge-evidence").exists(), "Existing charge evidence refused");
    }
    private void seed() {
        require(prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                .putString("theme", "dark").putString("time_format", "24h").commit(), "Fixture settings failed");
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile p = profiles.create(NAME, IDENTITY, 0);
            p.serial = SERIAL; p.mac = MAC; p.lastConnected = System.currentTimeMillis() - 60_000; profiles.update(p); profileId = p.id;
        });
        prefs("onboarding").edit().putBoolean("onboarding_done", true).commit();
    }
    /** The fake link replaces the service's own connection: authenticated, polled by Volt's own loop. */
    private void attach() {
        main(() -> { ble = service.ble; link = new FakeLink(); link.attach(); invoke(ble, "poll", new Class<?>[]{int.class}, ble.connectionGeneration()); });
        await(() -> onMain(() -> ble.linked() && ble.ready()), 10_000, "The fake link never became ready");
        main(() -> link.charging(1, 3_000)); SystemClock.sleep(5_000);
        await(() -> onMain(() -> service.chargeSchedule().manualBlockedReason == null && service.chargeSchedule().timeBlockedReason == null), 15_000,
                "Scheduled charging stayed blocked: " + onMainText(() -> service.chargeSchedule().manualBlockedReason + " / " + service.chargeSchedule().timeBlockedReason));
    }

    /** Questions 1-4, 6 and 7: a start armed while locked, passing, flapping, and the scooter's switch going off by itself. */
    private void scheduledStart() {
        long now = System.currentTimeMillis();
        start = ((now + 75_000) / 60_000 + 1) * 60_000;
        String set = schedule(done -> service.setManualChargeStart(start, done), "setting the start");
        require(set == null, "The start was not set: " + set);
        String on = schedule(done -> service.setManualChargeEnabled(true, done), "turning Scheduled charging on");
        require(on == null, "Scheduled charging did not turn on: " + on);
        // The scooter pauses until its start.
        main(() -> link.charging(0, 0));
        while (System.currentTimeMillis() < start + 4_000) SystemClock.sleep(250);
        main(() -> link.charging(1, 3_000));
        for (int i = 0; i < 6; i++) { SystemClock.sleep(2_500); final int c = i % 2 == 0 ? 0 : 1; main(() -> link.charging(c, c * 3_000)); }
        SystemClock.sleep(3_000);
        // The firmware clears its own schedule switch after the start: no command from Volt.
        int writes = link.writes.size();
        main(() -> { link.state[33] = 0; });
        SystemClock.sleep(6_000);
        require(link.writes.size() == writes, "Volt wrote while it only watched");
        if (onMain(() -> service.chargeSchedule().manualEnabled)) {
            String off = schedule(done -> service.setManualChargeEnabled(false, done), "turning Scheduled charging off");
            require(off == null, "Scheduled charging did not turn off: " + off);
        }
        SystemClock.sleep(6_000);
    }

    /** Question 5: a test whose charging readings stop while status frames keep coming, so no disconnect is logged. */
    private void scheduleTest() {
        int events = onMainInt(() -> ble.diagnosticEvents().size());
        testWall = System.currentTimeMillis();
        AtomicReference<String> blocked = new AtomicReference<>();
        main(() -> blocked.set(service.startScheduleTest()));
        require(blocked.get() == null, "The schedule test did not start: " + blocked.get());
        await(() -> onMain(() -> service.chargeSchedule().testPhase != null && service.chargeSchedule().testPhase != ChargeScheduleTest.Phase.ARMING), 20_000, "The test never armed");
        main(() -> link.charging(0, 0)); SystemClock.sleep(4_000);
        main(() -> link.answerBattery = false);
        await(() -> onMain(() -> service.chargeSchedule().testPhase == null), 90_000, "The test was never cut off");
        require(onMain(() -> ble.linked()), "The link dropped: this check needs a test cut off with the link still up");
        main(() -> link.answerBattery = true); SystemClock.sleep(6_000);
        require(onMainInt(() -> ble.diagnosticEvents().size()) == events, "A connection event was logged during the test");
    }

    /** Report an issue: the real sheet and its Preview, then Open email; the attachment is read back through the provider. */
    private String report() throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        main(() -> { View link = root().findViewWithTag("more-report-issue"); link.requestRectangleOnScreen(new Rect(0, 0, link.getWidth(), link.getHeight()), true); });
        settle(); started.set(null);
        main(() -> root().findViewWithTag("more-report-issue").performClick()); settle();
        await(() -> sheet("email-open") != null, 10_000, "No email sheet");
        String previewed = preview("scenario-preview", false);
        main(() -> sheet("email-open").performClick()); settle();
        Intent mail = mail();
        Uri uri = mail.getParcelableExtra(Intent.EXTRA_STREAM);
        require(uri != null && "content".equals(uri.getScheme()), "No attachment");
        String file = read(uri);
        require(file.equals(previewed), "Preview did not show the attached file");
        for (String secret : new String[]{NAME, IDENTITY, SERIAL, MAC, MAC.replace(":", ""), profileId})
            require(!file.toUpperCase(Locale.ROOT).contains(secret.toUpperCase(Locale.ROOT)), "A fixture secret reached the file: " + secret);
        File dir = new File(getTargetContext().getExternalFilesDir(null), "charge-evidence-validation"); require(dir.isDirectory() || dir.mkdirs(), "No output folder");
        try (FileOutputStream out = new FileOutputStream(new File(dir, "scenario-volt-diagnostics.json"))) { out.write(file.getBytes(StandardCharsets.UTF_8)); }
        return file;
    }

    /** Where the file answers each question, checked against what the fake scooter did. */
    private void questions(String file) throws Exception {
        JSONObject root = new JSONObject(file), e = root.getJSONObject("charge_evidence");
        require(e.getBoolean("loaded") && e.getBoolean("storage_healthy"), "Evidence not loaded");
        long base = e.getLong("base_wall_ms");
        Table timeline = new Table(e.getJSONObject("charging_timeline")), intentions = new Table(e.getJSONObject("intentions")), tests = new Table(e.getJSONObject("schedule_tests")),
                ops = new Table(e.getJSONObject("schedule_operations")), changes = new Table(e.getJSONObject("changes_not_by_volt"));
        // 1. Charging start and stop times.
        int stops = 0, starts = 0;
        for (int i = 0; i < timeline.size(); i++) if ("change".equals(timeline.str(i, "kind")) && base + timeline.num(i, "wall_ms") >= start) {
            if (timeline.num(i, "charging") == 1) starts++; else stops++;
            require(timeline.has(i, "soc") && timeline.has(i, "voltage_mv") && timeline.has(i, "locked") && timeline.has(i, "in_sight") && timeline.has(i, "schedule_on"), "A timeline row lacks its readings");
        }
        require(starts >= 3 && stops >= 3 && timeline.size() < 300, "The timeline lacks the flapping after the start: " + starts + " starts, " + stops + " stops");
        require(timeline.indexOf("kind", "first") >= 0, "The timeline lacks the connection's first reading");
        answers.add("1 charging start/stop: charge_evidence.charging_timeline rows kind=change (charging 1/0, base_wall_ms + wall_ms), " + starts + " starts and " + stops + " stops after the start");
        // 2 and 3. The plan, how it ended, and the lock at arming and at the start.
        int plan = -1; for (int i = 0; i < intentions.size(); i++) if (intentions.has(i, "start_ms") && base + intentions.num(i, "start_ms") == start) plan = i;
        require(plan >= 0, "No intention for the start");
        require("manual".equals(intentions.str(plan, "mode")) && intentions.num(plan, "soc_at_arming") == 50 && !"open".equals(intentions.str(plan, "end")) && intentions.has(plan, "end_ms"),
                "The intention lacks its plan or end: " + intentions.row(plan));
        answers.add("2 plan: charge_evidence.intentions row mode=manual start_ms=" + intentions.num(plan, "start_ms") + " soc_at_arming=50 end=" + intentions.str(plan, "end"));
        require(intentions.num(plan, "locked_at_arming") == 1 && intentions.has(plan, "locked_at_start") && intentions.num(plan, "locked_at_start") == 1, "Lock at arming/start missing: " + intentions.row(plan));
        answers.add("3 lock: charge_evidence.intentions locked_at_arming=1 locked_at_start=1 locked_at_end=" + intentions.opt(plan, "locked_at_end"));
        // 4. The switch going off by itself.
        int off = -1; for (int i = 0; i < changes.size(); i++) if ("timed_charging".equals(changes.str(i, "reading")) && changes.num(i, "from") == 1 && changes.num(i, "to") == 0) off = i;
        require(off >= 0 && base + changes.num(off, "wall_ms") > start, "The switch going off is not in changes_not_by_volt: " + e.getJSONObject("changes_not_by_volt"));
        for (int i = 0; i < changes.size(); i++) require(!"charge_start".equals(changes.str(i, "reading")) || base + changes.num(i, "wall_ms") > start, "Volt's own start write was blamed on the scooter");
        answers.add("4 switch off: charge_evidence.changes_not_by_volt reading=timed_charging from 1 to 0 at wall_ms=" + changes.num(off, "wall_ms"));
        // 5. Why the test was cut off.
        int test = -1; for (int i = 0; i < tests.size(); i++) if ("interrupted".equals(tests.str(i, "outcome"))) test = i;
        require(test >= 0 && "readings_stale".equals(tests.str(test, "interrupted_by")), "The test's interruption reason is missing: " + e.getJSONObject("schedule_tests"));
        boolean traced = false; for (int i = 0; i < ops.size(); i++) traced |= "test_end".equals(ops.str(i, "op")) && "interrupted".equals(ops.str(i, "result")) && "readings_stale".equals(ops.str(i, "interrupted_by"));
        require(traced, "The operation trace lacks the test's end");
        answers.add("5 test cut off: charge_evidence.schedule_tests interrupted_by=readings_stale, schedule_operations op=test_end result=interrupted; no connection event during the test");
        // 6. What Volt sent.
        JSONArray events = root.getJSONObject("command_log").getJSONArray("events");
        boolean startSent = false, clockSent = false; long wire = intentions.num(plan, "wire_start"), offset = intentions.num(plan, "clock_offset_s");
        for (int i = 0; i < events.length(); i++) {
            JSONObject c = events.getJSONObject(i);
            if ("charge_start".equals(c.optString("key")) && c.has("time_sent")) startSent |= c.getLong("time_sent") == wire && c.getLong("offset_sent_s") == offset;
            if ("clock_sync".equals(c.optString("key")) && c.has("time_sent")) clockSent |= Math.abs(c.getLong("time_sent") * 1000 - c.getLong("wall_ms")) < 5_000 && c.getLong("offset_sent_s") == offset;
        }
        require(startSent && clockSent, "The command log lacks what was sent");
        answers.add("6 sent: command_log.events key=charge_start time_sent=" + wire + " offset_sent_s=" + offset + "; key=clock_sync time_sent (wall seconds) with offset_sent_s");
        // 7. Second by second after the start.
        JSONArray windows = e.getJSONArray("dense_windows"); int after = 0, flips = 0;
        for (int w = 0; w < windows.length(); w++) {
            JSONObject window = windows.getJSONObject(w); if (!"start".equals(window.getString("kind"))) continue;
            require(base + window.getLong("anchor_ms") == start, "The start window is anchored elsewhere");
            Table rows = new Table(window); long last = -1, previous = Long.MIN_VALUE;
            for (int i = 0; i < rows.size(); i++) {
                long wall = base + rows.num(i, "wall_ms"); require(wall >= previous, "Dense rows out of order"); previous = wall;
                if (wall >= start) { after++; long c = rows.has(i, "charging") ? rows.num(i, "charging") : -1; if (last >= 0 && c >= 0 && c != last) flips++; if (c >= 0) last = c; }
            }
        }
        require(after >= 10 && flips >= 3, "The start window lacks the seconds after the start: " + after + " samples, " + flips + " flips");
        answers.add("7 after the start: charge_evidence.dense_windows kind=start, " + after + " samples after the start, " + flips + " charging flips");
        boolean testWindow = false; for (int w = 0; w < windows.length(); w++) testWindow |= "test_end".equals(windows.getJSONObject(w).getString("kind"));
        require(testWindow, "No window after the test ended");
    }

    /** Fills the command log, the connection log and this scooter's evidence to their caps, then Preview on that file at this size. */
    private void worstCase() throws Exception {
        main(() -> {
            long wall = System.currentTimeMillis() - 86_400_000L;
            CommandLog commands = (CommandLog) field(ble, "commands");
            for (int i = 0; i < CommandLog.MAX_RECORDS; i++) commands.record(new CommandLog.Command(wall + i, CommandLog.Kind.RESTORE, MutationGate.Origin.AUTOMATIC_RANGE_FOLLOW,
                    "charge_start", null, CommandLog.Outcome.REFUSED, -1, CapabilityResolver.Reason.values()[0], null, 86_399_999L, 4_294_967_295L, -64_800));
            ConnectionEventLog connections = (ConnectionEventLog) field(ble, "diagnostics");
            for (int i = 0; i < ConnectionEventLog.MAX_RECORDS; i++) connections.record(new ConnectionEventLog.Record(wall + i, 9_999_999_999L, ConnectionEventLog.Reason.CHARGE_RECOVERY_CANCELLED,
                    255, true, true, ConnectionEventLog.Phase.AUTHENTICATING, 9_999_999_999L, 9_999_999_999L));
            ChargeEvidence evidence = (ChargeEvidence) field(service, "evidence");
            @SuppressWarnings("unchecked") Map<String, ChargeEvidence.Book> books = (Map<String, ChargeEvidence.Book>) field(evidence, "books");
            synchronized (evidence) { books.put(profileId, full(wall)); }
        });
        settle();
        started.set(null);
        long[] stall = {0}; Thread watch = watchdog(stall);
        long before = SystemClock.uptimeMillis();
        main(() -> root().findViewWithTag("more-report-issue").performClick());
        await(() -> sheet("email-open") != null, 15_000, "No email sheet on the worst-case file");
        long sheetMs = SystemClock.uptimeMillis() - before;
        String text = preview("worst-case-preview", true);
        watch.interrupt();
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        require(bytes > 400_000 && bytes <= DiagnosticsBundle.MAX_BYTES, "Not a worst-case file: " + bytes + " bytes");
        timings.add("worst-case file " + bytes + " bytes; email sheet (builds the file) " + sheetMs + " ms; longest main-thread stall " + stall[0] + " ms");
        require(stall[0] < 5_000, "A main-thread stall long enough for 'isn't responding': " + stall[0] + " ms");
        ActivityManager am = getTargetContext().getSystemService(ActivityManager.class);
        List<ActivityManager.ProcessErrorStateInfo> errors = am.getProcessesInErrorState();
        if (errors != null) for (ActivityManager.ProcessErrorStateInfo error : errors) require(!error.processName.startsWith(getTargetContext().getPackageName()), "Volt is not responding: " + error.shortMsg);
        main(() -> { Dialog d = (Dialog) field(activity, "reportDialog"); if (d != null) d.cancel(); }); settle();
        require(started.get() == null, "Closing sent something");
    }
    private static ChargeEvidence.Book full(long wall) {
        ChargeEvidence.Book b = new ChargeEvidence.Book();
        long start = wall + 3_600_000L, wire = start / 1000 - 6 * 3600, N = ChargeEvidence.NONE;
        for (int i = 0; i < ChargeEvidence.INTENTIONS; i++)
            b.intentions.add(new long[]{wall + i, 2, start + i, start + 7_200_000L, wire, -21600, 41, 90, 1, ChargeScheduleController.Outcome.MISSED.ordinal(), start + 130_000L, 2, 41, 1, wire, 1, 1, 0, 1});
        b.open = new long[]{wall + 50, 1, start + 86_400_000L, start + 172_800_000L, wire + 86_400, -21600, 52, 100, 0, ChargeEvidence.END_OPEN, N, N, N, N, N, 1, N, N, 0};
        for (int i = 0; i < ChargeEvidence.TESTS; i++)
            b.tests.add(new long[]{wall + i * 600_000L, wall + i * 600_000L + 120_000L, ChargeScheduleTest.Outcome.INTERRUPTED.ordinal(), N, 1, N, ChargeScheduleController.Interruption.READINGS_STALE.ordinal()});
        for (int i = 0; i < ChargeEvidence.TIMELINE; i++) b.timeline.add(new long[]{i % 4, wall + i * 61_000L, 9_999_999_999L + i, i % 2, 100, 249_999, -999_999, -99_999, -79, 1, 4_294_967_295L, 1, 1, 99_999});
        for (int w = 0; w < ChargeEvidence.WINDOWS; w++) {
            b.windows.add(new long[]{w % 2, start + w * 1000L, N});
            List<long[]> rows = new ArrayList<>();
            for (int i = 0; i < ChargeEvidence.WINDOW_SAMPLES; i++) rows.add(new long[]{start + i * 2000L, 9_999_999_999L + i, 1, 100, 249_999, -999_999, -99_999, 1, 1});
            b.windowSamples.add(rows);
        }
        for (int i = 0; i < ChargeEvidence.OPERATIONS; i++) b.operations.add(new long[]{wall + i, 9_999_999_999L, ChargeScheduleController.Op.MEASURE_NOT_SET.ordinal(),
                ChargeScheduleController.Result.BATTERY_SAVED_NO_ESTIMATE.ordinal(), ChargeScheduleController.Interruption.GENERATION_CHANGED.ordinal(), 604_799_999L, wall, ChargeScheduleTest.Outcome.RESUMED_EARLY.ordinal()});
        for (int i = 0; i < ChargeEvidence.CHANGES; i++) b.changes.add(new long[]{wall + i, 9_999_999_999L, ChargeEvidence.Watched.TIMED_CHARGING.ordinal(), 4_294_967_295L, 4_294_967_294L, wall, 1});
        return b;
    }
    /** Records the longest gap between 50 ms ticks posted to the main thread. */
    private Thread watchdog(long[] longest) {
        Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                AtomicLong ran = new AtomicLong(); long posted = SystemClock.uptimeMillis();
                main.post(() -> ran.set(SystemClock.uptimeMillis()));
                while (ran.get() == 0) { if (SystemClock.uptimeMillis() - posted > 20_000) break; SystemClock.sleep(5); }
                long gap = (ran.get() == 0 ? SystemClock.uptimeMillis() : ran.get()) - posted;
                synchronized (longest) { longest[0] = Math.max(longest[0], gap); }
                try { Thread.sleep(50); } catch (InterruptedException stop) { return; }
            }
        }, "stall-watch");
        t.start(); return t;
    }

    /** Preview: the exact file, readable and scrolling; timed when asked. Returns its text. */
    private String preview(String label, boolean time) throws Exception {
        long before = SystemClock.uptimeMillis();
        main(() -> sheet("diagnostics-preview").performClick());
        await(() -> onMain(() -> { ListView list = previewList(); return list != null && list.isShown() && list.getChildCount() > 0 && list.getChildAt(0).getHeight() > 0; }), 20_000, "Preview did not open");
        waitForIdleSync();
        long openMs = SystemClock.uptimeMillis() - before;
        AtomicReference<String> text = new AtomicReference<>();
        main(() -> {
            ListView list = previewList();
            require(list.canScrollVertically(1) && ((TextView) list.getChildAt(0)).getTextSize() > 0, "The preview is not a scrolling file");
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < list.getAdapter().getCount(); i++) { if (i > 0) all.append('\n'); all.append(list.getAdapter().getItem(i)); }
            text.set(all.toString());
        });
        screenshot(label);
        // Scrolling as a finger would, a screenful at a time (up to 60 screens), then a jump to the end.
        long scrollBefore = SystemClock.uptimeMillis(); int screens = 0;
        while (onMain(() -> previewList().canScrollVertically(1)) && screens < 60) {
            main(() -> { ListView list = previewList(); list.scrollListBy(list.getHeight()); }); waitForIdleSync(); screens++;
        }
        long scrollMs = SystemClock.uptimeMillis() - scrollBefore;
        long endBefore = SystemClock.uptimeMillis();
        main(() -> { ListView list = previewList(); list.setSelection(list.getAdapter().getCount() - 1); }); waitForIdleSync();
        main(() -> { ListView list = previewList(); list.scrollListBy(list.getHeight() * 4); }); waitForIdleSync();
        long endMs = SystemClock.uptimeMillis() - endBefore;
        require(!onMain(() -> previewList().canScrollVertically(1)), "Preview did not scroll to the end");
        screenshot(label + "-end");
        main(() -> previewList().setSelection(0)); waitForIdleSync();
        if (time) {
            int dp = Math.round(getTargetContext().getResources().getDisplayMetrics().widthPixels / getTargetContext().getResources().getDisplayMetrics().density);
            timings.add(label + " at " + dp + " dp wide: open " + openMs + " ms, " + screens + " screenfuls in " + scrollMs + " ms, jump to the end " + endMs + " ms");
        }
        main(() -> ((Dialog) field(activity, "diagnosticsPreview")).dismiss()); settle();
        require(sheet("email-open") != null && started.get() == null, "Closing the preview closed the sheet or sent something");
        return text.get();
    }
    private ListView previewList() { Dialog d = (Dialog) field(activity, "diagnosticsPreview"); return d == null || !d.isShowing() ? null : (ListView) d.getWindow().getDecorView().findViewWithTag("diagnostics-preview-list"); }

    /** A schedule call on the main thread, waited for; returns its completion text (null for success). */
    private String schedule(java.util.function.Consumer<java.util.function.Consumer<String>> call, String what) {
        AtomicReference<String[]> result = new AtomicReference<>();
        main(() -> call.accept(text -> result.set(new String[]{text})));
        await(() -> result.get() != null, 20_000, "No answer " + what);
        return result.get()[0];
    }

    /** An in-process stand-in for a UT5 MAX: answers 0x70 and 0x72 reads, applies and ACKs schedule writes, then reports status. */
    private final class FakeLink {
        final byte[] state = new byte[44], battery = new byte[36];
        final List<byte[]> writes = new CopyOnWriteArrayList<>();
        final Handler handler = new Handler(Looper.getMainLooper());
        volatile boolean answerBattery = true;
        BluetoothGatt gatt; BluetoothGattCharacteristic writer, notifier; BluetoothGattCallback callbacks;
        FakeLink() {
            state[1] = 3; state[2] = 1; state[3] = 1; state[6] = 2; state[7] = 1; state[19] = 3; state[25] = 50; state[31] = 100; state[33] = 0; state[34] = 3;
            battery[0] = 1; battery[1] = 50; le(battery, 2, 50_400); battery[10] = 100; battery[11] = 24;
        }
        void charging(int on, long mA) { battery[12] = (byte) on; le(battery, 6, mA); }
        void attach() { try {
            BluetoothDevice device = getTargetContext().getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(MAC);
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe"); Field unsafe = unsafeType.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
            gatt = (BluetoothGatt) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe.get(null), BluetoothGatt.class);
            Class<?> iface = Class.forName("android.bluetooth.IBluetoothGatt");
            InvocationHandler transport = (proxy, method, a) -> {
                if (method.getDeclaringClass() == Object.class) { if (method.getName().equals("equals")) return proxy == a[0]; if (method.getName().equals("hashCode")) return System.identityHashCode(proxy); return "EvidenceFixtureGatt"; }
                if (method.getName().equals("writeCharacteristic")) {
                    byte[] frame = null; if (a != null) for (Object arg : a) if (arg instanceof byte[]) frame = ((byte[]) arg).clone();
                    set(gatt, "mDeviceBusy", false); final byte[] packet = frame;
                    if (packet != null) handler.postDelayed(() -> received(packet), 10); return 0;
                }
                Class<?> type = method.getReturnType(); return type == int.class ? 0 : type == boolean.class ? false : type == long.class ? 0L : null;
            };
            set(gatt, "mService", Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, transport));
            set(gatt, "mClientRegistered", true); set(gatt, "mDeviceBusy", false); set(gatt, "mDeviceBusyLock", new Object()); set(gatt, "mStateLock", new Object()); set(gatt, "mDevice", device);
            set(gatt, "mServices", new CopyOnWriteArrayList<BluetoothGattService>());
            BluetoothGattService s = new BluetoothGattService(BleController.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY); set(s, "mDevice", device);
            writer = new BluetoothGattCharacteristic(BleController.WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
            notifier = new BluetoothGattCharacteristic(BleController.NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0); s.addCharacteristic(writer); s.addCharacteristic(notifier);
            callbacks = (BluetoothGattCallback) field(ble, "callbacks");
            ScooterProfiles.Profile p = null; for (ScooterProfiles.Profile each : service.profiles.all()) if (each.id.equals(profileId)) p = each;
            require(p != null, "Fixture profile missing");
            set(ble, "profile", p); set(ble, "gatt", gatt); set(ble, "writer", writer); set(ble, "serial", p.serial);
            set(ble, "authenticated", true); set(ble, "discovered", true); set(ble, "subscribed", true); set(ble, "wanted", false); set(ble, "state", BleController.State.READY);
            status(); notifyPacket(0x72, 0, battery.clone());
        } catch (Exception error) { throw new IllegalStateException("Synthetic GATT unavailable; relax hidden_api_policy on the disposable emulator", error); } }
        void received(byte[] frame) {
            callbacks.onCharacteristicWrite(gatt, writer, 0);
            int cmd = frame[3] & 255;
            if (frame.length == 7) { if (cmd == 0x70) status(); else if (cmd == 0x72 && answerBattery) notifyPacket(0x72, 0, battery.clone()); return; }
            if (cmd != 0x6f || frame.length < 9) return;
            writes.add(frame);
            int selector = frame[5] & 255;
            if (selector == 3 && frame.length >= 13) for (int i = 0; i < 4; i++) state[27 + i] = frame[6 + i];
            else if (selector == 1) state[33] = frame[6];
            notifyPacket(0x6f, 0, new byte[0]); status();
        }
        /** The settings frame, with the parked ride push a real scooter sends alongside (its battery level, not driving, speed 0). */
        void status() { notifyPacket(0x70, 0, state.clone()); notifyPacket(0x91, 0, new byte[]{battery[1], 0, 0, 0}); }
        void notifyPacket(int cmd, int status, byte[] payload) {
            byte[] packet = new byte[payload.length + 9]; packet[0] = 0x55; packet[1] = (byte) 0xaa; packet[3] = (byte) cmd; packet[4] = (byte) (payload.length + 1); packet[5] = (byte) status;
            System.arraycopy(payload, 0, packet, 6, payload.length); int sum = 0; for (int i = 0; i < packet.length - 3; i++) sum += packet[i] & 255;
            packet[packet.length - 3] = (byte) sum; packet[packet.length - 2] = (byte) 0xfe; packet[packet.length - 1] = (byte) 0xfd;
            handler.postDelayed(() -> callbacks.onCharacteristicChanged(gatt, notifier, packet), 10);
        }
    }
    private static void le(byte[] data, int offset, long value) { for (int i = 0; i < 4; i++) data[offset + i] = (byte) (value >>> (8 * i)); }

    /** A {columns, rows} table from the file. */
    private static final class Table {
        final List<String> columns = new ArrayList<>(); final JSONArray rows;
        Table(JSONObject t) throws Exception { JSONArray c = t.getJSONArray("columns"); for (int i = 0; i < c.length(); i++) columns.add(c.getString(i)); rows = t.getJSONArray("rows"); }
        int size() { return rows.length(); }
        int col(String name) { int i = columns.indexOf(name); if (i < 0) throw new IllegalStateException("No column " + name); return i; }
        boolean has(int row, String name) { return !rows.optJSONArray(row).isNull(col(name)); }
        long num(int row, String name) { return rows.optJSONArray(row).optLong(col(name), Long.MIN_VALUE); }
        String str(int row, String name) { return rows.optJSONArray(row).isNull(col(name)) ? null : rows.optJSONArray(row).optString(col(name)); }
        Object opt(int row, String name) { return rows.optJSONArray(row).opt(col(name)); }
        String row(int row) { return String.valueOf(rows.optJSONArray(row)); }
        int indexOf(String name, String value) { for (int i = 0; i < size(); i++) if (value.equals(str(i, name))) return i; return -1; }
    }

    private Intent mail() {
        long end = SystemClock.uptimeMillis() + 10_000;
        while (started.get() == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        Intent mail = started.get(); require(mail != null, "No email was opened");
        if (Intent.ACTION_CHOOSER.equals(mail.getAction())) mail = mail.getParcelableExtra(Intent.EXTRA_INTENT);
        return mail;
    }
    private String read(Uri uri) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = getTargetContext().getContentResolver().openInputStream(uri)) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    private View sheet(String tag) {
        AtomicReference<View> found = new AtomicReference<>();
        Runnable look = () -> { Dialog d = (Dialog) field(activity, "reportDialog"); found.set(d != null && d.isShowing() ? d.getWindow().getDecorView().findViewWithTag(tag) : null); };
        if (Looper.myLooper() == Looper.getMainLooper()) look.run(); else main(look);
        return found.get();
    }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "charge-evidence-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        File file = new File(dir, label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
    private View root() { return (View) field(activity, "root"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private void main(Runnable task) { final Throwable[] failure = {null}; runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { failure[0] = error; } }); if (failure[0] != null) throw new IllegalStateException(failure[0]); }
    private boolean onMain(BooleanSupplier query) { final boolean[] result = {false}; main(() -> result[0] = query.getAsBoolean()); return result[0]; }
    private int onMainInt(java.util.function.IntSupplier query) { final int[] result = {0}; main(() -> result[0] = query.getAsInt()); return result[0]; }
    private String onMainText(java.util.function.Supplier<String> query) { final String[] result = {null}; try { main(() -> result[0] = query.get()); } catch (RuntimeException e) { return e.toString(); } return result[0]; }
    private void await(BooleanSupplier query, long ms, String message) { long end = SystemClock.uptimeMillis() + ms; while (SystemClock.uptimeMillis() < end) { if (query.getAsBoolean()) return; SystemClock.sleep(50); } require(query.getAsBoolean(), message); }
    private static Field reflected(Object object, String name) { Class<?> type = object.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new IllegalStateException("Missing field " + name); }
    private static Object field(Object object, String name) { try { return reflected(object, name).get(object); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static void set(Object object, String name, Object value) { try { reflected(object, name).set(object, value); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static Object invoke(Object object, String name, Class<?>[] signature, Object... values) {
        try { Class<?> type = object.getClass(); while (type != null) { try { Method m = type.getDeclaredMethod(name, signature); m.setAccessible(true); return m.invoke(object, values); } catch (NoSuchMethodException next) { type = type.getSuperclass(); } }
            throw new NoSuchMethodException(name); } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
