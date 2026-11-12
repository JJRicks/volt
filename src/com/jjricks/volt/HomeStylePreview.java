package com.jjricks.volt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A small drawing of one Home style (hub layout plan, PR 8): a phone outline holding Clean's fixed parts (a header,
 * the top card, the Lock bar, four round actions and a list) or a grid of widget cards. Shapes only; its name for
 * screen readers comes from HubText. Used by Settings › Home style and Clean's Edit sheet.
 */
final class HomeStylePreview extends View {
    private final Ui ui;
    private final boolean clean;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    HomeStylePreview(Context context, Ui ui, boolean clean) {
        super(context);
        this.ui = ui;
        this.clean = clean;
        setContentDescription(HubText.homeStylePreview(clean).render());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        // A phone's proportions, never taller than 200 dp.
        int height = Math.min(ui.dp(200), Math.round(width * 1.6f));
        setMeasuredDimension(width, MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : height);
    }

    @Override protected void onDraw(Canvas canvas) {
        float h = getHeight(), w = Math.min(getWidth(), h / 1.6f);
        if (w <= 0 || h <= 0) return;
        float left = (getWidth() - w) / 2f, pad = w * .08f, r = ui.dp(10);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ui.dark ? ui.bg : ui.raised);
        box.set(left, 0, left + w, h);
        canvas.drawRoundRect(box, r, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ui.dp(1.5f));
        paint.setColor(ui.outline);
        canvas.drawRoundRect(box, r, r, paint);
        paint.setStyle(Paint.Style.FILL);
        float x0 = left + pad, x1 = left + w - pad, y = pad;
        // Header: the name and the two round buttons.
        paint.setColor(ui.muted);
        block(canvas, x0, y, x0 + (x1 - x0) * .5f, y + h * .03f, 2);
        if (clean) {
            float d = h * .035f;
            canvas.drawCircle(x1 - d / 2, y + d / 2, d / 2, paint);
            canvas.drawCircle(x1 - d * 1.8f, y + d / 2, d / 2, paint);
        }
        y += h * .07f;
        if (clean) {
            // The top area: the battery card with its red bar.
            paint.setColor(ui.surface);
            block(canvas, x0, y, x1, y + h * .3f, 6);
            paint.setColor(ui.text);
            block(canvas, x0 + pad, y + pad, x0 + (x1 - x0) * .35f, y + pad + h * .07f, 3);
            paint.setColor(ui.accent);
            block(canvas, x0 + pad, y + h * .24f, x1 - pad, y + h * .26f, 2);
            y += h * .33f;
            // The Lock bar.
            paint.setColor(ui.surface);
            block(canvas, x0, y, x1, y + h * .07f, 6);
            y += h * .1f;
            // Four round quick actions.
            float step = (x1 - x0) / 4f, dot = Math.min(step * .32f, h * .035f);
            paint.setColor(ui.accent);
            for (int i = 0; i < 4; i++) canvas.drawCircle(x0 + step * (i + .5f), y + dot, dot, paint);
            y += dot * 2 + h * .04f;
            // The subjects.
            paint.setColor(ui.surface);
            while (y + h * .05f < h - pad) {
                block(canvas, x0, y, x1, y + h * .045f, 3);
                y += h * .065f;
            }
        } else {
            // Widget cards of different sizes in two columns.
            float gap = w * .04f;
            float[][] cards = {{0, 0, 2, 2}, {2, 0, 2, 1}, {2, 1, 2, 1}, {0, 2, 4, 1}, {0, 3, 1, 1}, {1, 3, 1, 1}, {2, 3, 2, 2}, {0, 4, 2, 1}};
            float cell = (x1 - x0 + gap) / 4f, rowHeight = (h - y - pad + gap) / 5f;
            for (int i = 0; i < cards.length; i++) {
                float[] c = cards[i];
                paint.setColor(i == 0 ? ui.accent : ui.surface);
                float cx = x0 + c[0] * cell, cy = y + c[1] * rowHeight;
                block(canvas, cx, cy, cx + c[2] * cell - gap, cy + c[3] * rowHeight - gap, 5);
            }
        }
    }

    private void block(Canvas canvas, float l, float t, float r, float b, float radiusDp) {
        box.set(l, t, r, b);
        canvas.drawRoundRect(box, ui.dp(radiusDp), ui.dp(radiusDp), paint);
    }
}
