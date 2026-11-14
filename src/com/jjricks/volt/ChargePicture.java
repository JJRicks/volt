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
 * <p>Its layers fill the hero's stage, under the percentage; {@link #size} gives them the stage's width and the floor
 * the tyres stand on. Both kinds stand their tyres on the same floor and share the floor glow's place
 * ({@link #glowPlacement}); the percentage moves up {@link #PERCENT_LIFT_DP} over either and gets the kind's shade
 * ({@link #shade}, drawn by {@link Shade}). The layers are decorative: accessibility services skip them.
 */
abstract class ChargePicture {
    /**
     * Design tour only (android-tests): the photo key whose picture shows, whatever the connected model (the tour's
     * simulator is a UT5 MAX); null, the default, leaves it to the model.
     */
    static volatile String tourKey;

    /** How far the percentage moves up over a picture (dp; the prototype's 211 dp from 221). */
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

    /** The stage's width and where the tyres stand in it, in pixels from its top. */
    abstract void size(int stageWidth, int tyres);

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
    private ChargeWireModels.Placement glow = new ChargeWireModels.Placement(0, 0, 0);

    /**
     * Where the floor glow goes: under the UT5 MAX picture's tyres as the prototype draws it for every model (129 x 34 dp
     * round a point 10 dp left of the 283.6 dp picture's centre), at this stage's width and floor.
     */
    final ChargeWireModels.Placement glowPlacement(int width, int floor) {
        if (width != glowWidth || floor != glowFloor) {
            glowWidth = width; glowFloor = floor;
            glow = ChargeWireModels.place(ChargeWireModels.UT5_MAX, width, floor);
        }
        return glow;
    }

    // ---- the bundled cut-outs ----

    private static String cachedName;
    private static Bitmap cached;

    /**
     * A bundled cut-out at its own pixel size, decoded once (no density scaling, no mipmaps) and kept while it is the
     * last one asked for: one model's picture at a time, shared by Clean's view and Widgets' takeover. Null when it
     * is missing, has another size than its table says, or cannot be decoded.
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

        /**
         * Draws the shade of {@code kind} behind the percentage (a child of the canvas's view) in the page colour
         * {@code bg}: the halo in black on the dark page, in the page's own colour on the light page.
         */
        void draw(Canvas canvas, TextView percent, int kind, int bg, boolean dark) {
            Layout layout = percent.getLayout();
            if (kind == ChargePercentShade.NONE || layout == null || percent.getVisibility() != View.VISIBLE || percent.getWidth() <= 0) return;
            float x = percent.getLeft() + percent.getTotalPaddingLeft() + percent.getTranslationX();
            float left = x + layout.getLineLeft(0), right = x + layout.getLineRight(0);
            float baseline = percent.getTop() + percent.getTotalPaddingTop() + percent.getTranslationY() + layout.getLineBaseline(0);
            float size = percent.getTextSize();
            int alpha = Math.round(255 * percent.getAlpha());
            if (alpha <= 0) return;
            if (kind == ChargePercentShade.HALO) drawHalo(canvas, left, right, baseline, size, dark ? Color.BLACK : bg, alpha);
            else drawCutoff(canvas, left, right, baseline, size, bg, alpha);
        }

        private void drawHalo(Canvas canvas, float left, float right, float baseline, float size, int color, int alpha) {
            float[] c = ChargePercentShade.halo(left, right, baseline, size);
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

        private void drawCutoff(Canvas canvas, float left, float right, float baseline, float size, int color, int alpha) {
            float reach = ChargePercentShade.cutoffReach(size);
            float[] box = ChargePercentShade.cutoffBox(left, right, baseline, size);
            int w = (int) Math.ceil(box[2] - box[0] + 2 * reach), h = (int) Math.ceil(box[3] - box[1] + 2 * reach);
            if (w <= 0 || h <= 0) return;
            if (mask == null || w != maskWidth || h != maskHeight || color != maskColor || size != maskSize) {
                // In the mask's own pixels: the box starts reach in from its top-left corner.
                float[] local = {reach, reach, reach + box[2] - box[0], reach + box[3] - box[1]};
                int[] alphas = ChargePercentShade.cutoffMask(local, ChargePercentShade.cutoffRadius(size),
                        ChargePercentShade.cutoffSigma(size), 0, 0, w, h);
                int rgb = color & 0xFFFFFF;
                for (int i = 0; i < alphas.length; i++) alphas[i] = (alphas[i] << 24) | rgb;
                Bitmap next;
                try { next = Bitmap.createBitmap(alphas, w, h, Bitmap.Config.ARGB_8888); }
                catch (RuntimeException | OutOfMemoryError unavailable) { return; }
                mask = next; maskWidth = w; maskHeight = h; maskColor = color; maskSize = size; maskReach = reach;
            }
            paint.setShader(null);
            paint.setAlpha(alpha);
            canvas.drawBitmap(mask, box[0] - maskReach, box[1] - maskReach, paint);
            paint.setAlpha(255);
        }
    }
}
