package com.jjricks.volt;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * One card of Charge history &amp; recordings' "What Volt has learned" (owner's 2.0.1 item 13): a learned charger's
 * curve (LearnedCurveChart) as a small chart, or the empty state when nothing is learned yet.
 * <ul>
 *   <li>the speed: minutes per 1% over a 0–100% axis, a 2 dp line over a faint wash, broken where nothing is learned;</li>
 *   <li>under it the coverage strip: no data (the empty track), one recording (the light blue step, hatched), two or more
 *   agreeing (solid blue), recordings disagree (orange, cross-hatched): pattern and legend as well as colour;</li>
 *   <li>a tick where the lowest finish recording starts;</li>
 *   <li>the legend and the words (recordings used, the learned span, the gaps, the time to full); TalkBack reads all of it
 *   as the card's description.</li>
 * </ul>
 * Colours are the chart's own, validated with the dataviz palette validator against the card surface in both themes
 * (light #FFFFFF, dark #1F201E): the coverage ramp as an ordinal pair, the orange against the blue as categorical.
 * Text wears Ui's text and muted inks, never a series colour. All text comes from the caller (Words), which owns the
 * catalog calls. The chart draws in from the left once, with the app's motion, and not at all with animations off.
 */
final class LearnedCurveCard {
    /** Every word the card shows or speaks. */
    interface Words {
        String title(ChargeSessionStore.Charger charger);
        /** "From 2 recordings". */
        String recordings(int count);
        /** The speed axis' name: "Minutes per 1%". */
        String axis();
        /** The axis' top value: "5 min". */
        String minutes(double value);
        /** An axis label: "50%". */
        String percent(int value);
        /** The legend's name for a kind of coverage. */
        String kind(ChargeSessionStore.Summary.Coverage kind);
        /** The legend's name for the finish tick. */
        String finish();
        /** "Learned 18–100%". */
        String learned(int from, int to);
        /** "A finish recording from 46%". */
        String finishFrom(int from);
        /** "No data below 18%", "No data 50–54%", "No data above 50%", "Too cold or hot to learn 30–40%", "Recordings disagree 30–33%". */
        String gap(LearnedCurveChart.Gap gap);
        /** "And 2 more gaps". */
        String more(int count);
        /** "From 18% to full: 3 h 10 min". */
        String toFull(int from, double minutes);
        /** Spoken coverage: "1 recording, 18 to 45%". */
        String spokenRun(ChargeSessionStore.Summary.Coverage kind, int from, int to);
        /** The empty state's title and words: nothing learned yet, record a charge. */
        String emptyTitle();
        String empty();
        /** A charger's card whose recordings give no usable stretch yet, because they disagree. */
        String unusable();
        /** ... because a fast charge was measured only outside its learning temperatures (too cold or hot). */
        String temperature();
        /** ... because there is too little data (a dropout, or a recording too short to learn a percent from). */
        String tooLittle();
    }
    /** The section's view tag, and each card's (a charger's card adds ":" and the charger's name). */
    static final String SECTION_TAG = "learned-curves", TAG = "learned-curve-card";

    /**
     * The section: a card per learned charger (stock first; LearnedCurveChart.of), or one empty-state card. A charger's
     * card is kept across refreshes, so a refresh with the same curve redraws nothing and moves nothing.
     */
    static final class Section {
        final LinearLayout view;
        private final Context context;
        private final Ui ui;
        private final Words words;
        private final java.util.Map<String, LearnedCurveCard> cards = new java.util.HashMap<>();
        private String shown;

        Section(Context context, Ui ui, Words words) {
            this.context = context; this.ui = ui; this.words = words;
            view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); view.setTag(SECTION_TAG);
        }
        /**
         * The active scooter's summaries (ScooterService.learnedChargeSummaries); empty: the empty state. Null (still
         * loading) keeps what is shown, or shows nothing yet, so a load never flashes the empty state.
         */
        void show(List<ChargeSessionStore.Summary> summaries) {
            if (summaries == null) return;
            List<LearnedCurveChart> charts = LearnedCurveChart.of(summaries);
            StringBuilder order = new StringBuilder();
            for (LearnedCurveChart chart : charts) order.append(chart.charger).append(' ');
            String next = charts.isEmpty() ? "empty" : order.toString();
            if (!next.equals(shown)) {
                view.removeAllViews();
                if (charts.isEmpty()) view.addView(card("empty").card);
                else for (LearnedCurveChart chart : charts) view.addView(card(chart.charger.name()).card);
                shown = next;
            }
            if (charts.isEmpty()) card("empty").show(null);
            else for (LearnedCurveChart chart : charts) card(chart.charger.name()).show(chart);
        }
        private LearnedCurveCard card(String key) {
            LearnedCurveCard card = cards.get(key);
            if (card == null) {
                card = new LearnedCurveCard(context, ui, words); card.card.setTag(TAG + ":" + key.toLowerCase(java.util.Locale.ROOT));
                cards.put(key, card);
            }
            return card;
        }
    }

    final LinearLayout card;
    private final Ui ui;
    private final Words words;
    private final Plot plot;
    private final TextView title, sub, axis;
    private final LinearLayout legend, notes;
    private String signature;

    LearnedCurveCard(Context context, Ui ui, Words words) {
        this.ui = ui; this.words = words;
        card = ui.card(); card.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        card.setTag(TAG);
        title = ui.text("", 16, true); title.setTag("learned-curve-title");
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        sub = ui.caption(""); sub.setTag("learned-curve-recordings"); sub.setPadding(0, ui.dp(3), 0, 0);
        card.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        axis = ui.caption(""); axis.setTag("learned-curve-axis"); axis.setPadding(0, ui.dp(12), 0, ui.dp(2));
        card.addView(axis, new LinearLayout.LayoutParams(-1, -2));
        plot = new Plot(context); plot.setTag("learned-curve-plot");
        card.addView(plot, new LinearLayout.LayoutParams(-1, -2));
        legend = new Flow(context); legend.setTag("learned-curve-legend"); legend.setPadding(0, ui.dp(10), 0, 0);
        card.addView(legend, new LinearLayout.LayoutParams(-1, -2));
        notes = new LinearLayout(context); notes.setOrientation(LinearLayout.VERTICAL); notes.setTag("learned-curve-notes");
        card.addView(notes, new LinearLayout.LayoutParams(-1, -2));
        // One description for the whole card: its parts are not read one by one.
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); card.setFocusable(true);
        for (View part : new View[]{title, sub, axis, plot, legend, notes}) part.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    /** Shows chart, or the empty state for null. Nothing changes (and nothing moves) when what it shows is the same. */
    void show(LearnedCurveChart chart) {
        String next = chart == null ? "empty" : chart.signature();
        if (next.equals(signature)) return;
        boolean first = signature == null; signature = next;
        List<String> spoken = new ArrayList<>();
        legend.removeAllViews(); notes.removeAllViews();
        if (chart == null) {
            title.setText(words.emptyTitle()); sub.setText(words.empty());
            spoken.add(title.getText().toString()); spoken.add(sub.getText().toString());
            axis.setVisibility(View.GONE);
        } else {
            boolean speed = chart.any();
            title.setText(words.title(chart.charger)); sub.setText(words.recordings(chart.recordings));
            spoken.add(title.getText().toString()); spoken.add(sub.getText().toString());
            axis.setVisibility(speed ? View.VISIBLE : View.GONE); axis.setText(words.axis());
            List<String> lines = new ArrayList<>();
            if (!speed) {
                // Nothing usable: say why, and still show a finish recording, which Charge done by can use.
                LearnedCurveChart.Why why = chart.why();
                lines.add(why == LearnedCurveChart.Why.DISAGREE ? words.unusable() : why == LearnedCurveChart.Why.TEMPERATURE ? words.temperature() : words.tooLittle());
            }
            if (speed || chart.finishFrom >= 0 || chart.why() == LearnedCurveChart.Why.DISAGREE)
                for (ChargeSessionStore.Summary.Coverage kind : chart.legend()) legend.addView(key(kind, words.kind(kind)));
            if (chart.finishFrom >= 0) legend.addView(key(null, words.finish()));
            if (speed) lines.add(words.learned(chart.learnedFrom(), chart.learnedTo()));
            if (chart.finishFrom >= 0) lines.add(words.finishFrom(chart.finishFrom));
            for (LearnedCurveChart.Gap gap : chart.gaps) lines.add(words.gap(gap));
            if (chart.unlisted > 0) lines.add(words.more(chart.unlisted));
            if (chart.toFullFrom >= 0) lines.add(words.toFull(chart.toFullFrom, chart.toFullMinutes));
            for (String line : lines) note(line);
            spoken.addAll(lines);
            for (LearnedCurveChart.Run run : chart.runs) if (run.coverage != ChargeSessionStore.Summary.Coverage.NONE) spoken.add(words.spokenRun(run.coverage, run.from, run.to));
        }
        StringBuilder description = new StringBuilder();
        for (String part : spoken) { if (part == null || part.isEmpty()) continue; if (description.length() > 0) description.append(". "); description.append(part); }
        card.setContentDescription(description);
        legend.setVisibility(legend.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        plot.set(chart, first);
    }

    private void note(String line) {
        TextView text = ui.text(line, 13, false); text.setTextColor(ui.muted); text.setPadding(0, ui.dp(notes.getChildCount() == 0 ? 10 : 3), 0, 0);
        notes.addView(text, new LinearLayout.LayoutParams(-1, -2));
    }

    /** A legend entry: the strip's own swatch (or the finish tick, for kind null) beside its name in muted ink. */
    private View key(ChargeSessionStore.Summary.Coverage kind, String name) {
        LinearLayout item = new LinearLayout(card.getContext()); item.setGravity(android.view.Gravity.CENTER_VERTICAL);
        item.setTag(kind == null ? "learned-curve-key-finish" : "learned-curve-key-" + kind.name().toLowerCase(java.util.Locale.ROOT));
        View swatch = new View(card.getContext()) {
            @Override protected void onDraw(Canvas canvas) {
                if (kind == null) plot.drawTick(canvas, getWidth() / 2f, 0, getHeight(), true);
                else plot.drawSegment(canvas, kind, 0, 0, getWidth(), getHeight(), 1f);
            }
        };
        item.addView(swatch, new LinearLayout.LayoutParams(ui.dp(kind == null ? 10 : 18), ui.dp(10)));
        TextView text = ui.text(name, 12, false); text.setTextColor(ui.muted); text.setPadding(ui.dp(6), 0, 0, 0);
        item.addView(text, new LinearLayout.LayoutParams(-2, -2));
        return item;
    }

    /** The chart's colours: the coverage ramp's two blues (the line is the darker), its orange, per theme. */
    static int robust(boolean dark) { return Color.parseColor(dark ? "#3987E5" : "#2A78D6"); }
    static int provisional(boolean dark) { return Color.parseColor(dark ? "#184F95" : "#86B6EF"); }
    static int disagreeing(boolean dark) { return Color.parseColor(dark ? "#D95926" : "#EB6834"); }

    /** The speed line, the coverage strip, the finish tick and the axes, drawn with Canvas. */
    final class Plot extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();
        private LearnedCurveChart chart;
        private float reveal = 1;
        private ValueAnimator motion;

        Plot(Context context) {
            super(context);
            stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND);
            label.setTypeface(ui.regular()); label.setTextSize(ui.sp(11)); label.setColor(ui.muted);
        }
        void set(LearnedCurveChart next, boolean first) {
            chart = next; requestLayout();
            if (motion != null) motion.cancel();
            // The chart draws in from the left the first time it shows something learned, with the app's motion only.
            if (first && next != null && next.any() && Motion.enabled()) {
                reveal = 0; motion = ValueAnimator.ofFloat(0, 1); motion.setDuration(Motion.ENTER_MS); motion.setInterpolator(Motion.STANDARD);
                motion.addUpdateListener(a -> { reveal = (float) a.getAnimatedValue(); invalidate(); }); motion.start();
            } else { reveal = 1; invalidate(); }
        }
        boolean speed() { return chart != null && chart.any(); }
        float labelHeight() { Paint.FontMetrics m = label.getFontMetrics(); return m.descent - m.ascent; }
        float stripHeight() { return ui.dp(10); }
        float plotHeight() { return speed() ? ui.dp(112) : 0; }
        /** Space over the plot for the finish tick's head and the top value's label; with no plot, a gap under the words. */
        float headroom() { return speed() ? Math.max(ui.dp(9), labelHeight() / 2) : ui.dp(12); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            float h = headroom() + plotHeight() + (speed() ? ui.dp(6) : 0) + stripHeight() + ui.dp(4) + labelHeight();
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), (int) Math.ceil(h));
        }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); if (motion != null) { motion.cancel(); reveal = 1; } }

        @Override protected void onDraw(Canvas canvas) {
            boolean speed = speed();
            float gutter = 0;
            String top = speed ? words.minutes(chart.scaleMax) : null, zero = speed ? words.minutes(0) : null;
            if (speed) gutter = Math.max(label.measureText(top), label.measureText(zero)) + ui.dp(6);
            float left = gutter, right = getWidth() - ui.dp(1), width = Math.max(1, right - left);
            float plotTop = headroom(), plotBottom = plotTop + plotHeight();
            float stripTop = speed ? plotBottom + ui.dp(6) : headroom(), stripBottom = stripTop + stripHeight();
            if (speed) {
                // Recessive frame: a hairline at the top value and the baseline, their values beside them in muted ink.
                stroke.setStrokeWidth(Math.max(1, ui.dp(.75f))); stroke.setColor(ui.outline);
                canvas.drawLine(left, plotTop, right, plotTop, stroke); canvas.drawLine(left, plotBottom, right, plotBottom, stroke);
                label.setTextAlign(Paint.Align.LEFT);
                float half = (label.descent() + label.ascent()) / 2;
                canvas.drawText(top, 0, plotTop - half, label); canvas.drawText(zero, 0, plotBottom - half, label);
                canvas.save(); canvas.clipRect(left - ui.dp(2), 0, left + (width + ui.dp(4)) * reveal, getHeight());
                drawSpeed(canvas, left, width, plotTop, plotBottom);
                canvas.restore();
            }
            // The strip: each run its own segment, a 2 dp surface gap between neighbours.
            int alpha = Math.round(255 * Math.min(1, reveal * 1.5f));
            for (LearnedCurveChart.Run run : runs()) {
                float a = left + width * run.from / 100f, b = left + width * run.to / 100f;
                if (run.from > 0) a += ui.dp(1); if (run.to < 100) b -= ui.dp(1);
                if (b - a < 1) b = a + 1;
                drawSegment(canvas, run.coverage, a, stripTop, b, stripBottom, alpha / 255f);
            }
            if (chart != null && chart.finishFrom >= 0) {
                float x = left + width * chart.finishFrom / 100f;
                if (speed) drawTick(canvas, x, plotTop - headroom(), plotBottom, false);
                else drawTick(canvas, x, stripTop - ui.dp(8), stripTop - ui.dp(2), true);
            }
            // The percent axis: 0, 25, 50, 75 and 100%, or only 0, 50 and 100% (then 0 and 100%) where they would touch.
            label.setColor(ui.muted);
            float baseline = stripBottom + ui.dp(4) - label.ascent();
            for (int[] set : new int[][]{{0, 25, 50, 75, 100}, {0, 50, 100}, {0, 100}}) {
                if (!fits(set, left, width) && set.length > 2) continue;
                for (int p : set) {
                    String text = words.percent(p);
                    label.setTextAlign(p == 0 ? Paint.Align.LEFT : p == 100 ? Paint.Align.RIGHT : Paint.Align.CENTER);
                    canvas.drawText(text, p == 0 ? left : p == 100 ? right : left + width * p / 100f, baseline, label);
                }
                break;
            }
        }
        private List<LearnedCurveChart.Run> runs() {
            if (chart != null) return chart.runs;
            List<LearnedCurveChart.Run> none = new ArrayList<>(); none.add(new LearnedCurveChart.Run(0, 100, ChargeSessionStore.Summary.Coverage.NONE)); return none;
        }
        private boolean fits(int[] set, float left, float width) {
            float end = -Float.MAX_VALUE;
            for (int p : set) {
                float w = label.measureText(words.percent(p)), x = left + width * p / 100f;
                float a = p == 0 ? x : p == 100 ? x - w : x - w / 2;
                if (a < end + ui.dp(8)) return false;
                end = a + w;
            }
            return true;
        }
        /** The speed: a faint wash under a 2 dp line, each learned stretch on its own (a gap breaks the line). */
        private void drawSpeed(Canvas canvas, float left, float width, float top, float bottom) {
            int color = robust(ui.dark);
            for (LearnedCurveChart.Run run : chart.learned) {
                path.reset();
                float firstX = left + width * run.from / 100f;
                for (int bin = run.from; bin < run.to; bin++) {
                    float y = bottom - (float) (chart.minutes[bin] / chart.scaleMax) * (bottom - top);
                    float x = left + width * (bin + .5f) / 100f;
                    if (bin == run.from) path.moveTo(firstX, y);
                    path.lineTo(x, y);
                    if (bin == run.to - 1) path.lineTo(left + width * run.to / 100f, y);
                }
                Path area = new Path(path); area.lineTo(left + width * run.to / 100f, bottom); area.lineTo(firstX, bottom); area.close();
                fill.setStyle(Paint.Style.FILL); fill.setColor(Ui.withAlpha(color, ui.dark ? 46 : 30)); canvas.drawPath(area, fill);
                stroke.setColor(color); stroke.setStrokeWidth(ui.dp(2)); canvas.drawPath(path, stroke);
            }
        }
        /** One coverage segment: the empty track, hatched light blue, solid blue, or cross-hatched orange. */
        void drawSegment(Canvas canvas, ChargeSessionStore.Summary.Coverage kind, float a, float top, float b, float bottom, float opacity) {
            float radius = Math.min((bottom - top) / 2, ui.dp(4));
            rect.set(a, top, b, bottom);
            fill.setStyle(Paint.Style.FILL);
            int base = kind == ChargeSessionStore.Summary.Coverage.NONE ? ui.raised : kind == ChargeSessionStore.Summary.Coverage.PROVISIONAL ? provisional(ui.dark)
                    : kind == ChargeSessionStore.Summary.Coverage.ROBUST ? robust(ui.dark) : Ui.withAlpha(disagreeing(ui.dark), 90);
            fill.setColor(base); fill.setAlpha(Math.round(Color.alpha(base) * opacity));
            canvas.drawRoundRect(rect, radius, radius, fill);
            if (kind != ChargeSessionStore.Summary.Coverage.PROVISIONAL && kind != ChargeSessionStore.Summary.Coverage.DISAGREEING) return;
            // The pattern: 45° lines for one recording; both directions for recordings that disagree.
            canvas.save(); path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW); canvas.clipPath(path);
            stroke.setStrokeWidth(ui.dp(1)); stroke.setStrokeCap(Paint.Cap.BUTT);
            int ink = kind == ChargeSessionStore.Summary.Coverage.PROVISIONAL ? robust(ui.dark) : disagreeing(ui.dark);
            stroke.setColor(ink); stroke.setAlpha(Math.round(255 * opacity));
            float step = ui.dp(4), h = bottom - top;
            for (float x = a - h; x < b + h; x += step) {
                canvas.drawLine(x, bottom, x + h, top, stroke);
                if (kind == ChargeSessionStore.Summary.Coverage.DISAGREEING) canvas.drawLine(x, top, x + h, bottom, stroke);
            }
            stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setAlpha(255);
            canvas.restore();
        }
        /** The finish tick: a small head in the text's ink over a muted hairline down to the speed's baseline. legend: the head alone, centred. */
        void drawTick(Canvas canvas, float x, float top, float bottom, boolean legend) {
            float half = ui.dp(4.5f), head = ui.dp(6);
            float y0 = legend ? (bottom - top - head) / 2 + top : top;
            path.reset(); path.moveTo(x - half, y0); path.lineTo(x + half, y0); path.lineTo(x, y0 + head); path.close();
            fill.setStyle(Paint.Style.FILL); fill.setColor(ui.text); canvas.drawPath(path, fill);
            if (legend) return;
            stroke.setColor(ui.muted); stroke.setStrokeWidth(Math.max(1, ui.dp(1)));
            canvas.drawLine(x, y0 + head, x, bottom, stroke);
        }
    }

    /** The legend's rows: entries wrap whole, never inside a name. */
    private final class Flow extends LinearLayout {
        Flow(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), available = Math.max(1, width - getPaddingLeft() - getPaddingRight());
            int x = 0, y = getPaddingTop(), row = 0, gapX = ui.dp(14), gapY = ui.dp(6);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > available) { y += row + gapY; x = 0; row = 0; }
                x += child.getMeasuredWidth() + gapX; row = Math.max(row, child.getMeasuredHeight());
            }
            setMeasuredDimension(width, y + row + getPaddingBottom());
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int available = getWidth() - getPaddingLeft() - getPaddingRight(), x = 0, y = getPaddingTop(), row = 0, gapX = ui.dp(14), gapY = ui.dp(6);
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
                if (x > 0 && x + w > available) { y += row + gapY; x = 0; row = 0; }
                int at = rtl ? getWidth() - getPaddingRight() - x - w : getPaddingLeft() + x;
                child.layout(at, y, at + w, y + h); x += w + gapX; row = Math.max(row, h);
            }
        }
    }
}
