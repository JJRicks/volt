package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Battery & charging's charge recordings invite (ChargeInvite, ChargeInviteSheet; owner, Nov 17 2026): what its banner
 * and sheet say, and how the page and the sheet are wired to it. Its rule is ChargeInviteRuleTest's.
 */
public final class ChargeInviteTextTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        text();
        wiring();
        System.out.println("ChargeInviteTextTest: " + checks + " checks passed");
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
        // #538 review, P2: no promise that any length helps (a very short automatic recording isn't kept).
        check(steps[1].render().equals("It doesn't have to be perfect. Even a partial charge helps."), "The second point: " + steps[1].render());
        check(steps[3].render().contains("near the scooter"), "The phone stays near the scooter: " + steps[3].render());
        check(send.contains(HubText.rowTitle(HubPages.BATTERY_HISTORY).render()) && send.contains(HubText.chargeSendRow().render()),
                "The last point names Charge history & recordings and Email to Volt: " + send);
        for (Msg step : steps) check(!step.render().replace("100%", "").contains("%"), "No placeholder left: " + step.render());
    }

    /** The banner, the sheet and Email to Volt use the rule and the existing controls (source checks). */
    private static void wiring() throws Exception {
        String main = read("src/com/jjricks/volt/MainActivity.java").replace(" ", "");
        String sheet = read("src/com/jjricks/volt/ChargeInviteSheet.java").replace(" ", "");
        check(main.contains("invite=HubPages.BATTERY.equals(spec.page)?chargeInviteBanner():null;"), "Only Battery & charging builds the banner");
        check(main.contains("preferences.edit().putString(ChargeInvite.KEY,ChargeInvite.DISMISSED).commit();"), "Its X stores the answer, committed");
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
        // #538 review: the chart tile gives way before a word of the banner would break (F03), and a refused Record this
        // charge says the reason now, not an earlier recording's outcome (F05).
        check(main.contains("intfits=widestWord(wordViews[0])<=room&&widestWord(wordViews[1])<=room?View.VISIBLE:View.GONE;"),
                "The banner's tile gives way before a word would break");
        String more = read("src/com/jjricks/volt/MoreScreen.java").replace(" ", "");
        check(more.contains("Stringrefused=closed||s==null||s.isChargeRecording()?null:s.chargeCaptureUnavailable();\nif(refused!=null)host.toast(refused);"),
                "A refused Record this charge from the sheet says the current reason");
    }

    private static String read(String path) throws Exception { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
