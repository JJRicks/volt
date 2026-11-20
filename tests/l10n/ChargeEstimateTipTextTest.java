package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Home's estimate tip (ChargeEstimateTip; owner, Nov 19 2026): its words and narrow rungs, its sheet's words (the
 * invite's own, reused), and how Home and the sheet are wired to the rule (source checks). The rule itself is
 * ChargeEstimateTipRuleTest's.
 */
public final class ChargeEstimateTipTextTest {
    private static int checks;
    private static final ChargeEstimateTip.Tip RECORD = ChargeEstimateTip.Tip.NO_RECORDINGS;

    public static void main(String[] args) throws Exception {
        text();
        wiring();
        System.out.println("ChargeEstimateTipTextTest: " + checks + " checks passed");
    }

    /** Every tip's words: a full rung, a shorter one, and an icon from the app's set. The record tip's sheet reuses the invite's. */
    private static void text() {
        check("Improve this estimate".equals(HubText.estimateTip(RECORD).render()), "Full: " + HubText.estimateTip(RECORD).render());
        check("Improve estimate".equals(HubText.estimateTipShort(RECORD).render()), "Short: " + HubText.estimateTipShort(RECORD).render());
        for (ChargeEstimateTip.Tip tip : ChargeEstimateTip.Tip.values()) {
            String full = HubText.estimateTip(tip).render(), brief = HubText.estimateTipShort(tip).render();
            check(!full.isEmpty() && !brief.isEmpty() && brief.length() < full.length(), tip.id + ": the short rung is shorter");
            check(!ChargeEstimateTip.icon(tip).isEmpty(), tip.id + ": an icon");
        }
        check("Record your charges".equals(HubText.recordTipTitle().render()), "Sheet title");
        check("Hide this tip".equals(HubText.estimateTipHide().render()), "Hide button");
        Msg[] steps = HubText.recordTipSteps();
        Msg[] invite = HubText.chargeInviteSteps();
        check(steps.length == 4, "Four points");
        for (int i = 0; i < steps.length; i++)
            check(steps[i].render().equals(invite[i + 1].render()), "Point " + i + " is the invite's own: " + steps[i].render());
        check(steps[3].render().contains(HubText.chargeSendRow().render()), "Donating comes last: " + steps[3].render());
    }

    /** Home and the sheet (source checks). */
    private static void wiring() throws Exception {
        String home = read("src/com/jjricks/volt/DashboardScreen.java").replace(" ", "");
        String sheet = read("src/com/jjricks/volt/RecordTipSheet.java").replace(" ", "");
        check(home.contains("timeTile.update(estimate,chargeAlertEstimate(values),level);\nshowTip();"), "Every charging refresh decides the tip");
        check(home.contains("ChargeEstimateTip.choose(true,s.chargeRecordingsSaved(),s.isChargeRecording(),stored)"), "Home asks the policy with the service's facts");
        check(home.contains("Motion.reveal(root,tipSlot,next!=null);"), "It comes and goes with Motion.reveal (instant with animations off)");
        check(home.contains("preferences.edit().putBoolean(tip.hiddenKey(),true).commit();"), "Hide this tip stores the answer, committed");
        check(home.contains("Stringunavailable=s.chargeCaptureUnavailable();\nif(unavailable!=null){host.toast(unavailable);returnfalse;}"),
                "Record this charge says the existing reason when a recording can't start, and the sheet stays");
        check(home.contains("MoreScreen.recordChargeQuestion(host,s,"), "Record this charge asks Charge history & recordings' own question on Home");
        check(home.contains("if(!ChargingFit.extraFits(plan,without)){"), "Clean's tip makes way first");
        check(home.contains("caseNO_RECORDINGS:") && home.contains("RecordTipSheet.show(host,preferences,this::recordFromTip,()->hideTip(tip));"),
                "The record tip's tap opens its sheet");
        check(sheet.contains("HubText.recordTipSteps()") && sheet.contains("HubText.chargeInviteIntro()")
                && sheet.contains("ChargeInviteSheet.point(host,ui,step.render())"), "The sheet reuses the invite's intro, points and parts");
        check(sheet.contains("ChargeInviteSheet.autoToggle(ui,preferences,ChargeEstimateTip.TAG_AUTO)"), "The same auto-record switch");
        check(!sheet.contains("putBoolean("), "The sheet itself stores nothing: Home's hide does");
    }

    private static String read(String path) throws Exception { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
