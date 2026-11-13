package com.jjricks.volt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * The charge track Clean's charging view draws under the scooter (DashboardScreen.ChargingHero): a full rounded track,
 * filled to the charge, with a tick at the charge limit. Battery & charging's summary card shows it as a view of its
 * own; {@link #draw} is the one drawing both use, so they cannot drift apart.
 */
final class ChargeTrack extends View {
    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double level = Double.NaN, limit = Double.NaN;
    private int fill, tick;

    ChargeTrack(Context context, Ui ui) {
        super(context); this.ui = ui; fill = ui.accent; tick = ui.text;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** level and limit in percent (NaN: none); fill and tick are the fill's and the limit tick's colours. */
    void set(double level, double limit, int fill, int tick) {
        boolean same = Double.compare(level, this.level) == 0 && Double.compare(limit, this.limit) == 0 && fill == this.fill && tick == this.tick;
        this.level = level; this.limit = limit; this.fill = fill; this.tick = tick;
        if (!same) invalidate();
    }

    /** The limit tick's reach above and below the track on this view (the plan: 4.5 dp each way). */
    static float reach(Ui ui) { return ui.dp(4.5f); }

    /** The view's height: the track's thickness and the tick's reach above and below it. */
    static int height(Ui ui) { return ui.dp(8) + 2 * Math.round(reach(ui)); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthSpec), resolveSize(height(ui), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float reach = (getHeight() - ui.dp(8)) / 2f;
        draw(canvas, paint, ui, getWidth(), getHeight() - reach, level, limit, fill, tick, reach, reach);
    }

    /**
     * Draws the track along the bottom of a w by h area: 8 dp thick in the raised colour, filled in fill to level, and
     * a 2 dp tick in tick at limit reaching 3 dp above the track (none at 100% or when limit is NaN), as Clean's
     * charging view draws it. The paint's shader and alpha are reset first.
     */
    static void draw(Canvas canvas, Paint paint, Ui ui, float w, float h, double level, double limit, int fill, int tick) {
        draw(canvas, paint, ui, w, h, level, limit, fill, tick, ui.dp(3), 0);
    }

    /** The same, with the tick reaching above and below pixels over and under the track (ChargeTrackShape.tickSpan). */
    static void draw(Canvas canvas, Paint paint, Ui ui, float w, float h, double level, double limit, int fill, int tick, float above, float below) {
        float thick = ui.dp(8), r = thick / 2, top = h - thick;
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ui.raised);
        canvas.drawRoundRect(0, top, w, h, r, r, paint);
        float end = ChargeTrackShape.fillEnd(w, thick, level);
        if (!Float.isNaN(end)) {
            paint.setColor(fill);
            canvas.drawRoundRect(0, top, end, h, r, r, paint);
        }
        float x = ChargeTrackShape.tickAt(w, limit);
        if (!Float.isNaN(x)) {
            paint.setColor(tick);
            float[] span = ChargeTrackShape.tickSpan(top, thick, above, below);
            canvas.drawRect(x - ui.dp(1), span[0], x + ui.dp(1), span[1], paint);
        }
    }
}
