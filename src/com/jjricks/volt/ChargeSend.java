package com.jjricks.volt;

import java.io.File;
import java.util.function.Consumer;

/**
 * Email to Volt on Charge history & recordings (hub layout plan, PR 4): what to tell the owner when the service refuses
 * the export behind it (ScooterService.exportChargeRecordings). Pure, so the JVM suite tests it (tests/ChargeSendTest.java).
 * The council's review of #396 (P3): only a charge being recorded is worth a toast ("Stop charge recording before
 * sending the recordings"); an earlier export still being written (a double tap) stays quiet, so the owner is never
 * told to stop a recording that isn't running. The council's review of #387 (P3) added the rest: no scooter chosen
 * (finding 9), an attachment that couldn't be prepared (7), the mailbox that keeps a finished export across a page
 * rebuild or an activity recreation (10), and the age under which DiagnosticsProvider leaves a temporary file alone (3).
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
     * Where a finished export goes: the activity showing Volt now (it emails it), whichever page or activity asked. A
     * rotation or fold rebuilds the page that asked (its screen closes), and a theme or text-size change recreates the
     * activity; either way the email still opens. With no activity the file waits, until one registers or for
     * {@link #HOLD_MS}; once the app has truly closed (not a recreation) it is deleted instead, so an email never opens
     * long after the owner left.
     */
    static final class Mailbox {
        static final long HOLD_MS = 2 * 60_000L;
        private Consumer<File> receiver;
        private boolean accepting = true;
        private File held;
        private long heldAt;

        /** The activity's own: it emails what arrives now, and what waited for it if that is recent. */
        synchronized void register(Consumer<File> receiver, long nowMs) {
            this.receiver = receiver; accepting = true;
            File waiting = held; held = null;
            if (waiting == null) return;
            if (nowMs - heldAt >= 0 && nowMs - heldAt < HOLD_MS) receiver.accept(waiting); else waiting.delete();
        }

        /** The activity is going: recreating keeps a file arriving meanwhile for the next one; closing drops it. */
        synchronized void unregister(Consumer<File> receiver, boolean recreating) {
            if (this.receiver != receiver) return;
            this.receiver = null; accepting = recreating;
            if (!recreating && held != null) { held.delete(); held = null; }
        }

        /** A finished export: to the activity, else held (one at a time; an older one is deleted), else deleted. */
        synchronized void deliver(File file, long nowMs) {
            if (file == null) return;
            if (receiver != null) { receiver.accept(file); return; }
            if (!accepting) { file.delete(); return; }
            if (held != null && !held.equals(file)) held.delete();
            held = file; heldAt = nowMs;
        }
    }

    static final Mailbox MAILBOX = new Mailbox();
}
