package com.jjricks.volt;

/**
 * The automatic update check's rules, kept pure for the JVM tests (UpdateScheduleTest): it asks GitHub
 * at most once a day, and a found release is announced once per version. UpdateCheckJob runs it.
 */
final class UpdateSchedule {
    /** app_settings: the owner turned the automatic check on (off by default). Backed up with App preferences. */
    static final String AUTO = "update_auto_check";
    /** app_settings: wall-clock time of the last finished automatic check. This phone only. */
    static final String LAST = "update_auto_last";
    /** app_settings: the newest version a notification (or a manual check) already told the owner about. This phone only. */
    static final String TOLD = "update_auto_told";
    static final long DAY_MS = 24L * 60 * 60 * 1000;
    private UpdateSchedule() {}

    /**
     * How long until the next check may run: 0 when none has run yet, or a day or more has passed. A clock that moved
     * back before the last check counts as a fresh start, so a wrong clock can delay the check by at most a day.
     */
    static long wait(long last, long now) {
        if (last <= 0 || now < last) return 0;
        long elapsed = now - last;
        return elapsed >= DAY_MS ? 0 : DAY_MS - elapsed;
    }
    static boolean due(long last, long now) { return wait(last, now) == 0; }

    /** A found release is announced only when it is newer than the last one announced; an unreadable record announces. */
    static boolean announce(String latest, String told) {
        int[] found = VersionName.numbers(latest);
        if (found == null) return false;
        int[] before = told == null || told.isEmpty() ? null : VersionName.numbers(told);
        return before == null || VersionName.compare(found, before) > 0;
    }

    /** What to keep as TOLD after a check found `latest`: never moves back to an older version. */
    static String told(String latest, String told) { return announce(latest, told) ? latest : told; }
}
