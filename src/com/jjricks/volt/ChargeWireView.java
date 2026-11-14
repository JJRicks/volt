package com.jjricks.volt;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AnimationUtils;
import android.widget.ScrollView;

/**
 * The animated charging picture for a model in ChargeWireModels (the contributor's charging animation, 14 Nov 2026 on
 * #383), on Clean Home's charging view and Widgets Home's takeover: the cut-out scooter with its battery bay's dark
 * panel, procedural cells and grey cable drawn over it, and green pulses running down the cable that light the cells
 * when they land.
 *
 * <p>Two layers fill the stage under the percentage. {@link #picture} draws the resting picture (the cut-out, the
 * panel, the unlit cells and the grey cable) as one bitmap, baked in software once per placement at the screen's own
 * pixels (so it is anti-aliased the same on every GPU, and the cut-out is averaged down rather than sampled).
 * {@link #live} is redrawn every frame while {@link #start} runs it: each lit column of cells as one strip of a
 * pre-rendered lit pack at its light, the pack's bloom, and the pulses' comets (ChargeWireComet). Nothing is
 * allocated per frame. Both draw in the cut-out's pixels through one scale and offset (the placement), so the
 * geometry is built once (ChargeWire).
 */
final class ChargeWireView extends ChargePicture {
    /**
     * For android-tests' ChargePictureValidation only: a frozen moment of the animation, in seconds, drawn whether or
     * not it runs (the emulator's animations are off), and the charging power its mode follows (null: the reported
     * one). NaN, the default, leaves the picture to the app.
     */
    static volatile double tourSeconds = Double.NaN;
    static volatile Double tourWatts;

    /** The bundled cut-out for each model in ChargeWireModels. */
    static int resource(String key) {
        switch (key) {
            case "ut5max": return R.drawable.ut5max_charge;
            case "ut5ultrax": return R.drawable.ut5ultrax_charge;
            default: return 0;
        }
    }

    private static final float RING = 2.2f;

    final ChargeWireModels.Model model;
    final View picture, live;
    private final Bitmap cut;
    private final float density;
    private ChargeWire wire;
    private ChargeWireModels.Placement placement = new ChargeWireModels.Placement(0, 0, 0);
    private Double watts;
    private boolean running;
    private long startMs;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), strip = new Paint();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path panel = new Path(), cable = new Path(), comet = new Path();
    private final RectF oval = new RectF();
    private final float[] point = new float[2], ends = new float[2];
    private final ChargeWirePulses.State state = new ChargeWirePulses.State();
    private final float[] levels = new float[ChargeWire.HALF_COLUMNS];
    /** Each row's gradients (they run top to bottom over a cell's top or wall, and every cell in a row shares them). */
    private final LinearGradient[] topShader = new LinearGradient[ChargeWire.ROWS], wallShader = new LinearGradient[ChargeWire.ROWS],
            litShader = new LinearGradient[ChargeWire.ROWS];
    /** The cells' indices by half-pitch column (lit copies draw column by column). */
    private int[] byColumn;
    private int[] columnStart;
    private final ChargeWireComet comets;
    private RadialGradient bloom;
    private float bloomRatio, bloomRadius;
    private final int cableEdge, cableCore;

    /** The baked resting picture and where its top-left pixel goes in the layer; null until drawn at a placement. */
    private Bitmap baked;
    private int bakedLeft, bakedTop;
    /** The lit pack, one strip per half-pitch column, and each strip's place in it and in the layer. */
    private Bitmap litPack;
    private final Rect[] litFrom = new Rect[ChargeWire.HALF_COLUMNS], litTo = new Rect[ChargeWire.HALF_COLUMNS];
    /** The screen's edge the cable runs off (the nearest scroller's right edge, layer pixels; NaN: none known). */
    private float edge = Float.NaN;
    /** The cable's end needs working out again (a new placement). */
    private boolean cableStale = true;
    /** The cable's fade along x in cut-out pixels (both infinite: it runs off the edge). */
    private double fadeStart = Double.POSITIVE_INFINITY, fadeEnd = Double.POSITIVE_INFINITY;

    ChargeWireView(Context context, Ui ui, ChargeWireModels.Model model, Bitmap cut) {
        super(model.key);
        this.model = model;
        this.cut = cut;
        density = context.getResources().getDisplayMetrics().density;
        boolean dark = ui.dark;
        int green = ui.ok;
        // The resting cable: light grey on the dark page (the cut-out's own), a darker grey on the light page so it
        // stays visible there; a lighter core along its middle keeps it round.
        cableEdge = Color.parseColor(dark ? "#B0B1AE" : "#8C8E89");
        cableCore = Color.parseColor(dark ? "#CBCBC9" : "#A5A7A2");
        // The comets' colours per theme (the prototype's in dark).
        comets = new ChargeWireComet((float) model.cableWidth, green,
                dark ? Color.parseColor("#79DB95") : ChargeWireComet.mix(green, Color.WHITE, .15f),
                dark ? Color.parseColor("#D6FBE0") : ChargeWireComet.mix(green, Color.WHITE, .6f),
                dark ? Color.parseColor("#9BE8B2") : ChargeWireComet.mix(green, Color.WHITE, .3f),
                dark ? Color.parseColor("#F4FFF6") : ChargeWireComet.mix(green, Color.WHITE, .85f),
                dark ? 1 : .7f);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        fill.setStyle(Paint.Style.FILL);
        wire = new ChargeWire(model);
        cells(green);
        for (int c = 0; c < ChargeWire.HALF_COLUMNS; c++) { litFrom[c] = new Rect(); litTo[c] = new Rect(); }
        picture = new LayerView(context) {
            @Override protected void onDraw(Canvas canvas) { drawPicture(canvas); }
            @Override protected void onDetachedFromWindow() { reclip(); super.onDetachedFromWindow(); }
        };
        live = new LayerView(context) {
            @Override protected void onDraw(Canvas canvas) { drawLive(canvas); }
        };
    }

    /** The per-row cell gradients, the columns' cell lists, the panel and the bloom: all in cut-out pixels, once. */
    private void cells(int green) {
        ChargeWire w = wire;
        for (int r = 0; r < ChargeWire.ROWS; r++) {
            float cy = w.rowY[r];
            topShader[r] = new LinearGradient(0, cy - w.ry, 0, cy + w.ry,
                    new int[] {Color.parseColor("#D9DBD6"), Color.parseColor("#BFC1BC"), Color.parseColor("#A4A6A1")},
                    new float[] {0, .55f, 1}, Shader.TileMode.CLAMP);
            wallShader[r] = new LinearGradient(0, cy, 0, cy + w.wall,
                    Color.parseColor("#7D7F7A"), Color.parseColor("#4E504C"), Shader.TileMode.CLAMP);
            litShader[r] = new LinearGradient(0, cy - w.ry, 0, cy + w.ry,
                    new int[] {Color.parseColor("#F6FFF8"), Color.parseColor("#C9F5D6"), Color.parseColor("#8EE0A7")},
                    new float[] {0, .55f, 1}, Shader.TileMode.CLAMP);
        }
        byColumn = new int[w.cellCount];
        columnStart = new int[ChargeWire.HALF_COLUMNS + 1];
        int k = 0;
        for (int c = 0; c < ChargeWire.HALF_COLUMNS; c++) {
            columnStart[c] = k;
            for (int i = 0; i < w.cellCount; i++) if (w.cellColumn[i] == c) byColumn[k++] = i;
        }
        columnStart[ChargeWire.HALF_COLUMNS] = k;
        // The panel: its top, right and bottom straight with small round corners, its left edge leaning.
        double top = w.panelTop(), bottom = w.panelBottom(), right = w.panelRight(), r = model.panelRadius;
        panel.reset();
        panel.moveTo((float) (w.panelLeft(top) + r), (float) top);
        panel.lineTo((float) (right - r), (float) top);
        panel.quadTo((float) right, (float) top, (float) right, (float) (top + r));
        panel.lineTo((float) right, (float) (bottom - r));
        panel.quadTo((float) right, (float) bottom, (float) (right - r), (float) bottom);
        panel.lineTo((float) (w.panelLeft(bottom) + r), (float) bottom);
        panel.quadTo((float) w.panelLeft(bottom), (float) bottom, (float) w.panelLeft(bottom - r), (float) (bottom - r));
        panel.lineTo((float) w.panelLeft(top + r), (float) (top + r));
        panel.quadTo((float) w.panelLeft(top), (float) top, (float) (w.panelLeft(top) + r), (float) top);
        panel.close();
        // The pack's green bloom: the prototype's ellipse over the cells blurred by 26 px, as one soft radial fade.
        double[] box = model.cells;
        float bx = (float) ((box[2] - box[0]) * 0.55 + 39), by = (float) ((box[3] - box[1]) * 0.62 + 39);
        bloom = new RadialGradient(0, 0, bx, new int[] {Ui.withAlpha(green, 255), Ui.withAlpha(green, 150), Ui.withAlpha(green, 0)},
                new float[] {0, .55f, 1}, Shader.TileMode.CLAMP);
        bloomRatio = by / bx;
        bloomRadius = bx;
        cablePath();
    }

    /** The grey cable's path: from the plug's tip to the end of the straight run. */
    private void cablePath() {
        ChargeWire w = wire;
        cable.reset();
        w.point(w.cableStart, point);
        cable.moveTo(point[0], point[1]);
        for (int i = w.after(w.cableStart); i < w.count; i++) cable.lineTo(w.xs[i], w.ys[i]);
    }

    @Override View[] layers() { return new View[] {picture, live}; }

    @Override int shade() { return ChargePercentShade.HALO; }

    @Override void invalidateLive() { live.invalidate(); }

    /** The reported charging power (W), which picks the pulses' mode; null while unknown. */
    @Override void watts(Double value) { watts = value; }

    /** Runs the animation from its start (each frame then needs invalidateLive()). */
    @Override void start() {
        if (running) return;
        running = true;
        startMs = AnimationUtils.currentAnimationTimeMillis();
        live.invalidate();
    }

    /** Stops it: the picture rests (grey cable and cells, no pulses). */
    @Override void stop() {
        if (!running) return;
        running = false;
        live.invalidate();
    }

    private int width, floor;
    private float box;

    @Override void size(int stageWidth, int tyres, float box) {
        if (width == stageWidth && floor == tyres && this.box == box) return;
        width = stageWidth; floor = tyres; this.box = box;
        ChargeWireModels.Placement next = ChargeWireModels.place(model, width, floor, box);
        if (next.scale != placement.scale || next.left != placement.left || next.top != placement.top) {
            placement = next;
            cableStale = true;
            forget();
            picture.invalidate();
            live.invalidate();
        }
    }

    /** Drops the baked bitmaps (a new placement or cable end bakes them again on the next draw). */
    private void forget() {
        if (baked != null) { baked.recycle(); baked = null; }
        if (litPack != null) { litPack.recycle(); litPack = null; }
    }

    // ---- the cable's end and the parents' clipping ----

    /** The parents {@link #clip} let the layers draw past, to clip again. */
    private final java.util.ArrayList<ViewGroup> unclipped = new java.util.ArrayList<>();

    /**
     * At rest (ChargePictureFit.atRest) and where the cable runs off the screen's edge, every parent up to the
     * nearest scroller (the scroller included, so its content column's margins do not clip) lets the layers draw
     * outside its bounds; the scroller's own parent still clips it, so nothing reaches past the scroller or into
     * another pane. Any other moment (hidden, animating open or closed, editing, charging stopped, detached) they
     * clip again, as on the base branch. Without a scroller nothing changes.
     */
    @Override void clip(boolean atRest) {
        if (placement.scale <= 0) atRest = false;
        updateCable();
        boolean open = ChargePictureFit.unclipped(atRest && picture.isAttachedToWindow(), Double.isInfinite(fadeEnd) && !Float.isNaN(edge));
        if (open == !unclipped.isEmpty()) return;
        if (!open) { reclip(); return; }
        int from = unclipped.size();
        for (ViewParent p = picture.getParent(); p instanceof ViewGroup; p = p.getParent()) {
            ViewGroup g = (ViewGroup) p;
            if (g.getClipChildren()) { g.setClipChildren(false); unclipped.add(g); }
            if (p instanceof ScrollView) return;
        }
        // No scroller: nothing is opened.
        for (int i = unclipped.size() - 1; i >= from; i--) unclipped.remove(i).setClipChildren(true);
    }

    /** Clips again what {@link #clip} opened. */
    private void reclip() {
        for (ViewGroup g : unclipped) g.setClipChildren(true);
        unclipped.clear();
    }

    /** The scroller's right edge in the layers' pixels, or NaN when there is none (no allocation). */
    private float scrollerEdge() {
        float x = 0;
        View v = picture;
        for (ViewParent p = v.getParent(); p instanceof ViewGroup; p = p.getParent()) {
            x += v.getLeft() - ((ViewGroup) p).getScrollX();
            v = (View) p;
            if (p instanceof ScrollView) return ((ScrollView) p).getWidth() - x;
        }
        return Float.NaN;
    }

    /**
     * Where the cable ends for the current layout (ChargePictureFit.cableEnd): off the screen's edge (the straight run
     * long enough to reach past it, so a pulse enters off screen) or faded out a short way past the picture.
     */
    private void updateCable() {
        float s = placement.scale;
        if (s <= 0) return;
        float e = scrollerEdge();
        if (!cableStale && (e == edge || (Float.isNaN(e) && Float.isNaN(edge)))) return;
        edge = e; cableStale = false;
        ChargePictureFit.cableEnd(placement.left + model.width * s, e, density, ends);
        double start = Double.isInfinite(ends[0]) ? Double.POSITIVE_INFINITY : (ends[0] - placement.left) / s;
        double end = Double.isInfinite(ends[1]) ? Double.POSITIVE_INFINITY : (ends[1] - placement.left) / s;
        // The run: past the edge by 40 px, or past the fade's end.
        double reach = Double.isInfinite(end) ? (Float.isNaN(e) ? ChargeWire.EXT : (e - placement.left) / s - model.width + 40 / s) : end - model.width + 2 / s;
        if (start != fadeStart || end != fadeEnd || Math.abs(reach - wire.ext) > 4 && (reach > ChargeWire.EXT || wire.ext > ChargeWire.EXT)) {
            fadeStart = start; fadeEnd = end;
            if (Math.abs(reach - wire.ext) > 4) { wire = new ChargeWire(model, reach); cablePath(); }
            forget();
            picture.invalidate();
            live.invalidate();
        }
    }

    // ---- the resting picture, baked ----

    private void drawPicture(Canvas canvas) {
        float s = placement.scale;
        if (s <= 0 || cut == null) return;
        updateCable();
        if (baked == null) bake();
        if (baked != null) canvas.drawBitmap(baked, bakedLeft, bakedTop, null);
    }

    /**
     * The resting picture into one bitmap at the layer's pixels: the cut-out (averaged down), the dark panel, the
     * unlit cells and the grey cable to its end, fading out there where it does not run off the screen.
     */
    private void bake() {
        float s = placement.scale;
        ChargeWire w = wire;
        w.point(w.length, point);
        float endX = placement.left + point[0] * s, endY = placement.top + point[1] * s;
        float pad = (float) Math.ceil(model.cableWidth * s) + 2;
        int left = (int) Math.floor(placement.left) - 2, top = (int) Math.floor(placement.top) - 2;
        int right = (int) Math.ceil(Math.max(placement.left + model.width * s, endX)) + 2;
        int bottom = (int) Math.ceil(Math.max(placement.top + model.height * s, endY + pad)) + 2;
        if (right <= left || bottom <= top) return;
        Bitmap next;
        try { next = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888); }
        catch (RuntimeException | OutOfMemoryError unavailable) { return; }
        Canvas c = new Canvas(next);
        c.translate(placement.left - left, placement.top - top);
        ChargePicture.drawDownscaled(c, cut, s, bitmapPaint);
        c.scale(s, s);
        fill.setShader(null);
        fill.setColor(0xFF1C1D1B);
        fill.setAlpha(255);
        c.drawPath(panel, fill);
        drawCells(c);
        // The grey cable over the cut-out's own, along the same centre line, then on to its end.
        stroke.setShader(null);
        stroke.setAlpha(255);
        stroke.setColor(cableEdge);
        stroke.setStrokeWidth((float) model.cableWidth);
        c.drawPath(cable, stroke);
        stroke.setColor(cableCore);
        stroke.setStrokeWidth((float) model.cableWidth * .45f);
        c.drawPath(cable, stroke);
        if (!Double.isInfinite(fadeEnd)) {
            // Past the picture (only the cable is there): erase it smoothly from fadeStart to fadeEnd.
            Paint erase = new Paint();
            erase.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            double mid = (fadeStart + fadeEnd) / 2;
            erase.setShader(new LinearGradient((float) fadeStart, 0, (float) fadeEnd, 0,
                    new int[] {0x00000000, Ui.withAlpha(Color.BLACK, Math.round(255 * (1 - (float) ChargeWireComet.fadeAt(mid, fadeStart, fadeEnd)))), 0xFF000000},
                    null, Shader.TileMode.CLAMP));
            c.drawRect((float) fadeStart, -model.height, (right - placement.left) / s + 10, model.height * 2, erase);
        }
        baked = next; bakedLeft = left; bakedTop = top;
    }

    /** The unlit cells: a cylinder's wall under each top, then the top, its raised inner ring and a soft highlight. */
    private void drawCells(Canvas canvas) {
        ChargeWire w = wire;
        float rx = w.rx, ry = w.ry, wall = w.wall;
        for (int i = 0; i < w.cellCount; i++) {
            float cx = w.cellX[i], cy = w.cellY[i];
            int r = w.cellRow[i];
            fill.setAlpha(255);
            fill.setShader(wallShader[r]);
            canvas.drawRect(cx - rx, cy, cx + rx, cy + wall, fill);
            fill.setShader(null);
            fill.setColor(0xFF4A4C48);
            oval.set(cx - rx, cy + wall - ry, cx + rx, cy + wall + ry);
            canvas.drawOval(oval, fill);
            fill.setShader(topShader[r]);
            oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
            canvas.drawOval(oval, fill);
            fill.setShader(null);
            stroke.setShader(null);
            stroke.setStrokeWidth(RING);
            stroke.setColor(0xFFEEF0EB);
            stroke.setAlpha(140);
            oval.set(cx - rx * .72f, cy + .35f - ry * .72f, cx + rx * .72f, cy + .35f + ry * .72f);
            canvas.drawOval(oval, stroke);
            stroke.setColor(0xFF8A8C87);
            oval.set(cx - rx * .72f, cy - ry * .72f, cx + rx * .72f, cy + ry * .72f);
            canvas.drawOval(oval, stroke);
            fill.setColor(Color.WHITE);
            fill.setAlpha(56);
            oval.set(cx - rx * .25f - rx * .45f, cy - ry * .45f - ry * .28f, cx - rx * .25f + rx * .45f, cy - ry * .45f + ry * .28f);
            canvas.drawOval(oval, fill);
        }
        fill.setAlpha(255);
    }

    /**
     * The lit pack: every half-pitch column's cells lit in full, each column in its own strip of one bitmap at the
     * layer's pixels, so a frame draws a lit column as one bitmap at its light.
     */
    private void bakeLitPack() {
        float s = placement.scale;
        ChargeWire w = wire;
        float rx = w.rx, ry = w.ry;
        int x = 0, height = 0;
        for (int c = 0; c < ChargeWire.HALF_COLUMNS; c++) {
            float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (int k = columnStart[c]; k < columnStart[c + 1]; k++) {
                int i = byColumn[k];
                x0 = Math.min(x0, w.cellX[i] - rx); x1 = Math.max(x1, w.cellX[i] + rx);
                y0 = Math.min(y0, w.cellY[i] - ry); y1 = Math.max(y1, w.cellY[i] + ry);
            }
            if (x0 > x1) { litTo[c].setEmpty(); litFrom[c].setEmpty(); continue; }
            int l = (int) Math.floor(placement.left + x0 * s) - 1, t = (int) Math.floor(placement.top + y0 * s) - 1;
            int r = (int) Math.ceil(placement.left + x1 * s) + 1, b = (int) Math.ceil(placement.top + y1 * s) + 1;
            litTo[c].set(l, t, r, b);
            litFrom[c].set(x, 0, x + r - l, b - t);
            x += r - l + 1;
            height = Math.max(height, b - t);
        }
        if (x <= 0 || height <= 0) return;
        Bitmap next;
        try { next = Bitmap.createBitmap(x, height, Bitmap.Config.ARGB_8888); }
        catch (RuntimeException | OutOfMemoryError unavailable) { return; }
        Canvas canvas = new Canvas(next);
        for (int c = 0; c < ChargeWire.HALF_COLUMNS; c++) {
            if (litTo[c].isEmpty()) continue;
            canvas.save();
            canvas.clipRect(litFrom[c]);
            canvas.translate(litFrom[c].left - litTo[c].left + placement.left, litFrom[c].top - litTo[c].top + placement.top);
            canvas.scale(s, s);
            for (int k = columnStart[c]; k < columnStart[c + 1]; k++) {
                int i = byColumn[k];
                float cx = w.cellX[i], cy = w.cellY[i];
                fill.setShader(litShader[w.cellRow[i]]);
                fill.setAlpha(255);
                oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
                canvas.drawOval(oval, fill);
                fill.setShader(null);
                oval.set(cx - rx * .72f, cy - ry * .72f, cx + rx * .72f, cy + ry * .72f);
                stroke.setShader(null);
                stroke.setStrokeWidth(RING * 1.2f);
                stroke.setColor(0xFF3FB866);
                canvas.drawOval(oval, stroke);
                stroke.setStrokeWidth(RING * .6f);
                stroke.setColor(0xFFF4FFF6);
                stroke.setAlpha(Math.round(255 * .8f));
                canvas.drawOval(oval, stroke);
            }
            canvas.restore();
        }
        fill.setAlpha(255);
        stroke.setAlpha(255);
        litPack = next;
    }

    // ---- each frame ----

    private Canvas drawing;
    private final ChargeWireComet.Sink sink = new ChargeWireComet.Sink() {
        @Override public void piece(double s0, double s1, int argb, float width) {
            stretch(s0, s1);
            stroke.setColor(argb);
            stroke.setStrokeWidth(width);
            drawing.drawPath(comet, stroke);
        }
        @Override public void cap(double s, int argb, float r) {
            ChargeWire w = wire;
            w.point(s, point);
            float fx = point[0], fy = point[1];
            w.point(Math.min(w.length, s + 2), point);
            float angle = (float) Math.toDegrees(Math.atan2(fy - point[1], fx - point[0]));
            oval.set(fx - r, fy - r, fx + r, fy + r);
            fill.setColor(argb);
            drawing.drawArc(oval, angle - 90, 180, true, fill);
        }
    };

    private void drawLive(Canvas canvas) {
        float s = placement.scale;
        if (s <= 0 || cut == null) return;
        double frozen = tourSeconds;
        boolean tour = !Double.isNaN(frozen);
        if (!running && !tour) return;
        double t;
        if (tour) t = frozen;
        else {
            // The animator duration scale (Developer options) slows or speeds it as it does every other animation.
            float scale = ValueAnimator.getDurationScale();
            t = (AnimationUtils.currentAnimationTimeMillis() - startMs) / 1000.0 / (scale > 0 ? scale : 1);
        }
        Double power = tour && tourWatts != null ? tourWatts : watts;
        ChargeWirePulses.Mode mode = ChargeWirePulses.mode(power);
        ChargeWirePulses.state(t, mode, state);
        double energy = ChargeWirePulses.cells(state, mode, levels);
        // The lit columns, each one strip of the lit pack at its light.
        if (litPack == null) bakeLitPack();
        if (litPack != null) {
            for (int c = 0; c < ChargeWire.HALF_COLUMNS; c++) {
                float level = levels[c];
                if (level <= .004f || litTo[c].isEmpty()) continue;
                strip.setAlpha(Math.round(255 * Math.min(1, level)));
                canvas.drawBitmap(litPack, litFrom[c], litTo[c], strip);
            }
        }
        canvas.save();
        canvas.translate(placement.left, placement.top);
        canvas.scale(s, s);
        if (energy > .004) {
            double[] box = model.cells;
            canvas.save();
            canvas.translate((float) ((box[0] + box[2]) / 2), (float) ((box[1] + box[3]) / 2));
            canvas.scale(1, bloomRatio);
            fill.setShader(bloom);
            fill.setAlpha((int) Math.round(255 * .32 * energy));
            canvas.drawCircle(0, 0, bloomRadius, fill);
            fill.setShader(null);
            fill.setAlpha(255);
            canvas.restore();
        }
        stroke.setShader(null);
        fill.setShader(null);
        drawing = canvas;
        comets.draw(wire, state, mode, fadeStart, fadeEnd, sink);
        drawing = null;
        stroke.setAlpha(255);
        fill.setAlpha(255);
        canvas.restore();
    }

    /** The cable's path from arc length s0 to s1, into {@link #comet}. */
    private void stretch(double s0, double s1) {
        ChargeWire w = wire;
        comet.rewind();
        w.point(s0, point);
        comet.moveTo(point[0], point[1]);
        for (int i = w.after(s0); i < w.count && i * w.step < s1; i++) comet.lineTo(w.xs[i], w.ys[i]);
        w.point(s1, point);
        comet.lineTo(point[0], point[1]);
    }
}
