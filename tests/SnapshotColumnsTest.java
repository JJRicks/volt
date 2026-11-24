package com.jjricks.volt;

import java.util.Arrays;

/** 2.0.5 item 5: diagnostics files arrived dated 1969 because the provider never answered their modified time. */
public final class SnapshotColumnsTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) {
        long mtime = 1_795_483_063_521L;
        String[] all = SnapshotColumns.columns(null);
        check(Arrays.asList(all).containsAll(Arrays.asList("_display_name", "_size", "last_modified")), "All columns include the modified time");
        Object[] row = SnapshotColumns.row(all, "volt-diagnostics.json", 340_710, mtime);
        check("volt-diagnostics.json".equals(row[0]) && Long.valueOf(340_710).equals(row[1]) && Long.valueOf(mtime).equals(row[2]), "Name, size and time");
        String[] asked = {"last_modified", "date_modified", "_size", "mime_type", "_display_name"};
        row = SnapshotColumns.row(asked, "volt-charge-recordings.json", 12, mtime);
        check(Long.valueOf(mtime).equals(row[0]), "last_modified in milliseconds");
        check(Long.valueOf(mtime / 1000).equals(row[1]), "date_modified in seconds");
        check(Long.valueOf(12).equals(row[2]) && row[3] == null && "volt-charge-recordings.json".equals(row[4]), "Order as asked; unknown columns null");
        row = SnapshotColumns.row(asked, "volt-diagnostics.json", 1, 0);
        check(row[0] == null && row[1] == null, "An unknown time is null, never 0 (the epoch)");
        check(SnapshotColumns.columns(asked) == asked, "A projection is kept as asked");
        System.out.println("SnapshotColumnsTest: " + checks + " assertions passed");
    }
}
