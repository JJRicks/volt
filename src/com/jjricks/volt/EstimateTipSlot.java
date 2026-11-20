package com.jjricks.volt;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The estimate tip's slot (ChargeEstimateTip; owner, Nov 19 2026: "a subtle but noticeable UI element around the
 * charge time estimation in charging mode"), under the charging view's time tile on Widgets and Clean: one small
 * outlined pill, accent words and the tip's icon, no fill, so it never competes with the charging pill above. Its
 * start lines up with the time tile's (the tile's whole width under a stacked pair), and it is as wide as its words,
 * at most the tile's width. Its label steps down the narrow-label ladder in that room: the full words, the short
 * ones, then the icon alone, which TalkBack and a long press still name in full; never a broken word or smaller
 * text. 48 dp to touch (more with larger text); the outline sits 8 dp inside that. Which tip shows is
 * ChargeEstimateTip's choice; the charging view shows and hides the slot (Motion.reveal) and a tap runs the tip's
 * action (DashboardScreen.openTip).
 */
final class EstimateTipSlot extends FrameLayout {
    final TextView pill;
    /** The time tile the pill sits under. */
    final View anchor;
    /** The tip it holds (kept while it fades out); null before the first. */
    ChargeEstimateTip.Tip tip;
    /** Its label's rungs (rendered when measured, in the language current then). */
    Msg full, brief;
    /** The rung showing: 0 full, 1 short, -1 the icon alone; -2 none chosen yet. */
    int rung = -2;
    /** Clean: false where its fixed slot has no room for the tip (ChargingHero.onMeasure, ChargingFit.extraFits). */
    boolean fits = true;

    private final Ui ui;
    /** The tip's icon, drawn at the given tip's size and colour (DashboardScreen's line glyphs). */
    private final Function<ChargeEstimateTip.Tip, Drawable> icon;

    /** tap: runs the shown tip's action (DashboardScreen.openTip). */
    EstimateTipSlot(Context context, Ui ui, View anchor, Function<ChargeEstimateTip.Tip, Drawable> icon, Consumer<ChargeEstimateTip.Tip> tap) {
        super(context);
        this.ui = ui;
        this.anchor = anchor;
        this.icon = icon;
        setTag(ChargeEstimateTip.TAG_SLOT);
        setClipChildren(false); setClipToPadding(false);
        pill = ui.text("", 12, false);
        pill.setTag(ChargeEstimateTip.TAG_PILL);
        pill.setTypeface(ui.medium());
        pill.setTextColor(ui.accentText);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setMaxLines(1);
        pill.setMinHeight(ui.dp(48));
        pill.setCompoundDrawablePadding(ui.dp(6));
        int inset = ui.dp(8);
        pill.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 36)),
                new InsetDrawable(ui.shape(Color.TRANSPARENT, 100, Ui.withAlpha(ui.accentText, ui.dark ? 120 : 110)), 0, inset, 0, inset),
                new InsetDrawable(ui.shape(Color.WHITE, 100, 0), 0, inset, 0, inset)));
        pill.setClickable(true); pill.setFocusable(true);
        pill.setOnClickListener(v -> { if (tip != null) tap.accept(tip); });
        addView(pill, new FrameLayout.LayoutParams(-2, -2));
        setVisibility(GONE);
    }

    /** Holds this tip: its icon, its words and its spoken name. Showing or hiding is the charging view's. */
    void hold(ChargeEstimateTip.Tip next) {
        if (next == null || next == tip) return;
        tip = next;
        full = HubText.estimateTip(next);
        brief = HubText.estimateTipShort(next);
        pill.setContentDescription(full.render());
        // The icon grows with the words (Text size), so it stays their companion at large text.
        pill.setCompoundDrawablesRelative(icon.apply(next), null, null, null);
        rung = -2;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int tile = anchor.getMeasuredWidth();
        int room = tile > 0 && tile <= width ? tile : width;
        // The words' room: the pill's room less its padding and the icon beside them.
        int iconPx = ui.dp(Math.round(14 * ui.textScale)), start = ui.dp(10), end = ui.dp(12), gap = ui.dp(6);
        android.text.TextPaint paint = pill.getPaint();
        String fullText = full == null ? "" : full.render(), briefText = brief == null ? "" : brief.render();
        int next = TileRung.first(new float[] {paint.measureText(fullText), paint.measureText(briefText)}, room - start - end - iconPx - gap);
        if (next != rung) {
            rung = next;
            boolean words = next >= 0;
            String text = next == 0 ? fullText : next == 1 ? briefText : "";
            if (TileRung.needsSet(text, pill.getText())) pill.setText(text);
            // The icon alone: centred in a pill of even sides, named by its tooltip as by TalkBack.
            // Above and below: the outline's 8 dp inset plus room inside it, so larger text grows the pill, never past its outline.
            int around = ui.dp(8) + ui.dp(6);
            pill.setPaddingRelative(words ? start : end, around, end, around);
            pill.setCompoundDrawablePadding(words ? gap : 0);
            pill.setTooltipText(words ? null : fullText);
        }
        pill.measure(MeasureSpec.makeMeasureSpec(Math.max(0, room), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int visibility = fits ? VISIBLE : GONE;
        if (pill.getVisibility() != visibility) pill.setVisibility(visibility);
        setMeasuredDimension(width, fits ? pill.getMeasuredHeight() : 0);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // The time tile's start in this slot's own coordinates (both are inside the charging view's rows).
        int x = 0;
        View parent = getParent() instanceof View ? (View) getParent() : null;
        for (View v = anchor; v != null && v != parent; v = v.getParent() instanceof View ? (View) v.getParent() : null) x += v.getLeft();
        x -= getLeft();
        int w = pill.getMeasuredWidth();
        int at = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? x + anchor.getWidth() - w : x;
        at = Math.max(0, Math.min(at, (right - left) - w));
        pill.layout(at, 0, at + w, pill.getMeasuredHeight());
    }
}
