package com.jjricks.volt;

/**
 * The columns DiagnosticsProvider answers about a snapshot it serves (the report's volt-diagnostics.json, or Email to
 * Volt's charge recordings). Pure Java (tests/SnapshotColumnsTest).
 *
 * <p>Name and size, as every email app asks, and the file's modified time. Without the time, an app that copies the
 * attachment on asks for it, gets nothing and dates the copy 0, the Unix epoch: the owner's diagnostics files arrived
 * dated 31 December 1969 (2.0.5 item 5), while a charge export saved as a document kept its real date. The time is
 * answered as Android's document column (last_modified, milliseconds) and as MediaStore's (date_modified, seconds).
 */
final class SnapshotColumns {
    /** OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, MediaStore.MediaColumns.DATE_MODIFIED. */
    static final String NAME = "_display_name", SIZE = "_size", LAST_MODIFIED = "last_modified", DATE_MODIFIED = "date_modified";
    /** The columns answered when an app asks for all of them (a null projection). */
    static final String[] DEFAULT = {NAME, SIZE, LAST_MODIFIED};

    private SnapshotColumns() { }

    /** The asked columns (null: {@link #DEFAULT}), in the order asked. */
    static String[] columns(String[] projection) { return projection == null ? DEFAULT.clone() : projection; }

    /** One row for these columns: an unknown column is null; a modified time of 0 or less (unknown) is null too. */
    static Object[] row(String[] columns, String name, long size, long modifiedMs) {
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            String c = columns[i];
            if (NAME.equals(c)) row[i] = name;
            else if (SIZE.equals(c)) row[i] = size;
            else if (LAST_MODIFIED.equals(c)) row[i] = modifiedMs > 0 ? (Object) modifiedMs : null;
            else if (DATE_MODIFIED.equals(c)) row[i] = modifiedMs > 0 ? (Object) (modifiedMs / 1000) : null;
            else row[i] = null;
        }
        return row;
    }
}
