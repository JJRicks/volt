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
 * The files report emails attach, read-only. Each prepared report is its own snapshot, cache/diagnostics/<id>.json
 * with a random 128-bit id, written once and never changed, served at content://<authority>/<id>/volt-diagnostics.json.
 * So an email app that opens its attachment late still gets the file the owner previewed, and a read grant on one
 * send intent never reaches another report. Not exported; nothing else in the cache, and no other path, is served.
 */
public final class DiagnosticsProvider extends ContentProvider {
    static final String DIRECTORY = "diagnostics", NAME = "volt-diagnostics.json", TYPE = "application/json";

    static String authority(Context context) { return context.getPackageName() + ".diagnostics"; }
    static final long KEEP_MS = 24L * 60 * 60 * 1000;
    static final int KEEP = 10;
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[0-9a-f]{32}");
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    static Uri uri(Context context, String id) { return new Uri.Builder().scheme("content").authority(authority(context)).appendPath(id).appendPath(NAME).build(); }

    /** Writes this bundle as a new snapshot that never changes, prunes old ones, and returns its own content URI. */
    static Uri write(Context context, String json) throws IOException {
        File directory = new File(context.getCacheDir(), DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No diagnostics folder");
        prune(directory, System.currentTimeMillis());
        byte[] random = new byte[16]; RANDOM.nextBytes(random);
        StringBuilder id = new StringBuilder();
        for (byte b : random) id.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        File file = new File(directory, id + ".json"), temporary = new File(directory, id + ".tmp");
        if (file.exists()) throw new IOException("Diagnostics snapshot already exists");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(json.getBytes(StandardCharsets.UTF_8)); out.flush(); out.getFD().sync();
            }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary.toPath(), file.toPath()); }
        } finally { Files.deleteIfExists(temporary.toPath()); }
        return uri(context, id.toString());
    }

    /** Drops snapshots older than a day and all but the newest KEEP, so a recently prepared email keeps its file. */
    static void prune(File directory, long nowMs) {
        File[] files = directory.listFiles();
        if (files == null) return;
        java.util.List<File> snapshots = new java.util.ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".tmp") || name.equals(NAME)) { f.delete(); continue; }
            if (name.endsWith(".json") && ID.matcher(name.substring(0, name.length() - 5)).matches()) snapshots.add(f);
        }
        snapshots.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 0; i < snapshots.size(); i++)
            if (i >= KEEP || nowMs - snapshots.get(i).lastModified() > KEEP_MS) snapshots.get(i).delete();
    }

    /** The snapshot for exactly content://<authority>/<id>/volt-diagnostics.json, else null. */
    private File file(Uri uri) {
        Context context = getContext();
        if (context == null || uri == null || !"content".equals(uri.getScheme()) || !authority(context).equals(uri.getAuthority())) return null;
        List<String> path = uri.getPathSegments();
        if (path.size() != 2 || !ID.matcher(path.get(0)).matches() || !NAME.equals(path.get(1)) || uri.getQuery() != null) return null;
        return new File(new File(context.getCacheDir(), DIRECTORY), path.get(0) + ".json");
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
