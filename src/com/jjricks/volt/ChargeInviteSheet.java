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
 * hides it (ChargeInvite's rule). Text from HubText and the rows' own strings.
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
        boolean auto;
        try { auto = preferences.getBoolean(ChargeAutoRecordPolicy.KEY, false); } catch (ClassCastException wrongType) { auto = false; }
        // The same switch as Charge history & recordings' (MoreScreen.charging): one preference, which the service follows.
        View toggle = ui.toggleRow(T.moreChargeAutoRecord(), "recording", T.moreChargeAutoRecordSub(), auto, true,
                enabled -> preferences.edit().putBoolean(ChargeAutoRecordPolicy.KEY, enabled).apply());
        toggle.setTag(ChargeInvite.TAG_AUTO);
        LinearLayout.LayoutParams toggleSpace = new LinearLayout.LayoutParams(-1, -2);
        toggleSpace.topMargin = ui.dp(14);
        content.addView(toggle, toggleSpace);
        TextView record = ui.button(T.moreDevRecord(), () -> {
            close.run();
            host.open(HubPages.BATTERY_HISTORY, HubNav.Args.anchor(ChargeInvite.RECORD_ANCHOR));
        });
        record.setTag(ChargeInvite.TAG_RECORD);
        content.addView(record, buttonSpace(ui, 4));
        TextView open = ui.secondaryButton(HubText.rowTitle(HubPages.BATTERY_HISTORY).render(), () -> {
            close.run();
            host.open(HubPages.BATTERY_HISTORY);
        });
        open.setTag(ChargeInvite.TAG_OPEN);
        content.addView(open, buttonSpace(ui, 8));
        TextView later = ui.secondaryButton(T.mainReportNotNow(), close);
        later.setTag(ChargeInvite.TAG_NOT_NOW);
        content.addView(later, buttonSpace(ui, 8));
        shown[0] = host.showSheet(content);
        return shown[0];
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
