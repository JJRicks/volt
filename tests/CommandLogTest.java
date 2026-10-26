package com.jjricks.volt;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * The command diagnostics log: count and size caps with oldest-first rotation, atomic restart-safe storage,
 * corruption recovery, the typed boundary that cannot take identities, PIN digits or bytes, and the outcome
 * rules BleController applies to its mutation, timeout, disconnect and guided-try paths.
 */
public final class CommandLogTest {
    private static int checks;
    private static final long WALL = 1792026000000L;
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            CommandLog log = CommandLog.open(new File(args[0]));
            log.flush().get(10, TimeUnit.SECONDS);
            List<CommandLog.Record> records = log.snapshot();
            require(log.loaded() && records.size() == Integer.parseInt(args[1]), "persisted count survived process restart");
            CommandLog.Command last = (CommandLog.Command) records.get(records.size() - 1);
            require(last.wallMs == Long.parseLong(args[2]) && last.key.equals("headlight") && last.value == 1 && last.outcome == CommandLog.Outcome.READBACK_SAME
                    && last.read == 1 && last.elapsedMs == 640 && last.origin == MutationGate.Origin.USER, "last event round-trips");
            require(records.get(0) instanceof CommandLog.Session || records.get(0) instanceof CommandLog.Command, "typed records reload");
            return;
        }
        boundedAndRestart();
        sizeCap();
        corruptionRecovers();
        typedPrivacyBoundary();
        mutationOutcomes();
        guidedTryOutcomes();
        System.out.println("CommandLog tests passed: " + checks + " assertions");
    }

    private static CommandLog.Command set(long i) {
        return new CommandLog.Command(WALL + i, CommandLog.Kind.SET, MutationGate.Origin.USER, "headlight", 1,
                CommandLog.Outcome.READBACK_SAME, -1, null, 1, 640);
    }
    private static CommandLog.Session session(long i) {
        return new CommandLog.Session(WALL + i, "1.7.0", "25462", ModelRegistry.Layer.OEM_GROUP, "2.0.3.1", "1.4", false);
    }

    private static void boundedAndRestart() throws Exception {
        File path = Files.createTempDirectory("volt-command-bounded").resolve("command-events").toFile();
        CommandLog log = CommandLog.open(path);
        log.record(session(0));
        for (int i = 1; i <= 1500; i++) log.record(set(i));
        log.flush().get(10, TimeUnit.SECONDS);
        List<CommandLog.Record> records = log.snapshot();
        require(log.loaded() && log.storageHealthy(), "asynchronous load and atomic write completed");
        require(records.size() == CommandLog.MAX_RECORDS, "about 1,000 events at most: " + records.size());
        require(records.get(0).wallMs == WALL + 501 && records.get(999).wallMs == WALL + 1500, "rotation drops the oldest first, keeping order");
        require(path.length() <= CommandLog.MAX_FILE_BYTES && path.length() > 0, "file within 256 KB: " + path.length());
        require(!new File(path + ".tmp").exists(), "atomic temporary file cleaned up");
        records.clear();
        require(log.snapshot().size() == CommandLog.MAX_RECORDS, "a snapshot cannot change the ring");
        require(CommandLog.open(new File(path.getParentFile(), "./command-events")) == log, "one writer per canonical path");
        restart(path, "1000", Long.toString(WALL + 1500));
    }

    private static void sizeCap() throws Exception {
        File path = Files.createTempDirectory("volt-command-size").resolve("command-events").toFile();
        int cap = 2048;
        CommandLog log = CommandLog.openForTest(path, CommandLog.MAX_RECORDS, cap);
        for (int i = 0; i < 400; i++) log.record(i % 50 == 0 ? session(i) : set(i));
        log.flush().get(10, TimeUnit.SECONDS);
        List<CommandLog.Record> records = log.snapshot();
        require(log.storageHealthy() && path.length() <= cap, "the byte cap binds before the count: " + path.length());
        require(records.size() < 400 && records.size() > 10 && records.get(records.size() - 1).wallMs == WALL + 399, "byte rotation keeps the newest: " + records.size());
        for (int i = 1; i < records.size(); i++) require(records.get(i).wallMs > records.get(i - 1).wallMs, "byte rotation keeps order");
        // The real caps: 1,000 of the largest records fit in 256 KB, so the count is the usual limit.
        String longest = "";
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) if (f.key.length() > longest.length()) longest = f.key;
        for (ScooterCommands.Feature f : ScooterCommands.oemOnly()) if (f.key.length() > longest.length()) longest = f.key;
        int largest = new CommandLog.Command(WALL, CommandLog.Kind.RESTORE, MutationGate.Origin.AUTOMATIC_RANGE_FOLLOW, longest, null,
                CommandLog.Outcome.REFUSED, -1, CapabilityResolver.Reason.PARKED_UNCONFIRMED, null, 86400000L).bytes();
        require(largest * CommandLog.MAX_RECORDS + 20 < CommandLog.MAX_FILE_BYTES, "1,000 events fit in 256 KB");
    }

    private static void corruptionRecovers() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            File path = Files.createTempDirectory("volt-command-corrupt").resolve("command-events").toFile();
            if (kind == 0) Files.write(path.toPath(), new byte[]{1, 2, 3});
            else if (kind == 1) Files.write(path.toPath(), new byte[CommandLog.MAX_FILE_BYTES + 1]);
            else {
                File valid = Files.createTempDirectory("volt-command-valid").resolve("command-events").toFile();
                CommandLog source = CommandLog.open(valid); source.record(set(5)); source.flush().get(10, TimeUnit.SECONDS);
                byte[] tampered = Files.readAllBytes(valid.toPath()); tampered[20] ^= 1; Files.write(path.toPath(), tampered);
            }
            CommandLog log = CommandLog.open(path);
            log.record(set(99)); log.flush().get(10, TimeUnit.SECONDS);
            require(log.loaded() && log.storageHealthy() && log.snapshot().size() == 1 && log.snapshot().get(0).wallMs == WALL + 99,
                    "a bad journal is replaced without losing the new event: " + kind);
        }
        File parent = Files.createTempDirectory("volt-command-denied").resolve("not-a-directory").toFile();
        Files.write(parent.toPath(), new byte[]{0});
        CommandLog log = CommandLog.open(new File(parent, "command-events"));
        log.record(set(1)); log.flush().get(10, TimeUnit.SECONDS);
        require(!log.storageHealthy() && log.snapshot().size() == 1, "storage errors are reported, the log still works");
    }

    /** Synthetic private values; none can be expressed by a record. */
    static final String SERIAL = "N2546ZZ9US1234567", MAC = "C0:FF:EE:12:34:56", OWNER_KEY = "a1b2c3d4e5f6", NAME = "Synthetic Blue Rocket";
    static final int PIN = 7395;
    private static void typedPrivacyBoundary() {
        for (Class<?> type : new Class<?>[]{CommandLog.Record.class, CommandLog.Session.class, CommandLog.Command.class})
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> t = field.getType();
                require(Modifier.isFinal(field.getModifiers()), "immutable after enqueue: " + field.getName());
                require(t.isPrimitive() || t.isEnum() || t == Integer.class || t == String.class, "no bytes, lists or objects: " + field);
                if (t == String.class) require(Arrays.asList("appVersion", "modelId", "meter", "controller", "key").contains(field.getName()),
                        "only checked strings: " + field.getName());
            }
        // Every free-text slot refuses identities, names and locations.
        for (String key : new String[]{SERIAL, SERIAL.toLowerCase(Locale.ROOT), MAC, OWNER_KEY, NAME, "synthetic_blue_rocket", "40.7128,-74.0060", "", null})
            rejects(() -> new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, key, null, CommandLog.Outcome.ACK, -1, null, null, 10), "key " + key);
        for (String text : new String[]{SERIAL, MAC, NAME, "40.7128,-74.0060", "1.7.0 " + OWNER_KEY})
            rejects(() -> new CommandLog.Session(WALL, text, "", ModelRegistry.Layer.SHARED, "", "", false), "app version " + text);
        rejects(() -> new CommandLog.Session(WALL, "1.7.0", SERIAL, ModelRegistry.Layer.SHARED, "", "", false), "model id is a serial");
        rejects(() -> new CommandLog.Session(WALL, "1.7.0", "25462", ModelRegistry.Layer.SHARED, NAME, "", false), "firmware is a name");
        rejects(() -> new CommandLog.Session(WALL, "1.7.0", "25462", ModelRegistry.Layer.SHARED, "", MAC, false), "firmware is a MAC");
        // The PIN, times and the clock never carry a value, sent or read.
        rejects(() -> new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, "pin", PIN, CommandLog.Outcome.ACK, -1, null, null, 10), "PIN value");
        rejects(() -> new CommandLog.Command(WALL, CommandLog.Kind.READ, null, "pin", null, CommandLog.Outcome.READBACK_SAME, -1, null, PIN, 10), "PIN readback");
        rejects(() -> new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, "charge_start", 1792026, CommandLog.Outcome.ACK, -1, null, null, 10), "time value");
        rejects(() -> new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, "clock_sync", 12, CommandLog.Outcome.ACK, -1, null, null, 10), "clock value");
        require(CommandLog.loggable("pin", PIN) == null && CommandLog.loggable("charge_start", 5) == null && CommandLog.loggable("clock_sync", 5) == null
                && CommandLog.loggable("headlight", 1) == 1 && CommandLog.loggable("headlight", 70000) == null, "BleController's values pass through loggable()");
        // Bounded numbers and consistent outcomes.
        rejects(() -> new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, "headlight", 0x1234567, CommandLog.Outcome.ACK, -1, null, null, 10), "value above 65535");
        rejects(() -> new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, "headlight", 1, CommandLog.Outcome.REFUSED, -1, null, null, -1), "refusal without a reason");
        rejects(() -> new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, "headlight", 1, CommandLog.Outcome.REJECTED, -1, null, null, 10), "rejection without a code");
        rejects(() -> new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, "headlight", 1, CommandLog.Outcome.ACK, 300, null, null, 10), "code out of range");
        rejects(() -> new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, "headlight", 1, CommandLog.Outcome.ACK, -1, null, 1, 10), "a read on an ACK");
        rejects(() -> new CommandLog.Command(-1, CommandLog.Kind.SET, MutationGate.Origin.USER, "headlight", 1, CommandLog.Outcome.ACK, -1, null, null, 10), "negative time");
        new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, "clock_sync", null, CommandLog.Outcome.ACK, -1, null, null, 120);
        new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, "pin", null, CommandLog.Outcome.REFUSED, -1, CapabilityResolver.Reason.OWNER_ONLY, null, -1);
        new CommandLog.Command(WALL, CommandLog.Kind.READ, null, "maximum_speed", null, CommandLog.Outcome.UNEXPECTED_VALUE, -1, null, 0, -1);
        require(true, "well-formed events are accepted");
    }

    /** The decisions BleController's mutation paths make: reply, confirmation timeout and disconnect. */
    private static void mutationOutcomes() {
        require(CommandLog.reply(0) == CommandLog.Outcome.ACK && CommandLog.reply(3) == CommandLog.Outcome.REJECTED, "reply status: ACK or rejection");
        require(CommandLog.expired(false, true, null, 1) == CommandLog.Outcome.TIMEOUT, "no ACK in the window: timeout");
        require(CommandLog.expired(true, true, 0, 1) == CommandLog.Outcome.READBACK_OTHER, "ACK, readback stayed on another value");
        require(CommandLog.expired(true, true, null, 1) == CommandLog.Outcome.UNCONFIRMED, "ACK, no readback: unconfirmed");
        require(CommandLog.expired(true, false, 0, 1) == CommandLog.Outcome.UNCONFIRMED, "ACK on a field the firmware does not expose: unconfirmed");
        require(CommandLog.expired(true, true, 1, 1) == CommandLog.Outcome.UNCONFIRMED, "a matching readback finishes earlier, never as other");
        require(CommandLog.closed(false, false) == CommandLog.Outcome.CANCELLED && CommandLog.closed(true, false) == CommandLog.Outcome.INTERRUPTED
                && CommandLog.closed(true, true) == CommandLog.Outcome.UNCONFIRMED, "a disconnect: cancelled before sending, interrupted, or unconfirmed after the ACK");
    }

    /** The try journal path: GuidedTest's observer, through CommandLog.fromTest as BleController records it. */
    private static void guidedTryOutcomes() throws IOException {
        DeviceDescriptor d = GuidedTestTest.oem(); StatusFields s = GuidedTestTest.status(GuidedTestTest.data(), 100000);
        FeatureVariant prompt = CapabilityResolver.decideExperimental("prompt_tone", d, s, GuidedTestTest.parked()).variant;
        long now = 101000;
        // Worked, kept, then Set back: TRY readback same, RESTORE readback same; the owner's answer is not a command event.
        TestJournal j = new TestJournal(new GuidedTestTest.Memory());
        List<CommandLog.Command> seen = new ArrayList<>();
        GuidedTest t = tried(j, d, prompt, seen);
        GuidedTestTest.sendTarget(t, 0x6c); t.onStatus(GuidedTestTest.with(21, 4), now + 400); t.ownerEffect(true, now + 600);
        require(seen.size() == 1 && one(seen.get(0), CommandLog.Kind.TRY, 4, CommandLog.Outcome.READBACK_SAME, 4), "a confirmed try: " + text(seen));
        GuidedTest back = GuidedTest.restoreFrom(j, j.find(t.testId()), prompt, d, GuidedTestTest.with(21, 4), now + 5000, WALL + 5000);
        back.observe(collector(seen)); GuidedTestTest.sendRestore(back, 0x6c); back.onStatus(GuidedTestTest.with(21, 2), now + 6000);
        require(seen.size() == 2 && one(seen.get(1), CommandLog.Kind.RESTORE, 2, CommandLog.Outcome.READBACK_SAME, 2)
                && seen.get(1).origin == MutationGate.Origin.GUIDED_RESTORE, "Set back confirmed: " + text(seen));
        // Another value, an unchanged value, an ACK with no readback, a disconnect.
        seen.clear(); GuidedTest other = tried(new TestJournal(new GuidedTestTest.Memory()), d, prompt, seen);
        GuidedTestTest.sendTarget(other, 0x6c); other.onStatus(GuidedTestTest.with(21, 3), now + 400);
        require(seen.size() == 1 && one(seen.get(0), CommandLog.Kind.TRY, 4, CommandLog.Outcome.READBACK_OTHER, 3), "another value read: " + text(seen));
        seen.clear(); GuidedTest unchanged = tried(new TestJournal(new GuidedTestTest.Memory()), d, prompt, seen);
        GuidedTestTest.sendTarget(unchanged, 0x6c); unchanged.onStatus(GuidedTestTest.with(21, 2), now + 400);
        require(seen.size() == 1 && one(seen.get(0), CommandLog.Kind.TRY, 4, CommandLog.Outcome.READBACK_OTHER, 2), "unchanged, and no restore event without a restore write: " + text(seen));
        seen.clear(); GuidedTest timeout = tried(new TestJournal(new GuidedTestTest.Memory()), d, prompt, seen);
        require(timeout.approve(now) && timeout.writeNow() == 4, "write handed out"); timeout.onAck(0x6c, 0, now + 50); timeout.onMutationTimeout(true, now + 6500);
        require(seen.size() == 1 && one(seen.get(0), CommandLog.Kind.TRY, 4, CommandLog.Outcome.UNCONFIRMED, null), "ACK without readback: " + text(seen));
        seen.clear(); GuidedTest dropped = tried(new TestJournal(new GuidedTestTest.Memory()), d, prompt, seen);
        require(dropped.approve(now) && dropped.writeNow() == 4, "write handed out"); dropped.onDisconnect(now + 20);
        require(seen.size() == 1 && one(seen.get(0), CommandLog.Kind.TRY, 4, CommandLog.Outcome.INTERRUPTED, null), "dropped mid-write: " + text(seen));
        // The scooter's rejection and Volt's refusal are logged where they happen, not twice through the journal.
        seen.clear(); GuidedTest rejected = tried(new TestJournal(new GuidedTestTest.Memory()), d, prompt, seen);
        require(rejected.approve(now) && rejected.writeNow() == 4, "write handed out"); rejected.onAck(0x6c, 3, now + 50);
        rejected.onReadRequested(0x70, now + 60); rejected.onStatus(GuidedTestTest.with(21, 2), now + 70);
        require(seen.isEmpty(), "a rejection is the wire's event, and the verify read is no write: " + text(seen));
        seen.clear(); GuidedTest refused = tried(new TestJournal(new GuidedTestTest.Memory()), d, prompt, seen);
        require(refused.approve(now) && refused.writeNow() == 4, "write handed out"); refused.onWriteRefused(now + 10);
        require(seen.isEmpty(), "a refusal is logged by BleController with its Reason: " + text(seen));
    }
    private static GuidedTest tried(TestJournal j, DeviceDescriptor d, FeatureVariant v, List<CommandLog.Command> seen) {
        GuidedTest t = GuidedTest.trial(j, "owner-1", d, v, TestJournal.EXPERIMENTAL, 4, 2, 101000, WALL, new Random(9));
        t.observe(collector(seen));
        t.onReadRequested(0x70, 101000); t.onStatus(GuidedTestTest.status(GuidedTestTest.data(), 100000), 101000);
        return t;
    }
    private static GuidedTest.Observer collector(List<CommandLog.Command> seen) {
        return (test, outcome, code, inFlight, restoring) -> {
            CommandLog.Command c = CommandLog.fromTest(WALL, test, outcome, code, inFlight, restoring, 400);
            if (c != null) seen.add(c);
        };
    }
    private static boolean one(CommandLog.Command c, CommandLog.Kind kind, Integer value, CommandLog.Outcome outcome, Integer read) {
        return c.kind == kind && c.key.equals("prompt_tone") && Objects.equals(c.value, value) && c.outcome == outcome && Objects.equals(c.read, read) && c.elapsedMs == 400;
    }
    private static String text(List<CommandLog.Command> seen) {
        StringBuilder b = new StringBuilder();
        for (CommandLog.Command c : seen) b.append(c.kind).append('/').append(c.outcome).append('/').append(c.value).append('/').append(c.read).append(' ');
        return b.toString();
    }

    private static void rejects(Runnable build, String what) {
        boolean rejected = false;
        try { build.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "refused: " + what);
    }
    private static void restart(File path, String... expected) throws Exception {
        ArrayList<String> command = new ArrayList<>(Arrays.asList(
                new File(System.getProperty("java.home"), "bin/java").getPath(), "-cp",
                System.getProperty("java.class.path"), CommandLogTest.class.getName(), path.getPath()));
        command.addAll(Arrays.asList(expected));
        Process process = new ProcessBuilder(command).inheritIO().start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Restart timed out"); }
        require(process.exitValue() == 0, "a fresh process reads the committed journal");
    }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
