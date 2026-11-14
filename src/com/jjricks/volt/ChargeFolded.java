package com.jjricks.volt;

/**
 * The still charging picture's per-model table (the contributor's charging pictures, 14 Nov 2026 on #383): the models
 * shown folded under the percentage instead of the animated wire picture (ChargeWireModels). Pure Java; the view
 * (ChargePicture) picks the bundled cut-out ({@code <key>_folded.webp}) by {@link Model#key} and places it with
 * {@link #place}, so another model is one more entry here plus its cut-out.
 *
 * <p>Each picture is static (no animation): centred at {@link #WIDTH_SHARE} of the content width, its tyres standing
 * where the wire picture's do (on the red floor glow, ChargeWireModels.Model.tyreGapDp above the track), and the
 * percentage in front of it with its cut-off shadow (ChargePercentShade).
 */
final class ChargeFolded {
    private ChargeFolded() {}

    /** The picture's width as a share of the content width (the prototype's 246.4 dp of 308). */
    static final double WIDTH_SHARE = 0.8;

    /** One model's folded cut-out. */
    static final class Model {
        /** The model's photo key (ModelRegistry.photo), e.g. "st3pro". */
        final String key;
        /** The cut-out's size in pixels and the row its tyres touch the floor on. */
        final int width, height, tyreY;

        Model(String key, int width, int height, int tyreY) {
            this.key = key; this.width = width; this.height = height; this.tyreY = tyreY;
        }
    }

    /** NAVEE product photos supplied by the contributor, cut out (or trimmed) on a transparent background. */
    static final Model ST3_PRO = new Model("st3pro", 1062, 477, 474);
    static final Model N65I = new Model("n65i", 718, 352, 351);
    static final Model GT3_MAX = new Model("gt3max", 1100, 483, 480);
    static final Model GT5_MAX = new Model("gt5max", 1100, 538, 537);
    static final Model XT5_ULTRA = new Model("xt5ultra", 1100, 526, 524);

    private static final Model[] ALL = {ST3_PRO, N65I, GT3_MAX, GT5_MAX, XT5_ULTRA};

    /** The model's folded picture, or null when it has none. */
    static Model forKey(String key) {
        if (key == null) return null;
        for (Model model : ALL) if (model.key.equals(key)) return model;
        return null;
    }

    static Model[] all() { return ALL.clone(); }

    /**
     * The picture in a stage {@code width} pixels wide whose tyres stand at {@code floor} (pixels from the stage's
     * top): {@link #WIDTH_SHARE} of the width, smaller where the stage is too short for it (it may reach up to the
     * stage's top, behind the percentage), centred across.
     */
    static ChargeWireModels.Placement place(Model m, int width, float floor) {
        if (width <= 0 || floor <= 0) return new ChargeWireModels.Placement(0, 0, 0);
        float scale = (float) Math.min(WIDTH_SHARE * width / m.width, floor / (double) m.tyreY);
        return new ChargeWireModels.Placement(scale, (width - m.width * scale) / 2, floor - m.tyreY * scale);
    }
}
