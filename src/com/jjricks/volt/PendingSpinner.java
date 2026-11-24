package com.jjricks.volt;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;

/**
 * The pending spinner drawn over a control the scooter hasn't confirmed yet (PendingControl decides when): a faint ring
 * with a turning arc, in the app's spinner style (MainActivity's Spinner). With animations off (the system's Remove
 * animations, an animator scale of 0) or held by an emulator validation (EndlessMotion) it is the same picture standing
 * still, so the control still reads as waiting.
 */
final class PendingSpinner {
    private PendingSpinner() { }

    /**
     * Draws the spinner as a ring inside bounds and returns whether it turns, in which case the caller asks for the next
     * frame (postInvalidateOnAnimation, while it is shown).
     * @param track the faint full ring's colour, or 0 for none
     */
    static boolean draw(Canvas canvas, RectF bounds, Paint paint, float stroke, int color, int track) {
        Paint.Style style = paint.getStyle();
        Paint.Cap cap = paint.getStrokeCap();
        float width = paint.getStrokeWidth();
        RectF arc = new RectF(bounds);
        arc.inset(stroke / 2f, stroke / 2f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (track != 0) { paint.setColor(track); canvas.drawOval(arc, paint); }
        boolean motion = Ui.endlessMotionEnabled();
        paint.setColor(color);
        // One turn a second, as the app's other spinners; still, the arc rests at the top.
        canvas.drawArc(arc, motion ? (SystemClock.uptimeMillis() % 1000) * .36f : -90, 100, false, paint);
        paint.setStyle(style); paint.setStrokeCap(cap); paint.setStrokeWidth(width);
        return motion;
    }
}
