package com.jjricks.volt;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The one file a report email attaches, read-only: cache/diagnostics/volt-diagnostics.json. Not exported; an
 * email app reads it only through the read grant on that one send intent. Nothing else in the cache, and no
 * other path, is ever served.
 */
public final class DiagnosticsProvider extends ContentProvider {
    static final String DIRECTORY = "diagnostics", NAME = "volt-diagnostics.json", TYPE = "application/json";

    static String authority(Context context) { return context.getPackageName() + ".diagnostics"; }
    static Uri uri(Context context) { return new Uri.Builder().scheme("content").authority(authority(context)).appendPath(NAME).build(); }

    /** Replaces the file atomically with this bundle and returns its content URI. */
    static Uri write(Context context, String json) throws IOException {
        File directory = new File(context.getCacheDir(), DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No diagnostics folder");
        File file = new File(directory, NAME), temporary = new File(directory, NAME + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(json.getBytes(StandardCharsets.UTF_8)); out.flush(); out.getFD().sync();
            }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary.toPath()); }
        return uri(context);
    }

    /** The file for exactly content://<authority>/volt-diagnostics.json, else null. */
    private File file(Uri uri) {
        Context context = getContext();
        if (context == null || uri == null || !"content".equals(uri.getScheme()) || !authority(context).equals(uri.getAuthority())) return null;
        List<String> path = uri.getPathSegments();
        if (path.size() != 1 || !NAME.equals(path.get(0)) || uri.getQuery() != null) return null;
        return new File(new File(context.getCacheDir(), DIRECTORY), NAME);
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return file(uri) == null ? null : TYPE; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !mode.equals("r")) throw new SecurityException("Diagnostics are read-only");
        File file = file(uri);
        if (file == null || !file.isFile()) throw new FileNotFoundException("No diagnostics file");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    /** Name and size only, as email apps ask before attaching. */
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = file(uri);
        if (file == null || !file.isFile()) return null;
        String[] columns = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++)
            row[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? NAME : OpenableColumns.SIZE.equals(columns[i]) ? (Object) file.length() : null;
        cursor.addRow(row);
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only"); }
}
