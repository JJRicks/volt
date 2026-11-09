package com.jjricks.volt;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Saves several of one ride's export files at once (Rides, Export ride data): Android's folder picker asks once,
 * then each staged file (RideExportFiles) is written into the picked folder under its usual name. The folder's
 * access is only for this save: no grant is kept, and the folder is remembered only as the picker's next starting
 * place. A cancelled pick writes nothing. Like RideAutoExportPickerActivity it has no views (a translucent theme)
 * and finishes once the files are written; the staging folder is removed either way.
 */
public final class RideExportFolderActivity extends Activity {
    private static final int PICK_FOLDER = 502;
    private File staging;

    static Intent intent(Context context, File staging, String stem, List<RideExportFiles.Format> formats) {
        return new Intent(context, RideExportFolderActivity.class).putExtra(RideExportFiles.EXTRA_DIR, staging.getAbsolutePath())
                .putExtra(RideExportFiles.EXTRA_STEM, stem).putExtra(RideExportFiles.EXTRA_FORMATS, RideExportFiles.ids(formats));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String path = getIntent().getStringExtra(RideExportFiles.EXTRA_DIR);
        staging = path == null ? null : new File(path);
        if (!RideExportFiles.isStaging(staging) || DataMaintenance.active()) { RideExportFiles.remove(staging); finish(); return; }
        // A recreated activity is already waiting for the picker's answer.
        if (state != null) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        Uri start = startingPlace();
        if (start != null) {
            try { picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUriUsingTree(start, DocumentsContract.getTreeDocumentId(start))); }
            catch (RuntimeException ignored) { }
        }
        try { startActivityForResult(picker, PICK_FOLDER); }
        catch (RuntimeException missing) { toast(T.ridesToastExportNoPicker()); RideExportFiles.remove(staging); finish(); }
    }

    /** The folder the last several-file export went to, else the automatic copies' folder; null opens the picker's default. */
    private Uri startingPlace() {
        SharedPreferences settings = settings();
        try {
            String hint = settings.getString(RideExportFiles.FOLDER_HINT, null);
            if (hint != null && !hint.isEmpty()) return Uri.parse(hint);
        } catch (RuntimeException invalid) { }
        return RideAutoExportFolder.chosen(settings);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FOLDER) return;
        Uri tree = result == RESULT_OK && data != null ? data.getData() : null;
        if (tree == null || DataMaintenance.active()) { RideExportFiles.remove(staging); finish(); return; }
        settings().edit().putString(RideExportFiles.FOLDER_HINT, tree.toString()).apply();
        String stem = getIntent().getStringExtra(RideExportFiles.EXTRA_STEM);
        List<RideExportFiles.Format> formats = RideExportFiles.formats(getIntent().getStringArrayExtra(RideExportFiles.EXTRA_FORMATS));
        ContentResolver resolver = getContentResolver();
        File source = staging;
        new Thread(() -> {
            int saved = 0;
            if (stem != null) saved = write(resolver, tree, source, stem, formats);
            RideExportFiles.remove(source);
            final int count = saved, total = formats.size();
            runOnUiThread(() -> {
                if (count == total && total > 0) toast(T.ridesToastExportSaved(count, count));
                else if (count == 0) toast(T.ridesToastExportFolderFailed());
                else toast(T.ridesToastExportPartial(total, count, total));
                finish();
            });
        }).start();
    }

    /** Creates each file in the picked folder (the provider adds " (1)" rather than replace a file of the same name). */
    private static int write(ContentResolver resolver, Uri tree, File staging, String stem, List<RideExportFiles.Format> formats) {
        Uri root;
        try { root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)); }
        catch (RuntimeException notAFolder) { return 0; }
        int saved = 0;
        for (RideExportFiles.Format format : formats) {
            String name = RideExportFiles.fileName(format, stem);
            Uri document = null;
            try (InputStream in = new FileInputStream(new File(staging, name))) {
                document = DocumentsContract.createDocument(resolver, root, format.mime, name);
                if (document == null) continue;
                try (OutputStream out = resolver.openOutputStream(document, "wt")) {
                    if (out == null) throw new IOException(name);
                    RideExportFiles.copy(in, out);
                }
                saved++;
            } catch (IOException | RuntimeException failure) {
                // Never leave a half-written file behind.
                if (document != null) try { DocumentsContract.deleteDocument(resolver, document); } catch (Exception ignored) { }
            }
        }
        return saved;
    }

    private void toast(String text) { Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show(); }
    private SharedPreferences settings() { return getSharedPreferences("app_settings", MODE_PRIVATE); }
}
