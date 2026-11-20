package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Home's estimate tip (ChargeEstimateTip; owner, Nov 19 2026): which tip shows under the charging view's time, every
 * gate both ways; its hide preference and its backup; its words and its narrow rungs; Clean's room rule
 * (ChargingFit.extraFits); and the text-free wiring around it (source checks). Fixtures are synthesized.
 */
public final class ChargeEstimateTipTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        rule();
        snapshot();
        hidden();
        backup();
        text();
        room();
        wiring();
        System.out.println("ChargeEstimateTipTest: " + checks + " checks passed");
    }

    private static final ChargeEstimateTip.Tip RECORD = ChargeEstimateTip.Tip.NO_RECORDINGS;

    /** The record tip: the time on screen, no saved recording (known), none running, not hidden. Each gate both ways. */
    private static void rule() {
        Map<String, Object> none = new HashMap<>();
        check(ChargeEstimateTip.choose(true, Boolean.FALSE, false, none) == RECORD, "No recordings yet: the record tip shows");
        check(ChargeEstimateTip.choose(true, Boolean.FALSE, false, null) == RECORD, "No preferences read: nothing hidden, it shows");
        // The time on screen.
        check(ChargeEstimateTip.choose(false, Boolean.FALSE, false, none) == null, "No charging time on screen: no tip");
        // Saved recordings: once one is saved it goes; unknown shows nothing (no flicker); none again brings it back.
        check(ChargeEstimateTip.choose(true, Boolean.TRUE, false, none) == null, "A saved recording: the tip goes");
        check(ChargeEstimateTip.choose(true, null, false, none) == null, "Not known yet: no tip until it is");
        // A recording running.
        check(ChargeEstimateTip.choose(true, Boolean.FALSE, true, none) == null, "A recording running: no tip");
        // Hidden for good.
        Map<String, Object> hid = new HashMap<>();
        hid.put(RECORD.hiddenKey(), true);
        check(ChargeEstimateTip.choose(true, Boolean.FALSE, false, hid) == null, "Hidden with Hide this tip: no tip");
        hid.put(RECORD.hiddenKey(), false);
        check(ChargeEstimateTip.choose(true, Boolean.FALSE, false, hid) == RECORD, "Stored false: not hidden");
        // Everything against it at once still says none, and each gate alone is enough.
        check(ChargeEstimateTip.choose(false, Boolean.TRUE, true, null) == null, "Every gate shut: no tip");
    }

    /** What the store's snapshot already answers, and where the service counts the scooter's own recordings. */
    private static void snapshot() {
        check(Boolean.FALSE.equals(ChargeEstimateTip.savedFromSnapshot(0, false)), "Nothing saved for any scooter: none for this one");
        check(Boolean.FALSE.equals(ChargeEstimateTip.savedFromSnapshot(0, true)), "Zero sessions wins (evidence can't outlive its files)");
        check(Boolean.TRUE.equals(ChargeEstimateTip.savedFromSnapshot(3, true)), "This scooter has a curve or gauge evidence: saved");
        check(ChargeEstimateTip.savedFromSnapshot(3, false) == null, "Recordings, but none taught this scooter: count its own");
        check(Boolean.FALSE.equals(ChargeEstimateTip.savedFromSnapshot(-1, false)), "A nonsense count reads as none");
    }

    /** Only a stored TRUE hides a tip; anything else (a wrong type, a damaged restore) is no answer. */
    private static void hidden() {
        Map<String, Object> stored = new HashMap<>();
        check(!ChargeEstimateTip.hidden(RECORD, stored) && !ChargeEstimateTip.hidden(RECORD, null), "Nothing stored: shown");
        for (Object odd : new Object[] {"true", 1, 1L, "yes", Boolean.FALSE}) {
            stored.put(RECORD.hiddenKey(), odd);
            check(!ChargeEstimateTip.hidden(RECORD, stored), "Stored " + odd + " is no answer");
        }
        stored.put(RECORD.hiddenKey(), Boolean.TRUE);
        check(ChargeEstimateTip.hidden(RECORD, stored), "Stored TRUE hides it");
        check("estimate_tip_hidden_no_recordings".equals(RECORD.hiddenKey()), "The preference key is stable: " + RECORD.hiddenKey());
        // Every tip: its own id, its own key, under the backed-up prefix.
        Set<String> keys = new HashSet<>();
        for (ChargeEstimateTip.Tip tip : ChargeEstimateTip.Tip.values()) {
            check(tip.id.matches("[a-z_]+") && keys.add(tip.hiddenKey()), "Each tip has its own lower-case id: " + tip.id);
            check(tip.hiddenKey().startsWith(ChargeEstimateTip.HIDDEN_PREFIX), "Each hide key is under the prefix");
        }
    }

    /** The hide preferences travel with App preferences (the round trip itself is BackupPlanTest.estimateTip). */
    private static void backup() throws Exception {
        for (ChargeEstimateTip.Tip tip : ChargeEstimateTip.Tip.values())
            check(BackupRegistry.PREFERENCES.equals(BackupRegistry.category("app_settings", tip.hiddenKey())),
                    "Hidden " + tip.id + " travels as App preferences");
        check(BackupRegistry.PREFERENCES.equals(BackupRegistry.category("app_settings", ChargeEstimateTip.HIDDEN_PREFIX + "a_later_tip")),
                "A tip added later is backed up with no new rule");
        String plan = read("tests/BackupPlanTest.java").replace(" ", "");
        check(plan.contains("estimateTip(root);"), "The backup round trip covers a hidden tip (BackupPlanTest.estimateTip)");
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

    /** Clean's slot: the tip shows only where it costs nothing but some of the stage's height. */
    private static void room() {
        float d = 2.625f;
        int line = dp(30, d), brief = dp(30, d), percent = dp(70, d), readings = dp(66, d), tip = dp(48, d);
        // A tall slot: everything at full size either way; the stage is shorter with the tip.
        ChargingFit.Plan without = ChargingFit.plan(dp(560, d), line, brief, percent, readings, d);
        ChargingFit.Plan with = ChargingFit.plan(dp(560, d), line, brief, percent, readings + tip, d);
        check(ChargingFit.extraFits(with, without) && with.stageHeight < without.stageHeight, "Tall slot: it fits, the stage gives the height");
        // A slot where the tip would cost the percentage's size or the line: it makes way.
        for (int h = 150; h <= 560; h += 2) {
            ChargingFit.Plan a = ChargingFit.plan(dp(h, d), line, brief, percent, readings + tip, d);
            ChargingFit.Plan b = ChargingFit.plan(dp(h, d), line, brief, percent, readings, d);
            if (ChargingFit.extraFits(a, b)) {
                check(a.line == b.line && a.percentScale >= b.percentScale && a.photo() == b.photo() && a.track == b.track
                        && a.readingsGap >= b.readingsGap, "At " + h + " dp it fits only costing stage height");
            } else {
                check(a.line != b.line || a.shortLine != b.shortLine || a.percentScale < b.percentScale || a.photo() != b.photo()
                        || a.track != b.track || a.readingsGap < b.readingsGap || a.percent != b.percent, "At " + h + " dp it makes way for a reason");
            }
        }
        ChargingFit.Plan tight = ChargingFit.plan(dp(200, d), line, brief, percent, readings + tip, d);
        ChargingFit.Plan tightWithout = ChargingFit.plan(dp(200, d), line, brief, percent, readings, d);
        check(!ChargingFit.extraFits(tight, tightWithout), "A short slot: the tip makes way first");
    }

    /** Home, the sheet, the service and Charge history &amp; recordings (source checks). */
    private static void wiring() throws Exception {
        String home = read("src/com/jjricks/volt/DashboardScreen.java").replace(" ", "");
        String sheet = read("src/com/jjricks/volt/ChargeInviteSheet.java").replace(" ", "");
        String more = read("src/com/jjricks/volt/MoreScreen.java").replace(" ", "");
        String service = read("src/com/jjricks/volt/ScooterService.java").replace(" ", "");
        check(home.contains("timeTile.update(estimate,chargeAlertEstimate(values),level);\nshowTip();"), "Every charging refresh decides the tip");
        check(home.contains("ChargeEstimateTip.choose(true,s.chargeRecordingsSaved(),s.isChargeRecording(),stored)"), "Home asks the policy with the service's facts");
        check(home.contains("Motion.reveal(root,tipSlot,next!=null);"), "It comes and goes with Motion.reveal (instant with animations off)");
        check(home.contains("preferences.edit().putBoolean(tip.hiddenKey(),true).commit();"), "Hide this tip stores the answer, committed");
        check(home.contains("Stringunavailable=s.chargeCaptureUnavailable();\nif(unavailable!=null){host.toast(unavailable);returnfalse;}"),
                "Record this charge says the existing reason when a recording can't start, and the sheet stays");
        check(home.contains("ChargeRecordDialog.show(host,s,"), "Record this charge asks the charger question on Home");
        check(home.contains("if(!ChargingFit.extraFits(plan,without)){"), "Clean's tip makes way first");
        check(sheet.contains("staticAlertDialogshowRecordTip(") && sheet.contains("HubText.recordTipSteps()")
                && sheet.contains("HubText.chargeInviteIntro()"), "The sheet reuses the invite's intro and points");
        check(sheet.contains("autoToggle(ui,preferences,ChargeEstimateTip.TAG_AUTO)"), "The same auto-record switch");
        check(!sheet.contains("ChargeEstimateTip.Tip.NO_RECORDINGS.hiddenKey") && !sheet.contains("putBoolean(ChargeEstimateTip"),
                "The sheet itself stores nothing: Home's hide does");
        check(more.contains("ChargeRecordDialog.show(host,s,()->closed||service()!=s,this::refreshCharging,this::show);"),
                "Charge history & recordings asks the same question");
        check(service.contains("Booleanknown=ChargeEstimateTip.savedFromSnapshot(snapshot.sessions,evidence);"),
                "The service answers from the snapshot first");
        check(service.contains("saved=!chargeStore.recordings(profile).isEmpty();"), "and counts this scooter's own only where it must");
    }

    private static int dp(int value, float density) { return Math.round(value * density); }
    private static String read(String path) throws Exception { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
