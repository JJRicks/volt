package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F1: ScooterService reads the wave-2 display accessors. ScooterService is an Android class, so this suite repeats
 * its converted expressions (as MeasureNoticeTest does) and checks the service source for them:
 * - the charge-schedule context takes ConnectionText.english(Decision.reasonMessage()), which is the English
 *   reasonText() gave (ServiceConsumersEnglishTest, frozen by G5) in English and stays English under any other
 *   language, so ChargeScheduleController's saved status and refusals never hold translated text; reasonText()
 *   would have rendered it in the shown language;
 * - a battery alert keeps what it was built from (AlertShown) and renders its title and text whenever shown, in
 *   English exactly as the base's text kept when the alert fired, and in the shown language under another one;
 * - the connection notice falls back to BleController.displayMessage(), and the alert and connection notices
 *   name the scooter with Profile.displayName();
 * - the first applyLocale() (service start) re-labels an existing alerts channel;
 * - the charge status line keeps the capture refusals deferred.
 */
public final class ServiceConsumersTest {
    private static int checks;
    private static String service;

    /** Answers every id with its English text between marks, so text from the catalog shows them. */
    private static final T.Source MARKED = new T.Source() {
        @Override public String string(int id) { return "⟦" + EnglishText.INSTANCE.string(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + EnglishText.INSTANCE.plural(id, quantity) + "⟧"; }
        @Override public String language() { return "xx"; }
    };

    public static void main(String[] args) throws Exception {
        service = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/ScooterService.java")), StandardCharsets.UTF_8);
        source();
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR"), Locale.forLanguageTag("ar-EG-u-nu-arab")}) {
                Locale.setDefault(phone);
                english();
            }
            Locale.setDefault(Locale.US);
            T.install(MARKED);
            try {
                marked();
            } finally {
                T.install(null);
            }
            english();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("ServiceConsumersTest: " + checks + " checks passed (schedule reasons stay English ids, alerts render when "
                + "shown, displayMessage and displayName in the notices)");
    }

    /** chargeScheduleContext's reason, as the service now reads it from Decision.reasonMessage(). */
    private static String contextReason(CapabilityResolver.Reason reason) {
        return ConnectionText.english(CapabilityResolver.message(reason));
    }

    /** The service's AlertShown, field for field. */
    private static final class Alert {
        final ChargeAlertPolicy.Event event; final int level, percent; final String scooter; final boolean defaultName;
        Alert(ChargeAlertPolicy.Event event, int level, int percent, String name, boolean nameDefault) {
            this.event = event; this.level = level; this.percent = percent; scooter = name;
            defaultName = nameDefault && "Scooter".equals(name);
        }
        String title() { return ChargeAlertPolicy.alertTitle(event, level); }
        String text() { return ChargeAlertPolicy.alertText(event, defaultName ? T.ownerProfileDefaultName() : scooter, percent); }
    }

    private static void english() {
        for (CapabilityResolver.Reason reason : CapabilityResolver.Reason.values()) {
            same(ServiceConsumersEnglishTest.REASONS.get(reason), contextReason(reason), "context reason " + reason);
            same(CapabilityResolver.text(reason), contextReason(reason), "the base read for " + reason);
        }
        // What the base kept when the alert fired: alertTitle(event, level) and alertText(event, profile.name, percent).
        String[] names = {"Synthetic GT3 Max", "Scooter", "My %1$s 100%", ""};
        for (ChargeAlertPolicy.Event event : ChargeAlertPolicy.Event.values())
            for (int level : new int[]{0, 5, 20, 80, 100})
                for (int percent : new int[]{0, 19, 81, 100})
                    for (String name : names)
                        for (boolean generic : new boolean[]{false, true}) {
                            Alert alert = new Alert(event, level, percent, name, generic);
                            same(ChargeAlertPolicy.alertTitle(event, level), alert.title(), "alert title " + event + " " + level);
                            same(ChargeAlertPolicy.alertText(event, name, percent), alert.text(), "alert text " + event + " " + name + " " + generic);
                        }
        same("Scooter", T.ownerProfileDefaultName(), "the default name's English is ScooterProfiles.DEFAULT_NAME");
    }

    private static void marked() {
        for (CapabilityResolver.Reason reason : CapabilityResolver.Reason.values()) {
            String english = ServiceConsumersEnglishTest.REASONS.get(reason);
            same(english, contextReason(reason), "context reason " + reason + " stays English");
            if (!english.isEmpty()) same("⟦" + english + "⟧", CapabilityResolver.text(reason), "reasonText() would have stored the shown language");
        }
        Alert charged = new Alert(ChargeAlertPolicy.Event.CHARGE, 80, 81, "Synthetic GT3 Max", false);
        same("⟦Charged to 80%⟧", charged.title(), "the alert title renders in the shown language");
        same("⟦Synthetic GT3 Max is at 81%.⟧", charged.text(), "the alert text renders in the shown language");
        same("⟦⟦Scooter⟧ is at 19%.⟧", new Alert(ChargeAlertPolicy.Event.LOW, 20, 19, "Scooter", true).text(),
                "a default name renders as displayName() does");
        same("⟦Scooter is at 19%.⟧", new Alert(ChargeAlertPolicy.Event.LOW, 20, 19, "Scooter", false).text(),
                "a typed name that equals the default stays as typed");
    }

    private static void source() throws Exception {
        String s = service;
        check(!s.contains(".reasonText()"), "ScooterService no longer reads Decision.reasonText()");
        check(count(s, "c.toggleReason=toggle.available()?null:ConnectionText.english(toggle.reasonMessage());"
                + "c.timeReason=time.available()?null:ConnectionText.english(time.reasonMessage());") == 1,
                "the schedule context takes reasonMessage()'s English");
        check(!s.contains("ble.message()") && count(s, ": ble.ready() ? connectedText() : ble.displayMessage();") == 1,
                "the connection notice falls back to displayMessage()");
        check(count(s, "alertPausedNotice(event,profile.displayName())") == 1, "the paused notice names the scooter with displayName()");
        check(count(s, "String name = profile == null ? T.serviceNoticeScooter() : profile.displayName();") == 1,
                "the connected notice names the scooter with displayName()");
        check(!Pattern.compile("\\balert(Title|Text)\\s*=[^=]").matcher(s.replace("alertTitle=\"\",alertText=\"\"", "")).find(),
                "the alert title and text are never kept rendered");
        check(count(s, "String title(){return ChargeAlertPolicy.alertTitle(event,level);}") == 1
                && count(s, "String text(){return ChargeAlertPolicy.alertText(event,defaultName?T.ownerProfileDefaultName():scooter,percent);}") == 1
                && count(s, "this.event=event;this.level=level;this.percent=percent;scooter=profile.name;") == 1
                && count(s, "defaultName=profile.nameDefault&&ScooterProfiles.DEFAULT_NAME.equals(profile.name);") == 1,
                "AlertShown is the Alert above");
        check(count(s, "alertShown=new AlertShown(event,level,pct==null?level:pct,profile);") == 1
                && count(s, "if(visible){pendingChargeAlert=alertShown;return;}") == 1
                && count(s, "AlertShown alert=pendingChargeAlert;return alert==null?null:new String[]{alert.title(),alert.text()};") == 1,
                "an alert keeps its inputs and the in-app dialog renders them when read");
        String profiles = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/ScooterProfiles.java")), StandardCharsets.UTF_8);
        check(profiles.contains("static final String DEFAULT_NAME=\"Scooter\";")
                && profiles.contains("public String displayName(){return nameDefault&&DEFAULT_NAME.equals(name)?T.ownerProfileDefaultName():name;}"),
                "AlertShown's default name follows Profile.displayName()");
        Matcher first = Pattern.compile("if\\(first\\)\\{(.*?)return;\\s*\\}", Pattern.DOTALL).matcher(s);
        check(first.find() && first.group(1).contains("if(manager.getNotificationChannel(ALERT_CHANNEL)!=null)alertChannel(manager);"),
                "the first applyLocale() re-labels an existing alerts channel");
        check(!s.contains("unavailable=chargeCaptureUnavailable()") && count(s, "CharSequence unavailable=chargeCaptureBlocked();") == 1,
                "the charge status keeps the deferred refusal");
        check(!Pattern.compile("error\\s*=\\s*T\\.serviceCharge").matcher(s).find(), "the clear and export errors stay deferred");
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }

    private static void same(String expected, String actual, String what) {
        check(expected.equals(actual), what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
