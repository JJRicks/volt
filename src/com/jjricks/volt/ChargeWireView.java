package com.jjricks.volt;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AnimationUtils;
import android.widget.ScrollView;
import java.util.HashMap;
import java.util.Map;

/**
 * Clean Home's animated charging picture for a model in ChargeWireModels (the contributor's charging animation, 14 Nov
 * 2026 on #383): the cut-out scooter with its battery bay's dark panel, procedural cells and grey cable drawn over it,
 * and green pulses running down the cable that light the cells when they land.
 *
 * <p>Two layers fill the charging view's stage under the percentage: {@link #picture}, drawn once per layout (the
 * cut-out, the panel, the unlit cells and the grey cable), and {@link #live}, redrawn every frame while
 * {@link #start} runs it (the pulses, the lit cells and their bloom). Both draw in the cut-out's pixels through one
 * scale and offset (ChargeWireModels.place), so the animation's geometry is built once (ChargeWire) and a frame
 * allocates nothing. The layers are decorative: accessibility services skip them (the stage speaks the charge).
 */
final class ChargeWireView {
    /**
     * Design tour only (android-tests): a frozen moment of the animation, in seconds, drawn whether or not it runs
     * (the tour's emulator has animations off), and the charging power its mode follows (null: the reported one).
     * NaN, the default, leaves the picture to the app.
     */
    static volatile double tourSeconds = Double.NaN;
    static volatile Double tourWatts;

    private static final Map<String, Bitmap> CUTS = new HashMap<>();

    /** The bundled cut-out for each model in ChargeWireModels. */
    private static int resource(String key) {
        switch (key) {
            case "ut5max": return R.drawable.ut5max_charge;
            default: return 0;
        }
    }

    /** The model's cut-out, decoded once; null when it has none or it cannot be decoded. */
    static synchronized Bitmap cut(Resources resources, ChargeWireModels.Model model) {
        Bitmap cached = CUTS.get(model.key);
        if (cached != null) return cached;
        int id = resource(model.key);
        if (id == 0) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap decoded = BitmapFactory.decodeResource(resources, id, options);
            if (decoded == null || decoded.getWidth() != model.width || decoded.getHeight() != model.height) return null;
            decoded.setHasMipMap(true);
            CUTS.put(model.key, decoded);
            return decoded;
        } catch (RuntimeException | OutOfMemoryError unavailable) { return null; }
    }

    /** One layer of a pulse: a stroke along the cable, brightest at the head and fading out down its tail. */
    private static final class Layer {
        final boolean glow, head;
        /** Its width in cut-out pixels; its length: a share of the lit trail, or a number of head lengths. */
        final float width;
        final double length;
        final LinearGradient[] gradients = new LinearGradient[ChargeWirePulses.SLOTS];
        Layer(boolean glow, boolean head, float width, double length) {
            this.glow = glow; this.head = head; this.width = width; this.length = length;
        }
    }

    /** The pulse's sizes as the prototype draws them on the phone, in cut-out pixels. */
    private static final float HALO = 44, BLUR = 11, HEAD = 30, RING = 2.2f;
    private static final int GRADIENT_STOPS = 24;

    final ChargeWireModels.Model model;
    final View picture, live;
    private final Bitmap cut;
    private final boolean dark;
    private ChargeWire wire;
    private ChargeWireModels.Placement placement = new ChargeWireModels.Placement(0, 0, 0);
    private Double watts;
    private boolean running;
    private long startMs;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path panel = new Path(), cable = new Path(), comet = new Path();
    private final RectF oval = new RectF();
    private final Matrix matrix = new Matrix();
    private final float[] point = new float[2];
    private final ChargeWirePulses.State state = new ChargeWirePulses.State();
    private final float[] levels = new float[ChargeWire.HALF_COLUMNS];
    /** Each row's gradients (they run top to bottom over a cell's top or wall, and every cell in a row shares them). */
    private final LinearGradient[] topShader = new LinearGradient[ChargeWire.ROWS], wallShader = new LinearGradient[ChargeWire.ROWS],
            litShader = new LinearGradient[ChargeWire.ROWS];
    /** The cells' indices by half-pitch column (lit copies draw column by column). */
    private int[] byColumn;
    private int[] columnStart;
    private final Layer[] layers;
    private RadialGradient bloom;
    private final int green, cableEdge, cableCore;
    /** The window edge the cable runs off, in the layers' pixels (from the nearest scroller's right edge). */
    private float edge = Float.NaN;

    ChargeWireView(Context context, Ui ui, ChargeWireModels.Model model, Bitmap cut) {
        this.model = model;
        this.cut = cut;
        this.dark = ui.dark;
        green = ui.ok;
        // The resting cable: light grey on the dark page (the cut-out's own), a darker grey on the light page so it
        // stays visible there; a lighter core along its middle keeps it round.
        cableEdge = Color.parseColor(dark ? "#B0B1AE" : "#8C8E89");
        cableCore = Color.parseColor(dark ? "#CBCBC9" : "#A5A7A2");
        float cableWidth = (float) model.cableWidth;
        layers = new Layer[] {
                new Layer(true, false, HALO, 0.55),               // wide glow, strongest round the head
                new Layer(true, false, HALO * 0.55f, 0.85),       // narrower glow down more of the tail
                new Layer(false, false, cableWidth, 1),            // the cable itself, lit green
                new Layer(false, true, cableWidth * 1.22f, 2.4),   // the head's bright body, a little fuller than the cable
                new Layer(false, true, cableWidth * 0.8f, 1.5),    // near-white core
        };
        // Brightness along each layer: {peak, fade exponent}; colours per theme (the prototype's in dark).
        double[][] fades = {{.46, 1.1}, {.50, 1.25}, {.90, 1.5}, {.92, 1.15}, {1, 1.2}};
        int glowGreen = green;
        int litCable = dark ? Color.parseColor("#79DB95") : mix(green, Color.WHITE, .15f);
        int bodyHead = dark ? Color.parseColor("#D6FBE0") : mix(green, Color.WHITE, .6f);
        int bodyTail = dark ? Color.parseColor("#9BE8B2") : mix(green, Color.WHITE, .3f);
        int core = dark ? Color.parseColor("#F4FFF6") : mix(green, Color.WHITE, .85f);
        float glowShare = dark ? 1 : .7f;
        for (int j = 0; j < layers.length; j++) {
            int[] colors = new int[GRADIENT_STOPS + 1];
            float[] stops = new float[GRADIENT_STOPS + 1];
            for (int k = 0; k <= GRADIENT_STOPS; k++) {
                float u = k / (float) GRADIENT_STOPS;
                int base = j < 2 ? glowGreen : j == 2 ? litCable : j == 3 ? mix(bodyHead, bodyTail, Math.min(1, u * 2)) : core;
                double alpha = fades[j][0] * ChargeWirePulses.easeOut(u, fades[j][1]) * (j < 2 ? glowShare : 1);
                colors[k] = Ui.withAlpha(base, (int) Math.round(255 * alpha));
                stops[k] = u;
            }
            for (int i = 0; i < ChargeWirePulses.SLOTS; i++)
                layers[j].gradients[i] = new LinearGradient(0, 0, 1, 0, colors, stops, Shader.TileMode.CLAMP);
        }
        stroke.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        wire = new ChargeWire(model);
        cells();
        picture = new View(context) {
            @Override protected void onDraw(Canvas canvas) { drawPicture(canvas); }
            @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { replace(); }
            @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); unclip(this); }
        };
        live = new View(context) {
            @Override protected void onDraw(Canvas canvas) { drawLive(canvas); }
        };
        for (View layer : new View[] {picture, live}) {
            layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            layer.setWillNotDraw(false);
        }
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    /** The per-row cell gradients, the columns' cell lists, the panel and the bloom: all in cut-out pixels, once. */
    private void cells() {
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

    private float bloomRatio, bloomRadius;

    /** The grey cable's path: from the plug's tip to the end of the straight run. */
    private void cablePath() {
        ChargeWire w = wire;
        cable.reset();
        w.point(w.cableStart, point);
        cable.moveTo(point[0], point[1]);
        for (int i = w.after(w.cableStart); i < w.count; i++) cable.lineTo(w.xs[i], w.ys[i]);
    }

    /** The reported charging power (W), which picks the pulses' mode; null while unknown. */
    void watts(Double value) { watts = value; }

    /** Runs the animation from its start (each frame then needs live.invalidate()). */
    void start() {
        if (running) return;
        running = true;
        startMs = AnimationUtils.currentAnimationTimeMillis();
        live.invalidate();
    }

    /** Stops it: the picture rests (grey cable and cells, no pulses). */
    void stop() {
        if (!running) return;
        running = false;
        live.invalidate();
    }

    boolean running() { return running; }

    /** The placement in the stage (both layers fill it): the tyres stand at floor pixels from its top. */
    ChargeWireModels.Placement placement() { return placement; }

    private int width, floor;

    /** The stage's width and where the tyres stand in it, in pixels from its top. */
    void size(int stageWidth, int tyres) {
        if (width == stageWidth && floor == tyres) return;
        width = stageWidth; floor = tyres;
        replace();
    }

    private void replace() {
        ChargeWireModels.Placement next = ChargeWireModels.place(model, width > 0 ? width : picture.getWidth(), floor);
        if (next.scale != placement.scale || next.left != placement.left || next.top != placement.top) {
            placement = next;
            edge = Float.NaN;
            picture.invalidate();
            live.invalidate();
        }
    }

    /**
     * The cable runs off the screen's edge, past the stage: every parent up to the nearest scroller lets the layers
     * draw outside their bounds (the scroller's own bounds still clip them, so nothing reaches another pane).
     */
    private static void unclip(View layer) {
        for (ViewParent p = layer.getParent(); p instanceof ViewGroup; p = p.getParent()) {
            ((ViewGroup) p).setClipChildren(false);
            if (p instanceof ScrollView) break;
        }
    }

    /** The scroller's right edge in the layers' pixels, or NaN when there is none. */
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

    /** The straight run reaches past the scroller's edge (with a margin, so a pulse enters off screen). */
    private void reach() {
        float s = placement.scale;
        if (s <= 0) return;
        edge = scrollerEdge();
        if (Float.isNaN(edge)) return;
        double needed = (edge - placement.left) / s - model.width + 40 / s;
        if (Math.abs(needed - wire.ext) > 4 && (needed > ChargeWire.EXT || wire.ext > ChargeWire.EXT)) {
            wire = new ChargeWire(model, needed);
            cablePath();
            live.invalidate();
        }
    }

    private void drawPicture(Canvas canvas) {
        float s = placement.scale;
        if (s <= 0 || cut == null) return;
        reach();
        canvas.save();
        canvas.translate(placement.left, placement.top);
        canvas.scale(s, s);
        canvas.drawBitmap(cut, 0, 0, bitmapPaint);
        fill.setShader(null);
        fill.setColor(0xFF1C1D1B);
        fill.setAlpha(255);
        canvas.drawPath(panel, fill);
        drawCells(canvas);
        // The grey cable over the cut-out's own, along the same centre line, then on off the screen's edge.
        stroke.setShader(null);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setAlpha(255);
        stroke.setColor(cableEdge);
        stroke.setStrokeWidth((float) model.cableWidth);
        canvas.drawPath(cable, stroke);
        stroke.setColor(cableCore);
        stroke.setStrokeWidth((float) model.cableWidth * .45f);
        canvas.drawPath(cable, stroke);
        canvas.restore();
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

    private void drawLive(Canvas canvas) {
        float s = placement.scale;
        if (s <= 0 || cut == null) return;
        double frozen = tourSeconds;
        boolean tour = !Double.isNaN(frozen);
        if (!running && !tour) return;
        double t = tour ? frozen : (AnimationUtils.currentAnimationTimeMillis() - startMs) / 1000.0;
        Double power = tour && tourWatts != null ? tourWatts : watts;
        ChargeWirePulses.Mode mode = ChargeWirePulses.mode(power);
        ChargeWirePulses.state(t, mode, state);
        canvas.save();
        canvas.translate(placement.left, placement.top);
        canvas.scale(s, s);
        double energy = ChargeWirePulses.cells(state, mode, levels);
        drawLitCells(canvas);
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
        drawPulses(canvas, mode);
        canvas.restore();
    }

    /** Each half-pitch column's lit copies at its light, connector side first. */
    private void drawLitCells(Canvas canvas) {
        ChargeWire w = wire;
        float rx = w.rx, ry = w.ry;
        for (int c = 0; c < ChargeWire.HALF_COLUMNS; c++) {
            float level = levels[c];
            if (level <= .004f) continue;
            int alpha = Math.round(255 * Math.min(1, level));
            for (int k = columnStart[c]; k < columnStart[c + 1]; k++) {
                int i = byColumn[k];
                float cx = w.cellX[i], cy = w.cellY[i];
                fill.setShader(litShader[w.cellRow[i]]);
                fill.setAlpha(alpha);
                oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
                canvas.drawOval(oval, fill);
                fill.setShader(null);
                oval.set(cx - rx * .72f, cy - ry * .72f, cx + rx * .72f, cy + ry * .72f);
                stroke.setShader(null);
                stroke.setStrokeWidth(RING * 1.2f);
                stroke.setColor(0xFF3FB866);
                stroke.setAlpha(alpha);
                canvas.drawOval(oval, stroke);
                stroke.setStrokeWidth(RING * .6f);
                stroke.setColor(0xFFF4FFF6);
                stroke.setAlpha(Math.round(alpha * .8f));
                canvas.drawOval(oval, stroke);
            }
        }
        fill.setAlpha(255);
    }

    /**
     * Each pulse as one comet along the cable: every layer a stroke of the path from the head down its length, its
     * gradient sliding with the head (the cable only runs left to right, so a horizontal gradient follows it). The glow
     * layers stand in for the prototype's blur with three strokes, wider and fainter outwards.
     */
    private void drawPulses(Canvas canvas, ChargeWirePulses.Mode mode) {
        ChargeWire w = wire;
        double length = w.length, trail = ChargeWirePulses.trail(length, mode);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Color.WHITE);
        for (int p = 0; p < state.pulses; p++) {
            double progress = state.progress[p];
            int slot = ChargeWirePulses.slot(state.number[p]);
            double head = ChargeWirePulses.head(length, progress), visible = ChargeWirePulses.headVisible(progress);
            for (Layer layer : layers) {
                double len = layer.head ? HEAD * layer.length : trail * layer.length;
                double a = Math.max(0, head), b = Math.min(length, head + len);
                if (b - a <= 0.01 || (layer.head && visible <= 0)) continue;
                comet.rewind();
                w.point(a, point);
                comet.moveTo(point[0], point[1]);
                for (int i = w.after(a); i < w.count && i * w.step < b; i++) comet.lineTo(w.xs[i], w.ys[i]);
                w.point(b, point);
                comet.lineTo(point[0], point[1]);
                float x1 = w.xAt(head), x2 = w.xAt(head + len);
                matrix.setScale(Math.max(.01f, x2 - x1), 1);
                matrix.postTranslate(x1, 0);
                LinearGradient gradient = layer.gradients[slot];
                gradient.setLocalMatrix(matrix);
                stroke.setShader(gradient);
                int alpha = layer.head ? (int) Math.round(255 * visible) : 255;
                if (layer.glow) {
                    stroke.setAlpha(Math.round(alpha * .42f));
                    for (int k = 0; k < 3; k++) {
                        stroke.setStrokeWidth(k == 0 ? layer.width + 2.5f * BLUR : k == 1 ? layer.width + BLUR : Math.max(1, layer.width - BLUR * .5f));
                        canvas.drawPath(comet, stroke);
                    }
                } else {
                    stroke.setStrokeWidth(layer.width);
                    stroke.setAlpha(alpha);
                    canvas.drawPath(comet, stroke);
                }
            }
        }
        stroke.setShader(null);
        stroke.setAlpha(255);
    }
}
