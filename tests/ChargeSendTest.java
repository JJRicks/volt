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
