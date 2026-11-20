package com.jjricks.volt;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.BooleanSupplier;

/**
 * The sheet Home's record tip opens (ChargeEstimateTip.Tip.NO_RECORDINGS; owner, Nov 19 2026: "We recommend recording
 * your charges! Recording a charge helps Volt learn how your scooter charges and improves your estimation accuracy. Or
 * you can donate recordings to the developers…"). Built from Battery &amp; charging's invite sheet's parts and words
 * (ChargeInviteSheet), never a copy of them: "Record your charges", the invite's intro (the rider's own estimate first),
 * the invite's points but its first (any charge helps, 100% helps most, keep the phone near, then send them to Volt for
 * the model), then the controls in the order a rider on the charging screen wants them: Record this charge, Record
 * charges automatically, Charge history &amp; recordings, Not now, and a quiet Hide this tip. Closing it any way but
 * Hide this tip keeps the tip.
 */
final class RecordTipSheet {
    private RecordTipSheet() { }

    /**
     * Shows the sheet and returns it, so Home can close it with itself. record: Record this charge (Home starts it right
     * there when it can and says why not otherwise); it returns whether the sheet closes. hide: Hide this tip, after the
     * sheet has closed.
     */
    static AlertDialog show(ScreenHost host, SharedPreferences preferences, BooleanSupplier record, Runnable hide) {
        Ui ui = host.ui();
        AlertDialog[] shown = new AlertDialog[1];
        Runnable close = () -> { if (shown[0] != null) shown[0].dismiss(); };
        LinearLayout content = host.sheetContent(HubText.recordTipTitle(), HubText.chargeInviteIntro(), close);
        content.setTag(ChargeEstimateTip.TAG_SHEET);
        for (Msg step : HubText.recordTipSteps())
            content.addView(ChargeInviteSheet.point(host, ui, step.render()), new LinearLayout.LayoutParams(-1, -2));
        TextView start = ui.button(T.moreDevRecord(), () -> { if (record.getAsBoolean()) close.run(); });
        start.setTag(ChargeEstimateTip.TAG_RECORD);
        content.addView(start, ChargeInviteSheet.buttonSpace(ui, 14));
        content.addView(ChargeInviteSheet.autoToggle(ui, preferences, ChargeEstimateTip.TAG_AUTO), ChargeInviteSheet.toggleSpace(ui));
        content.addView(ChargeInviteSheet.historyButton(host, ui, close, ChargeEstimateTip.TAG_OPEN), ChargeInviteSheet.buttonSpace(ui, 8));
        TextView later = ui.secondaryButton(T.mainReportNotNow(), close);
        later.setTag(ChargeEstimateTip.TAG_NOT_NOW);
        content.addView(later, ChargeInviteSheet.buttonSpace(ui, 8));
        // Quiet: plain muted words, no fill or outline, full width and 48 dp to touch.
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
