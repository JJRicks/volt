package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The automatic update check (More › Updates, UpdateCheckJob): it waits a full day between checks, a clock moved
 * back cannot stall it, and a found release is announced once per version, never again for an older one. Also
 * checks where the two rows sit: their own group in the More menu above the version line, no longer in
 * About & permissions. Listed in tests/l10n/update-checks.sources; tests/run.sh runs it.
 */
public final class UpdateScheduleTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        onceADay(); oncePerVersion(); rowsInTheirOwnSection();
        System.out.println("UpdateScheduleTest: " + checks + " checks passed (once a day, once per version, More › Updates rows)");
    }

    private static void onceADay() {
        long day = UpdateSchedule.DAY_MS, t = 1_780_000_000_000L;
        check(day == 86_400_000L, "a day is 24 hours");
        check(UpdateSchedule.due(0, t) && UpdateSchedule.wait(0, t) == 0, "never checked: due now");
        check(!UpdateSchedule.due(t, t) && UpdateSchedule.wait(t, t) == day, "just checked: wait a full day");
        check(!UpdateSchedule.due(t, t + 1) && UpdateSchedule.wait(t, t + 1) == day - 1, "a moment later: still waiting");
        check(!UpdateSchedule.due(t, t + day - 1) && UpdateSchedule.wait(t, t + day - 1) == 1, "one millisecond short of a day");
        check(UpdateSchedule.due(t, t + day) && UpdateSchedule.wait(t, t + day) == 0, "exactly a day later: due");
        check(UpdateSchedule.due(t, t + 3 * day), "days later: due");
        check(!UpdateSchedule.due(t, t + 23L * 60 * 60 * 1000), "23 hours later: not yet, so never twice in a day");
        check(UpdateSchedule.due(t, t - 1000), "the clock moved back before the last check: a fresh start, not a stall");
        for (long elapsed = 0; elapsed < day; elapsed += day / 97)
            check(UpdateSchedule.wait(t, t + elapsed) + elapsed == day, "the wait always ends a day after the last check: " + elapsed);
    }

    private static void oncePerVersion() {
        check(UpdateSchedule.announce("1.12.0", ""), "the first found release is announced");
        check(UpdateSchedule.announce("1.12.0", null), "no record: announced");
        check(!UpdateSchedule.announce("1.12.0", "1.12.0"), "the same version is not announced again");
        check(!UpdateSchedule.announce("1.11.2", "1.12.0"), "an older version is never announced");
        check(UpdateSchedule.announce("1.12.1", "1.12.0") && UpdateSchedule.announce("1.13.0", "1.12.0"), "a newer version is announced");
        check(!UpdateSchedule.announce("1.12", "1.12.0"), "1.12 is 1.12.0");
        check(UpdateSchedule.announce("1.12.0", "garbled"), "an unreadable record announces again");
        check(!UpdateSchedule.announce("garbled", "") && !UpdateSchedule.announce(null, ""), "an unreadable version is never announced");
        check(UpdateSchedule.told("1.12.0", "").equals("1.12.0") && UpdateSchedule.told("1.12.1", "1.12.0").equals("1.12.1"), "the record follows a newer version");
        check(UpdateSchedule.told("1.11.0", "1.12.0").equals("1.12.0"), "the record never moves back");
        // A day of checks against an unchanged release: one announcement in all.
        String told = ""; int posted = 0;
        for (int day = 0; day < 5; day++) { if (UpdateSchedule.announce("1.12.0", told)) { posted++; told = UpdateSchedule.told("1.12.0", told); } }
        check(posted == 1, "five daily checks of one release post once");
    }

    private static void rowsInTheirOwnSection() throws Exception {
        String more = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/MoreScreen.java")), StandardCharsets.UTF_8);
        int updates = more.indexOf("        updates();\n"), footer = more.indexOf("T.moreFooter(");
        check(updates > 0 && footer > updates, "the Updates section is built in the menu, before the version and not-affiliated footer");
        int about = more.indexOf("private void privacy() {"), aboutEnd = more.indexOf("\n    }\n", about);
        check(about > 0 && aboutEnd > about && !more.substring(about, aboutEnd).contains("check-updates"), "Check for updates left About & permissions");
        int section = more.indexOf("private void updates() {"), sectionEnd = more.indexOf("\n    }\n", section);
        String body = more.substring(section, sectionEnd);
        check(body.contains("T.moreUpdatesTitle()") && body.contains("T.moreUpdatesAuto(), T.moreUpdatesAutoSub()")
                && body.contains("T.moreAboutCheckUpdates()") && body.contains("\"check-updates\""), "the section holds the switch and the manual check");
        check(body.indexOf("T.moreUpdatesAuto()") < body.indexOf("T.moreAboutCheckUpdates()"), "the switch comes first");
    }
}
