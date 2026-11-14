package com.jjricks.volt;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import java.text.BreakIterator;

/** Keeps narrow-card captions readable: whole words, a short label, then a named icon. */
final class DashboardCaption extends TextView {
    private String normalLabel, shortLabel;
    private final Drawable fallbackIcon;
    private boolean oneLine, iconBeside;
    /** The caption's own size (px), and the least share of it a label may take to stay on one line (1: never). */
    private final float fullPx;
    private float oneLineFloor = 1f;

    DashboardCaption(Context context, Ui ui, String fullName, String normalLabel,
            String shortLabel, Drawable icon) {
        super(context);
        fallbackIcon = icon;
        if (fallbackIcon != null && fallbackIcon.getBounds().isEmpty()) {
            int size = ui.dp(16);
            fallbackIcon.setBounds(0, 0, size, size);
        }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12 * ui.textScale);
        fullPx = getTextSize();
        setTextColor(ui.muted);
        setTypeface(ui.regular());
        setIncludeFontPadding(false);
        setLineSpacing(ui.dp(3), 1);
        setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        setMaxLines(2);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setLabels(fullName, normalLabel, shortLabel);
        setText(this.normalLabel);
    }

    /**
     * One line, never wrapped: the normal label when it fits on one line, otherwise the short
     * label, otherwise the icon alone. The full name stays the description.
     */
    void fitOneLine() {
        oneLine = true;
        setMaxLines(1);
        setEllipsize(android.text.TextUtils.TruncateAt.END);
        requestLayout();
    }

    /**
     * A label too wide for one line takes its shorter rung first, then a little smaller text, down to {@code floor}
     * of its size, before it wraps (Clean's charging tiles, whose inset stays at the Layout Plan's 12 dp). Wider than
     * that, it wraps as usual.
     */
    void shrinkToOneLine(float floor) {
        oneLineFloor = Math.max(.5f, Math.min(1f, floor));
        requestLayout();
    }

    /**
     * Whether a separate icon already shows beside this caption. Then a caption with no rung that fits shows
     * nothing, so the icon stands alone instead of twice; otherwise it shows its own fallback icon.
     */
    void iconBeside(boolean shown) {
        if (iconBeside == shown) return;
        iconBeside = shown;
        requestLayout();
    }

    /** Updates changing captions without losing their measured fallback choices. */
    void setLabels(String fullName, String normalLabel, String shortLabel) {
        String normal = normalLabel == null ? "" : normalLabel;
        String compact = shortLabel == null ? normal : shortLabel;
        String description = fullName == null ? normal : fullName;
        boolean changed = !normal.equals(this.normalLabel) || !compact.equals(this.shortLabel);
        this.normalLabel = normal;
        this.shortLabel = compact;
        setContentDescription(description);
        setTooltipText(description);
        if (changed) requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        boolean unbounded = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED;
        String oneLineText = null;
        if (oneLineFloor < 1f) {
            // On one line, the first that fits: the normal label, then the shorter rung, at full size; then each a
            // little smaller, down to the floor. Neither fits: the full size, and the usual wrap below.
            float px = fullPx;
            if (!unbounded) {
                if (fits(normalLabel, width, 1, fullPx)) oneLineText = normalLabel;
                else if (fits(shortLabel, width, 1, fullPx)) oneLineText = shortLabel;
                else {
                    for (String label : new String[] {normalLabel, shortLabel}) {
                        for (float scale = .95f; oneLineText == null && scale >= oneLineFloor - 1e-4f; scale -= .05f) {
                            if (fits(label, width, 1, fullPx * scale)) { px = fullPx * scale; oneLineText = label; }
                        }
                        if (oneLineText != null) break;
                    }
                }
            }
            if (Math.abs(getTextSize() - px) > .5f) setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        }
        // One line falls to the icon as two lines do; only a caption with no icon at all keeps its short label cut.
        String shown = oneLineText != null ? oneLineText : unbounded || fits(normalLabel, width) ? normalLabel
                : fits(shortLabel, width) || oneLine && fallbackIcon == null && !iconBeside ? shortLabel : null;
        Drawable icon = shown == null && !iconBeside ? fallbackIcon : null;
        // No fitting text with a missing icon is still accessible, without rendering
        // a label broken into individual letters. Callers normally supply an icon.
        String text = shown == null ? "" : shown;
        if (!text.contentEquals(getText())) setText(text);
        if (getCompoundDrawablesRelative()[0] != icon)
            setCompoundDrawablesRelative(icon, null, null, null);
        super.onMeasure(widthSpec, heightSpec);
    }

    private boolean fits(String text, int width) {
        return fits(text, width, oneLine ? 1 : 2, getTextSize());
    }

    /** Whether {@code text} fits {@code width} in at most {@code lines} lines of whole words at {@code px}. */
    private boolean fits(String text, int width, int lines, float px) {
        if (text.isEmpty()) return true;
        if (width <= 0) return false;
        android.text.TextPaint paint = getPaint();
        if (Math.abs(paint.getTextSize() - px) > .01f) {
            paint = new android.text.TextPaint(paint);
            paint.setTextSize(px);
        }
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setIncludePad(getIncludeFontPadding())
                .setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier())
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .build();
        if (layout.getLineCount() > lines) return false;
        // A shipped language's own line-break rules decide where a caption may wrap (between words,
        // or between characters in a language written without spaces). English keeps the check below.
        BreakIterator lineBreaks = Fmt.legacy() ? null : BreakIterator.getLineInstance(Fmt.displayLocale());
        if (lineBreaks != null) lineBreaks.setText(text);
        for (int line = 0; line < layout.getLineCount(); line++) {
            if (layout.getLineMax(line) > width) return false;
            int end = layout.getLineEnd(line);
            if (end >= text.length()) continue;
            if (lineBreaks != null) { if (!lineBreaks.isBoundary(end)) return false; continue; }
            char before = text.charAt(end - 1), after = text.charAt(end);
            if (!Character.isWhitespace(before) && !Character.isWhitespace(after)
                    && before != '-' && before != '/' && before != '\u2010' && before != '\u2013')
                return false;
        }
        return true;
    }
}
