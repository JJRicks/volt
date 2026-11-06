package com.jjricks.volt;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * Finished rides' summaries and chart previews saved beside RideStore's memory cache, so the first Rides open in a new
 * process reads a few hundred bytes per ride instead of replaying every journal. Pure Java, no Android dependency.
 *
 * <p>It is a cache, never a record: everything here is rebuilt from the journals. The folder lives in the app's
 * no-backup storage ({@code no_backup/rides-index}), outside {@code files/}, so no backup, ride backup or export
 * collects it. An entry is trusted only when its ride id, journal length, journal modified time, {@link #FORMAT},
 * {@link #ANALYSIS_VERSION} and preview size all match and both checksums pass; anything else is a miss, and the
 * caller replays the journal as before and writes the entry again.
 *
 * <p><b>Bump {@link #ANALYSIS_VERSION} whenever summary, gap, energy or preview maths changes</b> (anything that would
 * make a replay of an unchanged journal give a different Summary or Point). Old entries then miss and are rebuilt.
 *
 * <p>One file per ride, {@code <uuid>.sum}: a header, the summary state with its CRC32, then the deflated preview with
 * its own CRC32. Service start reads only the header and the summary state; the preview is read when a chart needs it.
 * Writes run on one low-priority daemon thread, through a temporary file and a rename.
 */
final class RideSummaryStore {
    /** See the class comment: bump on any change to summary or preview maths. */
    static final int ANALYSIS_VERSION = 1;
    /** The file layout below; bump when it changes. */
    static final int FORMAT = 1;
    private static final int MAGIC = 0x56525331; // "VRS1"
    private static final int MAX_STATE_BYTES = 64 * 1024, MAX_PREVIEW_BYTES = 1024 * 1024;
    private static final String SUFFIX = ".sum", TEMPORARY = ".sum.tmp";

    private final File directory;
    private final int previewPoints, analysisVersion;
    /** Entries this process read or wrote, by id, as {length, modified}; saves rewriting an unchanged entry. */
    private final ConcurrentHashMap<String, long[]> saved = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ride-summary-store");
        thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    RideSummaryStore(File directory, int previewPoints) { this(directory, previewPoints, ANALYSIS_VERSION); }
    /** Tests pass another analysis version to show that a bump misses every older entry. */
    RideSummaryStore(File directory, int previewPoints, int analysisVersion) {
        if (directory == null) throw new IllegalArgumentException("directory");
        this.directory = directory; this.previewPoints = previewPoints; this.analysisVersion = analysisVersion;
    }

    File directory() { return directory; }
    File file(String id) { return new File(directory, id + SUFFIX); }

    /** Records that the entry on disk is good for this journal state (its summary decoded), so it is not rewritten. */
    void markSaved(String id, long length, long modified) { saved.put(id, new long[] {length, modified}); }
    /** Forgets that mark after a part of the entry turned out unusable, so the next replay saves it again. */
    void forgetSaved(String id, long length, long modified) {
        long[] key = saved.get(id);
        if (key != null && key[0] == length && key[1] == modified) saved.remove(id, key);
    }
    /** True when this process already read or wrote the entry for exactly this journal state. */
    boolean saved(String id, long length, long modified) {
        long[] key = saved.get(id);
        return key != null && key[0] == length && key[1] == modified;
    }

    /** The saved summary state for this journal state, or null on any mismatch, damage or read error. Never throws. */
    byte[] readState(String id, long length, long modified) {
        File file = file(id);
        if (!file.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 2048))) {
            return header(in, id, length, modified) ? section(in, MAX_STATE_BYTES) : null;
        } catch (IOException | RuntimeException damaged) { return null; }
    }

    /** The saved, still deflated preview for this journal state, or null on any mismatch, damage or read error. */
    byte[] readPreview(String id, long length, long modified) {
        File file = file(id);
        if (!file.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 16384))) {
            if (!header(in, id, length, modified) || section(in, MAX_STATE_BYTES) == null) return null;
            return section(in, MAX_PREVIEW_BYTES);
        } catch (IOException | RuntimeException damaged) { return null; }
    }

    /** Saves an entry off the caller's thread; a failed write leaves no entry (the next replay tries again). */
    void write(String id, long length, long modified, Encoder encoder) {
        if (saved(id, length, modified)) return;
        saved.put(id, new long[] {length, modified});
        submit(() -> {
            byte[] state, preview;
            try { state = encoder.state(); preview = deflate(encoder.preview()); }
            catch (IOException | RuntimeException unavailable) { forgetSaved(id, length, modified); return; }
            File temporary = new File(directory, id + "." + Long.toHexString(System.nanoTime()) + TEMPORARY);
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("mkdirs");
                try (FileOutputStream bytes = new FileOutputStream(temporary);
                        DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(bytes, 8192))) {
                    out.writeInt(MAGIC); out.writeInt(FORMAT); out.writeInt(analysisVersion); out.writeInt(previewPoints);
                    out.writeUTF(id); out.writeLong(length); out.writeLong(modified);
                    writeSection(out, state); writeSection(out, preview);
                }
                if (!temporary.renameTo(file(id))) throw new IOException("rename");
            } catch (IOException | RuntimeException failed) {
                temporary.delete(); forgetSaved(id, length, modified);
            }
        });
    }
    /** What a write encodes, run on the writer thread. */
    interface Encoder { byte[] state() throws IOException; byte[] preview() throws IOException; }

    /** Removes one ride's entry, after any write already queued for it. */
    void remove(String id) {
        saved.remove(id);
        submit(() -> file(id).delete());
    }

    /** Removes entries (and leftover temporary files, or anything else) for rides that are not in {@code journals}. */
    void prune(Set<String> journals) {
        submit(() -> {
            File[] files = directory.listFiles();
            if (files == null) return;
            for (File file : files) {
                String name = file.getName();
                String id = name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length()) : null;
                if (id != null && journals.contains(id)) continue;
                if (id != null) saved.remove(id);
                file.delete();
            }
        });
    }

    /** Test-only: waits until every queued task has run, including tasks that queued more (a stop's save). */
    void flush() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (queued.get() > 0) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("flush");
            Thread.sleep(1);
        }
    }
    private final java.util.concurrent.atomic.AtomicInteger queued = new java.util.concurrent.atomic.AtomicInteger();
    /** Runs on the writer thread, in order; a shut-down writer just skips the work. */
    void submit(Runnable task) {
        queued.incrementAndGet();
        try { writer.execute(() -> { try { task.run(); } finally { queued.decrementAndGet(); } }); }
        catch (java.util.concurrent.RejectedExecutionException skipped) { queued.decrementAndGet(); }
    }

    private boolean header(DataInputStream in, String id, long length, long modified) throws IOException {
        return in.readInt() == MAGIC && in.readInt() == FORMAT && in.readInt() == analysisVersion
                && in.readInt() == previewPoints && id.equals(in.readUTF())
                && in.readLong() == length && in.readLong() == modified;
    }
    private static byte[] section(DataInputStream in, int max) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > max) return null;
        byte[] data = new byte[size];
        in.readFully(data);
        return in.readLong() == crc(data) ? data : null;
    }
    private static void writeSection(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length); out.write(data); out.writeLong(crc(data));
    }
    private static long crc(byte[] data) { CRC32 crc = new CRC32(); crc.update(data, 0, data.length); return crc.getValue(); }

    static byte[] deflate(byte[] data) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED);
        try {
            deflater.setInput(data); deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 3));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
            return out.toByteArray();
        } finally { deflater.end(); }
    }
    static byte[] inflate(byte[] data) throws IOException {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 3);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw new IOException("truncated");
                out.write(buffer, 0, n);
                if (out.size() > 8 * MAX_PREVIEW_BYTES) throw new IOException("oversized");
            }
            return out.toByteArray();
        } catch (java.util.zip.DataFormatException damaged) { throw new IOException("inflate", damaged); }
        finally { inflater.end(); }
    }
}
