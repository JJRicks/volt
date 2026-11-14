package com.jjricks.volt;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * The still folded picture (ChargeFolded): the model's folded cut-out, centred at 80% of the stage's width (times the
 * reference box's share, ChargePictureFit) with its tyres on the floor, no animation. One layer, baked once per
 * placement at the screen's pixels; the percentage gets the cut-off over it.
 */
final class ChargeFoldedView extends ChargePicture {
    /** The bundled cut-out for each model in ChargeFolded. */
    static int resource(String key) {
        switch (key) {
            case "st3pro": return R.drawable.st3pro_folded;
            case "n65i": return R.drawable.n65i_folded;
            case "gt3max": return R.drawable.gt3max_folded;
            case "gt5max": return R.drawable.gt5max_folded;
            case "xt5ultra": return R.drawable.xt5ultra_folded;
            default: return 0;
        }
    }

    final ChargeFolded.Model model;
    private final Bitmap cut;
    private final View picture;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private ChargeWireModels.Placement placement = new ChargeWireModels.Placement(0, 0, 0);
    private int width, floor;
    private float box;
    /** The cut-out baked at its placed size (averaged down, drawn 1:1), and where it goes; null until drawn. */
    private Bitmap baked;
    private int bakedLeft, bakedTop;

    ChargeFoldedView(Context context, ChargeFolded.Model model, Bitmap cut) {
        super(model.key);
        this.model = model;
        this.cut = cut;
        picture = new LayerView(context) {
            @Override protected void onDraw(Canvas canvas) {
                if (placement.scale <= 0) return;
                if (baked == null) bake();
                if (baked != null) canvas.drawBitmap(baked, bakedLeft, bakedTop, null);
            }
        };
    }

    @Override View[] layers() { return new View[] {picture}; }

    @Override int shade() { return ChargePercentShade.CUTOFF; }

    @Override void size(int stageWidth, int tyres, float box) {
        if (width == stageWidth && floor == tyres && this.box == box) return;
        width = stageWidth; floor = tyres; this.box = box;
        // It grows and shrinks with the reference box (ChargePictureFit), from 80% of the width at full size.
        float widthBox = ChargePictureFit.widthBox(width);
        ChargeWireModels.Placement next = ChargeFolded.place(model, width, floor, widthBox <= 0 ? 0 : box / widthBox);
        if (next.scale != placement.scale || next.left != placement.left || next.top != placement.top) {
            placement = next;
            if (baked != null) { baked.recycle(); baked = null; }
            picture.invalidate();
        }
    }

    private void bake() {
        float s = placement.scale;
        int left = (int) Math.floor(placement.left), top = (int) Math.floor(placement.top);
        int right = (int) Math.ceil(placement.left + model.width * s), bottom = (int) Math.ceil(placement.top + model.height * s);
        if (right <= left || bottom <= top) return;
        try { baked = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888); }
        catch (RuntimeException | OutOfMemoryError unavailable) { return; }
        Canvas c = new Canvas(baked);
        c.translate(placement.left - left, placement.top - top);
        ChargePicture.drawDownscaled(c, cut, s, paint);
        bakedLeft = left; bakedTop = top;
    }
}
