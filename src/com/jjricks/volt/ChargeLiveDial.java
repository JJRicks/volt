package com.jjricks.volt;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;

/**
 * The battery dial in the live card's right-hand slot (owner's choice of three concepts, 2026-11-08): the level as an
 * arc and a number. It paints its own dark disc because a notification bitmap can't know the card's theme.
 * Drawn again only when what it shows changes, so a card that reposts for a new finish time or power reuses it.
 */
final class ChargeLiveDial {
    private static final int SIZE = 192;
    private static final float INSET = 15f, STROKE = 15f;
    /** Inside the ring, with a little air. */
    private static final float ROOM = SIZE - 2 * INSET - 29f;
    private static final float TEXT = 64f, TEXT_FLOOR = 50f;

    private int shownPercent = -1;
    private String shownLabel;
    private Icon shown;

    /** The dial for a level, or null if it can't be drawn (the card then stays the plain one with the level in the title). */
    Icon icon(int percent) {
        String label = T.fmtPercent(Fmt.integer(percent));
        if (shown != null && percent == shownPercent && label.equals(shownLabel)) return shown;
        try {
            Icon drawn = draw(percent, label, Fmt.integer(percent));
            shown = drawn;
            shownPercent = percent;
            shownLabel = label;
            return drawn;
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            shown = null;
            return null;
        }
    }

    private static Icon draw(int percent, String label, String number) {
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF1E1F1C);
        canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, paint);
        RectF ring = new RectF(INSET, INSET, SIZE - INSET, SIZE - INSET);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFF4A4C46);
        canvas.drawArc(ring, -90, 360, false, paint);
        paint.setColor(Color.rgb(0xE3, 0x2B, 0x2F));
        canvas.drawArc(ring, -90, 3.6f * percent, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(TEXT);
        ChargeLiveState.DialFit fit = ChargeLiveState.dialFit(label, number, TEXT, TEXT_FLOOR, ROOM, paint::measureText);
        paint.setTextSize(fit.size);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(fit.text, SIZE / 2f, SIZE / 2f - (metrics.ascent + metrics.descent) / 2f, paint);
        return Icon.createWithBitmap(bitmap);
    }
}
