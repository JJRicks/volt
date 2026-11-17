package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Battery & charging's charge recordings invite (ChargeInvite, ChargeInviteSheet; owner, Nov 17 2026): when the banner
 * shows, what its sheet says, and how the page, the sheet and Email to Volt are wired to it.
 */
public final class ChargeInviteTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        rule();
        text();
        wiring();
        System.out.println("ChargeInviteTest: " + checks + " checks passed");
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

    /** The sheet's points, in English, name the controls they point to by their shown names. */
    private static void text() {
        String title = HubText.chargeInviteTitle().render(), body = HubText.chargeInviteBody().render();
        check(title.equals("Help improve your charge time estimate"), "Banner title: " + title);
        check(body.equals("Record a few charges and send them to Volt."), "Banner line: " + body);
        Msg[] steps = HubText.chargeInviteSteps();
        check(steps.length == 5, "Five points");
        String record = steps[0].render(), send = steps[4].render();
        check(record.contains(T.moreDevRecord()) && record.contains(T.moreChargeAutoRecord()),
                "The first point names Record this charge and Record charges automatically: " + record);
        check(steps[2].render().contains("100%"), "Charging to 100% helps the most: " + steps[2].render());
        check(steps[3].render().contains("near the scooter"), "The phone stays near the scooter: " + steps[3].render());
        check(send.contains(HubText.rowTitle(HubPages.BATTERY_HISTORY).render()) && send.contains(HubText.chargeSendRow().render()),
                "The last point names Charge history & recordings and Email to Volt: " + send);
        for (Msg step : steps) check(!step.render().replace("100%", "").contains("%"), "No placeholder left: " + step.render());
    }

    /** The banner, the sheet and Email to Volt use the rule and the existing controls (source checks). */
    private static void wiring() throws Exception {
        String main = read("src/com/jjricks/volt/MainActivity.java").replace(" ", "");
        String sheet = read("src/com/jjricks/volt/ChargeInviteSheet.java").replace(" ", "");
        String more = read("src/com/jjricks/volt/MoreScreen.java").replace(" ", "");
        String registry = read("src/com/jjricks/volt/BackupRegistry.java").replace(" ", "");
        check(main.contains("invite=HubPages.BATTERY.equals(spec.page)?chargeInviteBanner():null;"), "Only Battery & charging builds the banner");
        check(main.contains("preferences.edit().putString(ChargeInvite.KEY,ChargeInvite.DISMISSED).commit();"), "Its X stores the answer, committed");
        check(main.contains("elseif(outcome==ChargeSend.Outcome.SENT)preferences.edit().putString(ChargeInvite.KEY,ChargeInvite.SENT).apply();"),
                "An email with the recordings opened retires the banner");
        check(main.contains("inviteSheet=ChargeInviteSheet.show(MainActivity.this,preferences);"), "The banner opens the sheet");
        check(main.contains("if(invite!=null)Motion.reveal(glide,invite,chargeInviteShows(),still);")
                && main.contains("child!=testBanner&&child!=invite"), "It comes and goes with the page's banners' motion, the rows gliding");
        check(sheet.contains("preferences.edit().putBoolean(ChargeAutoRecordPolicy.KEY,enabled).apply()"),
                "The sheet's switch is Record charges automatically's own preference");
        check(sheet.contains("host.open(HubPages.BATTERY_HISTORY,HubNav.Args.anchor(ChargeInvite.RECORD_ANCHOR));"),
                "Record this charge opens Charge history & recordings at its row");
        check(sheet.contains("host.open(HubPages.BATTERY_HISTORY);"), "The sheet opens Charge history & recordings");
        check(sheet.contains("ui.secondaryButton(T.mainReportNotNow(),close)"), "Not now only closes the sheet");
        check(!sheet.contains("ChargeInvite.KEY"), "Nothing in the sheet answers the banner: only its X or a sent email does");
        check(more.contains("if(ChargeInvite.RECORD_ANCHOR.equals(id))returnchargeCaptureRow!=null?chargeCaptureRow.view:null;")
                && more.contains("||s==null||s.isChargeRecording()||s.chargeCaptureUnavailable()!=null)returnfalse;"),
                "The page brings Record this charge on screen and starts it only when a recording can start");
        check(more.contains("recordChargeDialog(s);\nreturntrue;"), "Starting it is the row's own step");
        check(registry.contains("add(app,ChargeInvite.KEY,String.class,PREFERENCES);"), "The answer travels with App preferences");
    }

    private static String read(String path) throws Exception { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
