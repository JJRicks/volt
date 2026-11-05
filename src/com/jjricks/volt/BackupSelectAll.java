package com.jjricks.volt;

/**
 * What the Back up and Restore pages' Select all box shows (BackupActivity.selectAll). Pure Java, so the
 * JVM tests reach it.
 */
final class BackupSelectAll {
    private BackupSelectAll() {}

    /**
     * Select all is checked when every enabled category box is checked. While Create backup runs, and after it
     * succeeds, createBackup has disabled every box and Select all itself (frozen): no box can change then, so
     * Select all keeps what it shows. Without that, a language change's relabel, which syncs Select all again,
     * would find no enabled box and check it over a partial selection; and counting the disabled boxes instead
     * would clear it over a full one, since an empty category's box is disabled and never checked.
     *
     * @param shown   what Select all shows now
     * @param frozen  Select all is disabled (only createBackup disables it)
     * @param enabled each category box's enabled state
     * @param checked each category box's checked state, in the same order
     */
    static boolean checked(boolean shown, boolean frozen, boolean[] enabled, boolean[] checked) {
        if (frozen) return shown;
        for (int i = 0; i < enabled.length; i++) if (enabled[i] && !checked[i]) return false;
        return true;
    }
}
