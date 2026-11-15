package com.jjricks.volt;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.text.Layout;
import android.view.View;
import android.widget.TextView;

/**
 * The charging picture under the percentage for a model with one (the contributor's charging pictures, 14 Nov 2026 on
 * #383), on Clean Home's charging view and Widgets Home's charging takeover alike (DashboardScreen.ChargingHero):
 * the animated wire picture (ChargeWireView, ChargeWireModels) or the still folded photo (ChargeFoldedView,
 * ChargeFolded), chosen by the model's photo key; every other model keeps its photo.
 *
 * <p>Its layers fill the hero's stage, under the percentage; {@link #size} gives them the stage's width, the floor
 * the tyres stand on and the reference box ChargePictureFit sized with the percentage. Both kinds stand their tyres on
 * the same floor and share the floor glow's place ({@link #glowPlacement}); the percentage gets the kind's shade
 * ({@link #shade}, drawn by {@link Shade}). The layers are decorative: accessibility services skip them. They take no
 * height of their own when measured without an exact height ({@link LayerView}), so a stage that wraps its content
 * (Widgets' takeover, while it animates open or closed) is never made taller by them.
 */
abstract class ChargePicture {
    /**
     * For android-tests' ChargePictureValidation only: the photo key whose picture shows, whatever the connected model
     * (the validation's simulator is a UT5 MAX); null, the default, leaves it to the model.
     */
    static volatile String tourKey;

    /** Clean: how far the percentage moves up over a picture (dp; the prototype's 211 dp from 221). */
    static final int PERCENT_LIFT_DP = 10;

    final String key;

    ChargePicture(String key) { this.key = key; }

    /** The picture for this photo key: the animated wire, the folded still, or null (the model keeps its photo). */
    static ChargePicture create(Context context, Ui ui, String key) {
        String shown = tourKey != null ? tourKey : key;
        ChargeWireModels.Model wire = ChargeWireModels.forKey(shown);
        if (wire != null) {
            Bitmap cut = cut(context.getResources(), wire.key, ChargeWireView.resource(wire.key), wire.width, wire.height);
            return cut == null ? null : new ChargeWireView(context, ui, wire, cut);
        }
        ChargeFolded.Model folded = ChargeFolded.forKey(shown);
        if (folded != null) {
            Bitmap cut = cut(context.getResources(), folded.key + "_folded", ChargeFoldedView.resource(folded.key), folded.width, folded.height);
            return cut == null ? null : new ChargeFoldedView(context, folded, cut);
        }
        return null;
    }

    /** The key a picture would show for this model's photo key (the tour's, when it forces one). */
    static String shownKey(String key) { return tourKey != null ? tourKey : key; }

    /** Its layers, back to front (they fill the stage). */
    abstract View[] layers();

    /** The percentage's shade over this picture (ChargePercentShade.HALO or CUTOFF). */
    abstract int shade();

    /**
     * The stage's width, where the tyres stand in it (pixels from its top) and the reference box's height
     * (ChargePictureFit.Fit.box: the UT5 MAX picture's height at this size; 0: nothing shows).
     */
    abstract void size(int stageWidth, int tyres, float box);

    /**
     * Whether the picture may draw past its stage now: at rest (shown, not animating open or closed, not editing,
     * charging; ChargePictureFit.atRest). Only the wire picture's cable reaches past it; the default draws inside.
     */
    void clip(boolean atRest) {}

    /** Animation: runs it, rests it, and the reported charging power its mode follows (the still picture has none). */
    void start() {}
    void stop() {}
    void watts(Double value) {}
    /** Redraws its moving layer, if any (each frame of the glow's loop). */
    void invalidateLive() {}

    /** The prototype's track (dp), whose top the tyres' gap is measured from (its foot is the app's track's foot). */
    static final float TRACK_DP = 8;

    /** The tyres' gap above the prototype's track's top (dp): the same for every picture. */
    static float tyreGapDp() { return (float) ChargeWireModels.UT5_MAX.tyreGapDp; }

    private int glowWidth = -1, glowFloor = -1;
    private float glowBox = -1;
    private ChargeWireModels.Placement glow = new ChargeWireModels.Placement(0, 0, 0);

    /**
     * Where the floor glow goes: under the UT5 MAX picture's tyres as the prototype draws it for every model (129 x 34 dp
     * round a point 10 dp left of the 283.6 dp picture's centre), at this stage's width and floor, at the box's size.
     */
    final ChargeWireModels.Placement glowPlacement(int width, int floor, float box) {
        if (width != glowWidth || floor != glowFloor || box != glowBox) {
            glowWidth = width; glowFloor = floor; glowBox = box;
            glow = ChargeWireModels.place(ChargeWireModels.UT5_MAX, width, floor, box);
        }
        return glow;
    }

    /**
     * A picture's layer: decorative, drawn by its picture, and measured by ChargePictureFit.layerHeight (the stage's
     * width; its height only when the stage gives an exact one, else none).
     */
    abstract static class LayerView extends View {
        LayerView(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setWillNotDraw(false);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), ChargePictureFit.layerHeight(heightSpec));
        }
    }

    /**
     * Draws a cut-out at {@code scale} (under 1) with its top-left at the canvas's origin: first halved (each halving
     * an exact 2 x 2 average) while the scale is under a half, then filtered once, so a large cut-out drawn small does
     * not alias. For the pictures' one-off bakes, never per frame.
     */
    static void drawDownscaled(Canvas canvas, Bitmap source, float scale, Paint paint) {
        Bitmap src = source, temporary = null;
        float left = scale;
        try {
            while (left < .5f && src.getWidth() >= 4 && src.getHeight() >= 4) {
                Bitmap half = Bitmap.createScaledBitmap(src, src.getWidth() / 2, src.getHeight() / 2, true);
                if (temporary != null) temporary.recycle();
                temporary = half; src = half;
                left *= 2;
            }
        } catch (RuntimeException | OutOfMemoryError unavailable) { src = source; }
        canvas.save();
        canvas.scale(scale * source.getWidth() / src.getWidth(), scale * source.getHeight() / src.getHeight());
        canvas.drawBitmap(src, 0, 0, paint);
        canvas.restore();
        if (temporary != null) temporary.recycle();
    }

    // ---- the bundled cut-outs ----

    private static String cachedName;
    private static Bitmap cached;

    /**
     * A bundled cut-out at its own pixel size, decoded once (no density scaling) and kept while it is the last one
     * asked for: one model's picture at a time, shared by Clean's view and Widgets' takeover, until Home closes
     * ({@link #clearCache}). The pictures draw it baked at their placed size (drawDownscaled). Null when it is
     * missing, has another size than its table says, or cannot be decoded.
     */
    static synchronized Bitmap cut(Resources resources, String name, int id, int width, int height) {
        if (name.equals(cachedName) && cached != null) return cached;
        if (id == 0) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeResource(resources, id, options);
            if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) return null;
            cachedName = name; cached = decoded;
            return decoded;
        } catch (RuntimeException | OutOfMemoryError unavailable) { return null; }
    }

    /** Forgets the cached cut-out (Home closed; a picture still showing keeps its own reference). */
    static synchronized void clearCache() { cachedName = null; cached = null; }

    /**
     * The percentage's shade (ChargePercentShade), drawn by the percentage's parent just before it. The cut-off's blur
     * is made once per size and colour as a small bitmap (in software, so it looks the same on every device and draws
     * as one textured rectangle); the halo is a radial gradient.
     */
    static final class Shade {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private RadialGradient halo;
        private float haloRadius = -1;
        private int haloColor;
        private Bitmap mask;
        private int maskWidth, maskHeight, maskColor;
        private float maskSize, maskReach;

        private final float[] haloAt = new float[3], box = new float[4], local = new float[4];

        /**
         * Draws the shade of {@code kind} behind the percentage (a child of the canvas's view) in the page colour
         * {@code bg}: the halo in black on the dark page, in the page's own colour on the light page. {@code em}: the
         * size the shade reaches out by (ChargePictureFit.Fit.shadeSize), 0 for the text's own size.
         */
        void draw(Canvas canvas, TextView percent, int kind, int bg, boolean dark, float em) {
            Layout layout = percent.getLayout();
            if (kind == ChargePercentShade.NONE || layout == null || percent.getVisibility() != View.VISIBLE || percent.getWidth() <= 0) return;
            float x = percent.getLeft() + percent.getTotalPaddingLeft() + percent.getTranslationX();
            float left = x + layout.getLineLeft(0), right = x + layout.getLineRight(0);
            float baseline = percent.getTop() + percent.getTotalPaddingTop() + percent.getTranslationY() + layout.getLineBaseline(0);
            float size = percent.getTextSize();
            if (em <= 0) em = size;
            int alpha = Math.round(255 * percent.getAlpha());
            if (alpha <= 0) return;
            if (kind == ChargePercentShade.HALO) drawHalo(canvas, left, right, baseline, size, em, dark ? Color.BLACK : bg, alpha);
            else drawCutoff(canvas, left, right, baseline, size, em, bg, alpha);
        }

        private void drawHalo(Canvas canvas, float left, float right, float baseline, float size, float em, int color, int alpha) {
            float[] c = ChargePercentShade.halo(left, right, baseline, size, em, haloAt);
            if (halo == null || haloRadius != c[2] || haloColor != color) {
                float[] a = ChargePercentShade.HALO_ALPHAS;
                halo = new RadialGradient(0, 0, c[2], new int[] {
                        Ui.withAlpha(color, Math.round(255 * a[0])), Ui.withAlpha(color, Math.round(255 * a[1])), Ui.withAlpha(color, Math.round(255 * a[2]))},
                        ChargePercentShade.HALO_STOPS, Shader.TileMode.CLAMP);
                haloRadius = c[2]; haloColor = color;
            }
            canvas.save();
            canvas.translate(c[0], c[1]);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(halo);
            paint.setAlpha(alpha);
            canvas.drawCircle(0, 0, c[2], paint);
            paint.setShader(null);
            paint.setAlpha(255);
            canvas.restore();
        }

        private void drawCutoff(Canvas canvas, float left, float right, float baseline, float size, float em, int color, int alpha) {
            float reach = ChargePercentShade.cutoffReach(em);
            float[] box = ChargePercentShade.cutoffBox(left, right, baseline, size, em, this.box);
            int w = (int) Math.ceil(box[2] - box[0] + 2 * reach), h = (int) Math.ceil(box[3] - box[1] + 2 * reach);
            if (w <= 0 || h <= 0) return;
            if (mask == null || w != maskWidth || h != maskHeight || color != maskColor || em != maskSize) {
                // In the mask's own pixels: the box starts reach in from its top-left corner (made once per size).
                local[0] = reach; local[1] = reach; local[2] = reach + box[2] - box[0]; local[3] = reach + box[3] - box[1];
                int[] alphas = ChargePercentShade.cutoffMask(local, ChargePercentShade.cutoffRadius(em),
                        ChargePercentShade.cutoffSigma(em), 0, 0, w, h);
                int rgb = color & 0xFFFFFF;
                for (int i = 0; i < alphas.length; i++) alphas[i] = (alphas[i] << 24) | rgb;
                Bitmap next;
                try { next = Bitmap.createBitmap(alphas, w, h, Bitmap.Config.ARGB_8888); }
                catch (RuntimeException | OutOfMemoryError unavailable) { return; }
                mask = next; maskWidth = w; maskHeight = h; maskColor = color; maskSize = em; maskReach = reach;
            }
            paint.setShader(null);
            paint.setAlpha(alpha);
            canvas.drawBitmap(mask, box[0] - maskReach, box[1] - maskReach, paint);
            paint.setAlpha(255);
        }
    }
}
