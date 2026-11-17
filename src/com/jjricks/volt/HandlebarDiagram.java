package com.jjricks.volt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The handlebar for "Unlock without phone" (owner, Nov 17 2026), drawn from the UT5 MAX as the rider sees it: the left
 * switch (turn-signal rocker in the middle, light button above it, M/power button below it, boost trigger underneath),
 * the dashboard, and the brake lever ahead of the right grip. The parts the guide's steps use are in the accent with
 * numbered markers matching the steps (1 rocker, 2 brake lever, 3 M/power); everything else is the theme's greys. It is
 * drawn on a {@link #WIDTH} x {@link #HEIGHT} grid scaled to the view, so it stays crisp at any size, and draws no words
 * (the button markings are shapes); its screen-reader text is the caller's. Never mirrored: it pictures real hardware.
 */
public final class HandlebarDiagram extends View {
    /** The design grid, in units. */
    public static final float WIDTH = 322, HEIGHT = 114;
    /** Design points inside each accent part (rocker, brake lever, M/power) and the neutral light button, for tests. */
    public static final float[][] ACCENT_POINTS = {{93, 66}, {300, 32}, {107, 75}}, NEUTRAL_POINTS = {{109, 39}};
    private static final float BAR = 62, LINE = 1.4f, FINE = 1.1f;
    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int maxWidth;

    public HandlebarDiagram(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        maxWidth = ui.dp(420);
        paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** As wide as it is given; as tall as the drawing at that width, up to {@code maxWidth}'s height. */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int drawn = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? maxWidth : Math.min(width, maxWidth);
        int height = Math.round(drawn * HEIGHT / WIDTH);
        setMeasuredDimension(MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? drawn : width, resolveSize(height, heightSpec));
    }

    /** The scale from design units to pixels; the drawing is centred at this scale. */
    public float scale() { return Math.min(getWidth() / WIDTH, getHeight() / HEIGHT); }
    /** A design point in this view's pixels. */
    public float[] toView(float x, float y) {
        float s = scale();
        return new float[] {(getWidth() - WIDTH * s) / 2 + x * s, (getHeight() - HEIGHT * s) / 2 + y * s};
    }

    @Override protected void onDraw(Canvas canvas) {
        if (getWidth() == 0 || getHeight() == 0) return;
        float s = scale();
        canvas.save();
        canvas.translate((getWidth() - WIDTH * s) / 2, (getHeight() - HEIGHT * s) / 2);
        canvas.scale(s, s);
        int body = ui.dark ? ui.outline : ui.raised, button = ui.dark ? ui.raised : ui.surface;
        int edge = ui.muted, faint = Ui.withAlpha(ui.muted, ui.dark ? 110 : 95), accent = ui.accent, on = ui.onAccent;

        // Left grip with its bar end, the clamp collar beside the switch, and the sleeve to the dashboard.
        part(canvas, round(6, BAR - 15, 68, BAR + 15, 9), body, edge, LINE);
        part(canvas, round(2, BAR - 11, 10, BAR + 11, 3), body, edge, LINE);
        for (float x = 16; x < 64; x += 6) line(canvas, x, BAR - 12, x - 5, BAR + 12, faint, 1);
        part(canvas, round(128, BAR - 13, 144, BAR + 13, 4), body, edge, LINE);
        part(canvas, poly(144, BAR - 15, 172, BAR - 17, 172, BAR + 17, 144, BAR + 15), body, edge, LINE);
        // Right side: sleeve, collar, grip and bar end.
        part(canvas, poly(242, BAR - 17, 264, BAR - 15, 264, BAR + 15, 242, BAR + 17), body, edge, LINE);
        part(canvas, round(264, BAR - 12, 276, BAR + 12, 4), body, edge, LINE);
        part(canvas, round(274, BAR - 15, 314, BAR + 15, 9), body, edge, LINE);
        part(canvas, round(310, BAR - 11, 318, BAR + 11, 3), body, edge, LINE);
        for (float x = 282; x < 310; x += 6) line(canvas, x + 5, BAR - 12, x, BAR + 12, faint, 1);
        // 2: the brake lever, ahead of the right grip (above it, as the rider looks down), pivoting at the collar.
        Path lever = new Path();
        lever.moveTo(265, 34); lever.lineTo(274, 30); lever.lineTo(314, 25); lever.quadTo(319, 25, 319, 30); lever.lineTo(318, 34);
        lever.quadTo(317, 37, 313, 37); lever.lineTo(278, 40); lever.lineTo(272, 47); lever.lineTo(265, 47); lever.close();
        part(canvas, lever, accent, accent, LINE);
        dot(canvas, 269, 41, 2.2f, on);

        // The dashboard between the sleeves: its display and the chevron light strip on each side.
        Path dash = new Path();
        dash.moveTo(172, 34); dash.lineTo(240, 34); dash.quadTo(246, 34, 246, 40); dash.lineTo(244, 84); dash.quadTo(243, 92, 235, 92);
        dash.lineTo(177, 92); dash.quadTo(169, 92, 168, 84); dash.lineTo(166, 40); dash.quadTo(166, 34, 172, 34); dash.close();
        part(canvas, dash, body, edge, LINE);
        part(canvas, round(184, 44, 228, 78, 6), button, edge, 1);
        stroke(canvas, open(180, 42, 172, 60, 180, 78), faint, 3);
        stroke(canvas, open(232, 42, 240, 60, 232, 78), faint, 3);
        paint.setStyle(Paint.Style.FILL); paint.setColor(faint);
        for (int i = 0; i < 4; i++) { box.set(189, 70 - i * 6, 196, 74 - i * 6); canvas.drawRoundRect(box, 1, 1, paint); }

        // The left switch. The boost trigger first: it comes out from under the housing and sticks out to the left.
        Path trigger = new Path();
        trigger.moveTo(68, 98); trigger.quadTo(68, 93, 73, 93); trigger.lineTo(96, 93); trigger.lineTo(98, 90); trigger.lineTo(120, 90);
        trigger.lineTo(120, 110); trigger.lineTo(80, 108.5f); trigger.quadTo(72, 108, 70, 103); trigger.close();
        part(canvas, trigger, button, edge, FINE);
        Path ridge = new Path(); ridge.moveTo(96, 93); ridge.lineTo(91.5f, 98); ridge.lineTo(116, 98);
        stroke(canvas, ridge, faint, 1);
        Path housing = new Path();
        housing.moveTo(67, 40); housing.quadTo(69, 32, 80, 30); housing.lineTo(128, 31); housing.quadTo(136, 32, 136, 40);
        housing.lineTo(134, 101); housing.quadTo(133, 110, 124, 110); housing.lineTo(117, 110); housing.lineTo(117, 92);
        housing.lineTo(99, 92); housing.lineTo(70, 78); housing.quadTo(65, 76, 65, 71); housing.close();
        part(canvas, housing, body, edge, LINE);
        // Light button, top right, its lower-left corner cut; the headlight mark (a D with three beams).
        Path light = new Path();
        light.moveTo(106, 36); light.lineTo(127, 36); light.quadTo(131, 36, 131, 40); light.lineTo(131, 46); light.quadTo(131, 50, 127, 50);
        light.lineTo(112, 50); light.lineTo(106, 44); light.close();
        part(canvas, light, button, edge, FINE);
        Path lamp = new Path();
        lamp.moveTo(120, 39.5f); lamp.lineTo(122, 39.5f); lamp.quadTo(126, 39.5f, 126, 43); lamp.quadTo(126, 46.5f, 122, 46.5f); lamp.lineTo(120, 46.5f); lamp.close();
        stroke(canvas, lamp, edge, FINE);
        for (float y : new float[] {40.5f, 43, 45.5f}) line(canvas, 114.5f, y, 118, y, edge, FINE);
        // 1: the turn-signal rocker, a long hexagon whose right end sits between the two buttons, an outline arrow each side.
        part(canvas, poly(70, 60, 77, 50, 112, 50, 120, 60, 112, 70, 77, 70), accent, accent, FINE);
        line(canvas, 95, 51.5f, 95, 68.5f, Ui.withAlpha(on, 115), 1);
        stroke(canvas, poly(79, 60, 85, 54.5f, 85, 57.5f, 91, 57.5f, 91, 62.5f, 85, 62.5f, 85, 65.5f), on, FINE);
        stroke(canvas, poly(111, 60, 105, 54.5f, 105, 57.5f, 99, 57.5f, 99, 62.5f, 105, 62.5f, 105, 65.5f), on, FINE);
        // 3: the M/power button below the rocker, marked M, a slash and the power symbol.
        part(canvas, round(104, 73, 131, 87, 4), accent, accent, FINE);
        stroke(canvas, open(108.5f, 83, 108.5f, 77, 111.5f, 81, 114.5f, 77, 114.5f, 83), on, FINE);
        line(canvas, 118.5f, 76.5f, 116.5f, 83.5f, on, FINE);
        Path power = new Path(); box.set(124.5f - 3.3f, 80.06f - 3.3f, 124.5f + 3.3f, 80.06f + 3.3f);
        power.arcTo(box, -48.2f, 276.4f, true);
        stroke(canvas, power, on, FINE);
        line(canvas, 124.5f, 76, 124.5f, 80, on, FINE);

        // Step markers, each with a leader to its part.
        marker(canvas, 1, 50, 16, 76, 52);
        marker(canvas, 2, 296, 12, 296, 28.5f);
        marker(canvas, 3, 152, 102, 131, 82);
        canvas.restore();
    }

    /** A filled shape with its outline. */
    private void part(Canvas canvas, Path path, int fill, int outline, float width) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(fill); canvas.drawPath(path, paint);
        stroke(canvas, path, outline, width);
    }
    private void stroke(Canvas canvas, Path path, int color, float width) {
        paint.setStyle(Paint.Style.STROKE); paint.setColor(color); paint.setStrokeWidth(width); canvas.drawPath(path, paint);
    }
    private void line(Canvas canvas, float x1, float y1, float x2, float y2, int color, float width) {
        paint.setStyle(Paint.Style.STROKE); paint.setColor(color); paint.setStrokeWidth(width); canvas.drawLine(x1, y1, x2, y2, paint);
    }
    private void dot(Canvas canvas, float x, float y, float r, int color) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); canvas.drawCircle(x, y, r, paint);
    }
    private void marker(Canvas canvas, int number, float x, float y, float tx, float ty) {
        line(canvas, x, y, tx, ty, ui.accent, 1.2f);
        dot(canvas, tx, ty, 1.8f, ui.accent);
        dot(canvas, x, y, 10.5f, ui.accent);
        paint.setStyle(Paint.Style.FILL); paint.setColor(ui.onAccent); paint.setTypeface(ui.bold());
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(12.5f);
        canvas.drawText(String.valueOf(number), x, y - (paint.descent() + paint.ascent()) / 2, paint);
    }
    private static Path round(float left, float top, float right, float bottom, float r) {
        Path path = new Path(); path.addRoundRect(new RectF(left, top, right, bottom), r, r, Path.Direction.CW); return path;
    }
    /** A closed polygon through x, y pairs. */
    private static Path poly(float... xy) { Path path = open(xy); path.close(); return path; }
    /** An open polyline through x, y pairs. */
    private static Path open(float... xy) {
        Path path = new Path(); path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        return path;
    }
}
