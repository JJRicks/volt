package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Start recording in the background on the 1.13 branch: main's keys and English unchanged (so main's translations carry
 * over by key), the switch's one home directly under Record rides automatically in More › App preferences › Connection &
 * recording, and the one-time What's new dialog's text and wiring.
 */
public final class BackgroundRecordingTextTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        try {
            T.install(null);
            equal("Start recording in the background", T.hubRecordingAutoBackground(), "the switch");
            equal("A ride that starts while Volt is in the background or with the screen locked has no GPS route.",
                    T.hubRecordingAutoBackgroundNote(), "the caption: a locked screen counts as background");
            equal("What's new in Volt 1.13.2", T.homeWhatsNewChanges("1.13.2"), "the dialog's title");
            equal("Volt now starts recording in the background only if you turn it on. A ride started in the background or with the screen locked has no GPS route.",
                    T.homeWhatsNewBackground(), "the dialog's message");
            check(T.key(T.HUB_RECORDING_AUTO_BACKGROUND).equals("hub_recording_auto_background")
                    && T.key(T.HUB_RECORDING_AUTO_BACKGROUND_NOTE).equals("hub_recording_auto_background_note")
                    && T.key(T.HOME_WHATS_NEW_CHANGES).equals("home_whats_new_changes")
                    && T.key(T.HOME_WHATS_NEW_BACKGROUND).equals("home_whats_new_background"), "main's key names");
        } finally {
            T.install(null);
        }

        String more = read("src/com/jjricks/volt/MoreScreen.java");
        String prefs = more.substring(more.indexOf("private void preferences() {"), more.indexOf("private void ridingPreferences() {"));
        int autoRecord = prefs.indexOf("AutoStartPolicy.AUTO_RECORD_KEY, false, this::offerRideLocation);");
        int row = prefs.indexOf("toggleRow(connection, \"walk\", T.hubRecordingAutoBackground(), T.hubRecordingAutoBackgroundNote(),");
        int battery = prefs.indexOf("T.morePrefsBattery()");
        check(autoRecord > 0 && row > autoRecord && battery > row, "the switch comes after Record rides automatically and before Battery use");
        String between = prefs.substring(autoRecord, row);
        check(!between.contains("preferenceToggle(") && !between.contains("row(connection"), "directly under Record rides automatically, in Connection & recording");
        check(prefs.contains("putBoolean(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY, enabled)")
                        && prefs.contains("AutoStartPolicy.backgroundSwitchDrawnOn(autoRecord, settings().getBoolean(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY, false)), autoRecord)")
                        && prefs.contains("setTag(AutoStartPolicy.BACKGROUND_ROW_TAG)"),
                "it stores its own key, is enabled only with Record rides automatically on, drawn as main draws it, and is tagged");
        check(count(more, "T.hubRecordingAutoBackground()") == 1, "one home: More shows the switch once (not in riding mode's menu)");
        long homes = 0;
        try (Stream<Path> files = Files.list(Paths.get("src/com/jjricks/volt"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.equals("MoreScreen.java") && !name.equals("MainActivity.java")) homes += count(read(file.toString()), "hubRecordingAutoBackground()");
            }
        }
        check(homes == 0, "no other screen shows the switch");

        String main = read("src/com/jjricks/volt/MainActivity.java");
        check(main.contains("maybeAutoOpenRiding();maybeShowBackgroundNotice();"), "the activity's refresh offers the note");
        String notice = main.substring(main.indexOf("private void maybeShowBackgroundNotice(){"), main.indexOf("/** More holds Volt's setup flows"));
        check(notice.contains("T.homeWhatsNewChanges(AppVersion.name(this))") && notice.contains("T.homeWhatsNewBackground()")
                        && notice.contains("T.hubRecordingAutoBackground()") && notice.contains("T.commonOk()"),
                "the dialog: title with the version, message, the switch, OK");
        check(notice.contains("AutoStartPolicy.showsBackgroundNotice(now,clear)") && notice.contains("AutoStartPolicy.screenClear("),
                "shown only when due and on a clear screen");
        check(!notice.contains("now=AutoStartPolicy.NOTICE_DONE"), "opening the note does not mark it done");
        check(notice.contains("if(backgroundNotice!=null&&backgroundNotice.isShowing())return;"), "one copy at a time");
        check(notice.contains("setPositiveButton(T.commonOk(),(d,which)->closeBackgroundNotice(true,false))")
                        && notice.contains("setOnCancelListener(d->closeBackgroundNotice(true,false))")
                        && notice.contains("closeBackgroundNotice(false,true);"),
                "OK, back or a tap outside, or the switch in it, end the note");
        check(notice.contains("setOnDismissListener(d->{if(backgroundNotice==dialog)backgroundNotice=null;});"),
                "a dismissal by Volt (rebuild, activity gone) only forgets the dialog, so the note stays due");

        // Who ends a due note (AutoStartPolicy.backgroundNoticeClosed).
        String due = AutoStartPolicy.NOTICE_DUE, done = AutoStartPolicy.NOTICE_DONE;
        equal(done, AutoStartPolicy.backgroundNoticeClosed(due, true, false), "closed by the person: done");
        equal(done, AutoStartPolicy.backgroundNoticeClosed(due, false, true), "the switch in it changed: done");
        equal(done, AutoStartPolicy.backgroundNoticeClosed(due, true, true), "both: done");
        equal(due, AutoStartPolicy.backgroundNoticeClosed(due, false, false), "taken down by Volt: still due");
        equal(done, AutoStartPolicy.backgroundNoticeClosed(done, false, false), "done stays done");
        check(AutoStartPolicy.backgroundNoticeClosed(null, true, true) == null, "no note state: none written");
        // The order the review named: due, opened on a clear screen, Volt takes it down, then the next clear screen.
        String state = due;
        check(AutoStartPolicy.showsBackgroundNotice(AutoStartPolicy.backgroundNoticeNow(state, true, false), true), "shows when due on a clear screen");
        state = AutoStartPolicy.backgroundNoticeClosed(state, false, false);
        check(AutoStartPolicy.showsBackgroundNotice(AutoStartPolicy.backgroundNoticeNow(state, true, false), true), "after a rebuild took it down it shows again");
        state = AutoStartPolicy.backgroundNoticeClosed(state, true, false);
        check(!AutoStartPolicy.showsBackgroundNotice(AutoStartPolicy.backgroundNoticeNow(state, true, false), true), "after the person closed it, never again");
        System.out.println("BackgroundRecordingTextTest: " + checks + " checks passed (background switch text, place and What's new note)");
    }

    private static String read(String path) throws IOException { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }
    private static int count(String text, String part) { return text.split(java.util.regex.Pattern.quote(part), -1).length - 1; }
    private static void equal(String expected, CharSequence actual, String message) {
        check(expected.contentEquals(actual), message + ": expected \"" + expected + "\" but was \"" + actual + "\"");
    }
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
