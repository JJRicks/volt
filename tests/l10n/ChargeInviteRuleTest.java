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

    /** Email to Volt, the backup registry and Charge history & recordings' anchor (source checks). */
    private static void wiring() throws Exception {
        String main = read("src/com/jjricks/volt/MainActivity.java").replace(" ", "");
        String more = read("src/com/jjricks/volt/MoreScreen.java").replace(" ", "");
        String registry = read("src/com/jjricks/volt/BackupRegistry.java").replace(" ", "");
        check(main.contains("elseif(outcome==ChargeSend.Outcome.SENT)preferences.edit().putString(ChargeInvite.KEY,ChargeInvite.SENT).apply();"),
                "An email with the recordings opened retires the banner");
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
