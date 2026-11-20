package com.jjricks.volt;

import android.app.AlertDialog;
import android.os.Build;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * "Record with Stock charger?": the question before a charge recording the owner starts, with the charger picked inside
 * the dialog and the title following it. Charge history &amp; recordings' Record this charge asks it (MoreScreen), and so
 * does Home's record tip sheet, so a recording starts right on the charging screen (ChargeEstimateTip). Capture labels
 * train curves, so only an explicit charger preference is pre-selected: an Automatic guess never becomes a label in one
 * tap. A dialog left open never starts another scooter's recording.
 */
final class ChargeRecordDialog {
    private ChargeRecordDialog() { }

    /**
     * Builds the question and hands it to {@code present} (with what to run when it closes) to show it.
     *
     * @param gone true once the screen that asked has closed or lost its service: Start then starts nothing
     * @param after runs after a Start (whether or not the recording started), for the screen to refresh
     */
    static void show(ScreenHost host, ScooterService s, BooleanSupplier gone, Runnable after, BiConsumer<AlertDialog, Runnable> present) {
        Ui ui = host.ui();
        ScooterProfiles.Profile profile = s.profiles.active();
        if (profile == null) { host.toast(T.moreHistoryChooseScooter()); return; }
        final String profileId = profile.id;
        final ChargeSessionStore.Charger[] selected = {s.chargeCharger()};
        LinearLayout box = new LinearLayout(host.activity());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(10));
        TextView heading = ui.text("", 22, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        box.addView(heading);
        TextView body = ui.caption(T.moreDevRecordBody());
        body.setPadding(0, ui.dp(7), 0, ui.dp(7));
        box.addView(body);
        final TextView[] start = new TextView[1];
        Runnable title = () -> {
            heading.setText(selected[0] == null ? T.moreDevWhichCharger()
                    : (selected[0] == ChargeSessionStore.Charger.STOCK ? T.moreDevRecordStock() : T.moreDevRecordFast()));
            if (start[0] != null) { start[0].setEnabled(selected[0] != null); start[0].setAlpha(selected[0] != null ? 1f : .5f); }
        };
        SegmentedSelector picker = new SegmentedSelector(ui, T.moreDevAttached(), new String[]{T.moreDevStock(), T.moreDevHighSpeed()}, choice -> {
            selected[0] = choice == 0 ? ChargeSessionStore.Charger.STOCK : ChargeSessionStore.Charger.HIGH_SPEED;
            title.run();
        });
        picker.setTag("charge-confirmation-selector");
        picker.select(selected[0] == null ? -1 : selected[0] == ChargeSessionStore.Charger.STOCK ? 0 : 1, false);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2); pp.topMargin = ui.dp(6); pp.bottomMargin = ui.dp(6);
        box.addView(picker, pp);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).create();
        // Two equal buttons at the foot: a quiet Cancel and the red Start recording.
        LinearLayout buttons = new LinearLayout(host.activity());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, ui.dp(10), 0, ui.dp(12));
        TextView cancel = ui.secondaryButton(T.commonCancel(), dialog::dismiss);
        start[0] = ui.button(T.moreDevStartRecording(), () -> {
            if (selected[0] == null) return;
            dialog.dismiss();
            ScooterProfiles.Profile current = s.profiles.active();
            if (gone.getAsBoolean() || current == null || !profileId.equals(current.id)) {
                host.toast(T.moreDevScooterChanged()); return;
            }
            if (!s.startChargeRecording(selected[0])) host.toast(s.chargeCaptureStatus());
            after.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.rightMargin = ui.dp(6);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1); rp.leftMargin = ui.dp(6);
        buttons.addView(cancel, lp); buttons.addView(start[0], rp); box.addView(buttons);
        start[0].setTag("charge-confirmation-start");
        title.run();
        present.accept(dialog, picker::finishMotion);
    }

    /** Shows the dialog as Volt's other dialogs show (its surface and corners, Motion's dialog entrance). */
    static void present(Ui ui, AlertDialog dialog, Runnable onDismiss) {
        if (onDismiss != null) dialog.setOnDismissListener(d -> onDismiss.run());
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline));
        Motion.present(dialog, Motion.DIALOG);
        dialog.show();
    }
}
