package com.jjricks.volt;

import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.BooleanSupplier;

/**
 * The sheet Home's charger tip opens (ChargeEstimateTip.Tip.OTHER_CHARGER; owner, Nov 19 2026: "if the power is wildly
 * off the known curve … a warning saying 'hey you should record with this charger too'"). Its title, one short text, and
 * one main button that names the charger the charge will be filed under: "Record this charge as High-speed" (a recording
 * starts here, or the reason it can't shows), or, while Volt is already recording this charge by itself with a guessed
 * charger, "Save this recording as High-speed" (that recording is filed under it, the owner's choice). Then Not now and
 * the quiet Hide this tip, as on the record tip's sheet.
 */
final class ChargerTipSheet {
    private ChargerTipSheet() { }

    /**
     * Shows the sheet and returns it. files: the action saves the running automatic recording (else it records this
     * charge); highSpeed: the charger it is filed under. action returns whether the sheet closes; hide runs after it has.
     */
    static AlertDialog show(ScreenHost host, boolean files, boolean highSpeed, BooleanSupplier action, Runnable hide) {
        Ui ui = host.ui();
        AlertDialog[] shown = new AlertDialog[1];
        Runnable close = () -> { if (shown[0] != null) shown[0].dismiss(); };
        LinearLayout content = host.sheetContent(HubText.chargerTipTitle(), HubText.chargerTipText(), close);
        content.setTag(ChargeEstimateTip.TAG_CHARGER_SHEET);
        TextView main = ui.button(HubText.chargerTipAction(files, highSpeed).render(), () -> { if (action.getAsBoolean()) close.run(); });
        main.setTag(ChargeEstimateTip.TAG_CHARGER_ACTION);
        content.addView(main, ChargeInviteSheet.buttonSpace(ui, 14));
        TextView later = ui.secondaryButton(T.mainReportNotNow(), close);
        later.setTag(ChargeEstimateTip.TAG_NOT_NOW);
        content.addView(later, ChargeInviteSheet.buttonSpace(ui, 8));
        String hideWords = HubText.estimateTipHide().render();
        TextView hideTip = ui.text(hideWords, 13.5f, false);
        hideTip.setTypeface(ui.medium());
        hideTip.setTextColor(ui.muted);
        hideTip.setGravity(Gravity.CENTER);
        hideTip.setMinHeight(ui.dp(48));
        hideTip.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        hideTip.setFocusable(true);
        hideTip.setClickable(true);
        hideTip.setContentDescription(hideWords);
        hideTip.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)),
                null, ui.shape(0xFFFFFFFF, 14, 0)));
        hideTip.setTag(ChargeEstimateTip.TAG_HIDE);
        hideTip.setOnClickListener(v -> { close.run(); hide.run(); });
        content.addView(hideTip, ChargeInviteSheet.buttonSpace(ui, 6));
        shown[0] = host.showSheet(content);
        return shown[0];
    }
}
