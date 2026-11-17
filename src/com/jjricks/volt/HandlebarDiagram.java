package com.jjricks.volt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The handlebar for "Unlock without phone" as the rider sees it (owner, Nov 17 2026: the old drawing "is old and made by
 * Codex and sucks"; "draw all the models that we have references for"). One original drawing per set of manual-unlock
 * pictures the official app shows ({@link ModelRegistry#unlockHandlebar}), checked against the owner's UT5 MAX photos,
 * its manual's handlebar figure and those pictures; nothing is traced.
 * <ul>
 *   <li>UT5, ST5, NT5 M/S and eCross: the left grip with the brake lever ahead of it, the left switch (turn signals,
 *       power, and the trigger underneath) and the dashboard on its stem; the right half of the bar is left out. The UT5
 *       has a one-piece rocker, the ST5 and NT5 M/S two arrow buttons in one recess, the eCross a faceted switch with
 *       diagonal arrow buttons and a power-only button. Each has its own dashboard.</li>
 *   <li>NT3 and the standard pictures: the whole bar, since power is on the right: the brake lever ahead of the left
 *       grip, a small turn-signal toggle under the left clamp, the dashboard, and the power button on a collar at the
 *       right grip beside the thumb throttle (the NT3 has a lever ahead of the right grip too).</li>
 * </ul>
 * The parts the guide's steps use are in the accent with numbered markers matching the steps (1 turn signal, 2 brake
 * lever, 3 power); everything else is the theme's greys. Drawn on a {@link #gridWidth()} x {@link #HEIGHT} grid scaled to
 * the view, so it stays crisp at any size, and draws no words (the button markings are shapes); its screen-reader text is
 * the caller's. Never mirrored: it pictures real hardware.
 */
public final class HandlebarDiagram extends View {
    /** The design grid's height, in units; its width depends on the handlebar ({@link #gridWidth()}). */
    public static final float HEIGHT = 114;
    private static final float BAR = 62, LINE = 1.4f, FINE = 1.1f, SWITCH_WIDTH = 256, WHOLE_WIDTH = 336;
    private final Ui ui;
    private final ModelRegistry.Handlebar handlebar;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int maxWidth;
    private int body, button, edge, faint, accent, on;

    public HandlebarDiagram(Context context, Ui ui, ModelRegistry.Handlebar handlebar) {
        super(context);
        this.ui = ui; this.handlebar = handlebar;
        maxWidth = ui.dp(420);
        paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public ModelRegistry.Handlebar handlebar() { return handlebar; }
    /** True for the drawings of the whole bar (power on the right). */
    private boolean whole() { return handlebar == ModelRegistry.Handlebar.NT3 || handlebar == ModelRegistry.Handlebar.STANDARD; }
    /** The design grid's width, in units. */
    public float gridWidth() { return whole() ? WHOLE_WIDTH : SWITCH_WIDTH; }
    /** Design points inside the accent parts, in step order (turn signal, brake lever, power), for tests. */
    public float[][] accentPoints() {
        switch (handlebar) {
            case NT3: case STANDARD: return new float[][] {{80.5f, 85}, {40, 40}, {250.8f, 50.8f}};
            case ECROSS: return new float[][] {{75, 74.5f}, {40, 40}, {110.5f, 66.5f}};
            case ST5: case NT5MS: return new float[][] {{92.5f, 56}, {40, 40}, {107, 75}};
            default: return new float[][] {{93, 66}, {40, 40}, {107, 75}};
        }
    }
    /** Design points inside neutral parts (the light button, trigger or clamp), for tests. */
    public float[][] neutralPoints() {
        switch (handlebar) {
            case NT3: case STANDARD: return new float[][] {{74, 62}};
            case ECROSS: return new float[][] {{100, 104}};
            default: return new float[][] {{109, 39}};
        }
    }

    /** As wide as it is given; as tall as the drawing at that width, up to {@code maxWidth}'s height. */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int drawn = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? maxWidth : Math.min(width, maxWidth);
        int height = Math.round(drawn * HEIGHT / gridWidth());
        setMeasuredDimension(MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? drawn : width, resolveSize(height, heightSpec));
    }

    /** The scale from design units to pixels; the drawing is centred at this scale. */
    public float scale() { return Math.min(getWidth() / gridWidth(), getHeight() / HEIGHT); }
    /** A design point in this view's pixels. */
    public float[] toView(float x, float y) {
        float s = scale();
        return new float[] {(getWidth() - gridWidth() * s) / 2 + x * s, (getHeight() - HEIGHT * s) / 2 + y * s};
    }

    @Override protected void onDraw(Canvas canvas) {
        if (getWidth() == 0 || getHeight() == 0) return;
        float s = scale();
        canvas.save();
        canvas.translate((getWidth() - gridWidth() * s) / 2, (getHeight() - HEIGHT * s) / 2);
        canvas.scale(s, s);
        body = ui.dark ? ui.outline : ui.raised; button = ui.dark ? ui.raised : ui.surface;
        edge = ui.muted; faint = Ui.withAlpha(ui.muted, ui.dark ? 110 : 95); accent = ui.accent; on = ui.onAccent;
        if (whole()) wholeBar(canvas); else switchBar(canvas);
        canvas.restore();
    }

    // ---- UT5, ST5, NT5 M/S and eCross: the left half, with the switch -----------------------------------------------

    private void switchBar(Canvas canvas) {
        // Left grip with its bar end, the clamp collar beside the switch, and the sleeve to the dashboard.
        leftGrip(canvas);
        part(canvas, round(128, BAR - 13, 144, BAR + 13, 4), body, edge, LINE);
        part(canvas, poly(144, BAR - 15, 172, BAR - 17, 172, BAR + 17, 144, BAR + 15), body, edge, LINE);
        // 2: the brake lever, ahead of the left grip (above it, as the rider looks down); its perch is under the switch.
        leftLever(canvas, 70);

        // The stem under the dashboard, and a stub of the bar beyond it (the right half is not pictured).
        paint.setStyle(Paint.Style.FILL); paint.setColor(body);
        canvas.drawPath(poly(190, 88, 222, 88, 225, HEIGHT, 187, HEIGHT), paint);
        line(canvas, 190, 88, 187, HEIGHT, edge, LINE); line(canvas, 222, 88, 225, HEIGHT, edge, LINE);
        paint.setStyle(Paint.Style.FILL); paint.setColor(body);
        canvas.drawPath(poly(242, BAR - 17, 255, BAR - 16, 255, BAR + 16, 242, BAR + 17), paint);
        line(canvas, 242, BAR - 17, 255, BAR - 16, edge, LINE); line(canvas, 242, BAR + 17, 255, BAR + 16, edge, LINE);
        switchDashboard(canvas);

        if (handlebar == ModelRegistry.Handlebar.ECROSS) { ecrossSwitch(canvas); return; }
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
        if (handlebar == ModelRegistry.Handlebar.UT5) {
            // 1: the turn-signal rocker, a long hexagon whose right end sits between the two buttons, an outline arrow each side.
            part(canvas, poly(70, 60, 77, 50, 112, 50, 120, 60, 112, 70, 77, 70), accent, accent, FINE);
            line(canvas, 95, 51.5f, 95, 68.5f, Ui.withAlpha(on, 115), 1);
            stroke(canvas, leftArrow(79, 60), on, FINE);
            stroke(canvas, rightArrow(111, 60), on, FINE);
        } else {
            // 1: ST5 and NT5 M/S: two turn-signal buttons side by side in one recess.
            part(canvas, round(70, 51, 121, 71, 5), button, edge, FINE);
            part(canvas, round(73, 54, 94.5f, 68, 3), accent, accent, FINE);
            part(canvas, round(96.5f, 54, 118, 68, 3), accent, accent, FINE);
            stroke(canvas, leftArrow(78, 61), on, FINE);
            stroke(canvas, rightArrow(113, 61), on, FINE);
        }
        // 3: the M/power button below the turn signals, marked M, a slash and the power symbol.
        part(canvas, round(104, 73, 131, 87, 4), accent, accent, FINE);
        stroke(canvas, open(108.5f, 83, 108.5f, 77, 111.5f, 81, 114.5f, 77, 114.5f, 83), on, FINE);
        line(canvas, 118.5f, 76.5f, 116.5f, 83.5f, on, FINE);
        powerMark(canvas, 124.5f, 80.06f, 3.3f);

        // Step markers, each with a leader to its part.
        if (handlebar == ModelRegistry.Handlebar.UT5) marker(canvas, 1, 90, 12, 88, 52);
        else marker(canvas, 1, 84, 12, 84, 54.5f);
        marker(canvas, 2, 34, 14, 34, 40);
        marker(canvas, 3, 152, 102, 131, 82);
    }

    /** The dashboard of each switch drawing, with its display. */
    private void switchDashboard(Canvas canvas) {
        switch (handlebar) {
            case ST5: {
                // A wide colour screen.
                part(canvas, round(160, 36, 252, 88, 7), body, edge, LINE);
                part(canvas, round(167, 42, 245, 82, 4), button, edge, 1);
                ring(canvas, 206, 62, 13);
                bars(canvas, 173);
                break;
            }
            case NT5MS: {
                // A hexagonal display.
                part(canvas, poly(172, 34, 240, 34, 252, 62, 240, 90, 172, 90, 160, 62), body, edge, LINE);
                part(canvas, poly(177, 40, 235, 40, 245, 62, 235, 84, 177, 84, 167, 62), button, edge, 1);
                ring(canvas, 206, 62, 13);
                bars(canvas, 178);
                break;
            }
            case ECROSS: {
                // A large square display.
                part(canvas, round(168, 30, 244, 92, 6), body, edge, LINE);
                part(canvas, round(174, 36, 238, 86, 3), button, edge, 1);
                ring(canvas, 206, 61, 16);
                break;
            }
            default: {
                // The UT5 MAX: its display and the chevron light strip on each side.
                Path dash = new Path();
                dash.moveTo(172, 34); dash.lineTo(240, 34); dash.quadTo(246, 34, 246, 40); dash.lineTo(244, 84); dash.quadTo(243, 92, 235, 92);
                dash.lineTo(177, 92); dash.quadTo(169, 92, 168, 84); dash.lineTo(166, 40); dash.quadTo(166, 34, 172, 34); dash.close();
                part(canvas, dash, body, edge, LINE);
                part(canvas, round(184, 44, 228, 78, 6), button, edge, 1);
                stroke(canvas, open(180, 42, 172, 60, 180, 78), faint, 3);
                stroke(canvas, open(232, 42, 240, 60, 232, 78), faint, 3);
                bars(canvas, 189);
            }
        }
    }

    /** The eCross's faceted switch: diagonal arrow buttons, a power-only button, and a paddle trigger underneath. */
    private void ecrossSwitch(Canvas canvas) {
        part(canvas, poly(70, 96, 76, 92, 110, 92, 120, 94, 120, 110, 84, 110, 72, 104), button, edge, FINE);
        part(canvas, poly(66, 44, 78, 30, 126, 30, 136, 40, 136, 98, 126, 110, 118, 110, 118, 94, 98, 94, 70, 80, 65, 72), body, edge, LINE);
        // 1: the turn signals, left-down and right-up, each its own button.
        part(canvas, round(72, 58, 96, 77, 4), accent, accent, FINE);
        part(canvas, round(94, 39, 118, 58, 4), accent, accent, FINE);
        canvas.save(); canvas.rotate(-45, 84, 67.5f); stroke(canvas, leftArrow(78, 67.5f), on, FINE); canvas.restore();
        canvas.save(); canvas.rotate(-45, 106, 48.5f); stroke(canvas, rightArrow(112, 48.5f), on, FINE); canvas.restore();
        // 3: the power button, marked with the power symbol only.
        part(canvas, round(108, 64, 132, 84, 5), accent, accent, FINE);
        powerMark(canvas, 120, 75, 3.6f);
        marker(canvas, 1, 100, 12, 104, 41);
        marker(canvas, 2, 34, 14, 34, 40);
        marker(canvas, 3, 152, 102, 130, 80);
    }

    // ---- NT3 and the standard pictures: the whole bar, power on the right ------------------------------------------

    private void wholeBar(Canvas canvas) {
        boolean nt3 = handlebar == ModelRegistry.Handlebar.NT3;
        // The bar tube behind everything, then the stem and the dashboard.
        part(canvas, round(88, BAR - 8, 252, BAR + 8, 3), body, edge, LINE);
        float stemTop = nt3 ? 78 : 96;
        paint.setStyle(Paint.Style.FILL); paint.setColor(body);
        canvas.drawPath(poly(158, stemTop, 182, stemTop, 185, HEIGHT, 155, HEIGHT), paint);
        line(canvas, 158, stemTop, 155, HEIGHT, edge, LINE); line(canvas, 182, stemTop, 185, HEIGHT, edge, LINE);
        if (nt3) {
            // The NT3's wide display.
            part(canvas, round(126, 38, 214, 80, 8), body, edge, LINE);
            part(canvas, round(133, 44, 207, 74, 4), button, edge, 1);
            ring(canvas, 150, 59, 8);
            paint.setStyle(Paint.Style.FILL); paint.setColor(faint);
            for (int i = 0; i < 4; i++) { box.set(170 + i * 8, 64, 176 + i * 8, 68); canvas.drawRoundRect(box, 1, 1, paint); }
        } else {
            // The standard pictures' tall display with a pointed foot.
            Path dash = new Path();
            dash.moveTo(146, 22); dash.lineTo(194, 22); dash.quadTo(200, 22, 200, 28); dash.lineTo(200, 84); dash.lineTo(186, 100);
            dash.lineTo(154, 100); dash.lineTo(140, 84); dash.lineTo(140, 28); dash.quadTo(140, 22, 146, 22); dash.close();
            part(canvas, dash, body, edge, LINE);
            part(canvas, round(147, 29, 193, 82, 4), button, edge, 1);
            ring(canvas, 170, 50, 11);
            paint.setStyle(Paint.Style.FILL); paint.setColor(faint);
            for (int i = 0; i < 4; i++) { box.set(152 + i * 9.5f, 70, 159 + i * 9.5f, 75); canvas.drawRoundRect(box, 1, 1, paint); }
        }

        // Left: the grip, the brake lever ahead of it on its perch, the clamp, and the turn-signal toggle under it.
        leftGrip(canvas);
        leftLever(canvas, 90);
        part(canvas, round(70, BAR - 17, 96, BAR + 17, 5), body, edge, LINE);
        part(canvas, round(78, 30, 96, 46, 4), body, edge, LINE);
        part(canvas, round(74, BAR + 14, 98, BAR + 32, 5), body, edge, LINE);
        // 1: the turn-signal toggle, pushed left or right (a two-way arrow).
        part(canvas, round(78, BAR + 17, 94, BAR + 29, 6), accent, accent, FINE);
        line(canvas, 82.5f, 88.5f, 89.5f, 81.5f, on, FINE);
        stroke(canvas, open(86.5f, 81.5f, 89.5f, 81.5f, 89.5f, 84.5f), on, FINE);
        stroke(canvas, open(82.5f, 85.5f, 82.5f, 88.5f, 85.5f, 88.5f), on, FINE);

        // Right: the grip, the NT3's lever ahead of it, the collar with the power button, and the thumb throttle.
        part(canvas, round(268, BAR - 15, 330, BAR + 15, 9), body, edge, LINE);
        part(canvas, round(326, BAR - 11, 334, BAR + 11, 3), body, edge, LINE);
        for (float x = 276; x < 322; x += 6) line(canvas, x + 5, BAR - 12, x, BAR + 12, faint, 1);
        if (nt3) {
            Path lever = new Path();
            lever.moveTo(246, 33); lever.lineTo(312, 37.5f); lever.quadTo(326, 38.5f, 330, 41); lever.quadTo(333, 44, 329, 46); lever.lineTo(314, 45.5f);
            lever.lineTo(246, 41); lever.close();
            part(canvas, lever, button, edge, LINE);
        }
        part(canvas, poly(248, 80, 268, 80, 274, 86, 270, 92, 252, 92), button, edge, FINE);
        part(canvas, round(244, BAR - 18, 268, BAR + 18, 5), body, edge, LINE);
        // 3: the power button on the collar.
        part(canvas, round(249, 49, 263, 63, 3), accent, accent, FINE);
        powerMark(canvas, 256, 56.6f, 3.2f);

        marker(canvas, 1, 118, 102, 92, 85);
        marker(canvas, 2, 34, 14, 34, 40);
        marker(canvas, 3, 256, 14, 256, 49);
    }

    // ---- Shared parts ----------------------------------------------------------------------------------------------

    /** The left grip with its bar end and ribs. */
    private void leftGrip(Canvas canvas) {
        part(canvas, round(6, BAR - 15, 68, BAR + 15, 9), body, edge, LINE);
        part(canvas, round(2, BAR - 11, 10, BAR + 11, 3), body, edge, LINE);
        for (float x = 16; x < 64; x += 6) line(canvas, x, BAR - 12, x - 5, BAR + 12, faint, 1);
    }
    /** 2: the brake lever ahead of the left grip, from its perch at x out to past the bar end. */
    private void leftLever(Canvas canvas, float perch) {
        Path lever = new Path();
        lever.moveTo(perch, 34); lever.lineTo(24, 37.5f); lever.quadTo(10, 38.5f, 6, 41); lever.quadTo(3, 44, 7, 46); lever.lineTo(22, 45.5f);
        lever.lineTo(perch, 42); lever.close();
        part(canvas, lever, accent, accent, LINE);
    }
    /** An outline arrow pointing left, its tip at x. */
    private static Path leftArrow(float tip, float y) {
        return poly(tip, y, tip + 6, y - 5.5f, tip + 6, y - 2.5f, tip + 12, y - 2.5f, tip + 12, y + 2.5f, tip + 6, y + 2.5f, tip + 6, y + 5.5f);
    }
    /** An outline arrow pointing right, its tip at x. */
    private static Path rightArrow(float tip, float y) {
        return poly(tip, y, tip - 6, y - 5.5f, tip - 6, y - 2.5f, tip - 12, y - 2.5f, tip - 12, y + 2.5f, tip - 6, y + 2.5f, tip - 6, y + 5.5f);
    }
    /** The power symbol: a ring open at the top with a bar through the gap. */
    private void powerMark(Canvas canvas, float x, float y, float r) {
        Path power = new Path(); box.set(x - r, y - r, x + r, y + r);
        power.arcTo(box, -48.2f, 276.4f, true);
        stroke(canvas, power, on, FINE);
        line(canvas, x, y - r * 1.23f, x, y - 0.06f, on, FINE);
    }
    /** A display's faint speed ring. */
    private void ring(Canvas canvas, float x, float y, float r) {
        paint.setStyle(Paint.Style.STROKE); paint.setColor(faint); paint.setStrokeWidth(2.5f); canvas.drawCircle(x, y, r, paint);
    }
    /** A display's faint battery bars, stacked at x. */
    private void bars(Canvas canvas, float x) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(faint);
        for (int i = 0; i < 4; i++) { box.set(x, 70 - i * 6, x + 7, 74 - i * 6); canvas.drawRoundRect(box, 1, 1, paint); }
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
