package com.jjricks.volt;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;

/**
 * Battery & charging's HISTORY card (the owner's Layout Plan): the recent charges as small bars (ChargeHistoryBars)
 * and the last charge's summary line under them; the whole card opens Charge history & recordings. Its text comes
 * from the caller, which owns the catalog calls.
 */
final class ChargeHistoryCard {
    final LinearLayout card;
    private final Ui ui;
    private final Bars bars;
    private final TextView line;

    ChargeHistoryCard(Context context, Ui ui, Runnable open) {
        this.ui = ui;
        card = ui.card(); card.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(14));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)), ui.shape(ui.surface, 20, 0), null));
        bars = new Bars(context);
        card.addView(bars, new LinearLayout.LayoutParams(-1, ui.dp(ChargeHistoryBars.TRACK_DP)));
        line = ui.text("", 14, false); line.setTextColor(ui.muted);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = ui.dp(10);
        card.addView(line, lp);
        card.setClickable(true); card.setFocusable(true); card.setOnClickListener(v -> open.run());
        ui.pressFeedback(card);
    }

    /** The charges (newest first) and the line under the bars; spoken: the card's whole description. */
    void show(List<ChargeHistoryBars.Charge> newestFirst, CharSequence text, CharSequence spoken) {
        bars.charges = newestFirst == null ? Collections.emptyList() : newestFirst; bars.invalidate();
        if (text == null || text.length() == 0) line.setVisibility(View.GONE);
        else { line.setVisibility(View.VISIBLE); if (!text.toString().contentEquals(line.getText())) line.setText(text); }
        card.setContentDescription(spoken);
    }

    /** The bars: a raised track each, filled from the bottom in the plan's blue by the points the charge gained. */
    private final class Bars extends View {
        List<ChargeHistoryBars.Charge> charges = Collections.emptyList();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        Bars(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float widthDp = getWidth() / density;
            int slots = ChargeHistoryBars.slots(widthDp, charges.size());
            float bar = ChargeHistoryBars.barWidth(widthDp, slots) * density, gap = ChargeHistoryBars.GAP_DP * density;
            double[] fills = ChargeHistoryBars.fills(charges, slots);
            float h = getHeight(), radius = ui.dp(5);
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            for (int i = 0; i < slots; i++) {
                int at = rtl ? slots - 1 - i : i;
                float left = at * (bar + gap);
                paint.setColor(ui.raised); rect.set(left, 0, left + bar, h); canvas.drawRoundRect(rect, radius, radius, paint);
                if (fills[i] < 0) continue;
                paint.setColor(ui.blue); rect.set(left, (float) (h * (1 - fills[i])), left + bar, h);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
        }
    }
}
