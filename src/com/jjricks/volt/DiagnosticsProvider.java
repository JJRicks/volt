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
 * send intent never reaches another report. Email to Volt's charge recordings are snapshots too, under their own name
 * (ChargeSend.attachment): cache/diagnostics/<id>.volt-charge-recordings.json[.gz], served at
 * content://<authority>/<id>/volt-charge-recordings.json[.gz]. Each snapshot is served only under its own name. Not
 * exported; nothing else in the cache, and no other path, is served.
 */
public final class DiagnosticsProvider extends ContentProvider {
    static final String DIRECTORY = "diagnostics", NAME = "volt-diagnostics.json", TYPE = "application/json";

    static String authority(Context context) { return context.getPackageName() + ".diagnostics"; }
    static final long KEEP_MS = 24L * 60 * 60 * 1000;
    static final int KEEP = 10;
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[0-9a-f]{32}");
    private static final String EXISTS = "Diagnostics snapshot already exists";
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    static Uri uri(Context context, String id, String name) { return new Uri.Builder().scheme("content").authority(authority(context)).appendPath(id).appendPath(name).build(); }

    /** The snapshot file's name after its id, for each name served: the report's .json, or "." and the recordings' name. */
    private static String suffix(String name) {
        return NAME.equals(name) ? ".json" : ChargeSend.named(name) != null ? "." + name : null;
    }

    /** The MIME type of the file served under this name, else null. */
    static String type(String name) {
        if (NAME.equals(name)) return TYPE;
        ChargeSend.Attachment attachment = ChargeSend.named(name);
        return attachment == null ? null : attachment.type;
    }

    /** Writes this bundle as a new snapshot that never changes, prunes old ones, and returns its own content URI. */
    static Uri write(Context context, String json) throws IOException {
        File directory = directory(context);
        String id = newId();
        File file = new File(directory, id + ".json"), temporary = new File(directory, id + ".tmp");
        if (file.exists()) throw new IOException(EXISTS);
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(json.getBytes(StandardCharsets.UTF_8)); out.flush(); out.getFD().sync();
            }
            publish(temporary, file);
        } finally { Files.deleteIfExists(temporary.toPath()); }
        return uri(context, id, NAME);
    }

    /**
     * As write(), for Email to Volt's charge recordings, a file too large to hold in memory: it becomes a snapshot under
     * the recordings' own name, and is then gone from where it was. The service's export has already made it ready
     * (ChargeSend.prepare: over 25 MB it is volt-charge-recordings.json.gz's content, compressed under the exporting
     * flag; #412 review), so here it is only moved into place, never read into memory. A file not yet prepared is
     * prepared first. Off the main thread.
     */
    static Uri adopt(Context context, File source) throws IOException {
        try {
            File ready = ChargeSend.prepare(source);
            try {
                File directory = directory(context);
                String id = newId();
                ChargeSend.Attachment attachment = ChargeSend.of(ready);
                File file = new File(directory, id + suffix(attachment.name)), temporary = new File(directory, id + ".tmp");
                if (file.exists()) throw new IOException(EXISTS);
                try {
                    // Same cache volume, so normally a rename; across volumes Files.move copies by streaming.
                    Files.move(ready.toPath(), temporary.toPath());
                    // A move keeps the export's own time: date the temporary file now, so prune() knows it is being made.
                    temporary.setLastModified(System.currentTimeMillis());
                    try (java.io.RandomAccessFile synced = new java.io.RandomAccessFile(temporary, "rw")) { synced.getFD().sync(); }
                    publish(temporary, file);
                } finally { Files.deleteIfExists(temporary.toPath()); }
                return uri(context, id, attachment.name);
            } finally { Files.deleteIfExists(ready.toPath()); }
        } finally { Files.deleteIfExists(source.toPath()); }
    }

    /** cache/diagnostics, made if needed and pruned. */
    private static File directory(Context context) throws IOException {
        File directory = new File(context.getCacheDir(), DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No diagnostics folder");
        prune(directory, System.currentTimeMillis());
        return directory;
    }

    /** A random 128-bit snapshot id, as 32 hex digits. */
    private static String newId() {
        byte[] random = new byte[16]; RANDOM.nextBytes(random);
        StringBuilder id = new StringBuilder();
        for (byte b : random) id.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return id.toString();
    }

    /** Moves a finished temporary file to its snapshot name, atomically where the file system can. */
    private static void publish(File temporary, File file) throws IOException {
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary.toPath(), file.toPath()); }
    }

    /**
     * Drops snapshots older than a day and all but the newest KEEP, so a recently prepared email keeps its file. A
     * temporary file is deleted only once it is a minute old (ChargeSend.dropTemporary): a younger one is another
     * report's attachment being made (adopt() syncs a large one before moving it into place), never left over.
     */
    static void prune(File directory, long nowMs) {
        File[] files = directory.listFiles();
        if (files == null) return;
        java.util.List<File> snapshots = new java.util.ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".tmp")) { if (ChargeSend.dropTemporary(f.lastModified(), nowMs)) f.delete(); continue; }
            if (name.equals(NAME)) { f.delete(); continue; }
            if (snapshot(name)) snapshots.add(f);
        }
        snapshots.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 0; i < snapshots.size(); i++)
            if (i >= KEEP || nowMs - snapshots.get(i).lastModified() > KEEP_MS) snapshots.get(i).delete();
    }

    /** Whether a file in the folder is a snapshot: an id and the suffix of a name served (the report's or the recordings'). */
    private static boolean snapshot(String fileName) {
        for (String name : new String[] {NAME, ChargeSend.NAME, ChargeSend.COMPRESSED_NAME}) {
            String suffix = suffix(name);
            if (fileName.endsWith(suffix) && ID.matcher(fileName.substring(0, fileName.length() - suffix.length())).matches()) return true;
        }
        return false;
    }

    /**
     * The snapshot for exactly content://<authority>/<id>/<name>, where name is volt-diagnostics.json or the charge
     * recordings' name; it exists only if that snapshot was made under that name. Else null.
     */
    private File file(Uri uri) { return file(getContext(), uri); }

    /**
     * As file(Uri), for a caller that holds a URI adopt() returned: the snapshot behind it (Email to Volt deletes it when
     * the mailbox drops an attachment it never emailed; #419 review).
     */
    static File file(Context context, Uri uri) {
        if (context == null || uri == null || !"content".equals(uri.getScheme()) || !authority(context).equals(uri.getAuthority())) return null;
        List<String> path = uri.getPathSegments();
        if (path.size() != 2 || !ID.matcher(path.get(0)).matches() || suffix(path.get(1)) == null || uri.getQuery() != null) return null;
        return new File(new File(context.getCacheDir(), DIRECTORY), path.get(0) + suffix(path.get(1)));
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return file(uri) == null ? null : type(uri.getLastPathSegment()); }
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
            row[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? uri.getLastPathSegment() : OpenableColumns.SIZE.equals(columns[i]) ? (Object) file.length() : null;
        cursor.addRow(row);
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only"); }
}
