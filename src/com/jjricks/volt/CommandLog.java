package com.jjricks.volt;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/**
 * Device-local history of control changes and their outcomes, for report emails. Like
 * ConnectionEventLog it cannot accept identities, PIN digits or packet bytes: every field is an
 * enum, a bounded number, a catalogue control key or a pattern-checked version, so a serial, MAC,
 * owner key, PIN, name or route has nowhere to go. Never backed up (getNoBackupFilesDir).
 */
final class CommandLog {
    static final int MAX_RECORDS = 1000;
    static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAGIC = 0x56434d44, VERSION = 1, HEADER_BYTES = 12, CRC_BYTES = 8;
    /** Volt's version name: numbers and dots with a short suffix ("1.7.0", "1.7.0-dev"), too short for a key or serial; or "unknown". */
    static final String APP_VERSION = "unknown|[0-9]{1,4}(\\.[0-9]{1,4}){1,3}([-+][0-9A-Za-z.]{1,10})?";
    /** set: an owner, automatic or simulator change; try/restore: a guided write and its set-back; read: a PIN status read or a flagged status value. */
    enum Kind { SET, TRY, RESTORE, READ }
    enum Outcome {
        /** The scooter answered status 0. */
        ACK,
        /** The scooter answered with this status code. */
        REJECTED,
        /** No answer within the confirmation window. */
        TIMEOUT,
        /** Volt refused before sending, for this Reason. */
        REFUSED,
        /** Queued, then not sent: the connection closed or the setting changed while it waited. */
        CANCELLED,
        /** A fresh readback showed the value sent. */
        READBACK_SAME,
        /** A fresh readback showed another value (read). */
        READBACK_OTHER,
        /** Acknowledged, but no conclusive readback arrived (or the firmware exposes none). */
        UNCONFIRMED,
        /** A guided write ended by a disconnect or without any answer. */
        INTERRUPTED,
        /** A status value outside the model's domain for this control (read). */
        UNEXPECTED_VALUE
    }

    /** One line of the log: a session header or a command event. */
    abstract static class Record {
        final long wallMs;
        private Record(long wallMs) { if (wallMs < 0) throw new IllegalArgumentException("Invalid time"); this.wallMs = wallMs; }
        abstract int bytes();
    }

    /** Once per connection: what this session's commands ran against. */
    static final class Session extends Record {
        final String appVersion, modelId, meter, controller;
        final ModelRegistry.Layer layer;
        final boolean simulated;
        Session(long wallMs, String appVersion, String modelId, ModelRegistry.Layer layer, String meter, String controller, boolean simulated) {
            super(wallMs);
            if (appVersion == null || !appVersion.matches(APP_VERSION) || modelId == null || !modelId.matches("|[0-9]{1,5}")
                    || layer == null || !firmware(meter) || !firmware(controller))
                throw new IllegalArgumentException("Invalid command session");
            this.appVersion = appVersion; this.modelId = modelId; this.layer = layer;
            this.meter = meter; this.controller = controller; this.simulated = simulated;
        }
        /** The same pattern CompatibilityReport keeps for firmware; "" when not read this session. */
        private static boolean firmware(String v) { return v != null && v.matches("|[0-9A-Za-z.]{1,16}"); }
        @Override int bytes() { return 8 + 1 + utf(appVersion) + utf(modelId) + utf(layer.name()) + utf(meter) + utf(controller) + 1; }
    }

    /** One step of one control change. Sensitive controls (PIN), times and the clock never carry values. */
    static final class Command extends Record {
        final Kind kind;
        /** Who asked (null for a read). */
        final MutationGate.Origin origin;
        /** A ScooterCommands catalogue key, or clock_sync. */
        final String key;
        /** The value sent, or null. */
        final Integer value;
        final Outcome outcome;
        /** The rejection code (0..255), else -1. */
        final int code;
        /** Why Volt refused (REFUSED only), else null. */
        final CapabilityResolver.Reason reason;
        /** The value read back or flagged, else null. */
        final Integer read;
        /** Milliseconds since the write was sent, or -1. */
        final long elapsedMs;
        Command(long wallMs, Kind kind, MutationGate.Origin origin, String key, Integer value, Outcome outcome,
                int code, CapabilityResolver.Reason reason, Integer read, long elapsedMs) {
            super(wallMs);
            if (kind == null || outcome == null || !control(key)) throw new IllegalArgumentException("Invalid command event");
            boolean valued = valued(key);
            if (value != null && (!valued || value < 0 || value > 65535) || read != null && (!valued || read < 0 || read > 65535))
                throw new IllegalArgumentException("This control never carries a value");
            if ((outcome == Outcome.REJECTED ? code < 0 || code > 255 : code != -1) || (outcome == Outcome.REFUSED) != (reason != null)
                    || read != null && outcome != Outcome.READBACK_SAME && outcome != Outcome.READBACK_OTHER && outcome != Outcome.UNEXPECTED_VALUE
                    || elapsedMs < -1 || elapsedMs > 24L * 3600 * 1000)
                throw new IllegalArgumentException("Invalid command outcome");
            this.kind = kind; this.origin = origin; this.key = key; this.value = value; this.outcome = outcome;
            this.code = code; this.reason = reason; this.read = read; this.elapsedMs = elapsedMs;
        }
        @Override int bytes() {
            return 8 + 1 + utf(kind.name()) + utf(origin == null ? "" : origin.name()) + utf(key) + 4 + utf(outcome.name()) + 4
                    + utf(reason == null ? "" : reason.name()) + 4 + 8;
        }
    }

    /** A control key Volt knows (never free text). */
    static boolean control(String key) {
        if ("clock_sync".equals(key)) return true;
        if (key == null || !key.matches("[a-z][a-z0-9_]{0,39}")) return false;
        try { ScooterCommands.find(key); return true; } catch (IllegalArgumentException unknown) { return false; }
    }
    /** Whether a value may be logged for this control: never the PIN, a time or the clock. */
    static boolean valued(String key) {
        if (!control(key) || "clock_sync".equals(key)) return false;
        ScooterCommands.Feature f = ScooterCommands.find(key);
        return !f.sensitive && f.type != ScooterCommands.Type.TIME && f.type != ScooterCommands.Type.PIN;
    }
    // ---- The outcome rules BleController applies (pure, so the JVM tests cover the same code) ----

    /** The scooter's reply to the write: status 0 is an ACK, anything else a rejection with that code. */
    static Outcome reply(int status) { return status == 0 ? Outcome.ACK : Outcome.REJECTED; }
    /**
     * The confirmation window closed: no ACK is a timeout; an ACK whose readable field last showed another value is
     * that value; an ACK with no conclusive readback (or a field the firmware does not expose) is unconfirmed.
     */
    static Outcome expired(boolean ack, boolean readable, Integer lastRead, int target) {
        return !ack ? Outcome.TIMEOUT : readable && lastRead != null && lastRead != target ? Outcome.READBACK_OTHER : Outcome.UNCONFIRMED;
    }
    /** The connection closed with the change still pending. */
    static Outcome closed(boolean sent, boolean ack) { return !sent ? Outcome.CANCELLED : ack ? Outcome.UNCONFIRMED : Outcome.INTERRUPTED; }
    /**
     * A guided write's journalled outcome (GuidedTest.Observer) as its command event, or null for one logged where it
     * happens (the scooter's ACK or rejection, Volt's refusal) or not about a write in flight (the owner's observation).
     */
    static Command fromTest(long wallMs, GuidedTest t, GuidedTest.Outcome o, int code, boolean inFlight, boolean restoring, long elapsedMs) {
        if (!inFlight) return null;
        Outcome outcome; Integer read;
        switch (o) {
            case SETTING_CONFIRMED: if (restoring) return null; outcome = Outcome.READBACK_SAME; read = t.target; break;
            case RESTORATION_CONFIRMED: if (!restoring) return null; outcome = Outcome.READBACK_SAME; read = t.baseline(); break;
            case READBACK_UNCHANGED: case READBACK_DIFFERENT: outcome = Outcome.READBACK_OTHER; read = code; break;
            case ACK_UNCONFIRMED: outcome = Outcome.UNCONFIRMED; read = null; break;
            case INTERRUPTED: outcome = Outcome.INTERRUPTED; read = null; break;
            default: return null;
        }
        Integer value = restoring ? t.baseline() : Integer.valueOf(t.target);
        return new Command(wallMs, restoring ? Kind.RESTORE : Kind.TRY, restoring ? MutationGate.Origin.GUIDED_RESTORE : MutationGate.Origin.GUIDED_TEST,
                t.feature, value == null ? null : loggable(t.feature, value), outcome, -1, null, read == null ? null : loggable(t.feature, read), elapsedMs);
    }
    /** The loggable form of a value: null for controls that never carry one. */
    static Integer loggable(String key, int value) { return valued(key) && value >= 0 && value <= 65535 ? value : null; }

    // A single process-local writer per canonical path, as ConnectionEventLog.
    private static final Map<String, CommandLog> OPEN = new HashMap<>();
    static synchronized CommandLog open(File file) {
        String path;
        try { path = file.getCanonicalPath(); }
        catch (IOException unavailable) { path = file.getAbsolutePath(); }
        CommandLog existing = OPEN.get(path);
        if (existing != null) return existing;
        CommandLog created = new CommandLog(new File(path), MAX_RECORDS, MAX_FILE_BYTES);
        OPEN.put(path, created);
        return created;
    }
    /** Tests only: smaller caps, outside the shared writers. */
    static CommandLog openForTest(File file, int maxRecords, int maxBytes) { return new CommandLog(file, maxRecords, maxBytes); }

    private final File file;
    private final int maxRecords, maxBytes;
    private final ArrayDeque<Record> records = new ArrayDeque<>();
    private int bytes = HEADER_BYTES + CRC_BYTES;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "command-diagnostics"); thread.setDaemon(true); return thread;
    });
    private boolean loaded, scheduled = true, storageHealthy = true;
    private long revision;
    private CommandLog(File file, int maxRecords, int maxBytes) {
        this.file = file; this.maxRecords = maxRecords; this.maxBytes = maxBytes;
        worker.execute(() -> {
            List<Record> saved;
            try { saved = read(file, maxRecords, maxBytes); }
            catch (IOException | IllegalArgumentException | SecurityException corrupt) {
                saved = Collections.emptyList();
                synchronized (this) { storageHealthy = false; }
            }
            synchronized (this) {
                ArrayList<Record> pending = new ArrayList<>(records);
                records.clear(); bytes = HEADER_BYTES + CRC_BYTES;
                for (Record record : saved) append(record);
                for (Record record : pending) append(record);
                loaded = true;
            }
            drain();
        });
    }
    /** Cheap on the caller's thread: an in-memory append; the file is written on the worker. */
    synchronized void record(Record record) {
        Objects.requireNonNull(record, "record");
        append(record); revision++;
        if (!scheduled) { scheduled = true; worker.execute(this::drain); }
    }
    /** Oldest first out, by count and by encoded size. */
    private void append(Record record) {
        records.addLast(record); bytes += record.bytes();
        while (records.size() > maxRecords || bytes > maxBytes && records.size() > 1) bytes -= records.removeFirst().bytes();
    }
    /** Nonblocking in-memory copy, oldest first. */
    synchronized List<Record> snapshot() { return new ArrayList<>(records); }
    synchronized boolean loaded() { return loaded; }
    synchronized boolean storageHealthy() { return storageHealthy; }
    /** Enqueues a barrier; never wait for this on the UI thread. */
    Future<?> flush() { return worker.submit(() -> {}); }
    private void drain() {
        while (true) {
            List<Record> copy; long captured;
            synchronized (this) { copy = new ArrayList<>(records); captured = revision; }
            boolean written = true;
            try { write(file, copy, maxBytes); }
            catch (IOException | SecurityException unavailable) { written = false; }
            synchronized (this) {
                storageHealthy = written;
                if (revision == captured) { scheduled = false; return; }
            }
        }
    }

    private static int utf(String s) { return 2 + s.length(); }
    private static void write(File file, List<Record> records, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(records.size());
            for (Record record : records) {
                out.writeLong(record.wallMs);
                if (record instanceof Session) {
                    Session s = (Session) record;
                    out.writeByte('S'); out.writeUTF(s.appVersion); out.writeUTF(s.modelId); out.writeUTF(s.layer.name());
                    out.writeUTF(s.meter); out.writeUTF(s.controller); out.writeBoolean(s.simulated);
                } else {
                    Command c = (Command) record;
                    out.writeByte('C'); out.writeUTF(c.kind.name()); out.writeUTF(c.origin == null ? "" : c.origin.name()); out.writeUTF(c.key);
                    out.writeInt(c.value == null ? -1 : c.value); out.writeUTF(c.outcome.name()); out.writeInt(c.code);
                    out.writeUTF(c.reason == null ? "" : c.reason.name()); out.writeInt(c.read == null ? -1 : c.read); out.writeLong(c.elapsedMs);
                }
            }
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length + CRC_BYTES > maxBytes) throw new IOException("Command journal too large");
        CRC32 crc = new CRC32(); crc.update(bytes);
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Storage unavailable");
        File temporary = new File(parent, file.getName() + ".tmp");
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary);
                    DataOutputStream out = new DataOutputStream(stream)) {
                out.write(bytes); out.writeLong(crc.getValue()); out.flush(); stream.getFD().sync();
            }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
    private static List<Record> read(File file, int maxRecords, int maxBytes) throws IOException {
        if (!file.exists()) return Collections.emptyList();
        if (file.length() > maxBytes) throw new IOException("Oversized command journal");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream input = new FileInputStream(file)) {
            byte[] chunk = new byte[4096]; int count;
            while ((count = input.read(chunk)) != -1) {
                if (buffer.size() + count > maxBytes) throw new IOException("Oversized command journal");
                buffer.write(chunk, 0, count);
            }
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length < HEADER_BYTES + CRC_BYTES) throw new IOException("Incomplete command journal");
        CRC32 crc = new CRC32(); crc.update(bytes, 0, bytes.length - CRC_BYTES);
        try (DataInputStream check = new DataInputStream(new ByteArrayInputStream(bytes, bytes.length - CRC_BYTES, CRC_BYTES))) {
            if (check.readLong() != crc.getValue()) throw new IOException("Corrupt command journal");
        }
        ArrayList<Record> records = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - CRC_BYTES))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Unknown command journal");
            int count = input.readInt();
            if (count < 0 || count > maxRecords) throw new IOException("Invalid command record count");
            for (int i = 0; i < count; i++) {
                long wall = input.readLong(); int type = input.readUnsignedByte();
                if (type == 'S') {
                    String app = input.readUTF(), model = input.readUTF(); ModelRegistry.Layer layer = ModelRegistry.Layer.valueOf(input.readUTF());
                    records.add(new Session(wall, app, model, layer, input.readUTF(), input.readUTF(), input.readBoolean()));
                } else if (type == 'C') {
                    Kind kind = Kind.valueOf(input.readUTF()); String origin = input.readUTF(), key = input.readUTF();
                    int value = input.readInt(); Outcome outcome = Outcome.valueOf(input.readUTF()); int code = input.readInt();
                    String reason = input.readUTF(); int read = input.readInt(); long elapsed = input.readLong();
                    records.add(new Command(wall, kind, origin.isEmpty() ? null : MutationGate.Origin.valueOf(origin), key,
                            value < 0 ? null : value, outcome, code, reason.isEmpty() ? null : CapabilityResolver.Reason.valueOf(reason),
                            read < 0 ? null : read, elapsed));
                } else throw new IOException("Unknown command record");
            }
            if (input.read() != -1) throw new IOException("Unexpected command journal data");
        }
        return records;
    }
}
