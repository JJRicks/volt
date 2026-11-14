package com.jjricks.volt;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * The still folded picture (ChargeFolded): the model's folded cut-out, centred at 80% of the stage's width with its
 * tyres on the floor, no animation. One layer, drawn on layout; the percentage gets the cut-off over it.
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

    ChargeFoldedView(Context context, ChargeFolded.Model model, Bitmap cut) {
        super(model.key);
        this.model = model;
        this.cut = cut;
        picture = new View(context) {
            @Override protected void onDraw(Canvas canvas) {
                float s = placement.scale;
                if (s <= 0) return;
                canvas.save();
                canvas.translate(placement.left, placement.top);
                canvas.scale(s, s);
                canvas.drawBitmap(ChargeFoldedView.this.cut, 0, 0, paint);
                canvas.restore();
            }
        };
        picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        picture.setWillNotDraw(false);
    }

    @Override View[] layers() { return new View[] {picture}; }

    @Override int shade() { return ChargePercentShade.CUTOFF; }

    @Override void size(int stageWidth, int tyres) {
        if (width == stageWidth && floor == tyres) return;
        width = stageWidth; floor = tyres;
        ChargeWireModels.Placement next = ChargeFolded.place(model, width, floor);
        if (next.scale != placement.scale || next.left != placement.left || next.top != placement.top) {
            placement = next;
            picture.invalidate();
        }
    }
}
