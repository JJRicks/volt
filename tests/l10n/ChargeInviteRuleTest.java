package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Battery & charging's charge recordings invite (ChargeInvite; owner, Nov 17 2026): when its banner shows, and the
 * text-free wiring around it (Email to Volt retires it, the answer travels with a backup, Record this charge's anchor
 * on Charge history & recordings). The banner, its sheet and their words come with the invite's strings.
 */
public final class ChargeInviteRuleTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        rule();
        handOver();
        wiring();
        System.out.println("ChargeInviteRuleTest: " + checks + " checks passed");
    }

    /** It shows with a scooter chosen until the X or Email to Volt answers it; anything else stored is no answer. */
    private static void rule() {
        check(ChargeInvite.shows(true, null), "No answer yet: the banner shows");
        check(!ChargeInvite.shows(false, null), "No scooter chosen: nothing to record, no banner");
        check(!ChargeInvite.shows(true, ChargeInvite.DISMISSED), "Closed with its X: hidden for good");
        check(!ChargeInvite.shows(true, ChargeInvite.SENT), "Recordings emailed to Volt: hidden for good");
        check(!ChargeInvite.shows(false, ChargeInvite.SENT), "Answered and no scooter: hidden");
        // A wrong type or an unknown word (a newer Volt's, or a damaged restore) never crashes and isn't an answer.
        check(ChargeInvite.shows(true, Boolean.TRUE) && ChargeInvite.shows(true, 1) && ChargeInvite.shows(true, "")
                && ChargeInvite.shows(true, "later"), "Anything else stored: the banner shows");
        check(ChargeInvite.answered("dismissed") && ChargeInvite.answered("sent") && !ChargeInvite.answered(null),
                "The stored words are the file format");
        check("charge_invite".equals(ChargeInvite.KEY), "The preference key is stable");
        check(!ChargeInvite.RECORD_ANCHOR.equals("charge-capture-action"),
                "The sheet's Record this charge anchor is its own, never the row's tag a search result could name");
    }

    /**
     * Email to Volt's hand-over (#537 review, F03): backing out of Android's chooser marked the invite sent. Android says
     * nothing about a send; an app taking the email covers Volt (it stops), a chooser over it only pauses it.
     */
    private static void handOver() {
        ChargeInvite.HandOver h = new ChargeInvite.HandOver();
        check(!h.pending() && !h.stopped(true, false), "Nothing handed over: a stop is just a stop");
        h.opened(); h.paused(); h.resumed();
        check(!h.pending() && !h.stopped(true, false), "The chooser closed with Back (paused, resumed, never stopped): not sent");
        h.opened(); h.paused();
        check(h.stopped(true, false), "An app took the email (paused, then stopped): sent");
        check(!h.pending() && !h.stopped(true, false), "Sent once: a later stop is just a stop");
        h.opened(); h.paused();
        check(!h.stopped(false, false) && h.pending(), "The screen went off over the chooser: not an app, still waiting");
        h.resumed();
        check(!h.pending(), "Back on with the chooser gone: not sent");
        h.opened(); h.paused();
        check(!h.stopped(false, false), "Screen off over the chooser");
        check(h.stopped(true, false), "...then an app picked after the screen came back on: sent");
        h.opened(); h.paused();
        check(!h.stopped(true, true) && h.pending(), "A configuration change's stop is not an app taking it");
        h.resumed();
        h.opened(); h.resumed();
        check(h.pending(), "Opened during onResume (the mailbox delivering): the rest of that resume doesn't cancel it");
        h.paused();
        check(h.stopped(true, false), "...and the app then taking it still counts");
        h.opened();
        check(h.stopped(true, false), "A stop straight after the hand-over counts too");
    }

    /** Email to Volt, the backup registry and Charge history & recordings' anchor (source checks). */
    private static void wiring() throws Exception {
        String main = read("src/com/jjricks/volt/MainActivity.java").replace(" ", "");
        String more = read("src/com/jjricks/volt/MoreScreen.java").replace(" ", "");
        String registry = read("src/com/jjricks/volt/BackupRegistry.java").replace(" ", "");
        // #537 review, F03: opening a chooser is not a send. The answer is stored only from onStop, once an app took it.
        check(!main.contains("elseif(outcome==ChargeSend.Outcome.SENT)preferences.edit().putString(ChargeInvite.KEY,ChargeInvite.SENT).apply();")
                && main.contains("elseif(outcome==ChargeSend.Outcome.SENT)chargeHandOver.opened();"),
                "Handing the recordings' email to Android only starts the hand-over; it never answers the invite by itself");
        check(main.contains("if(chargeHandOver.stopped(power==null||power.isInteractive(),isChangingConfigurations()))\npreferences.edit().putString(ChargeInvite.KEY,ChargeInvite.SENT).apply();")
                && main.indexOf("chargeHandOver.stopped(") > main.indexOf("protectedvoidonStop(){")
                && main.indexOf("chargeHandOver.stopped(") < main.indexOf("super.onStop();", main.indexOf("protectedvoidonStop(){")),
                "An app taking the email (Volt stops) retires the banner");
        check(main.indexOf("chargeHandOver.paused();") > main.indexOf("publicvoidonPause(){")
                && main.indexOf("chargeHandOver.paused();") < main.indexOf("if(startupRedirect||enteringMaintenance){super.onPause();return;}"),
                "Every pause counts, before onPause's early return");
        check(main.indexOf("chargeHandOver.resumed();") > main.indexOf("publicvoidonResume(){")
                && main.indexOf("chargeHandOver.resumed();") < main.indexOf("if(startupRedirect||enteringMaintenance)return;", main.indexOf("publicvoidonResume(){")),
                "Every resume counts, before onResume's early return");
        int sent = 0; for (int at = main.indexOf("ChargeInvite.SENT"); at >= 0; at = main.indexOf("ChargeInvite.SENT", at + 1)) sent++;
        check(sent == 1, "One place stores the sent answer: " + sent);
        check(more.contains("if(ChargeInvite.RECORD_ANCHOR.equals(id))returnchargeCaptureRow!=null?chargeCaptureRow.view:null;"),
                "The page brings Record this charge on screen for the anchor");
        check(more.contains("||s==null||s.isChargeRecording()||s.chargeCaptureUnavailable()!=null)returnfalse;")
                && more.contains("recordChargeDialog(s);\nreturntrue;"),
                "It starts the row's own step only when a recording can start now; otherwise the row flashes");
        check(registry.contains("add(app,ChargeInvite.KEY,String.class,PREFERENCES);"), "The answer travels with App preferences");
    }

    private static String read(String path) throws Exception { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
