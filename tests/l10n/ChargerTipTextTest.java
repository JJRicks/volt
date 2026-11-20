package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Home's charger tip (ChargeEstimateTip.Tip.OTHER_CHARGER; owner, Nov 19 2026, item 11): its words and narrow rungs, its
 * sheet's words, and how Home and the sheet are wired (source checks). The rule is ChargeEstimateTipRuleTest's; the
 * far-off rule and the relabel are LearnedChargerMatchTest's.
 */
public final class ChargerTipTextTest {
    private static int checks;
    private static final ChargeEstimateTip.Tip CHARGER = ChargeEstimateTip.Tip.OTHER_CHARGER;

    public static void main(String[] args) throws Exception {
        text();
        wiring();
        System.out.println("ChargerTipTextTest: " + checks + " checks passed");
    }

    private static void text() {
        check("Different charger? Record it".equals(HubText.estimateTip(CHARGER).render()), "Full: " + HubText.estimateTip(CHARGER).render());
        check("Record charger".equals(HubText.estimateTipShort(CHARGER).render()), "Short: " + HubText.estimateTipShort(CHARGER).render());
        check("bolt".equals(ChargeEstimateTip.icon(CHARGER)), "Icon");
        check("Different charger?".equals(HubText.chargerTipTitle().render()), "Sheet title");
        String text = HubText.chargerTipText().render();
        check(text.startsWith("Charging power doesn't match your recorded charges.") && text.length() < 140, "Sheet text is short: " + text);
        // The button names the charger the charge is filed under, in the app's own charger names.
        check("Record this charge as High-speed".equals(HubText.chargerTipAction(false, true).render()), HubText.chargerTipAction(false, true).render());
        check("Record this charge as Stock".equals(HubText.chargerTipAction(false, false).render()), HubText.chargerTipAction(false, false).render());
        check("Save this recording as High-speed".equals(HubText.chargerTipAction(true, true).render()), HubText.chargerTipAction(true, true).render());
        check("Save this recording as Stock".equals(HubText.chargerTipAction(true, false).render()), HubText.chargerTipAction(true, false).render());
        check(HubText.chargerTipAction(true, true).render().contains(M.mainRowHighSpeed().render()), "High-speed is the charger row's own word");
    }

    private static void wiring() throws Exception {
        String home = read("src/com/jjricks/volt/DashboardScreen.java").replace(" ", "");
        String sheet = read("src/com/jjricks/volt/ChargerTipSheet.java").replace(" ", "");
        String strings = read("res/values/strings_hub.xml");
        check(home.contains("next=ChargeEstimateTip.withCharger(next,true,s.recordChargerHint(),stored);"), "The charger tip comes first, from the service's far-off rule");
        check(home.contains("caseOTHER_CHARGER:"), "Its tap is handled");
        check(home.contains("ChargerTipSheet.show(host,files,family==ChargeSessionStore.Charger.HIGH_SPEED,()->chargerTipAction(family),()->hideTip(tip));"),
                "Its tap opens its sheet, with the charger it files under");
        check(home.contains("if(s.chargerTipFiles()){s.fileChargeRecordingAs(family);"), "During an automatic guess the button files that recording (CHOSEN, one tap)");
        check(home.contains("s.startChargeRecording(family);"), "Otherwise it records this charge under that charger");
        check(home.contains("Stringunavailable=s.chargeCaptureUnavailable();\nif(unavailable!=null){host.toast(unavailable);returnfalse;}"), "A recording that can't start says why");
        check(sheet.contains("content.setTag(ChargeEstimateTip.TAG_CHARGER_SHEET);") && sheet.contains("main.setTag(ChargeEstimateTip.TAG_CHARGER_ACTION);")
                && sheet.contains("later.setTag(ChargeEstimateTip.TAG_NOT_NOW);") && sheet.contains("hideTip.setTag(ChargeEstimateTip.TAG_HIDE);"), "The sheet's parts are tagged");
        check(!sheet.contains("putBoolean("), "The sheet itself stores nothing: Home's hide does");
        check(strings.contains("name=\"hub_estimate_tip_charger\"") && strings.contains("volt:ladder=\"full\"")
                && strings.contains("name=\"hub_estimate_tip_charger_short\""), "Its label has a full and a short rung");
    }

    private static String read(String path) throws Exception { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
