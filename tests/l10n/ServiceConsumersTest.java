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
 * - the first applyLocale() (service start) re-labels an existing alerts channel and posts nothing; a later
 *   change of the shown language (T.language(), and the number locale outside English, never the raw locale list,
 *   so English stays English with no re-post) also re-posts a battery alert notice still showing, silently in place,
 *   with what its last post showed (the same alert, Stop and delete intent or neither, its time), never a newer
 *   alert, and never one cleared, cancelled, superseded or never posted;
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

    /** ScooterService.shownLanguage()'s expression, as the source must hold it, and that expression run here. */
    private static final String SHOWN_LANGUAGE = "java.util.Arrays.asList(T.language(),Fmt.legacy()?null:Fmt.displayLocale())";
    private static java.util.List<?> shownLanguage() { return java.util.Arrays.asList(T.language(),Fmt.legacy()?null:Fmt.displayLocale()); }

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
        // Whatever the phone's locales, English is one shown language, so a locale list change never re-posts the alert.
        check(java.util.Arrays.asList("en", null).equals(shownLanguage()), "the shown language under " + Locale.getDefault().toLanguageTag() + " is English alone");
    }

    private static void marked() {
        for (CapabilityResolver.Reason reason : CapabilityResolver.Reason.values()) {
            String english = ServiceConsumersEnglishTest.REASONS.get(reason);
            same(english, contextReason(reason), "context reason " + reason + " stays English");
            if (!english.isEmpty()) same("⟦" + english + "⟧", CapabilityResolver.text(reason), "reasonText() would have stored the shown language");
        }
        check(!java.util.Arrays.asList("en", null).equals(shownLanguage()) && "xx".equals(shownLanguage().get(0)), "another language is another shown language");
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
        check(!s.contains("ble.message()") && count(s, ": ble.linked() ? connectedText() : ble.displayMessage();") == 1,
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
        relabel(s);
        check(!s.contains("unavailable=chargeCaptureUnavailable()") && count(s, "CharSequence unavailable=chargeCaptureBlocked();") == 1,
                "the charge status keeps the deferred refusal");
        check(!Pattern.compile("error\\s*=\\s*T\\.serviceCharge").matcher(s).find(), "the clear and export errors stay deferred");
    }

    /** applyLocale()'s change path re-posts a still-showing alert notice in the new language, without alerting. */
    private static void relabel(String s) {
        Matcher apply = Pattern.compile("void applyLocale\\(\\)\\{(.*?)\n    \\}\n", Pattern.DOTALL).matcher(s);
        check(apply.find(), "applyLocale() found");
        String body = apply.group(1);
        Matcher first = Pattern.compile("if\\(first\\)\\{(.*?)return;\\s*\\}", Pattern.DOTALL).matcher(body);
        check(first.find() && !first.group(1).contains("relabelAlertNotice") && !first.group(1).contains("post(")
                && !first.group(1).contains("updateNotification"), "the first applyLocale() posts no notice, as before");
        String guard = "java.util.List<?> language=shownLanguage();boolean relabel=!first&&!language.equals(noticeLanguage);noticeLanguage=language;";
        check(count(body, guard) == 1 && body.indexOf(guard) > body.indexOf("boolean first=noticeLocales==null;noticeLocales=locales;")
                && body.indexOf(guard) < first.start(), "every applyLocale() past the locale-list check records the shown language");
        check(count(body, "if(relabel)relabelAlertNotice();\n        updateNotification();changed();") == 1
                && body.trim().endsWith("if(relabel)relabelAlertNotice();\n        updateNotification();changed();")
                && body.indexOf("relabelAlertNotice") > first.end(),
                "only a change of the shown language re-posts the alert notice, then the connection notice");
        check(count(s, "relabelAlertNotice()") == 2 && count(s, "noticeLanguage") == 3, "only applyLocale() re-posts it");
        check(count(s, "private static java.util.List<?> shownLanguage(){return " + SHOWN_LANGUAGE + ";}") == 1 && count(s, "shownLanguage()") == 4,
                "the shown language is the catalog's resolved language and, outside English, the number locale");
        Matcher method = Pattern.compile("private void relabelAlertNotice\\(\\)\\{(.*?)\n    \\}\n", Pattern.DOTALL).matcher(s);
        check(method.find(), "relabelAlertNotice() found");
        String relabel = method.group(1);
        check(relabel.equals("\n        try{\n            AlertPost posted=alertPosted;\n            if(!relabelDue(posted))return;\n"
                + "            post(CHARGE_ALERT_NOTICE,()->posted==alertPosted&&relabelDue(posted)?alertNoticeContent(posted.shown,posted.sounding,posted.when):null);\n"
                + "        }catch(RuntimeException unavailable){}"),
                "it re-posts what the last post showed, under the same id, built when taken, and dropped if anything changed meanwhile");
        check(count(s, "private boolean relabelDue(AlertPost posted){\n        return posted!=null&&(posted.sounding?alarmPlaying():alertNoticeKept)"
                + "&&!notices.waiting(CHARGE_ALERT_NOTICE)&&alertNoticeShown();\n    }") == 1 && count(s, "relabelDue(") == 3,
                "only a post still showing, with its kept state or sounding alarm, and none waiting to replace it");
        check(count(s, "private boolean alertNoticeShown(){\n        NotificationManager manager=getSystemService(NotificationManager.class);boolean shown=false;\n"
                + "        for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications())shown|=n.getId()==CHARGE_ALERT_NOTICE;\n"
                + "        return shown;\n    }") == 1 && count(s, "alertNoticeShown()") == 3,
                "the shown check answers whether Android lists notice 76 as active");
        // The snapshot: set where notice 76 is built to post, cleared where it is cancelled, and rendered as it was.
        check(count(s, "private AlertPost alertPosted;") == 1 && matches(s, "\\balertPosted\\s*=[^=]") == 2
                && count(s, "Notification notice=builder.build();alertPosted=new AlertPost(shown,sounding,notice.when);\n        return notice;") == 1
                && count(s, "private void cancelNotice(int id){if(id==CHARGE_ALERT_NOTICE)alertPosted=null;notices.cancel(id);") == 1,
                "what notice 76 shows is recorded as it posts and forgotten when it is cancelled");
        check(count(s, "private Notification alertNoticeContent(){return alertNoticeContent(alertShown,alarmPlaying(),0);}") == 1
                && count(s, "alertNoticeContent(") == 5 && count(s, "if(when!=0)builder.setWhen(when);") == 1 && !s.contains("boolean sounding=alarmPlaying();")
                && count(s, ".setContentTitle(alertTitle(shown)).setContentText(alertText(shown))") == 1
                && count(s, "private String alertTitle(){return alertTitle(alertShown);}") == 1 && count(s, "private String alertText(){return alertText(alertShown);}") == 1
                && count(s, "private String alertTitle(AlertShown shown){return shown==null?alertTitle:shown.title();}") == 1
                && count(s, "private String alertText(AlertShown shown){return shown==null?alertText:shown.text();}") == 1,
                "a new post builds from the latest alert and alarm, as before; the re-post from the snapshot with its time");
        for (String never : new String[]{"alertNotice(", "alertNoticeKept=", "startChargeAlarm", "stopChargeAlarm", "alarmTimeout",
                "alarmEndMono", "handler.", "cancelNotice", "setOnlyAlertOnce(false)", "Builder"})
            check(!relabel.contains(never), "the re-post leaves the alarm, its timer and the kept state alone: no " + never);
        check(count(s, "if(!alertNoticeShown())return;\n            if(alertNoticeKept)alertNotice(true);else cancelNotice(CHARGE_ALERT_NOTICE);") == 1,
                "finishAlertNotice() reads the same shown check");
        check(count(s, "shown|=n.getId()==CHARGE_ALERT_NOTICE;") == 1, "one active-notice check");
        Matcher content = Pattern.compile("private Notification alertNoticeContent\\(AlertShown shown,boolean sounding,long when\\)\\{(.*?)\n    \\}\n", Pattern.DOTALL).matcher(s);
        check(content.find() && content.group(1).contains(".setOnlyAlertOnce(true)")
                && content.group(1).contains("if(sounding){") && count(content.group(1), "setDeleteIntent(dismiss)") == 1
                && content.group(1).indexOf("setDeleteIntent(dismiss)") > content.group(1).indexOf("if(sounding){")
                && !content.group(1).contains("alarmPlaying()") && !content.group(1).contains("alertShown") && !content.group(1).contains("setSound")
                && !content.group(1).contains("setVibrate") && !content.group(1).contains("setDefaults")
                && content.group(1).contains("new Notification.Builder(this,ALERT_CHANNEL)"),
                "the alert notice only alerts once, so the in-place re-post is silent, and its Stop and delete intent follow the sounding it is given");
    }

    private static int matches(String text, String regex) {
        int n = 0;
        for (Matcher m = Pattern.compile(regex).matcher(text); m.find(); ) n++;
        return n;
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
