package com.jjricks.volt;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A release's notes drawn from their Markdown (ReleaseNotes) in one selectable TextView with native spans: headings
 * in semibold and a little larger, list items hanging under their bullet or number, quotes with a bar, code in
 * monospace, and a link's address after its text in the muted colour. Nothing here opens a link or loads anything.
 * Should reading ever fail, or the notes hold only markup that draws nothing, the plain notes are shown instead.
 */
final class ReleaseNotesView {
    private ReleaseNotesView() {}

    /** Body text size of the notes, in sp before the app's text scale. */
    static final float SIZE = 14;

    static TextView of(Ui ui, String markdown, String plain) {
        TextView view = ui.text("", SIZE, false);
        try {
            CharSequence shown = spanned(ui, view.getPaint(), ReleaseNotes.parse(markdown));
            // Notes of only markup (a lone rule or an empty fence) draw nothing: show them as written instead.
            view.setText(shown.length() == 0 ? plain : shown);
        } catch (RuntimeException unreadable) {
            view.setText(plain);
        }
        view.setLineSpacing(ui.dp(4), 1);
        return view;
    }

    static CharSequence spanned(Ui ui, Paint paint, List<ReleaseNotes.Block> blocks) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        boolean ruled = false;
        ReleaseNotes.Kind previous = null;
        for (ReleaseNotes.Block block : blocks) {
            if (block.kind == ReleaseNotes.Kind.RULE) { ruled = true; continue; }
            // An empty heading or fence holds only markup; Android refuses an empty exclusive span anyway.
            if (block.text.isEmpty()) continue;
            if (out.length() > 0) gap(out, gapBefore(previous, block.kind, ruled));
            ruled = false; previous = block.kind;
            int start = out.length();
            String prefix = block.marker.isEmpty() ? "" : block.marker + " ";
            out.append(prefix).append(block.text);
            int body = start + prefix.length(), end = out.length();
            switch (block.kind) {
                case HEADING:
                    out.setSpan(new FontSpan(ui.semibold()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(block.level <= 1 ? 1.25f : block.level == 2 ? 1.15f : 1.05f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case BULLET: case NUMBERED: {
                    int first = ui.dp(4) + block.level * ui.dp(16);
                    int rest = first + Math.round(paint.measureText(prefix));
                    hang(out, start, end, first, rest);
                    out.setSpan(new ForegroundColorSpan(ui.muted), start, body, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                }
                case QUOTE:
                    out.setSpan(Build.VERSION.SDK_INT >= 28 ? new QuoteSpan(ui.outline, ui.dp(3), ui.dp(10)) : new QuoteSpan(ui.outline),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(ui.muted), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case CODE:
                    out.setSpan(new FontSpan(Typeface.MONOSPACE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.9f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    hang(out, start, end, ui.dp(8), ui.dp(8));
                    break;
                default:
                    break;
            }
            inline(ui, out, body, block.spans);
        }
        return out;
    }

    /** Bold first, then the rest, so code inside bold text still shows in monospace. */
    private static void inline(Ui ui, SpannableStringBuilder out, int offset, List<ReleaseNotes.Span> spans) {
        List<ReleaseNotes.Span> ordered = new ArrayList<>();
        for (ReleaseNotes.Span span : spans) if (span.style == ReleaseNotes.Style.BOLD) ordered.add(span);
        for (ReleaseNotes.Span span : spans) if (span.style != ReleaseNotes.Style.BOLD) ordered.add(span);
        for (ReleaseNotes.Span span : ordered) {
            int from = offset + span.start, to = offset + span.end;
            switch (span.style) {
                case BOLD: out.setSpan(new FontSpan(ui.semibold()), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break;
                case ITALIC: out.setSpan(new StyleSpan(Typeface.ITALIC), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break;
                case STRIKE: out.setSpan(new StrikethroughSpan(), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break;
                case CODE:
                    out.setSpan(new FontSpan(Typeface.MONOSPACE), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(ui.raised), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case URL:
                    out.setSpan(new ForegroundColorSpan(ui.muted), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.9f), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
            }
        }
    }

    /** Room above a block, in lines: more before a heading or after a rule, less between items of one list. */
    private static float gapBefore(ReleaseNotes.Kind previous, ReleaseNotes.Kind next, boolean ruled) {
        if (ruled || next == ReleaseNotes.Kind.HEADING) return 0.9f;
        boolean listed = next == ReleaseNotes.Kind.BULLET || next == ReleaseNotes.Kind.NUMBERED;
        boolean wasListed = previous == ReleaseNotes.Kind.BULLET || previous == ReleaseNotes.Kind.NUMBERED;
        if (previous == ReleaseNotes.Kind.HEADING) return 0.35f;
        return listed && wasListed ? 0.35f : 0.6f;
    }

    /** A line break, then an empty line of the given height in lines, so blocks sit apart without a full blank line. */
    private static void gap(SpannableStringBuilder out, float lines) {
        out.append('\n');
        int at = out.length();
        out.append('\n');
        out.setSpan(new RelativeSizeSpan(lines), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Each line of a block starts at first and its wrapped lines at rest; a break inside the block starts at rest. */
    private static void hang(SpannableStringBuilder out, int start, int end, int first, int rest) {
        int from = start;
        boolean firstLine = true;
        while (from <= end) {
            int newline = -1;
            for (int i = from; i < end; i++) if (out.charAt(i) == '\n') { newline = i; break; }
            int to = newline < 0 ? end : newline + 1;
            if (to > from) out.setSpan(new LeadingMarginSpan.Standard(firstLine ? first : rest, rest), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            firstLine = false;
            if (newline < 0) break;
            from = newline + 1;
        }
    }

    /** A typeface over a range: Volt's semibold for bold, or monospace for code. */
    private static final class FontSpan extends MetricAffectingSpan {
        private final Typeface typeface;
        FontSpan(Typeface typeface) { this.typeface = typeface; }
        @Override public void updateDrawState(TextPaint paint) { apply(paint); }
        @Override public void updateMeasureState(TextPaint paint) { apply(paint); }
        private void apply(TextPaint paint) { if (typeface != null) paint.setTypeface(typeface); }
    }
}
