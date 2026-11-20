package com.jjricks.volt;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The sheet Battery &amp; charging's charge recordings banner opens (ChargeInvite; owner, Nov 17 2026): in plain words,
 * record your charges, any charge helps, a charge to 100% helps most, keep the phone near the scooter, then email the
 * recordings to Volt to tune the estimate for the scooter's model. Under the points, the ways to act on them:
 * Record charges automatically (the same switch and preference as Charge history &amp; recordings'), Record this charge
 * (opens that page at its Record this charge row and starts it when a recording can start now, else the row's status
 * says why), Charge history &amp; recordings itself, and Not now. Closing the sheet any way keeps the banner: only its X
 * hides it (ChargeInvite's rule). Text from HubText and the rows' own strings. Home's record tip opens a shorter sheet
 * from the same parts (showRecordTip).
 */
final class ChargeInviteSheet {
    private ChargeInviteSheet() { }

    /** Shows the sheet over the current screen and returns it, so the page can close it when it goes away. */
    static AlertDialog show(ScreenHost host, SharedPreferences preferences) {
        Ui ui = host.ui();
        AlertDialog[] shown = new AlertDialog[1];
        Runnable close = () -> { if (shown[0] != null) shown[0].dismiss(); };
        LinearLayout content = host.sheetContent(HubText.chargeInviteTitle(), HubText.chargeInviteIntro(), close);
        content.setTag(ChargeInvite.TAG_SHEET);
        for (Msg step : HubText.chargeInviteSteps()) content.addView(point(host, ui, step.render()), new LinearLayout.LayoutParams(-1, -2));
        content.addView(autoToggle(ui, preferences, ChargeInvite.TAG_AUTO), toggleSpace(ui));
        TextView record = ui.button(T.moreDevRecord(), () -> {
            close.run();
            host.open(HubPages.BATTERY_HISTORY, HubNav.Args.anchor(ChargeInvite.RECORD_ANCHOR));
        });
        record.setTag(ChargeInvite.TAG_RECORD);
        content.addView(record, buttonSpace(ui, 4));
        content.addView(historyButton(host, ui, close, ChargeInvite.TAG_OPEN), buttonSpace(ui, 8));
        TextView later = ui.secondaryButton(T.mainReportNotNow(), close);
        later.setTag(ChargeInvite.TAG_NOT_NOW);
        content.addView(later, buttonSpace(ui, 8));
        shown[0] = host.showSheet(content);
        return shown[0];
    }

    /**
     * Home's record tip's sheet (ChargeEstimateTip.Tip.NO_RECORDINGS; owner, Nov 19 2026): "Record your charges", the
     * invite's intro (Volt learns from real charges; recordings make the estimate more accurate), the invite's points but
     * its first (any charge helps, 100% helps most, keep the phone near, then send them to Volt for the model), then the
     * same controls in the order a rider on the charging screen wants them: Record this charge (record: the page starts
     * it right there when it can, and says why not otherwise; it returns whether the sheet should close), Record charges
     * automatically, Charge history &amp; recordings, Not now, and a quiet Hide this tip (hide), which hides the tip for
     * good. Closing the sheet any other way keeps the tip. Returns the sheet, so the page can close it with itself.
     */
    static AlertDialog showRecordTip(ScreenHost host, SharedPreferences preferences, java.util.function.BooleanSupplier record, Runnable hide) {
        Ui ui = host.ui();
        AlertDialog[] shown = new AlertDialog[1];
        Runnable close = () -> { if (shown[0] != null) shown[0].dismiss(); };
        LinearLayout content = host.sheetContent(HubText.recordTipTitle(), HubText.chargeInviteIntro(), close);
        content.setTag(ChargeEstimateTip.TAG_SHEET);
        for (Msg step : HubText.recordTipSteps()) content.addView(point(host, ui, step.render()), new LinearLayout.LayoutParams(-1, -2));
        TextView start = ui.button(T.moreDevRecord(), () -> { if (record.getAsBoolean()) close.run(); });
        start.setTag(ChargeEstimateTip.TAG_RECORD);
        content.addView(start, buttonSpace(ui, 14));
        content.addView(autoToggle(ui, preferences, ChargeEstimateTip.TAG_AUTO), toggleSpace(ui));
        content.addView(historyButton(host, ui, close, ChargeEstimateTip.TAG_OPEN), buttonSpace(ui, 8));
        TextView later = ui.secondaryButton(T.mainReportNotNow(), close);
        later.setTag(ChargeEstimateTip.TAG_NOT_NOW);
        content.addView(later, buttonSpace(ui, 8));
        // Quiet: plain muted words, no fill or outline, full width and 48 dp to touch.
        String hideWords = HubText.estimateTipHide().render();
        TextView hideTip = ui.text(hideWords, 13.5f, false);
        hideTip.setTypeface(ui.medium()); hideTip.setTextColor(ui.muted); hideTip.setGravity(android.view.Gravity.CENTER);
        hideTip.setMinHeight(ui.dp(48)); hideTip.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        hideTip.setFocusable(true); hideTip.setClickable(true); hideTip.setContentDescription(hideWords);
        hideTip.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)), null, ui.shape(0xFFFFFFFF, 14, 0)));
        hideTip.setTag(ChargeEstimateTip.TAG_HIDE);
        hideTip.setOnClickListener(v -> { close.run(); hide.run(); });
        content.addView(hideTip, buttonSpace(ui, 6));
        shown[0] = host.showSheet(content);
        return shown[0];
    }

    /** Record charges automatically: the same switch and preference as Charge history &amp; recordings' (MoreScreen.charging). */
    private static View autoToggle(Ui ui, SharedPreferences preferences, String tag) {
        boolean auto;
        try { auto = preferences.getBoolean(ChargeAutoRecordPolicy.KEY, false); } catch (ClassCastException wrongType) { auto = false; }
        // One preference, which the service follows.
        View toggle = ui.toggleRow(T.moreChargeAutoRecord(), "recording", T.moreChargeAutoRecordSub(), auto, true,
                enabled -> preferences.edit().putBoolean(ChargeAutoRecordPolicy.KEY, enabled).apply());
        toggle.setTag(tag);
        return toggle;
    }

    private static LinearLayout.LayoutParams toggleSpace(Ui ui) {
        LinearLayout.LayoutParams toggleSpace = new LinearLayout.LayoutParams(-1, -2);
        toggleSpace.topMargin = ui.dp(14);
        return toggleSpace;
    }

    /** Charge history &amp; recordings, by the page's own name: closes the sheet and opens it. */
    private static TextView historyButton(ScreenHost host, Ui ui, Runnable close, String tag) {
        TextView open = ui.secondaryButton(HubText.rowTitle(HubPages.BATTERY_HISTORY).render(), () -> {
            close.run();
            host.open(HubPages.BATTERY_HISTORY);
        });
        open.setTag(tag);
        return open;
    }

    /** One point of the list: a small accent dot beside its sentence, which wraps under itself. */
    private static View point(ScreenHost host, Ui ui, String text) {
        LinearLayout line = new LinearLayout(host.activity());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, ui.dp(6), 0, ui.dp(2));
        View dot = new View(host.activity());
        dot.setBackground(ui.shape(ui.accent, 3, 0));
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dotSize = new LinearLayout.LayoutParams(ui.dp(6), ui.dp(6));
        // Centred on the first line of 14 sp text (its line box is about 20 dp at 1x).
        dotSize.topMargin = Math.round(ui.dp(7) * ui.textScale);
        dotSize.setMarginEnd(ui.dp(10));
        line.addView(dot, dotSize);
        TextView words = ui.text(text, 14, false);
        words.setLineSpacing(ui.dp(2), 1);
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        return line;
    }

    private static LinearLayout.LayoutParams buttonSpace(Ui ui, int topDp) {
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.topMargin = ui.dp(topDp);
        return space;
    }
}
