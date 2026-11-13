package com.jjricks.volt;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/**
 * Email to Volt on Charge history & recordings (hub layout plan, PR 4): what to tell the owner when the service refuses
 * the export behind it (ScooterService.exportChargeRecordings). Pure, so the JVM suite tests it (tests/ChargeSendTest.java).
 * The council's review of #396 (P3): only a charge being recorded is worth a toast ("Stop charge recording before
 * sending the recordings"); an earlier export still being written (a double tap) stays quiet, so the owner is never
 * told to stop a recording that isn't running. The council's review of #387 (P3) added the rest: no scooter chosen
 * (finding 9), an attachment that couldn't be prepared (7), the mailbox that keeps a finished export across a page
 * rebuild or an activity recreation (10), and the age under which DiagnosticsProvider leaves a temporary file alone (3).
 * Its round-2 review (finding 3) made the mailbox carry the model line taken at the tap with the file ({@link Export}).
 */
final class ChargeSend {
    private ChargeSend() { }

    enum Refusal { STOP_RECORDING, CHOOSE_SCOOTER, QUIET }

    /** recording: a charge is being recorded now; exporting: an earlier export is still being written. */
    static Refusal refusal(boolean recording, boolean exporting) {
        return recording ? Refusal.STOP_RECORDING : Refusal.QUIET;
    }

    /**
     * As {@link #refusal(boolean, boolean)}, and noScooter: no scooter is chosen (no active profile), which the service
     * refuses too and which the owner can fix ("Choose a scooter first.").
     */
    static Refusal refusal(boolean noScooter, boolean recording, boolean exporting) {
        return noScooter ? Refusal.CHOOSE_SCOOTER : refusal(recording, exporting);
    }

    /** The Email to Volt row is enabled only with a scooter chosen and no export being written (as the recordings row). */
    static boolean rowEnabled(boolean service, boolean scooterChosen, boolean exporting) {
        return service && scooterChosen && !exporting;
    }

    enum Outcome { SENT, EXPORT_FAILED, NO_EMAIL_APP }

    /**
     * After the export was moved into place and the email offered: attached is false when the attachment couldn't be
     * prepared (a full cache volume: "Charge recordings could not be exported"), opened whether an app took the email.
     */
    static Outcome outcome(boolean attached, boolean opened) {
        return !attached ? Outcome.EXPORT_FAILED : opened ? Outcome.SENT : Outcome.NO_EMAIL_APP;
    }

    /**
     * A temporary file in the report attachments' folder younger than this (either way, for a clock change) is another
     * report's file being made, which DiagnosticsProvider.prune leaves alone; an older one is left over and deleted.
     */
    static final long TEMPORARY_KEEP_MS = 60_000L;

    static boolean dropTemporary(long modifiedMs, long nowMs) {
        return Math.abs(nowMs - modifiedMs) >= TEMPORARY_KEEP_MS;
    }

    /**
     * A finished export and the model line of the scooter whose recordings it holds, taken when Email to Volt was tapped
     * (empty for none or a simulated one). The email names this model, so a scooter selected while the export is written
     * can't label one scooter's recordings with another's model (#387 round-2 review, finding 3).
     */
    static final class Export {
        final File file;
        final String model;
        /**
         * Null unless it failed; else why (the service's status, as "Charge recordings could not be exported"), and file
         * is null. A failure goes through the mailbox as the file would, so the page showing Volt when the export ends, a
         * recreated one too, toasts it exactly once (#412 round-2 review, P3 1); then it is consumed, as a delivered file
         * is, and never emails. It is held as the message (a Msg), never as text rendered when it failed, and rendered only
         * when the toast shows, so a language change while the mailbox holds it can't leave it in the old language (#419
         * review, P3 1; docs/localization-for-contributors.md, rule 2).
         */
        final CharSequence failure;
        /**
         * Null unless the export was already moved into place as the email's attachment (DiagnosticsProvider.adopt);
         * else that attachment's content URI, and snapshot its file in cache/diagnostics. The activity that adopted it
         * hands it back through the mailbox, so the activity showing Volt when the attachment is ready opens the email,
         * a recreated one too, exactly once (#419 review, the emulator's lost email).
         */
        final String attachment;
        final File snapshot;

        Export(File file, String model) { this(file, model, null, null, null); }

        private Export(File file, String model, CharSequence failure, String attachment, File snapshot) {
            this.file = file; this.model = model == null ? "" : model; this.failure = failure; this.attachment = attachment; this.snapshot = snapshot;
        }

        /** The export failed with this message to toast (empty: the host's own "could not be exported"). */
        static Export failed(CharSequence message) { return new Export(null, "", message == null ? "" : message, null, null); }

        /** The export, now the email's attachment at this content URI (its file: snapshot), still to be emailed. */
        static Export attached(String uri, File snapshot, String model) { return new Export(null, model, null, uri, snapshot); }

        boolean failed() { return failure != null; }

        boolean attached() { return attachment != null; }

        /** Drops it unshown: a file (or an attachment's snapshot) is deleted, a failure just goes. */
        void discard() {
            if (file != null) file.delete();
            if (snapshot != null) snapshot.delete();
        }
    }

    /**
     * Where a finished export goes: the activity showing Volt now (it emails it), whichever page or activity asked. A
     * rotation or fold rebuilds the page that asked (its screen closes), and a theme or text-size change recreates the
     * activity; either way the email still opens. With no activity the file waits, until one registers or for
     * {@link #HOLD_MS}; once the app has truly closed (not a recreation) it is deleted instead, so an email never opens
     * long after the owner left.
     */
    static final class Mailbox {
        static final long HOLD_MS = 2 * 60_000L;
        private Consumer<Export> receiver;
        private boolean accepting = true;
        private Export held;
        private long heldAt;

        /** The activity's own: it emails what arrives now, and what waited for it if that is recent. */
        synchronized void register(Consumer<Export> receiver, long nowMs) {
            this.receiver = receiver; accepting = true;
            Export waiting = held; held = null;
            if (waiting == null) return;
            if (nowMs - heldAt >= 0 && nowMs - heldAt < HOLD_MS) receiver.accept(waiting); else waiting.discard();
        }

        /** The activity is going: recreating keeps a file arriving meanwhile for the next one; closing drops it. */
        synchronized void unregister(Consumer<Export> receiver, boolean recreating) {
            if (this.receiver != receiver) return;
            this.receiver = null; accepting = recreating;
            if (!recreating && held != null) { held.discard(); held = null; }
        }

        /**
         * A finished export, a failed one ({@link Export#failed}) or one made the email's attachment ({@link
         * Export#attached}): to the activity, else held (one at a time; an older one is dropped, its file deleted), else
         * dropped. Whatever is handed to an activity is gone from here.
         */
        synchronized void deliver(Export export, long nowMs) {
            if (export == null || (export.file == null && !export.failed() && !export.attached())) return;
            if (receiver != null) { receiver.accept(export); return; }
            if (!accepting) { export.discard(); return; }
            if (held != null && held != export && (held.file == null || !held.file.equals(export.file))) held.discard();
            held = export; heldAt = nowMs;
        }
    }

    static final Mailbox MAILBOX = new Mailbox();

    /**
     * The owner's decisions on #383 (2026-11-12) for Email to Volt's attachment: the charge recordings have their own
     * name, not report diagnostics' volt-diagnostics.json, so the two kinds of email can be told apart on arrival; and a
     * recordings file over {@link #COMPRESS_OVER_BYTES} (25 MB, more than most email services accept) is attached
     * gzip-compressed, as volt-charge-recordings.json.gz, which decompresses to the very same file.
     */
    static final String NAME = "volt-charge-recordings.json", TYPE = "application/json";
    static final String COMPRESSED_NAME = NAME + ".gz", COMPRESSED_TYPE = "application/gzip";
    static final long COMPRESS_OVER_BYTES = 25L * 1000 * 1000;

    /** How a recordings file of some size is attached: its name, its MIME type, and whether it is gzip-compressed. */
    static final class Attachment {
        final String name, type;
        final boolean compressed;

        private Attachment(String name, String type, boolean compressed) { this.name = name; this.type = type; this.compressed = compressed; }
    }

    private static final Attachment PLAIN = new Attachment(NAME, TYPE, false), COMPRESSED = new Attachment(COMPRESSED_NAME, COMPRESSED_TYPE, true);

    /** The attachment for a recordings file of this many bytes: compressed only when it is over 25 MB. */
    static Attachment attachment(long bytes) {
        return bytes > COMPRESS_OVER_BYTES ? COMPRESSED : PLAIN;
    }

    /** The attachment that carries this name (one of the two above), else null. */
    static Attachment named(String name) {
        return NAME.equals(name) ? PLAIN : COMPRESSED_NAME.equals(name) ? COMPRESSED : null;
    }

    /**
     * Whether a file is ready to attach as it is: a file prepare() compressed (named *.gz), or one of 25 MB or less.
     * DiagnosticsProvider.adopt() then only moves it into place, a rename (#412 review, P3 1).
     */
    static boolean ready(File file) {
        return file.getName().endsWith(".gz") || !attachment(file.length()).compressed;
    }

    /** The attachment a file ready to attach is sent as (see {@link #ready}): compressed exactly when it is named *.gz. */
    static Attachment of(File ready) {
        return ready.getName().endsWith(".gz") ? COMPRESSED : PLAIN;
    }

    /**
     * Makes a finished recordings export ready to attach (#412 review, P3 1 and 2): a file of 25 MB or less, or one
     * already prepared, is returned as it is; a larger one is streamed through gzip into "<its name>.gz" beside it (via a
     * "<its name>.gz.tmp" synced first, so a half-written file never carries the final name), and the original is then
     * deleted. ScooterService runs this as the last step of Email to Volt's export, under its exporting flag, so the row
     * stays busy and the mailbox gets the file only once it is final. On failure nothing it made is left (no partial
     * .gz), and the original stays for the caller to delete.
     */
    static File prepare(File source) throws IOException {
        if (ready(source)) return source;
        File zipped = new File(source.getParentFile(), source.getName() + ".gz"), temporary = new File(source.getParentFile(), zipped.getName() + ".tmp");
        try {
            try (InputStream in = new java.io.FileInputStream(source); java.io.FileOutputStream out = new java.io.FileOutputStream(temporary)) {
                gzip(in, out); out.flush(); out.getFD().sync();
            }
            java.nio.file.Files.move(temporary.toPath(), zipped.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failed) {
            java.nio.file.Files.deleteIfExists(temporary.toPath()); java.nio.file.Files.deleteIfExists(zipped.toPath());
            throw failed;
        }
        java.nio.file.Files.deleteIfExists(source.toPath());
        return zipped;
    }

    /**
     * The leftovers of an interrupted compression in cache/charge-exports (#412 round-2 review, the emulator's note): a
     * process killed while prepare() compresses an export leaves "<name>.gz.tmp" and the uncompressed "<name>"; killed
     * between its rename and its delete, "<name>" beside the finished "<name>.gz". Given every name in that folder, and
     * asked only while no export runs (ScooterService checks at the start of the next one, under its exporting flag),
     * a file is a leftover when it is
     * <ul>
     * <li>any "*.tmp": in that folder only prepare() makes them, and only inside an export (the activity's
     * DiagnosticsProvider.adopt gets a file the export already made ready, so its prepare() makes nothing there); or</li>
     * <li>a "*.json" with its own "<name>.gz.tmp" or "<name>.gz" beside it: the uncompressed export whose compression
     * was cut off (a finished compression deletes it before the export ends).</li>
     * </ul>
     * Nothing else is: a finished export (a "*.json" alone, which Export charge recordings may still be saving or the
     * mailbox holding for an email, or a "*.json.gz") stays for the one-day window, so nothing still reading it loses
     * it early.
     */
    static boolean leftover(String name, Set<String> names) {
        if (name.endsWith(".tmp")) return true;
        return name.endsWith(".json") && (names.contains(name + ".gz.tmp") || names.contains(name + ".gz"));
    }

    /** Deletes the leftovers ({@link #leftover}) in this folder of exports, and returns how many went. */
    static int clearLeftovers(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return 0;
        Set<String> names = new HashSet<>();
        for (File f : files) names.add(f.getName());
        int cleared = 0;
        for (File f : files) if (f.isFile() && leftover(f.getName(), names) && f.delete()) cleared++;
        return cleared;
    }

    /**
     * Streams in to out as gzip, a buffer at a time, so a large export is never held in memory; closes neither stream
     * (the gzip trailer is written). Decompressed, out is exactly the bytes of in.
     */
    static void gzip(InputStream in, OutputStream out) throws IOException {
        GZIPOutputStream zipped = new GZIPOutputStream(new java.io.FilterOutputStream(out) {
            @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
            @Override public void close() throws IOException { out.flush(); }
        }, 64 * 1024);
        byte[] buffer = new byte[64 * 1024];
        for (int n; (n = in.read(buffer)) != -1; ) zipped.write(buffer, 0, n);
        zipped.close();
    }
}
