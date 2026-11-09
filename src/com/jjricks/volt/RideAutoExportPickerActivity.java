package com.jjricks.volt;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;

/**
 * Android's folder picker for automatic ride copies (RideAutoExport), in a small activity of its own, as
 * BackupActivity hosts its file pickers: MainActivity stays out of it. It has no views (a translucent
 * theme) and finishes as soon as the picker answers. A cancelled pick changes nothing. A picked folder is
 * kept with a persisted read and write grant, turns auto-save on when the switch asked for it ({@link #TURN_ON}) and retries
 * the rides that are waiting. What went wrong is a {@link Refusal}, never text.
 */
public final class RideAutoExportPickerActivity extends Activity {
    /** Optional extra: true when the switch opened the picker, so auto-save turns on once a folder is kept. */
    static final String TURN_ON = "turn_on";
    private static final int PICK_FOLDER = 501;
    /** Why no folder was kept. */
    enum Refusal { NO_PICKER, NOT_KEPT }

    static Intent intent(android.content.Context context, boolean turnOn) {
        return new Intent(context, RideAutoExportPickerActivity.class).putExtra(TURN_ON, turnOn);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Never during backup or restore; a recreated activity is already waiting for the picker's answer.
        if (DataMaintenance.active()) { finish(); return; }
        if (state != null) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        Uri current = RideAutoExportFolder.chosen(settings());
        if (current != null && Build.VERSION.SDK_INT >= 26) {
            try { picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUriUsingTree(current, DocumentsContract.getTreeDocumentId(current))); }
            catch (RuntimeException ignored) { }
        }
        try { startActivityForResult(picker, PICK_FOLDER); }
        catch (RuntimeException missing) { refused(Refusal.NO_PICKER); finish(); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FOLDER) return;
        if (result == RESULT_OK && data != null && data.getData() != null && !DataMaintenance.active()) {
            try {
                RideAutoExportFolder.keep(this, settings(), data.getData(), getIntent().getBooleanExtra(TURN_ON, false));
                ScooterService service = ScooterService.currentForMaintenance();
                if (service != null) service.rideAutoExportFolderChosen();
            } catch (RuntimeException refused) {
                android.util.Log.w("Volt", "Ride auto-export folder not kept", refused);
                refused(Refusal.NOT_KEPT);
            }
        }
        finish();
    }

    /** No folder was kept; the setting stays as it was. */
    private void refused(Refusal why) {
        android.widget.Toast.makeText(getApplicationContext(), why == Refusal.NO_PICKER ? T.moreAutoSaveNoPicker() : T.moreAutoSaveNotKept(), android.widget.Toast.LENGTH_LONG).show();
        android.util.Log.w("Volt", "Ride auto-export picker: " + why);
    }

    private SharedPreferences settings() { return getSharedPreferences("app_settings", MODE_PRIVATE); }
}
