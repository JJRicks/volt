package com.jjricks.volt;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.*;

/**
 * The folder the rider chose for automatic ride copies (RideAutoExport), through the Storage Access
 * Framework, and the stored choice around it. Folder calls do real I/O: run them on a worker thread. A
 * missing grant, a deleted folder or a provider that refuses to list it is {@link RideAutoExport.Unavailable};
 * anything else is an ordinary IOException. Nothing here is shown text: failures are ids (RideAutoExport.Problem).
 */
final class RideAutoExportFolder implements RideAutoExport.Folder {
    private static final int GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    /** The temporary file's type: a provider never adds an extension to it (".partial.csv"). */
    private static final String TEMPORARY_MIME = "application/octet-stream";
    /** Guards every read-modify-write of the stored keys between the main thread and the export worker. */
    static final Object LOCK = new Object();
    private final ContentResolver resolver;
    private final Uri tree, root;

    RideAutoExportFolder(Context context, Uri tree) throws RideAutoExport.Unavailable {
        resolver = context.getContentResolver(); this.tree = tree;
        if (!granted(context, tree)) throw new RideAutoExport.Unavailable("Folder access is no longer granted");
        try { root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)); }
        catch (RuntimeException invalid) { throw new RideAutoExport.Unavailable("Not a folder", invalid); }
    }

    // ---- The stored choice ------------------------------------------------------------------------

    static boolean routeCsv(SharedPreferences settings) { return settings.getBoolean(RideAutoExport.ROUTE_CSV, false); }
    static boolean json(SharedPreferences settings) { return settings.getBoolean(RideAutoExport.JSON, false); }
    static boolean anyOn(SharedPreferences settings) { return RideAutoExport.anyOn(routeCsv(settings), json(settings)); }
    static RideAutoExport.Problem problem(SharedPreferences settings) { return RideAutoExport.Problem.of(settings.getString(RideAutoExport.PROBLEM, "")); }

    /** True while this installation still holds a persisted read and write grant for the tree. */
    static boolean granted(Context context, Uri tree) {
        if (tree == null) return false;
        try {
            for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions())
                if (permission.getUri().equals(tree) && permission.isReadPermission() && permission.isWritePermission()) return true;
        } catch (RuntimeException unavailable) { }
        return false;
    }
    /** The stored folder, or null when none was chosen on this installation. */
    static Uri chosen(SharedPreferences settings) {
        String stored = settings.getString(RideAutoExport.FOLDER, null);
        try { return stored == null || stored.isEmpty() ? null : Uri.parse(stored); } catch (RuntimeException invalid) { return null; }
    }
    /** "Download/VoltRides" for local storage, or null when the provider's folder id says nothing readable. */
    static String label(Uri tree) {
        try { return tree == null ? null : RideAutoExport.folderLabel(DocumentsContract.getTreeDocumentId(tree)); }
        catch (RuntimeException invalid) { return null; }
    }
    /** What the settings row for one type says. */
    static RideAutoExport.RowState rowState(Context context, SharedPreferences settings, String typeKey) {
        Uri folder = chosen(settings);
        return RideAutoExport.rowState(settings.getBoolean(typeKey, false), folder != null, granted(context, folder), problem(settings));
    }
    /** True when a type can be turned on without the picker: a folder is stored and still granted. */
    static boolean ready(Context context, SharedPreferences settings) { return granted(context, chosen(settings)); }

    /**
     * The picker returned a folder: keep its grant across restarts, give up the previous folder's, turn on
     * the type that asked for it (typeKey; null keeps both as they are) and clear the last problem.
     * Throws SecurityException when Android refuses to keep the grant; nothing is stored then.
     */
    static void keep(Context context, SharedPreferences settings, Uri picked, String typeKey) {
        context.getContentResolver().takePersistableUriPermission(picked, GRANT);
        synchronized (LOCK) {
            Uri previous = chosen(settings);
            if (previous != null && !previous.equals(picked)) release(context, previous);
            SharedPreferences.Editor edit = settings.edit().putString(RideAutoExport.FOLDER, picked.toString()).remove(RideAutoExport.PROBLEM);
            if (RideAutoExport.ROUTE_CSV.equals(typeKey) || RideAutoExport.JSON.equals(typeKey)) edit.putBoolean(typeKey, true);
            edit.commit();
            // Both off meanwhile (a folder change confirmed after the last switch went off): hold no grant.
            if (!anyOn(settings)) { release(context, picked); settings.edit().remove(RideAutoExport.FOLDER).commit(); }
        }
    }
    /**
     * Turns one type on or off. Turning the last one off forgets the folder, gives up its grant and drops
     * the rides still waiting and the last problem; recorded rides are untouched.
     */
    static void set(Context context, SharedPreferences settings, String typeKey, boolean on) {
        if (!RideAutoExport.ROUTE_CSV.equals(typeKey) && !RideAutoExport.JSON.equals(typeKey)) throw new IllegalArgumentException(typeKey);
        synchronized (LOCK) {
            settings.edit().putBoolean(typeKey, on).commit();
            if (anyOn(settings)) return;
            Uri previous = chosen(settings);
            if (previous != null) release(context, previous);
            settings.edit().remove(RideAutoExport.FOLDER).remove(RideAutoExport.PENDING).remove(RideAutoExport.PROBLEM).commit();
        }
    }
    /** A ride ended: it waits until each type that is on has been written. Persisted first, so a later pass or restart finds it. */
    static void queue(SharedPreferences settings, String rideId) {
        if (rideId == null) return;
        synchronized (LOCK) {
            if (!anyOn(settings)) return;
            settings.edit().putString(RideAutoExport.PENDING, RideAutoExport.add(settings.getString(RideAutoExport.PENDING, ""), rideId)).commit();
        }
    }
    static boolean waiting(SharedPreferences settings) {
        synchronized (LOCK) { return anyOn(settings) && !RideAutoExport.parse(settings.getString(RideAutoExport.PENDING, "")).isEmpty(); }
    }
    private static void release(Context context, Uri tree) {
        try { context.getContentResolver().releasePersistableUriPermission(tree, GRANT); } catch (RuntimeException notHeld) { }
    }

    // ---- The folder -------------------------------------------------------------------------------

    /** The child's document URI, or null when the folder has no file of that name. */
    private Uri find(String name) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(root));
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) throw new RideAutoExport.Unavailable("The folder could not be listed");
            while (cursor.moveToNext())
                if (name.equals(cursor.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
            return null;
        } catch (SecurityException | IllegalArgumentException | IllegalStateException gone) {
            // A deleted folder or revoked grant surfaces here (FileNotFoundException wrapped by the provider).
            throw new RideAutoExport.Unavailable("The folder could not be listed", gone);
        }
    }
    @Override public byte[] read(String name) throws IOException {
        Uri file = find(name);
        if (file == null) return null;
        try (InputStream in = resolver.openInputStream(file)) {
            if (in == null) throw new IOException("Unreadable " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } catch (SecurityException | IllegalArgumentException | IllegalStateException failure) { throw new IOException(failure); }
    }
    @Override public void delete(String name) throws IOException {
        Uri file = find(name);
        if (file == null) return;
        try { if (!DocumentsContract.deleteDocument(resolver, file)) throw new IOException("Could not delete " + name); }
        catch (SecurityException | IllegalArgumentException | IllegalStateException failure) { throw new IOException(failure); }
    }
    @Override public String create(String name, byte[] data) throws IOException {
        Uri file;
        try { file = DocumentsContract.createDocument(resolver, root, TEMPORARY_MIME, name); }
        catch (SecurityException | IllegalArgumentException | IllegalStateException failure) { throw new IOException(failure); }
        if (file == null) throw new IOException("The folder did not create " + name);
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(file, "wt")) {
            if (descriptor == null) throw new IOException("Unwritable " + name);
            try (FileOutputStream out = new FileOutputStream(descriptor.getFileDescriptor())) {
                out.write(data); out.flush();
                try { out.getFD().sync(); } catch (IOException notSupported) { /* a pipe from a cloud provider */ }
            }
        } catch (IOException | RuntimeException failure) {
            try { DocumentsContract.deleteDocument(resolver, file); } catch (Exception ignored) { }
            throw failure instanceof IOException ? (IOException) failure : new IOException(failure);
        }
        String given = displayName(file);
        return given == null ? name : given;
    }
    @Override public void rename(String from, String to) throws IOException {
        Uri file = find(from);
        if (file == null) throw new IOException("Missing " + from);
        Uri renamed;
        try { renamed = DocumentsContract.renameDocument(resolver, file, to); }
        catch (SecurityException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException failure) { throw new IOException(failure); }
        String given = displayName(renamed == null ? file : renamed);
        if (!to.equals(given)) {
            // The provider picked another name (another file appeared meanwhile): never leave a duplicate.
            try { DocumentsContract.deleteDocument(resolver, renamed == null ? file : renamed); } catch (Exception ignored) { }
            throw new IOException("The folder renamed " + from + " to " + given);
        }
    }
    private String displayName(Uri file) {
        try (Cursor cursor = resolver.query(file, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
        } catch (RuntimeException unavailable) { return null; }
    }
}
