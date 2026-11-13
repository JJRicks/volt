package com.jjricks.volt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** ChargeSend: Email to Volt asks the owner to stop a recording only while one is running (council review of #396, P3). */
public final class ChargeSendTest {
    private static int checks;

    public static void main(String[] args) {
        require(ChargeSend.refusal(true, false) == ChargeSend.Refusal.STOP_RECORDING, "A charge being recorded asks to stop it");
        require(ChargeSend.refusal(true, true) == ChargeSend.Refusal.STOP_RECORDING, "Recording while an export runs still asks to stop it");
        require(ChargeSend.refusal(false, true) == ChargeSend.Refusal.QUIET, "An earlier export still being written stays quiet");
        require(ChargeSend.refusal(false, false) == ChargeSend.Refusal.QUIET, "Any other refusal never mentions a recording");
        // #387 review, finding 9: no scooter chosen is said ("Choose a scooter first."), first, and the row is disabled.
        require(ChargeSend.refusal(true, false, false) == ChargeSend.Refusal.CHOOSE_SCOOTER
                && ChargeSend.refusal(true, true, true) == ChargeSend.Refusal.CHOOSE_SCOOTER, "No scooter: choose one first");
        require(ChargeSend.refusal(false, true, false) == ChargeSend.Refusal.STOP_RECORDING
                && ChargeSend.refusal(false, false, true) == ChargeSend.Refusal.QUIET
                && ChargeSend.refusal(false, false, false) == ChargeSend.Refusal.QUIET, "With a scooter, as before");
        require(ChargeSend.rowEnabled(true, true, false), "Enabled with a scooter and no export running");
        require(!ChargeSend.rowEnabled(true, false, false) && !ChargeSend.rowEnabled(false, false, false)
                && !ChargeSend.rowEnabled(true, true, true), "Disabled without a scooter, without the service, or while exporting");
        // Finding 7: an attachment that couldn't be prepared says so, never "No email app".
        require(ChargeSend.outcome(false, false) == ChargeSend.Outcome.EXPORT_FAILED, "No attachment: the export failed");
        require(ChargeSend.outcome(true, false) == ChargeSend.Outcome.NO_EMAIL_APP, "Attached, no app took it");
        require(ChargeSend.outcome(true, true) == ChargeSend.Outcome.SENT, "Attached and opened");
        // Finding 3: a temporary file under a minute old is another report's being made, and stays.
        long now = 10_000_000L;
        require(!ChargeSend.dropTemporary(now, now) && !ChargeSend.dropTemporary(now - 59_999L, now)
                && !ChargeSend.dropTemporary(now + 30_000L, now), "A young temporary file stays");
        require(ChargeSend.dropTemporary(now - 60_000L, now) && ChargeSend.dropTemporary(now - 3_600_000L, now)
                && ChargeSend.dropTemporary(now + 3_600_000L, now), "A left-over one goes");
        mailbox();
        attachment();
        compression();
        prepareSmall();
        failureHandover();
        leftovers();
        System.out.println("ChargeSend: " + checks + " checks passed");
    }

    /** Finding 10: a finished export reaches the activity showing Volt, across a page rebuild or an activity recreation. */
    private static void mailbox() {
        try {
            File dir = Files.createTempDirectory("charge-send").toFile();
            List<File> mailed = new ArrayList<>();
            Consumer<ChargeSend.Export> first = x -> mailed.add(x.file), second = x -> mailed.add(x.file);
            // A page rebuilt by a rotation closes, but the activity stays registered: the email opens.
            ChargeSend.Mailbox box = new ChargeSend.Mailbox();
            box.register(first, 0);
            File a = file(dir, "a");
            box.deliver(new ChargeSend.Export(a, "M"), 1);
            require(mailed.equals(Arrays.asList(a)), "Delivered to the registered activity");
            // Recreated (theme, text size): the file arriving between the two activities waits for the next one.
            box.unregister(first, true);
            File b = file(dir, "b");
            box.deliver(new ChargeSend.Export(b, "M"), 1_000);
            require(mailed.size() == 1 && b.exists(), "Held while no activity shows Volt");
            box.register(second, 2_000);
            require(mailed.equals(Arrays.asList(a, b)), "The next activity emails it");
            // An older activity going away later doesn't unregister the new one.
            box.unregister(first, false);
            File c = file(dir, "c");
            box.deliver(new ChargeSend.Export(c, "M"), 3_000);
            require(mailed.size() == 3 && mailed.get(2) == c, "Only its own receiver unregisters");
            // Held too long: deleted, never emailed.
            box.unregister(second, true);
            File d = file(dir, "d");
            box.deliver(new ChargeSend.Export(d, "M"), 4_000);
            box.register(first, 4_000 + ChargeSend.Mailbox.HOLD_MS);
            require(mailed.size() == 3 && !d.exists(), "A stale held export is deleted");
            // Closed for good (not a recreation): a file that arrives is deleted, and one held is too.
            box.unregister(first, true);
            File e = file(dir, "e");
            box.deliver(new ChargeSend.Export(e, "M"), 5_000);
            box.unregister(first, false);
            require(e.exists(), "Unregistering another receiver changes nothing");
            box.register(first, 5_100);
            require(mailed.size() == 4 && mailed.get(3) == e, "Held one delivered");
            box.unregister(first, false);
            File f = file(dir, "f");
            box.deliver(new ChargeSend.Export(f, "M"), 6_000);
            require(mailed.size() == 4 && !f.exists(), "After the app closed, an export is deleted, never emailed later");
            // Two held: only the newer waits.
            ChargeSend.Mailbox two = new ChargeSend.Mailbox();
            File g = file(dir, "g"), h = file(dir, "h");
            two.deliver(new ChargeSend.Export(g, "M"), 0); two.deliver(new ChargeSend.Export(h, "M"), 1);
            require(!g.exists() && h.exists(), "One held at a time");
            two.deliver(null, 2);
            two.deliver(new ChargeSend.Export(null, "M"), 2);
            List<File> got = new ArrayList<>();
            two.register(x -> got.add(x.file), 3);
            require(got.equals(Arrays.asList(h)), "Null is no export");
            exportModel(dir);
        } catch (IOException failed) {
            throw new AssertionError(failed);
        }
    }

    /**
     * #387 round-2 review, finding 3: the model line taken at the tap travels with the file, held or not, so the email
     * names the scooter whose recordings it holds whichever scooter is selected when the export arrives.
     */
    private static void exportModel(File dir) throws IOException {
        List<ChargeSend.Export> got = new ArrayList<>();
        ChargeSend.Mailbox box = new ChargeSend.Mailbox();
        box.register(got::add, 0);
        File a = file(dir, "model-a");
        box.deliver(new ChargeSend.Export(a, "Model: Navee N65"), 1);
        require(got.size() == 1 && got.get(0).file == a && got.get(0).model.equals("Model: Navee N65"), "Delivered with its model");
        ChargeSend.Mailbox held = new ChargeSend.Mailbox();
        File b = file(dir, "model-b");
        held.deliver(new ChargeSend.Export(b, "Model: Navee S65C"), 1_000);
        held.register(got::add, 2_000);
        require(got.size() == 2 && got.get(1).file == b && got.get(1).model.equals("Model: Navee S65C"), "Held, it keeps its model");
        require(new ChargeSend.Export(b, null).model.isEmpty(), "No model is empty, never null");
    }

    /** The owner's decisions on #383: the recordings' own name, and gzip over 25 MB with a matching name and type. */
    private static void attachment() {
        require(ChargeSend.NAME.equals("volt-charge-recordings.json") && !ChargeSend.NAME.equals(DIAGNOSTICS_NAME),
                "The recordings have their own name, not the diagnostics report's");
        for (long bytes : new long[] {0L, 1L, 1_000_000L, 25_000_000L}) {
            ChargeSend.Attachment a = ChargeSend.attachment(bytes);
            require(!a.compressed && "volt-charge-recordings.json".equals(a.name) && "application/json".equals(a.type),
                    "Up to 25 MB the file goes as it is (" + bytes + ")");
        }
        for (long bytes : new long[] {25_000_001L, 41_500_000L, 4_000_000_000L}) {
            ChargeSend.Attachment a = ChargeSend.attachment(bytes);
            require(a.compressed && "volt-charge-recordings.json.gz".equals(a.name) && "application/gzip".equals(a.type),
                    "Over 25 MB it is gzip-compressed, named and typed so (" + bytes + ")");
        }
        require(ChargeSend.named(ChargeSend.NAME) == ChargeSend.attachment(0L)
                && ChargeSend.named(ChargeSend.COMPRESSED_NAME) == ChargeSend.attachment(Long.MAX_VALUE), "Each name finds its attachment");
        require(ChargeSend.named(DIAGNOSTICS_NAME) == null && ChargeSend.named(null) == null && ChargeSend.named("") == null
                && ChargeSend.named("volt-charge-recordings.json.zip") == null, "No other name is a recordings attachment");
    }

    /** The diagnostics report's own name (DiagnosticsProvider.NAME), which stays as it is. */
    private static final String DIAGNOSTICS_NAME = "volt-diagnostics.json";

    /** A generated recordings-like file over 25 MB: compressed, it is much smaller and decompresses to the same bytes. */
    private static void compression() {
        try {
            File dir = Files.createTempDirectory("charge-gzip").toFile();
            File source = new File(dir, "charges.json"), zipped = new File(dir, "manual.json.gz"), prepared = new File(dir, "charges.json.gz");
            try {
                try (java.io.Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(source), java.nio.charset.StandardCharsets.UTF_8))) {
                    java.util.Random random = new java.util.Random(387);
                    w.write("{\"format\":\"volt-charge-sessions-v1\",\"sessions\":[{\"readings\":[");
                    for (int i = 0; source.length() <= ChargeSend.COMPRESS_OVER_BYTES + 1_000_000L || i % 1000 != 0; i++) {
                        if (i > 0) w.write(',');
                        w.write("{\"t\":" + (1_700_000_000_000L + i * 1000L) + ",\"soc\":" + (i / 600 % 100) + ",\"v\":" + (36 + random.nextInt(1300) / 100.0)
                                + ",\"a\":" + (random.nextInt(400) / 100.0) + ",\"w\":" + random.nextInt(200) + ",\"c\":" + (20 + random.nextInt(10)) + "}");
                        if (i % 1000 == 999) w.flush();
                    }
                    w.write("]}]}");
                }
                require(ChargeSend.attachment(source.length()).compressed, "The generated file is over the threshold");
                try (java.io.InputStream in = new java.io.FileInputStream(source); java.io.OutputStream out = new java.io.FileOutputStream(zipped)) {
                    ChargeSend.gzip(in, out);
                    out.write(new byte[0]); // gzip leaves the caller's stream open
                }
                require(zipped.length() > 0 && zipped.length() * 3 < source.length(), "The text shrinks a lot: " + source.length() + " -> " + zipped.length());
                try (java.io.InputStream a = new java.io.BufferedInputStream(new java.io.FileInputStream(source));
                     java.io.InputStream b = new java.io.BufferedInputStream(new java.util.zip.GZIPInputStream(new java.io.FileInputStream(zipped)))) {
                    long at = 0;
                    for (int x, y; ; at++) {
                        x = a.read(); y = b.read();
                        if (x != y) { require(false, "Decompressed bytes differ at " + at); break; }
                        if (x == -1) break;
                    }
                    require(at == source.length(), "Decompressed to the same " + source.length() + " bytes");
                }
                prepareLarge(dir, source, zipped, prepared);
                require(zipped.delete() && prepared.delete() && dir.delete(), "Cleaned up");
            } finally {
                // A failed check above still leaves no ~30 MB behind in the temporary folder.
                source.delete(); zipped.delete(); prepared.delete();
                new File(dir, "charges.json.gz.tmp").delete(); dir.delete();
            }
        } catch (IOException e) { throw new AssertionError(e); }
    }

    /**
     * #412 review, P3 1 and 2: the compression is the export's last step (ChargeSend.prepare, run by the service under its
     * exporting flag), so what the mailbox hands over is already final and the activity only moves it into place. A
     * failure leaves no partial .gz behind and the export for the service to delete.
     */
    private static void prepareLarge(File dir, File source, File zipped, File prepared) throws IOException {
        require(!ChargeSend.ready(source), "A raw export over 25 MB is not ready to attach");
        // A compression that fails (here: its temporary file can't be made) leaves nothing it made, and the export as it was.
        File blocked = new File(dir, "charges.json.gz.tmp");
        require(blocked.mkdir(), "Blocked the temporary file");
        long size = source.length();
        boolean threw = false;
        try { ChargeSend.prepare(source); } catch (IOException expected) { threw = true; }
        require(threw && !prepared.exists() && source.isFile() && source.length() == size, "A failed compression leaves no .gz, and the export");
        blocked.delete(); require(!blocked.exists(), "Unblocked");
        // While it runs the service is still exporting: the row is disabled and a second tap stays quiet.
        boolean exporting = true;
        File ready = ChargeSend.prepare(source);
        require(ChargeSend.refusal(false, false, exporting) == ChargeSend.Refusal.QUIET && !ChargeSend.rowEnabled(true, true, exporting),
                "During the compression the row is busy and a second tap is quiet");
        require(ready.equals(prepared) && prepared.isFile() && !source.exists() && !new File(dir, "charges.json.gz.tmp").exists(),
                "Compressed beside the export as <name>.gz; the export and the temporary file are gone");
        require(ChargeSend.ready(ready) && ChargeSend.of(ready).compressed && ChargeSend.COMPRESSED_NAME.equals(ChargeSend.of(ready).name)
                && ChargeSend.prepare(ready) == ready, "Prepared, it is ready: attached as volt-charge-recordings.json.gz, as it is");
        try (java.io.InputStream a = new java.io.BufferedInputStream(new java.util.zip.GZIPInputStream(new java.io.FileInputStream(zipped)));
             java.io.InputStream b = new java.io.BufferedInputStream(new java.util.zip.GZIPInputStream(new java.io.FileInputStream(ready)))) {
            long at = 0;
            for (int x, y; ; at++) {
                x = a.read(); y = b.read();
                if (x != y) { require(false, "The prepared attachment decompresses differently at " + at); break; }
                if (x == -1) break;
            }
            require(at == size, "The prepared attachment decompresses to the export's " + size + " bytes");
        }
        // Then the flag clears and the hand-over opens: the final file reaches the activity, across a recreation too.
        exporting = false;
        ChargeSend.Mailbox box = new ChargeSend.Mailbox();
        List<ChargeSend.Export> got = new ArrayList<>();
        Consumer<ChargeSend.Export> old = got::add, recreated = got::add;
        box.register(old, 0); box.unregister(old, true);
        box.deliver(new ChargeSend.Export(ready, "M"), 1);
        box.register(recreated, 2);
        require(!exporting && got.size() == 1 && got.get(0).file.equals(ready) && ChargeSend.ready(got.get(0).file) && ready.isFile(),
                "The recreated activity gets the final, compressed file");
    }

    /** Up to 25 MB, or an empty export, the file is ready as it is: prepare() returns it untouched, attached plain. */
    private static void prepareSmall() {
        try {
            File dir = Files.createTempDirectory("charge-prepare").toFile();
            File small = file(dir, "charges-small.json");
            require(ChargeSend.ready(small) && ChargeSend.prepare(small) == small && small.isFile() && small.length() == 1
                    && !new File(dir, "charges-small.json.gz").exists(), "A small export is attached as it is");
            require(!ChargeSend.of(small).compressed && ChargeSend.NAME.equals(ChargeSend.of(small).name), "Named volt-charge-recordings.json");
            require(small.delete() && dir.delete(), "Cleaned up");
        } catch (IOException e) { throw new AssertionError(e); }
    }

    /**
     * #412 round-2 review, P3 1: a failed export goes through the same hand-over as a file, so the activity showing Volt
     * when it ends, a recreated one too, shows it exactly once, and it never emails.
     */
    private static void failureHandover() {
        try {
            File dir = Files.createTempDirectory("charge-failure").toFile();
            String failed = "Charge recordings could not be exported";
            List<ChargeSend.Export> got = new ArrayList<>();
            Consumer<ChargeSend.Export> old = got::add, recreated = got::add, later = got::add;
            ChargeSend.Export failure = ChargeSend.Export.failed(failed);
            require(failure.failed() && failure.file == null && failed.equals(failure.failure) && failure.model.isEmpty()
                    && !new ChargeSend.Export(file(dir, "ok"), "M").failed(), "A failure carries its text and no file; a file export is no failure");
            require(ChargeSend.Export.failed(null).failed() && ChargeSend.Export.failed(null).failure.isEmpty(), "A failure without text is still one");
            // The activity that started it still shows Volt: it gets the failure, once.
            ChargeSend.Mailbox box = new ChargeSend.Mailbox();
            box.register(old, 0);
            box.deliver(ChargeSend.Export.failed(failed), 1);
            require(got.size() == 1 && got.get(0).failed() && got.get(0).file == null, "Delivered to the activity showing Volt");
            // Recreated while the export ran: the failure waits for the new activity and reaches it once.
            box.unregister(old, true);
            box.deliver(ChargeSend.Export.failed(failed), 1_000);
            require(got.size() == 1, "Held while no activity shows Volt");
            box.register(recreated, 2_000);
            require(got.size() == 2 && got.get(1).failed() && failed.equals(got.get(1).failure), "The recreated activity shows it");
            // Consumed: a later recreation shows nothing again.
            box.unregister(recreated, true);
            box.register(later, 3_000);
            require(got.size() == 2, "Shown once: a later recreation gets nothing");
            // The app closed for good: dropped, never shown later.
            box.unregister(later, false);
            box.deliver(ChargeSend.Export.failed(failed), 4_000);
            box.register(later, 4_100);
            require(got.size() == 2, "After the app closed, a failure is never shown later");
            // Held too long: dropped.
            box.unregister(later, true);
            box.deliver(ChargeSend.Export.failed(failed), 5_000);
            box.register(later, 5_000 + ChargeSend.Mailbox.HOLD_MS);
            require(got.size() == 2, "A stale failure is dropped");
            // One held at a time: a failure after a held file deletes the file (no email); a file after a failure replaces it.
            ChargeSend.Mailbox two = new ChargeSend.Mailbox();
            File a = file(dir, "a");
            two.deliver(new ChargeSend.Export(a, "M"), 0);
            two.deliver(ChargeSend.Export.failed(failed), 1);
            List<ChargeSend.Export> only = new ArrayList<>();
            two.register(only::add, 2);
            require(!a.exists() && only.size() == 1 && only.get(0).failed(), "The newer failure replaces a held file, which is deleted");
            ChargeSend.Mailbox three = new ChargeSend.Mailbox();
            File b = file(dir, "b");
            three.deliver(ChargeSend.Export.failed(failed), 0);
            three.deliver(new ChargeSend.Export(b, "M"), 1);
            List<ChargeSend.Export> next = new ArrayList<>();
            three.register(next::add, 2);
            require(b.exists() && next.size() == 1 && next.get(0).file == b && !next.get(0).failed(), "A newer file replaces a held failure");
            // Exactly one hand-over per export: a file or a failure, never both.
            require(got.stream().noneMatch(x -> x.failed() && x.file != null), "A failure never carries a file to email");
            for (File f : dir.listFiles()) f.delete();
            require(dir.delete(), "Cleaned up");
        } catch (IOException e) { throw new AssertionError(e); }
    }

    /**
     * #412 round-2 review (emulator): what a killed compression leaves in cache/charge-exports goes at the next export;
     * a finished export, plain or compressed, never does (it keeps its one-day window).
     */
    private static void leftovers() {
        java.util.Set<String> killedMid = new java.util.HashSet<>(Arrays.asList("charges-1.json", "charges-1.json.gz.tmp"));
        require(ChargeSend.leftover("charges-1.json.gz.tmp", killedMid) && ChargeSend.leftover("charges-1.json", killedMid),
                "Killed mid-compression: the temporary file and its uncompressed export are leftovers");
        java.util.Set<String> killedAfter = new java.util.HashSet<>(Arrays.asList("charges-2.json", "charges-2.json.gz"));
        require(ChargeSend.leftover("charges-2.json", killedAfter) && !ChargeSend.leftover("charges-2.json.gz", killedAfter),
                "Killed after the rename: the uncompressed export goes, the finished .gz stays");
        java.util.Set<String> finished = new java.util.HashSet<>(Arrays.asList("charges-3.json", "charges-4.json.gz", "charges-5.json", "charges-6.json.gz.tmp"));
        require(!ChargeSend.leftover("charges-3.json", finished) && !ChargeSend.leftover("charges-4.json.gz", finished)
                && !ChargeSend.leftover("charges-5.json", finished), "A finished export, plain or compressed, is never a leftover");
        require(ChargeSend.leftover("charges-6.json.gz.tmp", finished) && ChargeSend.leftover("x.tmp", new java.util.HashSet<>()),
                "Any temporary file is a leftover, its export gone or not");
        require(!ChargeSend.leftover("charges-7.json", new java.util.HashSet<>(Arrays.asList("charges-7.json", "charges-70.json.gz", "other.json.gz.tmp"))),
                "Only its own .gz or .gz.tmp makes an export a leftover");
        try {
            File dir = Files.createTempDirectory("charge-leftovers").toFile();
            File killed = file(dir, "charges-1.json"), partial = file(dir, "charges-1.json.gz.tmp"), raw = file(dir, "charges-2.json"),
                    zipped = file(dir, "charges-2.json.gz"), plain = file(dir, "charges-3.json"), held = file(dir, "charges-4.json.gz");
            require(ChargeSend.clearLeftovers(dir) == 3, "Three leftovers cleared");
            require(!killed.exists() && !partial.exists() && !raw.exists(), "The leftovers are gone");
            require(zipped.isFile() && plain.isFile() && held.isFile(), "Every finished export stays");
            require(ChargeSend.clearLeftovers(dir) == 0 && zipped.isFile() && plain.isFile() && held.isFile(), "Again: nothing more goes");
            require(ChargeSend.clearLeftovers(new File(dir, "missing")) == 0, "No folder: nothing to clear");
            for (File f : dir.listFiles()) f.delete();
            require(dir.delete(), "Cleaned up");
        } catch (IOException e) { throw new AssertionError(e); }
    }

    private static File file(File dir, String name) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), new byte[] {1});
        return f;
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
